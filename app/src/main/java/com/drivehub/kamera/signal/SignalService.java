package com.drivehub.kamera.signal;

import com.drivehub.kamera.R;

import com.drivehub.kamera.MainActivity;
import com.drivehub.kamera.camera.CameraIndex;
import com.drivehub.kamera.camera.OverlayService;
import com.drivehub.kamera.camera.TileViewActivity;
import com.drivehub.kamera.dashcam.DashcamSettingsController;
import com.drivehub.kamera.dashcam.RecordingService;
import com.drivehub.kamera.dev.DevRuntimeLog;
import com.drivehub.kamera.helper.app.NotificationChannelHelper;
import com.drivehub.kamera.helper.vehiclesensors.SystemPropertiesHelper;
import com.drivehub.kamera.settings.UiPrefs;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.app.ActivityManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/**
 * Listens only for turn-signal state:
 * - left signal  -> left camera overlay
 * - right signal -> right camera overlay
 * - no signal    -> hide overlay
 *
 * Priority: Car API via reflection.
 * Fallback: SystemProperties polling (arcsoft.avm.mCurCarTurnLamp).
 */
public class SignalService extends Service {

    private static final String TAG = "SignalService";
    private static final String CHANNEL_ID = "mg4_signal";
    private static final int NOTIF_ID = 100;
    private static final long HAZARD_TRIGGER_COOLDOWN_MS = 30_000L;

    private static final int TURN_PROP_ID = 0x21409326;
    private static final int HAZARD_LIGHTS_STATE_PROP_ID = 0x11400e03;
    private static final int HAZARD_LIGHTS_SWITCH_PROP_ID = 0x11400e13;
    /** Gear value reported by {@code arcsoft.avm.mCurCarGear} when reverse is engaged. */
    private static final int REVERSE_GEAR_VALUE = 2;
    private static final String OEM_AVM_PACKAGE = "com.saicmotor.hmi.aroundview";
    // Broadcasts emitted by AVMActivity in the OEM AVM app (verified via decompiled smali).
    private static final String ACTION_AVM_START = "com.saicmotor.hmi.aroundview.ACTION_START";
    private static final String ACTION_AVM_STOP = "com.saicmotor.hmi.aroundview.ACTION_STOP";
    // Inbound launch trigger to the OEM AVM app — sniffed here only for debugging visibility.
    private static final String ACTION_OEM_LAUNCH = "com.saicmotor.hmi.aroundview.startAVMActivity";
    // AVMActivity writes its lifecycle to this sysprop. "Start" is set in onCreate BEFORE
    // V4l2_Init claims the cameras — our earliest reliable warning that we must release them.
    private static final String OEM_LIFECYCLE_PROP = "arcsoft.avm.ActivityLifecycle";
    private static final String OEM_LIFECYCLE_START = "Start";
    private static final String OEM_LIFECYCLE_DESTROYED = "DestroyEnd";
    private static final long OEM_LIFECYCLE_POLL_MS = 75L;
    public static final String ACTION_ROUTE_CAMERA = "com.drivehub.kamera.ACTION_ROUTE_CAMERA";
    public static final String EXTRA_CAMERA_INDEX = "camera_index";

    private static volatile SignalService sInstance;

    private Object car;
    private Object lampManager;
    private Object lampCallbackProxy;
    private android.content.BroadcastReceiver debugBroadcastReceiver;

    private HandlerThread pollThread;
    private Handler pollHandler;
    private volatile boolean polling;
    private HandlerThread oemLifecycleThread;
    private Handler oemLifecycleHandler;
    private volatile boolean oemLifecycleMonitoring;
    private volatile String lastOemLifecycle = "";
    private SharedPreferences.OnSharedPreferenceChangeListener oemCoexistPrefListener;
    private int lastLamp = Integer.MIN_VALUE;
    private int currentLamp = 0;
    private int currentGear = 0;
    private int currentMode = -1; // -1:init, 0:none, 1:left, 2:right, 3:reverse
    private boolean lastObservedOemForeground = false;
    private volatile long overlayShownAtMs = 0L;
    private volatile long lastHazardTriggerAtMs = 0L;
    private long rearviewTempHiddenUntilMs = 0L; // mainHandler only
    private long signalTempHiddenUntilMs = 0L;   // mainHandler only

