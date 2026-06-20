package com.drivehub.kamera.maneuver;

import com.drivehub.kamera.camera.CameraIndex;
import com.drivehub.kamera.camera.OverlayService;
import com.drivehub.kamera.dev.DevRuntimeLog;
import com.drivehub.kamera.MainActivity;
import com.drivehub.kamera.helper.audio.MediaKeySuppressor;
import com.drivehub.kamera.helper.audio.VolumeRestoreGuard;
import com.drivehub.kamera.helper.vehiclesensors.VehicleSpeedReader;
import com.drivehub.kamera.settings.UiPrefs;
import com.drivehub.kamera.signal.SignalService;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;

import androidx.core.content.ContextCompat;

public final class ManeuverController {

    private static final String TAG = "ManeuverController";
    private static final String ACTION_RAW_HARDKEY = "com.saic.keyevent.hardkey.report";
    private static final String ACTION_SYSTEMUI_HARDKEY = "com.android.systemui.ACTION_HARD_KEY_EVENT";
    private static final String EXTRA_RAW_KEYCODE = "android.intent.extra.hardkey.keycode";
    private static final String EXTRA_RAW_KEYCODE_ALT = "keycode";
    private static final String EXTRA_RAW_KEYCODE_CAMEL = "keyCode";
    private static final String EXTRA_RAW_DOWN = "android.intent.extra.hardkey.down";
    private static final String EXTRA_RAW_DOWN_ALT = "down";
    private static final String EXTRA_RAW_LONGPRESS = "android.intent.extra.hardkey.longpress";
    private static final String EXTRA_RAW_LONGPRESS_ALT = "longpress";
    private static final String EXTRA_RAW_LONGPRESS_CAMEL = "longPress";
    private static final String EXTRA_LOGICAL_KEY_CODE = "KEY_CODE";
    private static final String EXTRA_LOGICAL_DOWN = "DOWN";

    public static final String ACTION_SUPPRESSOR_STATE =
            "com.drivehub.kamera.action.SET_MANEUVER_SUPPRESSOR";
    public static final String EXTRA_SUPPRESSOR_ENABLED = "enabled";

    private static final int RAW_SWC_UP = 0x129;
    private static final int RAW_SWC_DOWN = 0x12a;
    private static final int RAW_SWC_LEFT = 0x12b;
    private static final int RAW_SWC_RIGHT = 0x12c;
    private static final int RAW_SWC_CENTER = 0x12d;

    private static final long SPEED_MONITOR_MS = 500L;
    private static final long SUPPRESSOR_HEARTBEAT_MS = 300L;
    private static final long[] SUPPRESSOR_REARM_DELAYS_MS = {0L, 80L, 180L};

    private static volatile ManeuverController sInstance;

    private final Context context;
    private final Handler mainHandler;
    private final MediaKeySuppressor mediaKeySuppressor;
    private final VolumeRestoreGuard volumeRestoreGuard;
    private SharedPreferences prefs;
    private BroadcastReceiver hardkeyReceiver;
    private BroadcastReceiver logicalHardkeyReceiver;
    private SharedPreferences.OnSharedPreferenceChangeListener prefListener;

    private boolean registered;
    private volatile boolean captureActive;
    private boolean tileCleared;
    private boolean oemPaused;
    private boolean suppressorPublished;
    private boolean suppressorHeartbeatRunning;
    private int shownCameraIndex = -1;
    private CameraIndex selectedCamera = CameraIndex.REAR;

    private final Runnable speedMonitorRunnable = new Runnable() {
        @Override
        public void run() {
            refreshState();
        }
    };

    private final Runnable suppressorHeartbeatRunnable = new Runnable() {
        @Override
        public void run() {
            if (!captureActive) {
                suppressorHeartbeatRunning = false;
                return;
            }
            publishSuppressorState(true, true);
            mainHandler.postDelayed(this, SUPPRESSOR_HEARTBEAT_MS);
        }
    };

