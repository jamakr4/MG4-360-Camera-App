package com.drivehub.kamera.settings;

import com.drivehub.kamera.BuildConfig;
import com.drivehub.kamera.R;

import com.drivehub.kamera.MainActivity;
import com.drivehub.kamera.CameraProbe;
import com.drivehub.kamera.dashcam.DashcamSettingsController;
import com.drivehub.kamera.dashcam.RecordingService;
import com.drivehub.kamera.dev.DevRuntimeLog;
import com.drivehub.kamera.dev.DevSettingsController;
import com.drivehub.kamera.maneuver.ManeuverController;
import com.drivehub.kamera.maneuver.ManeuverSettingsController;
import com.drivehub.kamera.ota.OtaController;
import com.drivehub.kamera.signal.SignalCameraSettingsController;
import com.drivehub.kamera.signal.SignalService;
import com.drivehub.kamera.settings.SegmentedControl;

import android.app.Dialog;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;

/**
 * Owns the settings dialog: inflates it, wires up all section controllers (signal cam,
 * dashcam, dev, OTA, appearance), and reacts to recording-status updates while the
 * dialog is on screen. MainActivity only knows show() and onRecordingStatusChanged().
 */
public final class SettingsDialogController {

    private static final int DEFAULT_TAB_INDEX = 1; // Settings
    private static final int DEV_TAB_INDEX = 6;
    private static final int DEV_STATUS_TAB_INDEX = 7;
    private static final int DEV_UNLOCK_TAPS = 5;
    private static final int EXPECTED_TOTAL_CAMERAS = 4;
    private static final int CAMERA_PROBE_MAX_INDEX = 18;
    private static final int[] EXPECTED_VIDEO_INDEXES = {14, 15, 16, 17};
    private static final long DEV_STATUS_REFRESH_INTERVAL_MS = 500L;

    private final MainActivity activity;
    private final SettingsAppearanceController appearance;
    private final OtaController ota;
    private final Runnable onSafetyWarningChanged;

    private final SignalCameraSettingsController signalCam;
    private final ManeuverSettingsController maneuver = new ManeuverSettingsController();
    private final DashcamSettingsController dashcam;
    private final DevSettingsController dev = new DevSettingsController();

    private final Handler devStatusHandler = new Handler(Looper.getMainLooper());
    private final Runnable devStatusRefreshRunnable = new Runnable() {
        @Override
        public void run() {
            if (views == null) return;
            RecordingService.PersistedStatus s = RecordingService.readPersistedStatus(UiPrefs.getPrefs(activity));
            refreshDevStatusSection(s.status, s.activeCameras, s.totalCameras, s.lastError);
            devStatusHandler.postDelayed(this, DEV_STATUS_REFRESH_INTERVAL_MS);
        }
    };

    private Dialog dialog;
    private Views views;

    public SettingsDialogController(
            MainActivity activity,
            SettingsAppearanceController appearance,
            OtaController ota,
            Runnable onSafetyWarningChanged
    ) {
        this.activity = activity;
        this.appearance = appearance;
        this.ota = ota;
        this.onSafetyWarningChanged = onSafetyWarningChanged;
        // Sub-controllers need the activity for permission requests / lifecycle hooks.
        this.signalCam = new SignalCameraSettingsController(activity);
        this.dashcam = new DashcamSettingsController(activity);
    }

    public boolean isOpen() {
        return dialog != null;
    }

    /**
     * Forwards a fresh recording-status snapshot into the dialog. No-op when closed.
     */
    public void onRecordingStatusChanged(String status, int activeCameras, int totalCameras, String lastError) {
        if (views == null) return;
        String text = RecordingService.formatStatusText(activity, status, activeCameras, totalCameras, lastError);
        if (views.tvDashcamRecordingStatus != null) {
            views.tvDashcamRecordingStatus.setText(text);
        }
        refreshDevStatusSection(status, activeCameras, totalCameras, lastError);
    }