    private final Handler mainHandler = new Handler();
    private final Runnable hideRunnable = new Runnable() {
        @Override
        public void run() {
            clearOverlayShownTimestamp();
            lastLamp = Integer.MIN_VALUE;
            lastGear = Integer.MIN_VALUE;
            currentMode = -1;
            updateOverlayDecision();
            Log.i(TAG, "Signal off (delayed) => overlay state reapplied");
        }
    };

    private final Runnable rearviewRestoreRunnable = () -> {
        rearviewTempHiddenUntilMs = 0L;
        lastLamp = Integer.MIN_VALUE;
        lastGear = Integer.MIN_VALUE;
        currentMode = -1;
        updateOverlayDecision();
        Log.i(TAG, "Rearview temp-hide expired => overlay state reapplied");
    };

    private final Runnable signalRestoreRunnable = () -> {
        signalTempHiddenUntilMs = 0L;
        lastLamp = Integer.MIN_VALUE;
        lastGear = Integer.MIN_VALUE;
        currentMode = -1;
        updateOverlayDecision();
        Log.i(TAG, "Signal temp-hide expired => overlay state reapplied");
    };

    public static void start(Context context) {
        Intent i = new Intent(context, SignalService.class);
        context.startForegroundService(i);
    }

    public static boolean isRunning() {
        return sInstance != null;
    }

    public static void setOemAvmActive(Context context, boolean active) {
        SharedPreferences prefs = UiPrefs.getPrefs(context);
        UiPrefs.setOemAvmActive(prefs, active);
        DevRuntimeLog.add("SignalService", "OEM latch=" + active);
        SignalService inst = sInstance;
        if (inst == null) {
            if (active) {
                OverlayService.hideOverlay(context);
            }
            return;
        }
        inst.mainHandler.post(() -> {
            if (active) {
                inst.mainHandler.removeCallbacks(inst.hideRunnable);
                inst.clearOverlayShownTimestamp();
                OverlayService.hideOverlay(inst);
            }
            inst.lastLamp = Integer.MIN_VALUE;
            inst.lastGear = Integer.MIN_VALUE;
            inst.currentMode = -1;
            inst.updateOverlayDecision();
        });
    }

    public static void requestRecheck() {
        SignalService inst = sInstance;
        if (inst == null) return;
        inst.mainHandler.post(() -> {
            inst.lastLamp = Integer.MIN_VALUE;
            inst.lastGear = Integer.MIN_VALUE;
            inst.currentMode = -1;
            inst.updateOverlayDecision();
        });
    }

    public static void startRearviewTempHide(Context context) {
        SignalService inst = sInstance;
        if (inst == null) return;
        inst.mainHandler.post(() -> {
            int durationSec = UiPrefs.getDigitalRearviewTapToHideDurationSec(UiPrefs.getPrefs(context));
            long durationMs = durationSec * 1000L;
            inst.rearviewTempHiddenUntilMs = android.os.SystemClock.elapsedRealtime() + durationMs;
            inst.mainHandler.removeCallbacks(inst.rearviewRestoreRunnable);
            inst.mainHandler.postDelayed(inst.rearviewRestoreRunnable, durationMs);
            inst.lastLamp = Integer.MIN_VALUE;
            inst.lastGear = Integer.MIN_VALUE;
            inst.currentMode = -1;
            inst.updateOverlayDecision();
            Log.i(TAG, "Rearview temp-hide started for " + durationSec + "s");
        });
    }

    public static void cancelRearviewTempHide(Context context) {
        SignalService inst = sInstance;
        if (inst == null) return;
        inst.mainHandler.post(() -> {
            if (inst.rearviewTempHiddenUntilMs == 0L) return;
            inst.mainHandler.removeCallbacks(inst.rearviewRestoreRunnable);
            inst.rearviewTempHiddenUntilMs = 0L;
            inst.lastLamp = Integer.MIN_VALUE;
            inst.lastGear = Integer.MIN_VALUE;
            inst.currentMode = -1;
            inst.updateOverlayDecision();
            Log.i(TAG, "Rearview temp-hide cancelled");
        });
    }

