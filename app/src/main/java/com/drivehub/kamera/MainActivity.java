package com.drivehub.kamera;

import android.annotation.SuppressLint;
import android.app.Dialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.Window;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

public class MainActivity extends AppCompatActivity implements SurfaceHolder.Callback {

    private static final String AVM_PREFS_NAME = "AVM_Settings";
    private static final String KEY_SAFETY_WARNING = "ShowSafetyWarning";
    private static final int SWIPE_THRESHOLD_PX = 140;

    private SurfaceHolder surfaceHolder;
    private TextView tvStatus;
    private View recordingStatusPill;
    private View recordingStatusDot;
    private TextView tvRecordingStatus;
    private TextView tvDashcamDialogStatus;
    private Button btnRecordTestClip;
    private int currentVideoIndex = 15;
    private int activePreviewCameraIndex = -1;
    private boolean previewRunning = false;
    private boolean previewPausedForTestClip = false;
    private float downX = 0f;
    private float downY = 0f;

    private static volatile boolean sMainVisible = false;
    private static volatile boolean sSettingsDialogOpen = false;
    private final SettingsAppearanceController appearanceController = new SettingsAppearanceController(this);
    private final SignalCameraSettingsController signalCameraSettingsController =
            new SignalCameraSettingsController(this);
    private final DashcamSettingsController dashcamSettingsController =
            new DashcamSettingsController(this);

    private final OtaController otaController = new OtaController(this);