    @SuppressWarnings("deprecation")
    public void show() {
        MainActivity.setSettingsDialogOpen(true);
        ManeuverController.requestRefresh();
        SignalService.requestRecheck();

        dialog = new Dialog(activity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(R.layout.dialog_settings);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.setSoftInputMode(
                    WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                            | WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN);
        }

        SharedPreferences prefs = UiPrefs.getPrefs(activity);
        views = bindViews(prefs);
        wireSubControllers(prefs);
        wireAppearance(prefs);
        wireDevLogControls();
        wireTabs();
        wireDevUnlock();
        wireOta();

        views.dialogClose.setOnClickListener(v -> dialog.dismiss());
        dialog.setOnDismissListener(d -> handleDismiss());

        // Render the dialog's status views with the current persisted state.
        RecordingService.PersistedStatus s = RecordingService.readPersistedStatus(prefs);
        onRecordingStatusChanged(s.status, s.activeCameras, s.totalCameras, s.lastError);

        dialog.show();
        applyDialogWindowSize(dialog);
    }

    private void handleDismiss() {
        MainActivity.setSettingsDialogOpen(false);
        devStatusHandler.removeCallbacks(devStatusRefreshRunnable);
        dashcam.onDismiss();
        views = null;
        dialog = null;
        appearance.applyMainUiIconColors();
        ManeuverController.requestRefresh();
        SignalService.requestRecheck();
    }

    private void applyDialogWindowSize(Dialog d) {
        Window w = d.getWindow();
        if (w == null) return;
        float density = activity.getResources().getDisplayMetrics().density;
        int screenWidth = activity.getResources().getDisplayMetrics().widthPixels;
        int horizontalMargin = (int) (48 * density);
        int preferredWidth = (int) (880 * density);
        int dialogWidth = Math.min(preferredWidth, screenWidth - horizontalMargin);
        w.setLayout(dialogWidth, (int) (560 * density));
    }

    // ---------------------------------------------------------------- bind + wire

