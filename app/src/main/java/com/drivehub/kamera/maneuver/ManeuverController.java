package com.drivehub.kamera.maneuver;

import com.drivehub.kamera.camera.CameraIndex;
import com.drivehub.kamera.camera.OverlayService;
import com.drivehub.kamera.dev.DevRuntimeLog;
import com.drivehub.kamera.MainActivity;
import com.drivehub.kamera.helper.vehiclesensors.VehicleSpeedReader;
import com.drivehub.kamera.settings.UiPrefs;
import com.drivehub.kamera.signal.SignalService;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Handler;

public final class ManeuverController {

    public static final String ACTION_SUPPRESSOR_STATE =
            "com.drivehub.kamera.action.SET_MANEUVER_SUPPRESSOR";
    public static final String EXTRA_SUPPRESSOR_ENABLED = "enabled";

    private static final long SPEED_MONITOR_MS = 500L;
    private static final long SUPPRESSOR_HEARTBEAT_MS = 300L;
    private static final long[] SUPPRESSOR_REARM_DELAYS_MS = {0L, 80L, 180L};

    private static volatile ManeuverController sInstance;

    private final Context context;
    private final Handler mainHandler;
    private final ManeuverSuppressorController suppressorController;
    private SharedPreferences prefs;
    private ManeuverHardkeySuppressor hardkeySuppressor;
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
        this.suppressorController = new ManeuverSuppressorController(this.context, mainHandler);
    }

    public void register() {
        if (registered) return;
        prefs = UiPrefs.getPrefs(context);

        hardkeySuppressor = new ManeuverHardkeySuppressor(context, new ManeuverHardkeySuppressor.Callback() {
            @Override
            public boolean isCaptureActive() {
                return captureActive;
            }

            @Override
            public boolean shouldOwnSteeringStick() {
                return ManeuverController.this.shouldOwnSteeringStick();
            }

            @Override
            public void onRawHardkey(
                    ManeuverHardkeySuppressor.RawEvent event,
                    ManeuverHardkeySuppressor.BroadcastAbort abort
            ) {
                suppressorController.onRawHardkey(event, abort);
            }

            @Override
            public void onLogicalHardkey(
                    ManeuverHardkeySuppressor.LogicalEvent event,
                    ManeuverHardkeySuppressor.BroadcastAbort abort
            ) {
                suppressorController.onLogicalHardkey(event, abort);
            }

            @Override
            public void onStickDown(int rawCode) {
                handleStickDown(rawCode);
            }

            @Override
            public void onSuppressorRearmRequested() {
                rearmSuppressorForBurst();
            }
        });
        hardkeySuppressor.register();

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
        if (hardkeySuppressor != null) {
            hardkeySuppressor.unregister();
        }
        hardkeySuppressor = null;
        if (captureActive || suppressorPublished) {
            publishSuppressorState(false, false);
        }
        suppressorController.release();
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

    public static boolean shouldInterceptManeuverKeys(Context context) {
        ManeuverController inst = sInstance;
        if (inst != null) {
            return inst.shouldOwnSteeringStick();
        }
        SharedPreferences prefs = UiPrefs.getPrefs(context);
        return evaluateCaptureActive(prefs)
                && !MainActivity.isSettingsDialogOpen()
                && !SignalService.isReverseActive();
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
        syncSuppressorMode();

        if (captureActive) {
            publishSuppressorState(true, false);
            startSuppressorHeartbeat();
            updateTileState();
        } else {
            mainHandler.removeCallbacks(suppressorHeartbeatRunnable);
            suppressorHeartbeatRunning = false;
            publishSuppressorState(false, false);
            suppressorController.setOwningSteeringStick(false);
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
        syncSuppressorOwnership();
        if (!captureActive) {
            hideManeuverTile();
            return;
        }
        if (oemPaused && UiPrefs.isManeuverOemAvmAllowed(prefs)) {
            hideManeuverTile();
            return;
        }
        if (MainActivity.isSettingsDialogOpen() || SignalService.isReverseActive()) {
            hideManeuverTile();
            return;
        }
        if (!tileCleared) {
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

    private void syncSuppressorMode() {
        if (prefs == null) return;
        suppressorController.setMode(UiPrefs.getDevManeuverSuppressorMode(prefs));
    }

    private void syncSuppressorOwnership() {
        syncSuppressorMode();
        suppressorController.setOwningSteeringStick(shouldOwnSteeringStick());
    }

    private void handleStickDown(int rawCode) {
        suppressorController.onStickDown(rawCode);

        if (rawCode == ManeuverHardkeySuppressor.RAW_SWC_CENTER) {
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
            syncSuppressorOwnership();
            DevRuntimeLog.add("Maneuver", "paused for OEM AVM");
        } else {
            publishSuppressorState(true, false);
            startSuppressorHeartbeat();
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
                || UiPrefs.KEY_MANEUVER_ALLOW_OEM_AVM.equals(key)
                || UiPrefs.KEY_DEV_MANEUVER_SUPPRESSOR_MODE.equals(key);
    }

    // ---- Key code helpers ----

    private static CameraIndex mapCamera(int rawCode) {
        switch (rawCode) {
            case ManeuverHardkeySuppressor.RAW_SWC_UP:
                return CameraIndex.FRONT;
            case ManeuverHardkeySuppressor.RAW_SWC_DOWN:
                return CameraIndex.REAR;
            case ManeuverHardkeySuppressor.RAW_SWC_LEFT:
                return CameraIndex.LEFT;
            case ManeuverHardkeySuppressor.RAW_SWC_RIGHT:
                return CameraIndex.RIGHT;
            default:
                return null;
        }
    }
}