    public ManeuverController(Context context, Handler mainHandler) {
        this.context = context.getApplicationContext();
        this.mainHandler = mainHandler;
        this.mediaKeySuppressor = new MediaKeySuppressor(this.context, mainHandler);
        this.volumeRestoreGuard = new VolumeRestoreGuard(this.context, mainHandler);
    }

    public void register() {
        if (registered) return;
        prefs = UiPrefs.getPrefs(context);
        hardkeyReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                handleRawHardkey(intent, this);
            }
        };
        IntentFilter filter = new IntentFilter(ACTION_RAW_HARDKEY);
        filter.setPriority(1000);
        try {
            ContextCompat.registerReceiver(context, hardkeyReceiver, filter,
                    ContextCompat.RECEIVER_EXPORTED);
            DevRuntimeLog.add("Maneuver", "raw hardkey receiver registered");
        } catch (Throwable t) {
            DevRuntimeLog.add("Maneuver", "raw hardkey receiver failed: " + t.getClass().getSimpleName());
            Log.w(TAG, "Failed to register raw hardkey receiver", t);
        }

        logicalHardkeyReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                handleLogicalHardkey(intent, this);
            }
        };
        IntentFilter logicalFilter = new IntentFilter(ACTION_SYSTEMUI_HARDKEY);
        logicalFilter.setPriority(1000);
        try {
            ContextCompat.registerReceiver(context, logicalHardkeyReceiver, logicalFilter,
                    ContextCompat.RECEIVER_EXPORTED);
            DevRuntimeLog.add("Maneuver", "logical hardkey suppressor registered");
        } catch (Throwable t) {
            DevRuntimeLog.add("Maneuver", "logical suppressor failed: " + t.getClass().getSimpleName());
            Log.w(TAG, "Failed to register logical hardkey suppressor", t);
        }

        prefListener = (sp, key) -> {
            if (key == null || isManeuverPrefKey(key)) {
                mainHandler.post(this::refreshState);
            }
        };
        prefs.registerOnSharedPreferenceChangeListener(prefListener);
        registered = true;
        sInstance = this;
        refreshState();
    }

    public void unregister() {
        registered = false;
        sInstance = null;
        mainHandler.removeCallbacks(speedMonitorRunnable);
        mainHandler.removeCallbacks(suppressorHeartbeatRunnable);
        suppressorHeartbeatRunning = false;
        if (prefs != null && prefListener != null) {
            try {
                prefs.unregisterOnSharedPreferenceChangeListener(prefListener);
            } catch (Throwable ignored) {
            }
        }
        prefListener = null;
        if (hardkeyReceiver != null) {
            try {
                context.unregisterReceiver(hardkeyReceiver);
            } catch (Throwable ignored) {
            }
        }
        hardkeyReceiver = null;
        if (logicalHardkeyReceiver != null) {
            try {
                context.unregisterReceiver(logicalHardkeyReceiver);
            } catch (Throwable ignored) {
            }
        }
        logicalHardkeyReceiver = null;
        if (captureActive || suppressorPublished) {
            publishSuppressorState(false, false);
        }
        mediaKeySuppressor.setActive(false);
        mediaKeySuppressor.release();
        captureActive = false;
        tileCleared = false;
        oemPaused = false;
        hideManeuverTile();
    }

    public static void requestRefresh() {
        ManeuverController inst = sInstance;
        if (inst == null) return;
        inst.mainHandler.post(inst::refreshState);
    }

    public static boolean isCaptureActive(Context context) {
        return evaluateCaptureActive(UiPrefs.getPrefs(context));
    }

    public static boolean shouldBlockOemAvm(Context context) {
        SharedPreferences prefs = UiPrefs.getPrefs(context);
        return evaluateCaptureActive(prefs) && !UiPrefs.isManeuverOemAvmAllowed(prefs);
    }

    public static void onOemAvmState(Context context, boolean active) {
        ManeuverController inst = sInstance;
        if (inst == null) return;
        inst.mainHandler.post(() -> inst.handleOemAvmState(active));
    }

    public boolean isCachedCaptureActive() {
        return captureActive;
    }

    public void ensureTileState() {
        if (!captureActive) return;
        updateTileState();
    }

    private void refreshState() {
        if (prefs == null) {
            prefs = UiPrefs.getPrefs(context);
        }
        boolean nextCaptureActive = evaluateCaptureActive(prefs);
        boolean changed = nextCaptureActive != captureActive;
        captureActive = nextCaptureActive;

        if (captureActive) {
            publishSuppressorState(true, false);
            volumeRestoreGuard.captureBaseline();
            startSuppressorHeartbeat();
            updateTileState();
        } else {
            mainHandler.removeCallbacks(suppressorHeartbeatRunnable);
            suppressorHeartbeatRunning = false;
            publishSuppressorState(false, false);
            mediaKeySuppressor.setActive(false);
            if (changed) {
                tileCleared = false;
                oemPaused = false;
                hideManeuverTile();
            }
        }

        scheduleSpeedMonitorIfNeeded();
        if (changed) {
            SignalService.requestRecheck();
            DevRuntimeLog.add("Maneuver", "capture=" + captureActive
                    + " speed=" + VehicleSpeedReader.readSpeedKmh());
        }
    }

    private void updateTileState() {
        if (!captureActive) {
            mediaKeySuppressor.setActive(false);
            hideManeuverTile();
            return;
        }
        if (oemPaused && UiPrefs.isManeuverOemAvmAllowed(prefs)) {
            mediaKeySuppressor.setActive(false);
            hideManeuverTile();
            return;
        }
        if (MainActivity.isSettingsDialogOpen() || SignalService.isReverseActive()) {
            mediaKeySuppressor.setActive(false);
            hideManeuverTile();
            return;
        }
        mediaKeySuppressor.setActive(true);
        if (!tileCleared) {
            // Only (re)launch on an actual transition: a different camera, or when the
            // tile is no longer in front. Without this guard the 500ms speed monitor would
            // restart the foreground overlay service every tick.
            int target = selectedCamera.getVideoIndex();
            if (target != shownCameraIndex
                    || !OverlayService.isVisibleForReason(OverlayService.OVERLAY_REASON_MANEUVER)) {
                OverlayService.showOverlay(context, target, OverlayService.OVERLAY_REASON_MANEUVER);
                shownCameraIndex = target;
            }
        }
    }

    private void hideManeuverTile() {
        shownCameraIndex = -1;
        OverlayService.hideOverlay(context);
    }

    private void handleRawHardkey(Intent intent, BroadcastReceiver receiver) {
        if (intent == null) return;
        int rawCode = readRawKeyCode(intent);
        boolean isDown = readBooleanExtra(intent, EXTRA_RAW_DOWN, EXTRA_RAW_DOWN_ALT);
        boolean isLongPress = readBooleanExtra(intent, EXTRA_RAW_LONGPRESS,
                EXTRA_RAW_LONGPRESS_ALT, EXTRA_RAW_LONGPRESS_CAMEL);
        boolean ownsStick = shouldOwnSteeringStick() && isStickCode(rawCode);
        if (isStickCode(rawCode)) {
            DevRuntimeLog.add("Maneuver", "raw=" + formatHex(rawCode)
                    + " down=" + isDown + " long=" + isLongPress
                    + " capture=" + captureActive);
        }
        if (ownsStick) {
            suppressOrderedRawBroadcast(receiver, rawCode, isDown);
            rearmSuppressorForBurst();
        }
        if (!isDown || !ownsStick) {
            return;
        }

        if (isVolumeMappedStickCode(rawCode)) {
            volumeRestoreGuard.restoreBaselineSoon(formatHex(rawCode));
        }

        if (rawCode == RAW_SWC_CENTER) {
            toggleClear();
            return;
        }

        CameraIndex target = mapCamera(rawCode);
        if (target == null) return;
        selectedCamera = target;
        tileCleared = false;
        updateTileState();
        DevRuntimeLog.add("Maneuver", "selected " + target.name());
    }

    private void suppressOrderedRawBroadcast(BroadcastReceiver receiver, int rawCode, boolean isDown) {
        if (receiver == null) return;
        if (receiver.isOrderedBroadcast()) {
            try {
                receiver.abortBroadcast();
                if (isDown) {
                    DevRuntimeLog.add("Maneuver", "suppressed raw=" + formatHex(rawCode));
                }
            } catch (Throwable t) {
                if (isDown) {
                    DevRuntimeLog.add("Maneuver", "raw suppress failed: "
                            + t.getClass().getSimpleName());
                }
            }
        } else if (isDown) {
            DevRuntimeLog.add("Maneuver", "raw=" + formatHex(rawCode) + " not ordered");
        }
    }

    private void handleLogicalHardkey(Intent intent, BroadcastReceiver receiver) {
        if (intent == null || !shouldOwnSteeringStick()) return;
        int logicalCode = readIntExtra(intent, EXTRA_LOGICAL_KEY_CODE,
                EXTRA_RAW_KEYCODE, EXTRA_RAW_KEYCODE_ALT, EXTRA_RAW_KEYCODE_CAMEL);
        if (!isLogicalStickCode(logicalCode)) return;

        boolean down = readBooleanExtra(intent, EXTRA_LOGICAL_DOWN, EXTRA_RAW_DOWN_ALT);
        if (receiver.isOrderedBroadcast()) {
            receiver.abortBroadcast();
            if (down) {
                DevRuntimeLog.add("Maneuver", "suppressed logical=" + logicalCode);
            }
        } else if (down) {
            DevRuntimeLog.add("Maneuver", "logical=" + logicalCode + " not ordered");
        }
        rearmSuppressorForBurst();
    }

    private boolean shouldOwnSteeringStick() {
        return captureActive
                && !(oemPaused && UiPrefs.isManeuverOemAvmAllowed(prefs))
                && !MainActivity.isSettingsDialogOpen()
                && !SignalService.isReverseActive();
    }

    private void toggleClear() {
        tileCleared = !tileCleared;
        if (tileCleared) {
            hideManeuverTile();
            DevRuntimeLog.add("Maneuver", "tile view cleared");
        } else {
            updateTileState();
            DevRuntimeLog.add("Maneuver", "tile view restored");
        }
    }

    private void handleOemAvmState(boolean active) {
        if (!captureActive) return;
        if (!UiPrefs.isManeuverOemAvmAllowed(prefs)) {
            oemPaused = false;
            publishSuppressorState(true, false);
            updateTileState();
            return;
        }
        oemPaused = active;
        if (active) {
            hideManeuverTile();
            mainHandler.removeCallbacks(suppressorHeartbeatRunnable);
            suppressorHeartbeatRunning = false;
            publishSuppressorState(false, false);
            mediaKeySuppressor.setActive(false);
            DevRuntimeLog.add("Maneuver", "paused for OEM AVM");
        } else {
            publishSuppressorState(true, false);
            startSuppressorHeartbeat();
            mediaKeySuppressor.setActive(true);
            updateTileState();
            DevRuntimeLog.add("Maneuver", "resumed after OEM AVM");
        }
    }

    private void publishSuppressorState(boolean enabled, boolean heartbeat) {
        if (!heartbeat && suppressorPublished == enabled) return;
        suppressorPublished = enabled;
        Intent intent = new Intent(ACTION_SUPPRESSOR_STATE);
        intent.putExtra(EXTRA_SUPPRESSOR_ENABLED, enabled);
        intent.addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
        context.sendBroadcast(intent);
        if (!heartbeat) {
            DevRuntimeLog.add("Maneuver", "suppressor=" + enabled);
        }
    }

    private void rearmSuppressorForBurst() {
        if (!captureActive) return;
        for (long delayMs : SUPPRESSOR_REARM_DELAYS_MS) {
            if (delayMs <= 0L) {
                publishSuppressorState(true, true);
            } else {
                mainHandler.postDelayed(() -> {
                    if (captureActive) {
                        publishSuppressorState(true, true);
                    }
                }, delayMs);
            }
        }
    }

    private void startSuppressorHeartbeat() {
        if (suppressorHeartbeatRunning) return;
        suppressorHeartbeatRunning = true;
        publishSuppressorState(true, true);
        mainHandler.postDelayed(suppressorHeartbeatRunnable, SUPPRESSOR_HEARTBEAT_MS);
    }

    private void scheduleSpeedMonitorIfNeeded() {
        mainHandler.removeCallbacks(speedMonitorRunnable);
        if (prefs == null) return;
        if (UiPrefs.isManeuverModeEnabled(prefs)
                && UiPrefs.isManeuverSpeedThresholdEnabled(prefs)) {
            mainHandler.postDelayed(speedMonitorRunnable, SPEED_MONITOR_MS);
        }
    }

    private static boolean evaluateCaptureActive(SharedPreferences prefs) {
        if (!UiPrefs.isManeuverModeEnabled(prefs)) {
            return false;
        }
        if (!UiPrefs.isManeuverSpeedThresholdEnabled(prefs)) {
            return true;
        }
        int speedKmh = VehicleSpeedReader.readSpeedKmh();
        int thresholdKmh = UiPrefs.getManeuverSpeedThresholdKmh(prefs);
        return speedKmh < thresholdKmh;
    }

    private static boolean isManeuverPrefKey(String key) {
        return UiPrefs.KEY_MANEUVER_MODE_ENABLED.equals(key)
                || UiPrefs.KEY_MANEUVER_SPEED_THRESHOLD_ENABLED.equals(key)
                || UiPrefs.KEY_MANEUVER_SPEED_THRESHOLD_KMH.equals(key)
                || UiPrefs.KEY_MANEUVER_ALLOW_OEM_AVM.equals(key);
    }

    private static CameraIndex mapCamera(int rawCode) {
        switch (rawCode) {
            case RAW_SWC_UP:
                return CameraIndex.FRONT;
            case RAW_SWC_DOWN:
                return CameraIndex.REAR;
            case RAW_SWC_LEFT:
                return CameraIndex.LEFT;
            case RAW_SWC_RIGHT:
                return CameraIndex.RIGHT;
            default:
                return null;
        }
    }

    private static boolean isStickCode(int rawCode) {
        return rawCode == RAW_SWC_UP
                || rawCode == RAW_SWC_DOWN
                || rawCode == RAW_SWC_LEFT
                || rawCode == RAW_SWC_RIGHT
                || rawCode == RAW_SWC_CENTER;
    }

    private static boolean isVolumeMappedStickCode(int rawCode) {
        return rawCode == RAW_SWC_UP || rawCode == RAW_SWC_DOWN;
    }

    private static boolean isLogicalStickCode(int logicalCode) {
        return logicalCode >= 1 && logicalCode <= 15;
    }

    private static int readRawKeyCode(Intent intent) {
        return readIntExtra(intent, EXTRA_RAW_KEYCODE, EXTRA_RAW_KEYCODE_ALT, EXTRA_RAW_KEYCODE_CAMEL);
    }

    private static int readIntExtra(Intent intent, String... keys) {
        Bundle extras = intent.getExtras();
        if (extras == null) {
            return -1;
        }
        for (String key : keys) {
            if (!extras.containsKey(key)) {
                continue;
            }
            Object value = extras.get(key);
            if (value instanceof Number) {
                int intValue = ((Number) value).intValue();
                if (intValue >= 0) {
                    return intValue;
                }
            } else if (value instanceof String) {
                try {
                    int intValue = Integer.parseInt((String) value);
                    if (intValue >= 0) {
                        return intValue;
                    }
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return -1;
    }

    private static boolean readBooleanExtra(Intent intent, String... keys) {
        Bundle extras = intent.getExtras();
        if (extras == null) {
            return false;
        }
        for (String key : keys) {
            if (!extras.containsKey(key)) {
                continue;
            }
            Object value = extras.get(key);
            if (value instanceof Boolean && (Boolean) value) {
                return true;
            }
            if (value instanceof Number && ((Number) value).intValue() != 0) {
                return true;
            }
            if (value instanceof String) {
                String text = (String) value;
                if ("true".equalsIgnoreCase(text) || "1".equals(text)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static String formatHex(int value) {
        if (value < 0) return String.valueOf(value);
        return "0x" + Integer.toHexString(value);
    }
}