    private Views bindViews(SharedPreferences prefs) {
        Views v = new Views();
        v.swOverlay = dialog.findViewById(R.id.switchOverlayOnSignal);
        v.swManeuverMode = dialog.findViewById(R.id.switchManeuverMode);
        v.swManeuverThreshold = dialog.findViewById(R.id.switchManeuverThreshold);
        v.etManeuverThresholdKmh = dialog.findViewById(R.id.etManeuverThresholdKmh);
        v.swManeuverAllowOemAvm = dialog.findViewById(R.id.switchManeuverAllowOemAvm);
        v.swDigitalRearview = dialog.findViewById(R.id.switchDigitalRearview);
        v.swRearviewTapToHide = dialog.findViewById(R.id.switchRearviewTapToHide);
        v.etRearviewTapToHideDurationSec = dialog.findViewById(R.id.etRearviewTapToHideDurationSec);
        v.swRotateToDrivingDirection = dialog.findViewById(R.id.switchOverlayRotateToDrivingDirection);
        v.swDashcamEnabled = dialog.findViewById(R.id.switchDashcamEnabled);
        v.swSafetyWarning = dialog.findViewById(R.id.switchSafetyWarning);
        v.swOemAvmCoexist = dialog.findViewById(R.id.switchOemAvmCoexist);
        v.swAllowBetaUpdates = dialog.findViewById(R.id.switchAllowBetaUpdates);
        v.seekOverlayHideDelay = dialog.findViewById(R.id.seekOverlayHideDelay);
        v.etOverlayHideDelayValue = dialog.findViewById(R.id.etOverlayHideDelayValue);
        v.seekOverlayMinShow = dialog.findViewById(R.id.seekOverlayMinShow);
        v.etOverlayMinShowValue = dialog.findViewById(R.id.etOverlayMinShowValue);
        v.swSignalTapToHide = dialog.findViewById(R.id.switchSignalTapToHide);
        v.etSignalTapToHideDurationSec = dialog.findViewById(R.id.etSignalTapToHideDurationSec);
        v.etDashcamFps = dialog.findViewById(R.id.etDashcamFps);
        v.etDashcamSignature = dialog.findViewById(R.id.etDashcamSignature);
        v.swDashcamShowSpeed = dialog.findViewById(R.id.switchDashcamShowSpeed);
        v.swDashcamTestRecordEnabled = dialog.findViewById(R.id.switchDashcamTestRecordEnabled);
        v.etDashcamTestRecordDuration = dialog.findViewById(R.id.etDashcamTestRecordDuration);
        // Temporarily disabled storage UI:
        // v.segDashcamStorageTarget = dialog.findViewById(R.id.segDashcamStorageTarget);
        // v.tvDashcamStorageStatus = dialog.findViewById(R.id.tvDashcamStorageStatus);
        // v.tvDashcamStorageActivePath = dialog.findViewById(R.id.tvDashcamStorageActivePath);
        // v.btnDashcamStorageEjectUsb = dialog.findViewById(R.id.btnDashcamStorageEjectUsb);
        // v.tvDashcamStorageInternalWarning = dialog.findViewById(R.id.tvDashcamStorageInternalWarning);
        // v.etDashcamUsbClipCount = dialog.findViewById(R.id.etDashcamUsbClipCount);
        // v.etDashcamUsbEventDirs = dialog.findViewById(R.id.etDashcamUsbEventDirs);
        v.segDashcamStorageTarget = null;
        v.tvDashcamStorageStatus = null;
        v.tvDashcamStorageActivePath = null;
        v.btnDashcamStorageEjectUsb = null;
        v.tvDashcamStorageInternalWarning = null;
        v.etDashcamUsbClipCount = null;
        v.etDashcamUsbEventDirs = null;
        v.swBannerEvent = dialog.findViewById(R.id.switchBannerEvent);
        v.segBannerEventSize = dialog.findViewById(R.id.segBannerEventSize);
        v.seekBannerEventVolume = dialog.findViewById(R.id.seekBannerEventVolume);
        v.tvBannerEventVolumeValue = dialog.findViewById(R.id.tvBannerEventVolumeValue);
        v.btnBannerEventTest = dialog.findViewById(R.id.btnBannerEventTest);
        v.swBannerPauseResume = dialog.findViewById(R.id.switchBannerPauseResume);
        v.segBannerPauseResumeSize = dialog.findViewById(R.id.segBannerPauseResumeSize);
        v.seekBannerPauseResumeVolume = dialog.findViewById(R.id.seekBannerPauseResumeVolume);
        v.tvBannerPauseResumeVolumeValue = dialog.findViewById(R.id.tvBannerPauseResumeVolumeValue);
        v.btnBannerPauseResumeTest = dialog.findViewById(R.id.btnBannerPauseResumeTest);
        v.swBannerErrorRecovered = dialog.findViewById(R.id.switchBannerErrorRecovered);
        v.segBannerErrorRecoveredSize = dialog.findViewById(R.id.segBannerErrorRecoveredSize);
        v.seekBannerErrorRecoveredVolume = dialog.findViewById(R.id.seekBannerErrorRecoveredVolume);
        v.tvBannerErrorRecoveredVolumeValue = dialog.findViewById(R.id.tvBannerErrorRecoveredVolumeValue);
        v.btnBannerErrorRecoveredTest = dialog.findViewById(R.id.btnBannerErrorRecoveredTest);
        v.tvDashcamRecordingStatus = dialog.findViewById(R.id.tvDashcamRecordingStatus);
        v.etDevDefaultPollMs = dialog.findViewById(R.id.etDevDefaultPollMs);
        v.etDevSignalOffPollMs = dialog.findViewById(R.id.etDevSignalOffPollMs);
        v.etDevForegroundModePollMs = dialog.findViewById(R.id.etDevForegroundModePollMs);
        v.etDevOemAvmMaxSpeedKmh = dialog.findViewById(R.id.etDevOemAvmMaxSpeedKmh);
        v.spinnerDevManeuverSuppressorMode = dialog.findViewById(R.id.spinnerDevManeuverSuppressorMode);
        v.seekDevOverlayTopInsetPx = dialog.findViewById(R.id.seekDevOverlayTopInsetPx);
        v.etDevOverlayTopInsetPx = dialog.findViewById(R.id.etDevOverlayTopInsetPx);
        v.etDevDashcamRetentionClipCount = dialog.findViewById(R.id.etDevDashcamRetentionClipCount);
        v.etDevDashcamMaxEventDirs = dialog.findViewById(R.id.etDevDashcamMaxEventDirs);
        v.etDevDashcamRecordsPath = dialog.findViewById(R.id.etDevDashcamRecordsPath);
        v.tvDevDashcamRecordsPath = dialog.findViewById(R.id.tvDevDashcamRecordsPath);
        v.btnDevDashcamBrowseFolder = dialog.findViewById(R.id.btnDevDashcamBrowseFolder);
        v.btnDevResetDefaults = dialog.findViewById(R.id.btnDevResetDefaults);
        v.seekCorner = dialog.findViewById(R.id.seekCornerRadius);
        v.etCorner = dialog.findViewById(R.id.etCornerRadius);
        v.dialogClose = dialog.findViewById(R.id.btnClose);
        v.tabUpdate = dialog.findViewById(R.id.tabUpdate);
        v.tabSettings = dialog.findViewById(R.id.tabSettings);
        v.tabSignalCamera = dialog.findViewById(R.id.tabSignalCamera);
        v.tabManeuverMode = dialog.findViewById(R.id.tabManeuverMode);
        v.tabDashcam = dialog.findViewById(R.id.tabDashcam);
        v.tabCredits = dialog.findViewById(R.id.tabCredits);
        v.tabDev = dialog.findViewById(R.id.tabDev);
        v.tabDevStatus = dialog.findViewById(R.id.tabDevStatus);
        v.settingsTabs = new TextView[]{
                v.tabUpdate, v.tabSettings, v.tabSignalCamera, v.tabManeuverMode, v.tabDashcam,
                v.tabCredits, v.tabDev, v.tabDevStatus
        };
        v.sectionUpdate = dialog.findViewById(R.id.sectionUpdate);
        v.sectionSettings = dialog.findViewById(R.id.sectionSettings);
        v.sectionSignalCamera = dialog.findViewById(R.id.sectionSignalCamera);
        v.sectionManeuverMode = dialog.findViewById(R.id.sectionManeuverMode);
        v.sectionDashcam = dialog.findViewById(R.id.sectionDashcam);
        v.sectionCredits = dialog.findViewById(R.id.sectionCredits);
        v.sectionDev = dialog.findViewById(R.id.sectionDev);
        v.sectionDevStatus = dialog.findViewById(R.id.sectionDevStatus);
        v.settingsSections = new View[]{
                v.sectionUpdate, v.sectionSettings, v.sectionSignalCamera, v.sectionManeuverMode, v.sectionDashcam,
                v.sectionCredits, v.sectionDev, v.sectionDevStatus
        };
        v.accentRow = dialog.findViewById(R.id.rowAccentColor);
        v.accentPreview = dialog.findViewById(R.id.viewAccentPreview);
        v.etAccentColor = dialog.findViewById(R.id.etAccentColor);
        v.btnDevStatusLogClear = dialog.findViewById(R.id.btnDevStatusLogClear);
        v.tvVersion = dialog.findViewById(R.id.tvDialogVersion);
        v.tvBeta = dialog.findViewById(R.id.tvDialogVersionBeta);
        v.tvDevStatusSignalService = dialog.findViewById(R.id.tvDevStatusSignalService);
        v.tvDevStatusRecordingService = dialog.findViewById(R.id.tvDevStatusRecordingService);
        v.tvDevStatusTurnLamp = dialog.findViewById(R.id.tvDevStatusTurnLamp);
        v.tvDevStatusHazardLights = dialog.findViewById(R.id.tvDevStatusHazardLights);
        v.tvDevStatusDashcamEnabled = dialog.findViewById(R.id.tvDevStatusDashcamEnabled);
        v.tvDevStatusRecordingState = dialog.findViewById(R.id.tvDevStatusRecordingState);
        v.tvDevStatusLastError = dialog.findViewById(R.id.tvDevStatusLastError);
        v.tvDevStatusAvailableCameras = dialog.findViewById(R.id.tvDevStatusAvailableCameras);
        v.tvDevStatusCameraProbe = dialog.findViewById(R.id.tvDevStatusCameraProbe);
        v.tvDevStatusStorageWritable = dialog.findViewById(R.id.tvDevStatusStorageWritable);
        v.tvDevStatusRecordsPath = dialog.findViewById(R.id.tvDevStatusRecordsPath);
        v.tvDevStatusEventLog = dialog.findViewById(R.id.tvDevStatusEventLog);

        v.swSafetyWarning.setChecked(UiPrefs.isSafetyWarningEnabled(prefs));
        v.swSafetyWarning.setOnCheckedChangeListener((btn, checked) -> {
            UiPrefs.setSafetyWarningEnabled(prefs, checked);
            onSafetyWarningChanged.run();
        });
        if (v.swOemAvmCoexist != null) {
            v.swOemAvmCoexist.setChecked(UiPrefs.isOemAvmCoexistEnabled(prefs));
            v.swOemAvmCoexist.setOnCheckedChangeListener((btn, checked) ->
                    UiPrefs.setOemAvmCoexistEnabled(prefs, checked));
        }
        return v;
    }