    public static void startSignalTempHide(Context context) {
        SignalService inst = sInstance;
        if (inst == null) return;
        inst.mainHandler.post(() -> {
            int durationSec = UiPrefs.getSignalOverlayTapToHideDurationSec(UiPrefs.getPrefs(context));
            long durationMs = durationSec * 1000L;
            inst.signalTempHiddenUntilMs = android.os.SystemClock.elapsedRealtime() + durationMs;
            inst.mainHandler.removeCallbacks(inst.signalRestoreRunnable);
            inst.mainHandler.postDelayed(inst.signalRestoreRunnable, durationMs);
            inst.lastLamp = Integer.MIN_VALUE;
            inst.lastGear = Integer.MIN_VALUE;
            inst.currentMode = -1;
            inst.updateOverlayDecision();
            Log.i(TAG, "Signal temp-hide started for " + durationSec + "s");
        });
    }

    public static void cancelSignalTempHide(Context context) {
        SignalService inst = sInstance;
        if (inst == null) return;
        inst.mainHandler.post(() -> {
            if (inst.signalTempHiddenUntilMs == 0L) return;
            inst.mainHandler.removeCallbacks(inst.signalRestoreRunnable);
            inst.signalTempHiddenUntilMs = 0L;
            inst.lastLamp = Integer.MIN_VALUE;
            inst.lastGear = Integer.MIN_VALUE;
            inst.currentMode = -1;
            inst.updateOverlayDecision();
            Log.i(TAG, "Signal temp-hide cancelled");
        });
    }

    public static String getCurrentTurnLampDebugText() {
        SignalService inst = sInstance;
        if (inst != null) {
            return String.valueOf(inst.currentLamp);
        }
        String value = SystemPropertiesHelper.getString("arcsoft.avm.mCurCarTurnLamp");
        return value.isEmpty() ? "null" : value;
    }

    public static String getCurrentHazardDebugText(Context context) {
        SignalService inst = sInstance;
        if (inst != null) {
            return inst.readHazardDebugText();
        }
        return buildHazardDebugText(readHazardPropertyValue(context, null, HAZARD_LIGHTS_STATE_PROP_ID),
                readHazardPropertyValue(context, null, HAZARD_LIGHTS_SWITCH_PROP_ID));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        sInstance = this;
        NotificationChannelHelper.ensureChannel(this, CHANNEL_ID, "MG4 Signal");
        registerDebugBroadcastSniffer();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Notification n = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(getString(R.string.notification_signal_text))
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
        startForeground(NOTIF_ID, n);

        boolean carOk = tryStartCarApiListener();
        if (!carOk) {
            Log.w(TAG, "Car API listener unavailable, falling back to system property polling.");
            startPropertyPollingFallback();
        }
        registerOemCoexistListener();
        applyOemCoexistState();
        return START_STICKY;
    }

