package com.drivehub.kamera;

import com.drivehub.kamera.camera.OverlayService;
import com.drivehub.kamera.dashcam.RecordingService;
import com.drivehub.kamera.ota.OtaController;
import com.drivehub.kamera.settings.SettingsAppearanceController;
import com.drivehub.kamera.settings.SettingsDialogController;
import com.drivehub.kamera.settings.UiPrefs;
import com.drivehub.kamera.signal.SignalService;

import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

public class MainActivity extends AppCompatActivity implements SurfaceHolder.Callback {

    private static final int SWIPE_THRESHOLD_PX = 140;

    private SurfaceHolder surfaceHolder;
    private TextView tvStatus;
    private View recordingStatusPill;
    private View recordingStatusDot;
    private TextView tvRecordingStatus;
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
    private final OtaController otaController = new OtaController(this);
    private final SettingsDialogController settingsDialog = new SettingsDialogController(
            this, appearanceController, otaController, this::applyWarningVisibility);

    public static void setSettingsDialogOpen(boolean open) {
        sSettingsDialogOpen = open;
    }

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
        UiPrefs.migrateLegacyPrefsIfNeeded(this);
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
        btnSettings.setOnClickListener(v -> settingsDialog.show());

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
            if (!UiPrefs.isDashcamEnabled(UiPrefs.getPrefs(this)) || !RecordingService.isRunning()) {
                Toast.makeText(this, R.string.main_event_save_requires_dashcam, Toast.LENGTH_SHORT).show();
                return;
            }
            RecordingService.triggerEventSave(this);
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

    private void applyWarningVisibility() {
        boolean show = UiPrefs.isSafetyWarningEnabled(UiPrefs.getPrefs(this));
        int visibility = show ? View.VISIBLE : View.GONE;
        View bg = findViewById(R.id.bg_tishi);
        View banner = findViewById(R.id.warningBanner);
        if (bg != null) bg.setVisibility(visibility);
        if (banner != null) banner.setVisibility(visibility);
    }

    private void applyStoredRecordingStatus() {
        SharedPreferences prefs = UiPrefs.getPrefs(this);
        RecordingService.resetPersistedStatusIfStale(prefs);
        RecordingService.PersistedStatus s = RecordingService.readPersistedStatus(prefs);
        renderRecordingStatus(s.status, s.activeCameras, s.totalCameras, s.lastError);
    }

    private void renderRecordingStatus(String status, int activeCameras, int totalCameras, String lastError) {
        if (recordingStatusPill == null || recordingStatusDot == null || tvRecordingStatus == null) return;
        if (status == null || RecordingService.STATUS_OFF.equals(status)) {
            recordingStatusPill.setVisibility(View.GONE);
            resetTestClipButton();
            restartPreviewAfterTestClipIfNeeded();
        } else {
            recordingStatusPill.setVisibility(View.VISIBLE);
            if (RecordingService.STATUS_RECORDING.equals(status)) {
                if (btnRecordTestClip != null) btnRecordTestClip.setEnabled(false);
                recordingStatusDot.setVisibility(View.VISIBLE);
                tvRecordingStatus.setText(getString(R.string.main_recording_indicator, activeCameras, totalCameras));
            } else if (RecordingService.STATUS_PAUSED_OEM.equals(status)) {
                if (btnRecordTestClip != null) btnRecordTestClip.setEnabled(false);
                recordingStatusDot.setVisibility(View.GONE);
                tvRecordingStatus.setText(R.string.main_recording_paused_oem);
            } else if (RecordingService.STATUS_STARTING.equals(status)) {
                if (btnRecordTestClip != null) btnRecordTestClip.setEnabled(false);
                recordingStatusDot.setVisibility(View.VISIBLE);
                tvRecordingStatus.setText(R.string.main_recording_starting);
            } else {
                if (activeCameras <= 0) resetTestClipButton();
                recordingStatusDot.setVisibility(View.GONE);
                String error = lastError == null || lastError.trim().isEmpty() ? status : lastError.trim();
                tvRecordingStatus.setText(getString(R.string.main_recording_error, error));
                if (activeCameras <= 0) restartPreviewAfterTestClipIfNeeded();
            }
        }
        settingsDialog.onRecordingStatusChanged(status, activeCameras, totalCameras, lastError);
    }

    private void resetTestClipButton() {
        if (btnRecordTestClip == null) return;
        btnRecordTestClip.setEnabled(true);
        btnRecordTestClip.setText(R.string.main_button_record_test_30s);
    }

    private void restartPreviewAfterTestClipIfNeeded() {
        if (!previewPausedForTestClip) return;
        previewPausedForTestClip = false;
        startPreviewIfReady();
    }

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

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        startPreviewIfReady();
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        stopPreview();
    }

    private String cameraLabel(int videoIndex) {
        switch (videoIndex) {
            case 14:
                return getString(R.string.main_camera_label_right);
            case 15:
                return getString(R.string.main_camera_label_front);
            case 16:
                return getString(R.string.main_camera_label_left);
            case 17:
                return getString(R.string.main_camera_label_rear);
            default:
                return getString(R.string.main_camera_label_unknown, videoIndex);
        }
    }
}