    private void wireSubControllers(SharedPreferences prefs) {
        signalCam.bind(
                prefs,
                views.swOverlay,
                views.swDigitalRearview,
                views.swRearviewTapToHide,
                views.etRearviewTapToHideDurationSec,
                views.swSignalTapToHide,
                views.etSignalTapToHideDurationSec,
                views.swRotateToDrivingDirection,
                views.seekOverlayHideDelay,
                views.etOverlayHideDelayValue,
                views.seekOverlayMinShow,
                views.etOverlayMinShowValue
        );
        maneuver.bind(
                prefs,
                views.swManeuverMode,
                views.swManeuverThreshold,
                views.etManeuverThresholdKmh,
                views.swManeuverAllowOemAvm
        );
        dashcam.bind(
                prefs,
                views.swDashcamEnabled,
                views.etDashcamFps,
                views.etDashcamSignature,
                views.swDashcamShowSpeed,
                views.swDashcamTestRecordEnabled,
                views.etDashcamTestRecordDuration,
                // Re-enable this block when the storage section comes back:
                // new DashcamSettingsController.StorageViews(
                //         views.segDashcamStorageTarget,
                //         views.tvDashcamStorageStatus,
                //         views.tvDashcamStorageActivePath,
                //         views.btnDashcamStorageEjectUsb,
                //         views.tvDashcamStorageInternalWarning,
                //         views.etDashcamUsbClipCount,
                //         views.etDashcamUsbEventDirs),
                null,
                new DashcamSettingsController.BannerGroupViews(
                        views.swBannerEvent,
                        views.segBannerEventSize,
                        views.seekBannerEventVolume,
                        views.tvBannerEventVolumeValue,
                        views.btnBannerEventTest),
                new DashcamSettingsController.BannerGroupViews(
                        views.swBannerPauseResume,
                        views.segBannerPauseResumeSize,
                        views.seekBannerPauseResumeVolume,
                        views.tvBannerPauseResumeVolumeValue,
                        views.btnBannerPauseResumeTest),
                new DashcamSettingsController.BannerGroupViews(
                        views.swBannerErrorRecovered,
                        views.segBannerErrorRecoveredSize,
                        views.seekBannerErrorRecoveredVolume,
                        views.tvBannerErrorRecoveredVolumeValue,
                        views.btnBannerErrorRecoveredTest)
        );
        dev.bind(
                prefs,
                views.seekDevOverlayTopInsetPx,
                views.etDevOverlayTopInsetPx,
                views.etDevForegroundModePollMs,
                views.etDevDefaultPollMs,
                views.etDevSignalOffPollMs,
                views.etDevOemAvmMaxSpeedKmh,
                views.spinnerDevManeuverSuppressorMode,
                views.etDevDashcamRetentionClipCount,
                views.etDevDashcamMaxEventDirs,
                views.etDevDashcamRecordsPath,
                views.tvDevDashcamRecordsPath,
                views.btnDevDashcamBrowseFolder,
                views.btnDevResetDefaults
        );
    }