    private boolean tryStartCarApiListener() {
        try {
            Class<?> carClass = Class.forName("android.car.Car");
            Method createCar = carClass.getMethod("createCar", Context.class);
            car = createCar.invoke(null, this);
            if (car == null) return false;

            Method connect = carClass.getMethod("connect");
            connect.invoke(car);

            Method getCarManager = carClass.getMethod("getCarManager", String.class);
            lampManager = getCarManager.invoke(car, "lamp");
            if (lampManager == null) return false;

            Class<?> cbInterface = Class.forName("android.car.hardware.CarLampManager$CarLampEventCallback");
            lampCallbackProxy = Proxy.newProxyInstance(
                    cbInterface.getClassLoader(),
                    new Class[]{cbInterface},
                    new LampCallbackHandler()
            );

            Method register = lampManager.getClass().getMethod("registerCallback", cbInterface);
            register.invoke(lampManager, lampCallbackProxy);
            Log.i(TAG, "CarLampManager callback registered.");
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "tryStartCarApiListener failed: " + t);
            return false;
        }
    }

    private class LampCallbackHandler implements InvocationHandler {
        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if (method == null) return null;
            String name = method.getName();
            if (!"onChangeEvent".equals(name) || args == null || args.length < 1 || args[0] == null) {
                return null;
            }
            Object carPropertyValue = args[0];
            int propId = readIntMethod(carPropertyValue, "getPropertyId", Integer.MIN_VALUE);
            if (propId != TURN_PROP_ID) return null;

            Object valueObj = carPropertyValue.getClass().getMethod("getValue").invoke(carPropertyValue);
            int lamp = (valueObj instanceof Integer) ? (Integer) valueObj : Integer.MIN_VALUE;
            currentLamp = lamp;
            // The Car API can be incomplete or fail, so read the gear from the current system property.
            currentGear = SystemPropertiesHelper.getInt("arcsoft.avm.mCurCarGear", 0);
            reconcileOemAvmState();
            updateOverlayDecision();
            return null;
        }
    }

    private void startPropertyPollingFallback() {
        if (pollThread != null) return;
        pollThread = new HandlerThread("SignalPollThread");
        pollThread.start();
        pollHandler = new Handler(pollThread.getLooper());
        polling = true;
        pollHandler.post(new Runnable() {
            @Override
            public void run() {
                if (!polling) return;
                final int lamp = SystemPropertiesHelper.getInt("arcsoft.avm.mCurCarTurnLamp", 0);
                final int gear = SystemPropertiesHelper.getInt("arcsoft.avm.mCurCarGear", 0);
                mainHandler.post(() -> {
                    if (!polling) return;
                    currentLamp = lamp;
                    currentGear = gear;
                    reconcileOemAvmState();
                    updateOverlayDecision();
                });
                pollHandler.postDelayed(this, readNextPollingDelayMs(lamp));
            }
        });
    }

    private boolean isOemCoexistActive() {
        try {
            return UiPrefs.isOemAvmCoexistEnabled(UiPrefs.getPrefs(this));
        } catch (Throwable ignored) {
            return false;
        }
    }

    private boolean isDigitalRearviewEnabled() {
        try {
            return UiPrefs.isDigitalRearviewEnabled(UiPrefs.getPrefs(this));
        } catch (Throwable ignored) {
            return false;
        }
    }

    private void registerOemCoexistListener() {
        if (oemCoexistPrefListener != null) return;
        SharedPreferences prefs = UiPrefs.getPrefs(this);
        oemCoexistPrefListener = (sp, key) -> {
            if (UiPrefs.KEY_OEM_AVM_COEXIST.equals(key)) {
                mainHandler.post(this::applyOemCoexistState);
            } else if (UiPrefs.KEY_DIGITAL_REARVIEW_ENABLED.equals(key)) {
                mainHandler.post(() -> {
                    // Any explicit pref change cancels the temp-hide so the new state takes effect immediately.
                    mainHandler.removeCallbacks(rearviewRestoreRunnable);
                    rearviewTempHiddenUntilMs = 0L;
                    applyOemCoexistState();
                });
            }
        };
        prefs.registerOnSharedPreferenceChangeListener(oemCoexistPrefListener);
    }

    private void unregisterOemCoexistListener() {
        if (oemCoexistPrefListener == null) return;
        try {
            UiPrefs.getPrefs(this).unregisterOnSharedPreferenceChangeListener(oemCoexistPrefListener);
        } catch (Throwable ignored) {
        }
        oemCoexistPrefListener = null;
    }

    private void applyOemCoexistState() {
        if (isOemCoexistActive()) {
            startOemLifecycleMonitor();
        } else {
            stopOemLifecycleMonitor();
            // OEM coexist is off — release any stuck latch and resume recording.
            if (isOemAvmLatchedActive()) {
                setOemAvmActive(this, false);
                RecordingService.resumeAfterOemRequest(this);
            }
        }
    }

    private void startOemLifecycleMonitor() {
        if (oemLifecycleThread != null) return;
        oemLifecycleThread = new HandlerThread("OemAvmLifecycleMonitor");
        oemLifecycleThread.start();
        oemLifecycleHandler = new Handler(oemLifecycleThread.getLooper());
        oemLifecycleMonitoring = true;
        // The AVM sysprop is sticky across reboots — if the previous session ended with "Start"
        // (crash, force-kill) and AVM is no longer actually in the foreground, treating that
        // stale value as the baseline causes the next genuine open→close cycle to fire a
        // "resume" without a preceding "pause". Reset stale Starts to DestroyEnd so the next
        // real Start triggers the pause path correctly.
        String initialState = SystemPropertiesHelper.getString(OEM_LIFECYCLE_PROP);
        if (OEM_LIFECYCLE_START.equals(initialState) && !isOemAvmInForeground()) {
            DevRuntimeLog.add("SignalService",
                    "OEM lifecycle: stale 'Start' on monitor start — resetting baseline");
            initialState = OEM_LIFECYCLE_DESTROYED;
        }
        lastOemLifecycle = initialState;
        DevRuntimeLog.add("SignalService",
                "OEM lifecycle monitor started, initial='" + initialState + "'");
        oemLifecycleHandler.post(new Runnable() {
            @Override
            public void run() {
                if (!oemLifecycleMonitoring) return;
                String state = SystemPropertiesHelper.getString(OEM_LIFECYCLE_PROP);
                if (!state.equals(lastOemLifecycle)) {
                    String prev = lastOemLifecycle;
                    lastOemLifecycle = state;
                    mainHandler.post(() -> onOemLifecycleChanged(prev, state));
                }
                oemLifecycleHandler.postDelayed(this, OEM_LIFECYCLE_POLL_MS);
            }
        });
    }

    private void stopOemLifecycleMonitor() {
        if (oemLifecycleThread == null) return;
        DevRuntimeLog.add("SignalService", "OEM lifecycle monitor stopped");
        oemLifecycleMonitoring = false;
        if (oemLifecycleHandler != null) {
            oemLifecycleHandler.removeCallbacksAndMessages(null);
        }
        oemLifecycleThread.quitSafely();
        oemLifecycleThread = null;
        oemLifecycleHandler = null;
        lastObservedOemForeground = false;
    }

    private void onOemLifecycleChanged(String prev, String next) {
        DevRuntimeLog.add("SignalService", "OEM lifecycle: '" + prev + "' => '" + next + "'");
        if (OEM_LIFECYCLE_START.equals(next)) {
            setOemAvmActive(this, true);
            RecordingService.pauseForOemRequest(this);
        } else if (OEM_LIFECYCLE_DESTROYED.equals(next)) {
            setOemAvmActive(this, false);
            RecordingService.resumeAfterOemRequest(this);
        }
    }

    private int readIntMethod(Object target, String methodName, int def) {
        try {
            Object o = target.getClass().getMethod(methodName).invoke(target);
            if (o instanceof Integer) return (Integer) o;
        } catch (Throwable ignored) {
        }
        return def;
    }

    private String readHazardDebugText() {
        return buildHazardDebugText(
                readHazardPropertyValue(this, car, HAZARD_LIGHTS_STATE_PROP_ID),
                readHazardPropertyValue(this, car, HAZARD_LIGHTS_SWITCH_PROP_ID)
        );
    }

    private static String buildHazardDebugText(Integer stateValue, Integer switchValue) {
        return "state=" + formatHazardDebugValue(stateValue) + " switch=" + formatHazardDebugValue(switchValue);
    }

    private static String formatHazardDebugValue(Integer value) {
        return value == null ? "null" : String.valueOf(value);
    }

    private static Integer readHazardPropertyValue(Context context, Object existingCar, int propertyId) {
        Object localCar = existingCar;
        boolean disconnectAfterRead = false;
        try {
            if (localCar == null) {
                Class<?> carClass = Class.forName("android.car.Car");
                Method createCar = carClass.getMethod("createCar", Context.class);
                localCar = createCar.invoke(null, context);
                if (localCar == null) return null;
                Method connect = carClass.getMethod("connect");
                connect.invoke(localCar);
                disconnectAfterRead = true;
            }

            Method getCarManager = localCar.getClass().getMethod("getCarManager", String.class);
            Object propertyManager = getCarManager.invoke(localCar, "property");
            if (propertyManager == null) return null;

            Method getProperty = propertyManager.getClass().getMethod(
                    "getProperty", Class.class, int.class, int.class);
            Object value = getProperty.invoke(propertyManager, Integer.class, propertyId, 0);
            return value instanceof Integer ? (Integer) value : null;
        } catch (Throwable ignored) {
            return null;
        } finally {
            if (disconnectAfterRead && localCar != null) {
                try {
                    Method disconnect = localCar.getClass().getMethod("disconnect");
                    disconnect.invoke(localCar);
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private void updateOverlayDecision() {
        // Skip work if nothing changed.
        if (currentLamp == lastLamp && currentGear == readCachedGear()) {
            return;
        }
        int previousLamp = lastLamp;
        cacheGear(currentGear);
        lastLamp = currentLamp;
        maybeTriggerDashcamEventForHazards(previousLamp);

        final TurnSignal signal = TurnSignal.fromValue(currentLamp);
        int nextMode;
        if (currentGear == REVERSE_GEAR_VALUE) {
            nextMode = 3;
        } else if (signal == TurnSignal.LEFT && !isSignalTempHidden()) {
            nextMode = 1;
        } else if (signal == TurnSignal.RIGHT && !isSignalTempHidden()) {
            nextMode = 2;
        } else if (isDigitalRearviewEnabled() && !isRearviewTempHidden()
                && !MainActivity.isSettingsDialogOpen()) {
            nextMode = 4;
        } else {
            nextMode = 0;
        }

        if (nextMode == currentMode) return;
        int previousMode = currentMode;

        // If MainActivity is visible, do not use the overlay; route the main preview camera instead.
        // Digital rearview (nextMode==4) is skipped here — the main preview should stay independent.
        if (MainActivity.shouldBlockOverlay()) {
            currentMode = nextMode;
            mainHandler.removeCallbacks(hideRunnable);
            clearOverlayShownTimestamp();
            OverlayService.hideOverlay(this);
            if (nextMode == 4 || nextMode == 0) {
                Log.i(TAG, "Main visible => no route (mode " + nextMode + ")");
                return;
            }
            CameraIndex targetCamera = (nextMode == 3) ? CameraIndex.REAR
                    : (nextMode == 1) ? CameraIndex.LEFT
                    : (nextMode == 2) ? CameraIndex.RIGHT
                    : CameraIndex.FRONT;
            Intent i = new Intent(ACTION_ROUTE_CAMERA);
            i.setPackage(getPackageName());
            i.putExtra(EXTRA_CAMERA_INDEX, targetCamera.getVideoIndex());
            sendBroadcast(i);
            Log.i(TAG, "Main visible => route camera to main preview: " + targetCamera);
            return;
        }

        // TileView holds all four cameras directly — suppress every overlay to avoid conflicts.
        if (TileViewActivity.isVisible()) {
            currentMode = nextMode;
            mainHandler.removeCallbacks(hideRunnable);
            clearOverlayShownTimestamp();
            OverlayService.hideOverlay(this);
            Log.i(TAG, "TileView visible => suppress overlay (mode " + nextMode + ")");
            return;
        }

        // Signal overlay and digital rearview are controlled separately.
        if ((nextMode == 1 || nextMode == 2) && !isOverlayEnabled()) {
            currentMode = nextMode;
            // Also hide any leftover overlay if the setting is disabled.
            mainHandler.removeCallbacks(hideRunnable);
            clearOverlayShownTimestamp();
            OverlayService.hideOverlay(this);
            Log.i(TAG, "Overlay disabled in settings => no overlay, only listening.");
            return;
        }

        if (nextMode == 3) { // reverse
            currentMode = nextMode;
            // No overlay is wanted for reverse: hide it if present and leave only OEM/main screen behavior.
            mainHandler.removeCallbacks(hideRunnable);
            clearOverlayShownTimestamp();
            OverlayService.hideOverlay(this);
            Log.i(TAG, "Reverse => no overlay (disabled for reverse)");
            return;
        }

        // Prefer the OEM AVM latch from broadcasts so we can react immediately before
        // ActivityManager catches up and the OEM app is actually top-most.
        if (isOemAvmLatchedActive() || isOemAvmInForeground()) {
            currentMode = nextMode;
            mainHandler.removeCallbacks(hideRunnable);
            clearOverlayShownTimestamp();
            OverlayService.hideOverlay(this);
            Log.i(TAG, "OEM AVM active/visible (turn signal) => skip overlay.");
            return;
        }

        if ((previousMode == 1 || previousMode == 2) && nextMode == 0) {
            currentMode = -2;
            long delayMs = computeOverlayHideDelayMs();
            mainHandler.removeCallbacks(hideRunnable);
            mainHandler.postDelayed(hideRunnable, delayMs);
            Log.i(TAG, "Signal/gear off => will reapply overlay state after " + delayMs + "ms");
            return;
        }

        currentMode = nextMode;
        mainHandler.removeCallbacks(hideRunnable);
        if (nextMode == 1) {
            markOverlayShownNow();
            OverlayService.showOverlay(this, CameraIndex.LEFT.getVideoIndex());
            Log.i(TAG, "Left signal => overlay " + CameraIndex.LEFT);
        } else if (nextMode == 2) {
            markOverlayShownNow();
            OverlayService.showOverlay(this, CameraIndex.RIGHT.getVideoIndex());
            Log.i(TAG, "Right signal => overlay " + CameraIndex.RIGHT);
        } else if (nextMode == 4) {
            clearOverlayShownTimestamp();
            OverlayService.showOverlay(this, CameraIndex.REAR.getVideoIndex(),
                    OverlayService.OVERLAY_REASON_DIGITAL_REARVIEW);
            Log.i(TAG, "Digital rearview => overlay " + CameraIndex.REAR);
        } else {
            clearOverlayShownTimestamp();
            OverlayService.hideOverlay(this);
            Log.i(TAG, "No active overlay mode => overlay hide");
        }
    }

    private void maybeTriggerDashcamEventForHazards(int previousLamp) {
        if (TurnSignal.fromValue(currentLamp) != TurnSignal.HAZARD) {
            return;
        }
        if (previousLamp == Integer.MIN_VALUE || TurnSignal.fromValue(previousLamp) == TurnSignal.HAZARD) {
            return;
        }
        long now = SystemClock.elapsedRealtime();
        if ((now - lastHazardTriggerAtMs) < HAZARD_TRIGGER_COOLDOWN_MS) {
            Log.i(TAG, "Hazard trigger ignored due to cooldown");
            return;
        }
        lastHazardTriggerAtMs = now;
        RecordingService.triggerEventSave(this);
        Log.i(TAG, "Hazard lights => dashcam event trigger");
    }

    // Simple gear-change cache so mode transitions are not missed.
    private int lastGear = Integer.MIN_VALUE;
    private int readCachedGear() {
        return lastGear;
    }
    private void cacheGear(int g) {
        lastGear = g;
    }

    private boolean isOverlayEnabled() {
        try {
            return UiPrefs.isOverlayOnSignalEnabled(UiPrefs.getPrefs(this));
        } catch (Throwable t) {
            return false;
        }
    }

    private boolean isRearviewTempHidden() {
        return rearviewTempHiddenUntilMs > 0L
                && android.os.SystemClock.elapsedRealtime() < rearviewTempHiddenUntilMs;
    }

    private boolean isSignalTempHidden() {
        return signalTempHiddenUntilMs > 0L
                && android.os.SystemClock.elapsedRealtime() < signalTempHiddenUntilMs;
    }

    private long readOverlayHideDelayMs() {
        SharedPreferences sp = UiPrefs.getPrefs(this);
        return UiPrefs.getOverlayHideDelayMs(sp);
    }

    private long readOverlayMinShowMs() {
        SharedPreferences sp = UiPrefs.getPrefs(this);
        return UiPrefs.getOverlayMinShowMs(sp);
    }

    private int readNextPollingDelayMs(int lamp) {
        SharedPreferences sp = UiPrefs.getPrefs(this);
        TurnSignal signal = TurnSignal.fromValue(lamp);
        if (signal == TurnSignal.LEFT || signal == TurnSignal.RIGHT) {
            return UiPrefs.getDevSignalOffPollMs(sp);
        }
        return UiPrefs.getDevDefaultPollMs(sp);
    }

    private void markOverlayShownNow() {
        overlayShownAtMs = SystemClock.elapsedRealtime();
    }

    private void clearOverlayShownTimestamp() {
        overlayShownAtMs = 0L;
    }

    private long computeOverlayHideDelayMs() {
        long hideDelayMs = readOverlayHideDelayMs();
        long minShowMs = readOverlayMinShowMs();
        long shownAtMs = overlayShownAtMs;
        if (shownAtMs <= 0L || minShowMs <= 0L) {
            return hideDelayMs;
        }
        long elapsedVisibleMs = Math.max(0L, SystemClock.elapsedRealtime() - shownAtMs);
        long remainingMinShowMs = Math.max(0L, minShowMs - elapsedVisibleMs);
        return Math.max(hideDelayMs, remainingMinShowMs);
    }

    private boolean isOemAvmInForeground() {
        try {
            ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
            if (am == null) return false;
            // Because this is a system app, getRunningTasks(1) is usually permitted.
            java.util.List<ActivityManager.RunningTaskInfo> tasks = am.getRunningTasks(1);
            if (tasks == null || tasks.isEmpty()) return false;
            ActivityManager.RunningTaskInfo t = tasks.get(0);
            if (t.topActivity == null) return false;
            return OEM_AVM_PACKAGE.equals(t.topActivity.getPackageName());
        } catch (Throwable t) {
            Log.w(TAG, "isOemAvmInForeground failed: " + t);
            return false;
        }
    }

    private boolean isOemAvmLatchedActive() {
        try {
            return UiPrefs.isOemAvmActive(UiPrefs.getPrefs(this));
        } catch (Throwable ignored) {
            return false;
        }
    }

    private void reconcileOemAvmState() {
        if (!isOemCoexistActive()) {
            lastObservedOemForeground = false;
            return;
        }
        boolean foreground = isOemAvmInForeground();
        boolean latched = isOemAvmLatchedActive();

        if (foreground) {
            lastObservedOemForeground = true;
            if (!latched) {
                DevRuntimeLog.add("SignalService", "OEM foreground fallback => pause");
                setOemAvmActive(this, true);
                RecordingService.pauseForOemRequest(this);
            }
            return;
        }

        if (lastObservedOemForeground && latched) {
            DevRuntimeLog.add("SignalService", "OEM foreground fallback => resume");
            setOemAvmActive(this, false);
            RecordingService.resumeAfterOemRequest(this);
        }
        lastObservedOemForeground = false;
    }


    @Override
    public void onDestroy() {
        super.onDestroy();
        if (debugBroadcastReceiver != null) {
            try {
                unregisterReceiver(debugBroadcastReceiver);
            } catch (Throwable ignored) {
            }
            debugBroadcastReceiver = null;
        }
        try {
            if (lampManager != null && lampCallbackProxy != null) {
                Method unregister = lampManager.getClass()
                        .getMethod("unregisterCallback", lampCallbackProxy.getClass().getInterfaces()[0]);
                unregister.invoke(lampManager, lampCallbackProxy);
            }
        } catch (Throwable ignored) {
        }

        try {
            if (car != null) {
                Method disconnect = car.getClass().getMethod("disconnect");
                disconnect.invoke(car);
            }
        } catch (Throwable ignored) {
        }

        polling = false;
        if (pollHandler != null) {
            pollHandler.removeCallbacksAndMessages(null);
        }
        if (pollThread != null) {
            pollThread.quitSafely();
            pollThread = null;
        }
        stopOemLifecycleMonitor();
        unregisterOemCoexistListener();
        mainHandler.removeCallbacks(hideRunnable);
        mainHandler.removeCallbacks(rearviewRestoreRunnable);
        mainHandler.removeCallbacks(signalRestoreRunnable);
        OverlayService.hideOverlay(this);
        sInstance = null;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void registerDebugBroadcastSniffer() {
        if (debugBroadcastReceiver != null) return;
        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_AVM_START);
        filter.addAction(ACTION_AVM_STOP);
        filter.addAction(ACTION_OEM_LAUNCH);
        debugBroadcastReceiver = new android.content.BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (intent == null || intent.getAction() == null) return;
                DevRuntimeLog.add("Broadcast", intent.getAction() + formatIntentDebug(intent));
            }
        };
        try {
            registerReceiver(debugBroadcastReceiver, filter);
            DevRuntimeLog.add("SignalService", "debug sniffer registered for OEM actions");
        } catch (Throwable t) {
            DevRuntimeLog.add("SignalService", "debug sniffer failed: " + t.getClass().getSimpleName());
        }
    }

    private static String formatIntentDebug(Intent intent) {
        StringBuilder sb = new StringBuilder();
        if (intent.getPackage() != null) {
            sb.append(" pkg=").append(intent.getPackage());
        }
        if (intent.getComponent() != null) {
            sb.append(" cmp=").append(intent.getComponent().flattenToShortString());
        }
        if (intent.getExtras() != null && !intent.getExtras().isEmpty()) {
            sb.append(" extras=").append(intent.getExtras().keySet());
        }
        return sb.toString();
    }
}