    private final BroadcastReceiver cameraRouteReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null) return;
            if (!SignalService.ACTION_ROUTE_CAMERA.equals(intent.getAction())) return;
            int idx = intent.getIntExtra(SignalService.EXTRA_CAMERA_INDEX, currentVideoIndex);
            if (idx == currentVideoIndex) return;
            currentVideoIndex = idx;
            if (tvStatus != null) {
                tvStatus.setText(getString(R.string.main_preview_status, cameraLabel(currentVideoIndex)));
            }
            startPreviewIfReady();
        }
    };

    private final BroadcastReceiver recordingStatusReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null) return;
            if (!RecordingService.ACTION_STATUS_CHANGED.equals(intent.getAction())) return;
            renderRecordingStatus(
                    intent.getStringExtra(RecordingService.EXTRA_STATUS),
                    intent.getIntExtra(RecordingService.EXTRA_ACTIVE_CAMERAS, 0),
                    intent.getIntExtra(RecordingService.EXTRA_TOTAL_CAMERAS, 4),
                    intent.getStringExtra(RecordingService.EXTRA_LAST_ERROR)
            );
        }
    };

    public static boolean isMainVisible() {
        return sMainVisible;
    }

    public static boolean shouldBlockOverlay() {
        return sMainVisible && !sSettingsDialogOpen;
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        SurfaceView surfaceView = findViewById(R.id.surfaceView);
        surfaceHolder = surfaceView.getHolder();
        surfaceHolder.addCallback(this);

        tvStatus = findViewById(R.id.tvStatus);
        if (tvStatus != null) {
            tvStatus.setText(getString(R.string.main_preview_status, cameraLabel(currentVideoIndex)));
        }
        recordingStatusPill = findViewById(R.id.recordingStatusPill);
        recordingStatusDot = findViewById(R.id.recordingStatusDot);
        tvRecordingStatus = findViewById(R.id.tvRecordingStatus);

        ImageButton btnSettings = findViewById(R.id.btnSettings);
        btnSettings.setOnClickListener(v -> showSettingsDialog());

        ImageButton btnClose = findViewById(R.id.btnClose);
        btnClose.setOnClickListener(v -> finishAndRemoveTask());

        btnRecordTestClip = findViewById(R.id.btnRecordTestClip);
        btnRecordTestClip.setOnClickListener(v -> {
            renderRecordingStatus(RecordingService.STATUS_STARTING, 0, 4, "");
            btnRecordTestClip.setEnabled(false);
            btnRecordTestClip.setText(R.string.main_button_record_test_running);
            previewPausedForTestClip = true;
            stopPreview();
            try {
                RecordingService.startTestClip(this);
            } catch (Throwable t) {
                renderRecordingStatus(RecordingService.STATUS_ERROR, 0, 4, t.getClass().getSimpleName());
            }
        });

        Button btnTriggerEventSave = findViewById(R.id.btnTriggerEventSave);
        btnTriggerEventSave.setOnClickListener(v -> {
            SharedPreferences prefs = UiPrefs.getPrefs(this);
            if (!prefs.getBoolean("enabled", false) || !RecordingService.isRunning()) {
                Toast.makeText(this, R.string.main_event_save_requires_dashcam, Toast.LENGTH_SHORT).show();
                return;
            }
            RecordingService.triggerEventSave(this);
            Toast.makeText(this, R.string.main_event_save_armed, Toast.LENGTH_SHORT).show();
        });

        applyStoredRecordingStatus();

        appearanceController.applyMainUiIconColors();
        applyWarningVisibility();

        try {
            SignalService.start(this);
        } catch (Throwable ignored) {
        }

        surfaceView.setOnTouchListener((v, event) -> {
            if (event == null) return false;
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = event.getX();
                    downY = event.getY();
                    return true;
                case MotionEvent.ACTION_UP:
                    float dx = event.getX() - downX;
                    float dy = event.getY() - downY;
                    if (Math.abs(dx) > Math.abs(dy)) {
                        if (dx > SWIPE_THRESHOLD_PX) currentVideoIndex = 14;
                        else if (dx < -SWIPE_THRESHOLD_PX) currentVideoIndex = 16;
                        else return true;
                    } else {
                        if (dy < -SWIPE_THRESHOLD_PX) currentVideoIndex = 15;
                        else if (dy > SWIPE_THRESHOLD_PX) currentVideoIndex = 17;
                        else return true;
                    }
                    if (tvStatus != null) {
                        tvStatus.setText(getString(R.string.main_preview_status, cameraLabel(currentVideoIndex)));
                    }
                    startPreviewIfReady();
                    return true;
            }
            return false;
        });
    }

    @Override
    protected void onStart() {
        super.onStart();
        sMainVisible = true;
        try {
            ContextCompat.registerReceiver(
                    this,
                    cameraRouteReceiver,
                    new IntentFilter(SignalService.ACTION_ROUTE_CAMERA),
                    ContextCompat.RECEIVER_NOT_EXPORTED
            );
            ContextCompat.registerReceiver(
                    this,
                    recordingStatusReceiver,
                    new IntentFilter(RecordingService.ACTION_STATUS_CHANGED),
                    ContextCompat.RECEIVER_NOT_EXPORTED
            );
        } catch (Throwable ignored) {
        }
        OverlayService.hideOverlay(this);
        applyWarningVisibility();
        applyStoredRecordingStatus();
    }

    @Override
    protected void onStop() {
        super.onStop();
        sMainVisible = false;
        sSettingsDialogOpen = false;
        otaController.stop();
        try {
            unregisterReceiver(cameraRouteReceiver);
        } catch (Throwable ignored) {
        }
        try {
            unregisterReceiver(recordingStatusReceiver);
        } catch (Throwable ignored) {
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        sMainVisible = false;
        otaController.stop();
        stopPreview();
    }

    // -------------------------------------------------------------------------
    // Settings dialog
    // -------------------------------------------------------------------------

    @SuppressWarnings("deprecation")
    private void showSettingsDialog() {
        sSettingsDialogOpen = true;
        SignalService.requestRecheck();

        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(R.layout.dialog_settings);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        }

        SharedPreferences prefs = UiPrefs.getPrefs(this);
        SharedPreferences avmPrefs = getSharedPreferences(AVM_PREFS_NAME, MODE_PRIVATE);

        Switch swOverlay = dialog.findViewById(R.id.switchOverlayOnSignal);
        Switch swDashcamEnabled = dialog.findViewById(R.id.switchDashcamEnabled);
        Switch swSafetyWarning = dialog.findViewById(R.id.switchSafetyWarning);
        swSafetyWarning.setChecked(avmPrefs.getBoolean(KEY_SAFETY_WARNING, true));
        swSafetyWarning.setOnCheckedChangeListener((btn, checked) -> {
            avmPrefs.edit().putBoolean(KEY_SAFETY_WARNING, checked).apply();
            applyWarningVisibility();
        });
        Switch swAllowBetaUpdates = dialog.findViewById(R.id.switchAllowBetaUpdates);
        SeekBar seekOverlayHideDelay = dialog.findViewById(R.id.seekOverlayHideDelay);
        EditText etOverlayHideDelayValue = dialog.findViewById(R.id.etOverlayHideDelayValue);
        SeekBar seekOverlayMinShow = dialog.findViewById(R.id.seekOverlayMinShow);
        EditText etOverlayMinShowValue = dialog.findViewById(R.id.etOverlayMinShowValue);
        EditText etDashcamSegmentMin = dialog.findViewById(R.id.etDashcamSegmentMin);
        EditText etDashcamTotalMin = dialog.findViewById(R.id.etDashcamTotalMin);
        EditText etDashcamFps = dialog.findViewById(R.id.etDashcamFps);
        EditText etDashcamSignature = dialog.findViewById(R.id.etDashcamSignature);
        Switch swDashcamShowSpeed = dialog.findViewById(R.id.switchDashcamShowSpeed);
        TextView tvDashcamRecordsPath = dialog.findViewById(R.id.tvDashcamRecordsPath);
        tvDashcamDialogStatus = dialog.findViewById(R.id.tvDashcamRecordingStatus);
        Button btnDashcamExportUsb = dialog.findViewById(R.id.btnDashcamExportUsb);
        applyStoredRecordingStatus();

        SeekBar seekCorner = dialog.findViewById(R.id.seekCornerRadius);
        EditText etCorner = dialog.findViewById(R.id.etCornerRadius);
        ImageButton dialogClose = dialog.findViewById(R.id.btnClose);
        TextView tabUpdate = dialog.findViewById(R.id.tabUpdate);
        TextView tabSettings = dialog.findViewById(R.id.tabSettings);
        TextView tabSignalCamera = dialog.findViewById(R.id.tabSignalCamera);
        TextView tabDashcam = dialog.findViewById(R.id.tabDashcam);
        TextView tabOptik = dialog.findViewById(R.id.tabOptik);
        TextView tabCredits = dialog.findViewById(R.id.tabCredits);
        View sectionUpdate = dialog.findViewById(R.id.sectionUpdate);
        View sectionSettings = dialog.findViewById(R.id.sectionSettings);
        View sectionSignalCamera = dialog.findViewById(R.id.sectionSignalCamera);
        View sectionDashcam = dialog.findViewById(R.id.sectionDashcam);
        View sectionOptik = dialog.findViewById(R.id.sectionOptik);
        View sectionCredits = dialog.findViewById(R.id.sectionCredits);
        View sectionDevTools = dialog.findViewById(R.id.sectionDevTools);
        View accentRow = dialog.findViewById(R.id.rowAccentColor);
        View accentPreview = dialog.findViewById(R.id.viewAccentPreview);
        EditText etAccentColor = dialog.findViewById(R.id.etAccentColor);
        Button btnDevTestDashcamBanner = dialog.findViewById(R.id.btnDevTestDashcamBanner);
        signalCameraSettingsController.bind(
                prefs,
                swOverlay,
                seekOverlayHideDelay,
                etOverlayHideDelayValue,
                seekOverlayMinShow,
                etOverlayMinShowValue
        );
        dashcamSettingsController.bind(
                prefs,
                swDashcamEnabled,
                etDashcamSegmentMin,
                etDashcamTotalMin,
                etDashcamFps,
                etDashcamSignature,
                swDashcamShowSpeed,
                tvDashcamRecordsPath,
                btnDashcamExportUsb
        );

        appearanceController.bindSettingsAppearance(
                prefs,
                swOverlay,
                swDashcamEnabled,
                swDashcamShowSpeed,
                swSafetyWarning,
                swAllowBetaUpdates,
                dialogClose,
                seekOverlayHideDelay,
                seekOverlayMinShow,
                seekCorner,
                etCorner,
                accentRow,
                accentPreview,
                etAccentColor,
                tabUpdate,
                tabSettings,
                tabSignalCamera,
                tabDashcam,
                tabOptik,
                tabCredits
        );

        if (btnDevTestDashcamBanner != null) {
            btnDevTestDashcamBanner.setOnClickListener(v ->
                    DashcamEventOverlayService.showConfirmation(MainActivity.this));
        }

        bindSettingsTab(tabUpdate, tabSettings, tabSignalCamera, tabDashcam, tabOptik, tabCredits,
                sectionUpdate, sectionSettings, sectionSignalCamera, sectionDashcam, sectionOptik, sectionCredits, 1);
        appearanceController.reapplyForActiveTab(1);
        tabUpdate.setOnClickListener(v -> {
            bindSettingsTab(tabUpdate, tabSettings, tabSignalCamera, tabDashcam, tabOptik, tabCredits,
                    sectionUpdate, sectionSettings, sectionSignalCamera, sectionDashcam, sectionOptik, sectionCredits, 0);
            appearanceController.reapplyForActiveTab(0);
        });
        tabSettings.setOnClickListener(v -> {
            bindSettingsTab(tabUpdate, tabSettings, tabSignalCamera, tabDashcam, tabOptik, tabCredits,
                    sectionUpdate, sectionSettings, sectionSignalCamera, sectionDashcam, sectionOptik, sectionCredits, 1);
            appearanceController.reapplyForActiveTab(1);
        });
        tabSignalCamera.setOnClickListener(v -> {
            bindSettingsTab(tabUpdate, tabSettings, tabSignalCamera, tabDashcam, tabOptik, tabCredits,
                    sectionUpdate, sectionSettings, sectionSignalCamera, sectionDashcam, sectionOptik, sectionCredits, 2);
            appearanceController.reapplyForActiveTab(2);
        });
        tabDashcam.setOnClickListener(v -> {
            bindSettingsTab(tabUpdate, tabSettings, tabSignalCamera, tabDashcam, tabOptik, tabCredits,
                    sectionUpdate, sectionSettings, sectionSignalCamera, sectionDashcam, sectionOptik, sectionCredits, 3);
            appearanceController.reapplyForActiveTab(3);
        });
        tabOptik.setOnClickListener(v -> {
            bindSettingsTab(tabUpdate, tabSettings, tabSignalCamera, tabDashcam, tabOptik, tabCredits,
                    sectionUpdate, sectionSettings, sectionSignalCamera, sectionDashcam, sectionOptik, sectionCredits, 4);
            appearanceController.reapplyForActiveTab(4);
        });
        tabCredits.setOnClickListener(v -> {
            bindSettingsTab(tabUpdate, tabSettings, tabSignalCamera, tabDashcam, tabOptik, tabCredits,
                    sectionUpdate, sectionSettings, sectionSignalCamera, sectionDashcam, sectionOptik, sectionCredits, 5);
            appearanceController.reapplyForActiveTab(5);
        });

        TextView tvVersion = dialog.findViewById(R.id.tvDialogVersion);
        TextView tvBeta = dialog.findViewById(R.id.tvDialogVersionBeta);
        final int[] devTapCount = {0};
        final boolean[] devUnlocked = {false};
        try {
            String version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            tvVersion.setText(getString(R.string.settings_version_format, version));
        } catch (Exception ignored) {
            tvVersion.setText(R.string.settings_version_unknown);
        }
        tvBeta.setVisibility(BuildConfig.IS_BETA ? View.VISIBLE : View.GONE);
        View.OnClickListener unlockDevListener = v -> {
            if (devUnlocked[0]) {
                return;
            }
            devTapCount[0]++;
            if (devTapCount[0] < 5) {
                return;
            }
            devUnlocked[0] = true;
            if (sectionDevTools != null) {
                sectionDevTools.setVisibility(View.VISIBLE);
            }
            bindSettingsTab(tabUpdate, tabSettings, tabSignalCamera, tabDashcam, tabOptik, tabCredits,
                    sectionUpdate, sectionSettings, sectionSignalCamera, sectionDashcam, sectionOptik, sectionCredits, 1);
            appearanceController.reapplyForActiveTab(1);
            Toast.makeText(this, R.string.settings_dev_unlocked, Toast.LENGTH_SHORT).show();
        };
        tvVersion.setOnClickListener(unlockDevListener);
        tvBeta.setOnClickListener(unlockDevListener);

        otaController.setup(
                dialog,
                dialog.findViewById(R.id.tvDialogUpdateTag),
                dialog.findViewById(R.id.switchAllowBetaUpdates),
                dialog.findViewById(R.id.tvUpdateReleaseTitle),
                dialog.findViewById(R.id.tvUpdateChannelStatus),
                dialog.findViewById(R.id.tvUpdateChangelog),
                dialog.findViewById(R.id.tvUpdateSourceGithub),
                dialog.findViewById(R.id.tvUpdateSourceGitlab)
        );

        dialogClose.setOnClickListener(v -> dialog.dismiss());
        dialog.setOnDismissListener(d -> {
            sSettingsDialogOpen = false;
            tvDashcamDialogStatus = null;
            appearanceController.applyMainUiIconColors();
            SignalService.requestRecheck();
        });
        dialog.show();

        Window shownWindow = dialog.getWindow();
        if (shownWindow != null) {
            float density = getResources().getDisplayMetrics().density;
            int screenWidth = getResources().getDisplayMetrics().widthPixels;
            int horizontalMargin = (int) (48 * density);
            int preferredWidth = (int) (880 * density);
            int dialogWidth = Math.min(preferredWidth, screenWidth - horizontalMargin);
            shownWindow.setLayout(dialogWidth, (int) (560 * density));
        }
    }

    private void bindSettingsTab(
            TextView tabUpdate, TextView tabSettings, TextView tabSignalCamera, TextView tabDashcam, TextView tabOptik, TextView tabCredits,
            View sectionUpdate, View sectionSettings, View sectionSignalCamera, View sectionDashcam, View sectionOptik, View sectionCredits,
            int active
    ) {
        sectionUpdate.setVisibility(active == 0 ? View.VISIBLE : View.GONE);
        sectionSettings.setVisibility(active == 1 ? View.VISIBLE : View.GONE);
        sectionSignalCamera.setVisibility(active == 2 ? View.VISIBLE : View.GONE);
        sectionDashcam.setVisibility(active == 3 ? View.VISIBLE : View.GONE);
        sectionOptik.setVisibility(active == 4 ? View.VISIBLE : View.GONE);
        sectionCredits.setVisibility(active == 5 ? View.VISIBLE : View.GONE);
        styleSettingsTab(tabUpdate, active == 0);
        styleSettingsTab(tabSettings, active == 1);
        styleSettingsTab(tabSignalCamera, active == 2);
        styleSettingsTab(tabDashcam, active == 3);
        styleSettingsTab(tabOptik, active == 4);
        styleSettingsTab(tabCredits, active == 5);
    }

    private void styleSettingsTab(TextView tab, boolean active) {
        appearanceController.styleSettingsTab(tab, active);
    }

    // -------------------------------------------------------------------------
    // Warning banner
    // -------------------------------------------------------------------------

    private void applyWarningVisibility() {
        boolean show = getSharedPreferences(AVM_PREFS_NAME, MODE_PRIVATE)
                .getBoolean(KEY_SAFETY_WARNING, true);
        int visibility = show ? View.VISIBLE : View.GONE;
        View bg = findViewById(R.id.bg_tishi);
        View banner = findViewById(R.id.warningBanner);
        if (bg != null) bg.setVisibility(visibility);
        if (banner != null) banner.setVisibility(visibility);
    }

    private void applyStoredRecordingStatus() {
        SharedPreferences prefs = UiPrefs.getPrefs(this);
        String status = prefs.getString("recordingStatus", RecordingService.STATUS_OFF);
        if (status != null
                && !RecordingService.STATUS_OFF.equals(status)
                && !RecordingService.isRunning()) {
            prefs.edit()
                    .putString("recordingStatus", RecordingService.STATUS_OFF)
                    .putInt("recordingActiveCameras", 0)
                    .putInt("recordingTotalCameras", 4)
                    .putString("recordingLastError", "")
                    .apply();
            status = RecordingService.STATUS_OFF;
        }
        renderRecordingStatus(
                status,
                prefs.getInt("recordingActiveCameras", 0),
                prefs.getInt("recordingTotalCameras", 4),
                prefs.getString("recordingLastError", "")
        );
    }

    private void renderRecordingStatus(String status, int activeCameras, int totalCameras, String lastError) {
        if (recordingStatusPill == null || recordingStatusDot == null || tvRecordingStatus == null) return;
        if (status == null || RecordingService.STATUS_OFF.equals(status)) {
            recordingStatusPill.setVisibility(View.GONE);
            resetTestClipButton();
            renderDashcamDialogStatus(getString(R.string.settings_dashcam_status_off));
            restartPreviewAfterTestClipIfNeeded();
            return;
        }

        recordingStatusPill.setVisibility(View.VISIBLE);
        if (RecordingService.STATUS_RECORDING.equals(status)) {
            if (btnRecordTestClip != null) btnRecordTestClip.setEnabled(false);
            recordingStatusDot.setVisibility(View.VISIBLE);
            tvRecordingStatus.setText(getString(R.string.main_recording_indicator, activeCameras, totalCameras));
            renderDashcamDialogStatus(getString(R.string.settings_dashcam_status_recording, activeCameras, totalCameras));
        } else if (RecordingService.STATUS_STARTING.equals(status)) {
            if (btnRecordTestClip != null) btnRecordTestClip.setEnabled(false);
            recordingStatusDot.setVisibility(View.VISIBLE);
            tvRecordingStatus.setText(R.string.main_recording_starting);
            renderDashcamDialogStatus(getString(R.string.settings_dashcam_status_starting));
        } else {
            if (activeCameras <= 0) {
                resetTestClipButton();
            }
            recordingStatusDot.setVisibility(View.GONE);
            String error = lastError == null || lastError.trim().isEmpty()
                    ? status
                    : lastError.trim();
            tvRecordingStatus.setText(getString(R.string.main_recording_error, error));
            renderDashcamDialogStatus(getString(R.string.settings_dashcam_status_error, error));
            if (activeCameras <= 0) {
                restartPreviewAfterTestClipIfNeeded();
            }
        }
    }

    private void resetTestClipButton() {
        if (btnRecordTestClip == null) return;
        btnRecordTestClip.setEnabled(true);
        btnRecordTestClip.setText(R.string.main_button_record_test_30s);
    }

    private void renderDashcamDialogStatus(String text) {
        if (tvDashcamDialogStatus != null) {
            tvDashcamDialogStatus.setText(text);
        }
    }

    private void restartPreviewAfterTestClipIfNeeded() {
        if (!previewPausedForTestClip) return;
        previewPausedForTestClip = false;
        startPreviewIfReady();
    }

    // -------------------------------------------------------------------------
    // Camera preview
    // -------------------------------------------------------------------------

    private void startPreviewIfReady() {
        if (surfaceHolder == null || surfaceHolder.getSurface() == null ||
                !surfaceHolder.getSurface().isValid()) {
            if (tvStatus != null) tvStatus.setText(R.string.main_surface_not_ready);
            return;
        }
        if (activePreviewCameraIndex != -1 && activePreviewCameraIndex != currentVideoIndex) {
            CameraProbe.detachPreview(activePreviewCameraIndex);
            activePreviewCameraIndex = -1;
        }
        boolean ok = CameraProbe.attachPreview(currentVideoIndex, surfaceHolder.getSurface());
        previewRunning = ok;
        activePreviewCameraIndex = ok ? currentVideoIndex : -1;
        if (tvStatus != null) {
            tvStatus.setText(ok
                    ? getString(R.string.main_preview_status, cameraLabel(currentVideoIndex))
                    : getString(R.string.main_preview_stopped));
        }
    }

    private void stopPreview() {
        if (activePreviewCameraIndex == -1) return;
        CameraProbe.detachPreview(activePreviewCameraIndex);
        activePreviewCameraIndex = -1;
        previewRunning = false;
        if (tvStatus != null) tvStatus.setText(R.string.main_preview_stopped);
    }

    @Override public void surfaceCreated(SurfaceHolder holder) { startPreviewIfReady(); }
    @Override public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {}
    @Override public void surfaceDestroyed(SurfaceHolder holder) { stopPreview(); }

    private String cameraLabel(int videoIndex) {
        switch (videoIndex) {
            case 14: return getString(R.string.main_camera_label_right);
            case 15: return getString(R.string.main_camera_label_front);
            case 16: return getString(R.string.main_camera_label_left);
            case 17: return getString(R.string.main_camera_label_rear);
            default: return getString(R.string.main_camera_label_unknown, videoIndex);
        }
    }
}