    private void wireAppearance(SharedPreferences prefs) {
        appearance.bindSettingsAppearance(
                prefs,
                new Switch[]{
                        views.swOverlay,
                        views.swManeuverMode,
                        views.swManeuverThreshold,
                        views.swManeuverAllowOemAvm,
                        views.swDigitalRearview,
                        views.swRearviewTapToHide,
                        views.swSignalTapToHide,
                        views.swRotateToDrivingDirection,
                        views.swDashcamEnabled,
                        views.swDashcamShowSpeed,
                        views.swDashcamTestRecordEnabled,
                        views.swOemAvmCoexist,
                        views.swBannerEvent,
                        views.swBannerPauseResume,
                        views.swBannerErrorRecovered,
                        views.swSafetyWarning,
                        views.swAllowBetaUpdates
                },
                views.dialogClose,
                new SeekBar[]{
                        views.seekCorner,
                        views.seekOverlayHideDelay,
                        views.seekOverlayMinShow,
                        views.seekBannerEventVolume,
                        views.seekBannerPauseResumeVolume,
                        views.seekBannerErrorRecoveredVolume
                },
                views.seekCorner,
                views.etCorner,
                views.accentRow,
                views.accentPreview,
                views.etAccentColor,
                views.tabUpdate,
                views.tabSettings,
                views.tabSignalCamera,
                views.tabManeuverMode,
                views.tabDashcam,
                views.tabCredits,
                views.tabDev,
                views.tabDevStatus
        );
    }

    private void wireDevLogControls() {
        if (views.btnDevStatusLogClear == null) return;
        views.btnDevStatusLogClear.setOnClickListener(v -> {
            DevRuntimeLog.clear();
            DevRuntimeLog.add("UI", "Dev runtime log cleared");
            if (views.tvDevStatusEventLog != null) {
                views.tvDevStatusEventLog.setText(DevRuntimeLog.snapshot());
            }
        });
    }

    private void wireTabs() {
        switchTab(DEFAULT_TAB_INDEX);
        for (int i = 0; i < views.settingsTabs.length; i++) {
            final int tabIndex = i;
            TextView tab = views.settingsTabs[i];
            if (tab == null) continue;
            tab.setOnClickListener(v -> switchTab(tabIndex));
        }
    }

    private void switchTab(int activeIndex) {
        for (int i = 0; i < views.settingsSections.length; i++) {
            if (views.settingsSections[i] != null) {
                views.settingsSections[i].setVisibility(i == activeIndex ? View.VISIBLE : View.GONE);
            }
        }
        for (int i = 0; i < views.settingsTabs.length; i++) {
            if (views.settingsTabs[i] != null) {
                appearance.styleSettingsTab(views.settingsTabs[i], i == activeIndex);
            }
        }
        appearance.reapplyForActiveTab(activeIndex);
        // Dev status tab shows live updates (turn lamp, dashcam enabled, ...) — poll
        // periodically; otherwise the display freezes at the state when the dialog opened.
        devStatusHandler.removeCallbacks(devStatusRefreshRunnable);
        if (activeIndex == DEV_STATUS_TAB_INDEX) {
            devStatusHandler.post(devStatusRefreshRunnable);
        }
    }

    private void wireDevUnlock() {
        final int[] devTapCount = {0};
        final boolean[] devUnlocked = {false};
        try {
            String version = activity.getPackageManager().getPackageInfo(activity.getPackageName(), 0).versionName;
            views.tvVersion.setText(activity.getString(R.string.settings_version_format, version));
        } catch (Exception ignored) {
            views.tvVersion.setText(R.string.settings_version_unknown);
        }
        views.tvBeta.setVisibility(BuildConfig.IS_BETA ? View.VISIBLE : View.GONE);
        View.OnClickListener unlockDevListener = v -> {
            if (devUnlocked[0]) return;
            devTapCount[0]++;
            if (devTapCount[0] < DEV_UNLOCK_TAPS) return;
            devUnlocked[0] = true;
            if (views.tabDev != null) views.tabDev.setVisibility(View.VISIBLE);
            if (views.tabDevStatus != null) views.tabDevStatus.setVisibility(View.VISIBLE);
            switchTab(DEV_TAB_INDEX);
            Toast.makeText(activity, R.string.settings_dev_unlocked, Toast.LENGTH_SHORT).show();
        };
        views.tvVersion.setOnClickListener(unlockDevListener);
        views.tvBeta.setOnClickListener(unlockDevListener);
    }

    private void wireOta() {
        ota.setup(
                dialog,
                dialog.findViewById(R.id.tvDialogUpdateTag),
                dialog.findViewById(R.id.switchAllowBetaUpdates),
                dialog.findViewById(R.id.tvUpdateReleaseTitle),
                dialog.findViewById(R.id.tvUpdateChannelStatus),
                dialog.findViewById(R.id.tvUpdateChangelog),
                dialog.findViewById(R.id.tvUpdateSourceGithub),
                dialog.findViewById(R.id.tvUpdateSourceGitlab)
        );
    }

    // ---------------------------------------------------------------- dev status

    private void refreshDevStatusSection(String recordingStatus, int activeCameras, int totalCameras, String lastError) {
        if (views == null
                || views.tvDevStatusSignalService == null
                || views.tvDevStatusRecordingService == null
                || views.tvDevStatusTurnLamp == null
                || views.tvDevStatusHazardLights == null
                || views.tvDevStatusDashcamEnabled == null
                || views.tvDevStatusRecordingState == null
                || views.tvDevStatusLastError == null
                || views.tvDevStatusAvailableCameras == null
                || views.tvDevStatusCameraProbe == null
                || views.tvDevStatusStorageWritable == null
                || views.tvDevStatusRecordsPath == null
                || views.tvDevStatusEventLog == null) {
            return;
        }

        SharedPreferences prefs = UiPrefs.getPrefs(activity);
        File recordsDir = DashcamSettingsController.getRecordsBaseDir(activity);
        String error = lastError == null || lastError.trim().isEmpty()
                ? activity.getString(R.string.settings_status_none)
                : lastError.trim();
        String cameraProbe = readCameraProbeSummary();

        views.tvDevStatusSignalService.setText(activity.getString(
                SignalService.isRunning() ? R.string.settings_status_active : R.string.settings_status_inactive));
        views.tvDevStatusRecordingService.setText(activity.getString(
                RecordingService.isRunning() ? R.string.settings_status_active : R.string.settings_status_inactive));
        views.tvDevStatusTurnLamp.setText(SignalService.getCurrentTurnLampDebugText());
        views.tvDevStatusHazardLights.setText(SignalService.getCurrentHazardDebugText(activity));
        views.tvDevStatusDashcamEnabled.setText(activity.getString(
                UiPrefs.isDashcamEnabled(prefs)
                        ? R.string.settings_status_enabled
                        : R.string.settings_status_disabled));
        views.tvDevStatusRecordingState.setText(RecordingService.formatStatusText(
                activity, recordingStatus, activeCameras, totalCameras, lastError));
        views.tvDevStatusLastError.setText(error);
        views.tvDevStatusAvailableCameras.setText(String.valueOf(countExpectedAvailableCameras(cameraProbe)));
        views.tvDevStatusCameraProbe.setText(cameraProbe);
        views.tvDevStatusStorageWritable.setText(activity.getString(
                recordsDir.exists() && recordsDir.canWrite()
                        ? R.string.settings_status_active
                        : R.string.settings_status_inactive));
        views.tvDevStatusRecordsPath.setText(recordsDir.getAbsolutePath());
        views.tvDevStatusEventLog.setText(DevRuntimeLog.snapshot());
    }

    private String readCameraProbeSummary() {
        try {
            String summary = CameraProbe.probeAll(CAMERA_PROBE_MAX_INDEX);
            if (summary == null || summary.trim().isEmpty()) {
                return activity.getString(R.string.settings_status_none);
            }
            return summary.trim();
        } catch (Throwable t) {
            return t.getClass().getSimpleName();
        }
    }

    private int countExpectedAvailableCameras(String cameraProbe) {
        if (cameraProbe == null || cameraProbe.trim().isEmpty()) return 0;
        int count = 0;
        for (int videoIndex : EXPECTED_VIDEO_INDEXES) {
            if (cameraProbe.contains("/dev/video" + videoIndex)
                    || cameraProbe.contains("video" + videoIndex)) {
                count++;
            }
        }
        return count;
    }

    // ---------------------------------------------------------------- view holder

    private static final class Views {
        Switch swOverlay;
        Switch swManeuverMode;
        Switch swManeuverThreshold;
        EditText etManeuverThresholdKmh;
        Switch swManeuverAllowOemAvm;
        Switch swDigitalRearview;
        Switch swRearviewTapToHide;
        EditText etRearviewTapToHideDurationSec;
        Switch swSignalTapToHide;
        EditText etSignalTapToHideDurationSec;
        Switch swRotateToDrivingDirection;
        Switch swDashcamEnabled;
        Switch swSafetyWarning;
        Switch swOemAvmCoexist;
        Switch swAllowBetaUpdates;
        SeekBar seekOverlayHideDelay;
        EditText etOverlayHideDelayValue;
        SeekBar seekOverlayMinShow;
        EditText etOverlayMinShowValue;
        EditText etDashcamFps;
        EditText etDashcamSignature;
        Switch swDashcamShowSpeed;
        Switch swDashcamTestRecordEnabled;
        EditText etDashcamTestRecordDuration;
        SegmentedControl segDashcamStorageTarget;
        TextView tvDashcamStorageStatus;
        TextView tvDashcamStorageActivePath;
        Button btnDashcamStorageEjectUsb;
        TextView tvDashcamStorageInternalWarning;
        EditText etDashcamUsbClipCount;
        EditText etDashcamUsbEventDirs;
        Switch swBannerEvent;
        SegmentedControl segBannerEventSize;
        SeekBar seekBannerEventVolume;
        TextView tvBannerEventVolumeValue;
        Button btnBannerEventTest;
        Switch swBannerPauseResume;
        SegmentedControl segBannerPauseResumeSize;
        SeekBar seekBannerPauseResumeVolume;
        TextView tvBannerPauseResumeVolumeValue;
        Button btnBannerPauseResumeTest;
        Switch swBannerErrorRecovered;
        SegmentedControl segBannerErrorRecoveredSize;
        SeekBar seekBannerErrorRecoveredVolume;
        TextView tvBannerErrorRecoveredVolumeValue;
        Button btnBannerErrorRecoveredTest;
        TextView tvDashcamRecordingStatus;
        EditText etDevDefaultPollMs;
        EditText etDevSignalOffPollMs;
        EditText etDevForegroundModePollMs;
        EditText etDevOemAvmMaxSpeedKmh;
        Spinner spinnerDevManeuverSuppressorMode;
        SeekBar seekDevOverlayTopInsetPx;
        EditText etDevOverlayTopInsetPx;
        EditText etDevDashcamRetentionClipCount;
        EditText etDevDashcamMaxEventDirs;
        EditText etDevDashcamRecordsPath;
        TextView tvDevDashcamRecordsPath;
        Button btnDevDashcamBrowseFolder;
        Button btnDevResetDefaults;
        SeekBar seekCorner;
        EditText etCorner;
        ImageButton dialogClose;
        TextView tabUpdate;
        TextView tabSettings;
        TextView tabSignalCamera;
        TextView tabManeuverMode;
        TextView tabDashcam;
        TextView tabCredits;
        TextView tabDev;
        TextView tabDevStatus;
        TextView[] settingsTabs;
        View sectionUpdate;
        View sectionSettings;
        View sectionSignalCamera;
        View sectionManeuverMode;
        View sectionDashcam;
        View sectionCredits;
        View sectionDev;
        View sectionDevStatus;
        View[] settingsSections;
        View accentRow;
        View accentPreview;
        EditText etAccentColor;
        Button btnDevStatusLogClear;
        TextView tvVersion;
        TextView tvBeta;
        TextView tvDevStatusSignalService;
        TextView tvDevStatusRecordingService;
        TextView tvDevStatusTurnLamp;
        TextView tvDevStatusHazardLights;
        TextView tvDevStatusDashcamEnabled;
        TextView tvDevStatusRecordingState;
        TextView tvDevStatusLastError;
        TextView tvDevStatusAvailableCameras;
        TextView tvDevStatusCameraProbe;
        TextView tvDevStatusStorageWritable;
        TextView tvDevStatusRecordsPath;
        TextView tvDevStatusEventLog;
    }
}
