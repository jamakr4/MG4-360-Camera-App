package com.drivehub.kamera;

import android.annotation.SuppressLint;
import android.app.Dialog;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.TextView;
import android.widget.ImageButton;
import android.widget.Button;
import android.widget.SeekBar;
import android.widget.Switch;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import java.util.Locale;

public class MainActivity extends AppCompatActivity implements SurfaceHolder.Callback {

    private static final String AVM_PREFS_NAME = "AVM_Settings";
    private static final String KEY_SAFETY_WARNING = "ShowSafetyWarning";
    private static final String DEV_TUNING_PREFS_NAME = "dev_tuning_prefs";
    private static final String KEY_DEV_BALANCE = "dev_balance";
    private static final String KEY_DEV_FOV_SCALE = "dev_fov_scale";

    private SurfaceHolder surfaceHolder;
    private TextView tvStatus;
    private View devTuningPanel;
    private TextView tvDevBalance;
    private TextView tvDevFovScale;
    private SeekBar seekDevBalance;
    private SeekBar seekDevFovScale;
    // Initial camera when the app opens: front camera (v15)
    private int currentVideoIndex = 15;
    private boolean previewRunning = false;
    private float devBalance = 0.0f;
    private float devFovScale = 1.0f;

    // Swipe detection threshold in pixels
    private static final int SWIPE_THRESHOLD_PX = 140;
    private float downX = 0f;
    private float downY = 0f;
    private static volatile boolean sMainVisible = false;
    private static volatile boolean sSettingsDialogOpen = false;

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
        ImageButton btnSettings = findViewById(R.id.btnSettings);
        devTuningPanel = findViewById(R.id.devTuningPanel);
        tvDevBalance = findViewById(R.id.tvDevBalance);
        tvDevFovScale = findViewById(R.id.tvDevFovScale);
        seekDevBalance = findViewById(R.id.seekDevBalance);
        seekDevFovScale = findViewById(R.id.seekDevFovScale);

        btnSettings.setOnClickListener(v -> showSettingsDialog());

        ImageButton btnClose = findViewById(R.id.btnClose);
        btnClose.setOnClickListener(v -> finishAndRemoveTask());

        // Show the initial status label.
        if (tvStatus != null) {
            tvStatus.setText(getString(R.string.main_preview_status, cameraLabel(currentVideoIndex)));
        }
        loadDevTuningPrefs();
        setupDevTuningPanel();
        applyWarningVisibility();

        // Keep signal/gear listening always active; overlay visibility is controlled only by settings.
        try {
            SignalService.start(this);
        } catch (Throwable ignored) {
        }

        // Change cameras with swipe gestures.
        surfaceView.setOnTouchListener((v, event) -> {
            if (event == null) return false;

            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = event.getX();
                    downY = event.getY();
                    return true;
                case MotionEvent.ACTION_UP:
                    float upX = event.getX();
                    float upY = event.getY();
                    float dx = upX - downX;
                    float dy = upY - downY;

                    // Decide based on the dominant axis:
                    // - Horizontal: left -> v16, right -> v14
                    // - Vertical: up -> v15, down -> v17
                    if (Math.abs(dx) > Math.abs(dy)) {
                        // horizontal
                        if (dx > SWIPE_THRESHOLD_PX) {
                            currentVideoIndex = 14; // right camera
                        } else if (dx < -SWIPE_THRESHOLD_PX) {
                            currentVideoIndex = 16; // left camera
                        } else {
                            return true;
                        }
                    } else {
                        // vertical
                        if (dy < -SWIPE_THRESHOLD_PX) {
                            currentVideoIndex = 15; // front camera
                        } else if (dy > SWIPE_THRESHOLD_PX) {
                            currentVideoIndex = 17; // rear camera
                        } else {
                            return true;
                        }
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
            IntentFilter f = new IntentFilter(SignalService.ACTION_ROUTE_CAMERA);
            ContextCompat.registerReceiver(
                    this,
                    cameraRouteReceiver,
                    f,
                    ContextCompat.RECEIVER_NOT_EXPORTED
            );
        } catch (Throwable ignored) {
        }
        // Do not show the overlay while Main is open.
        OverlayService.hideOverlay(this);
        applyWarningVisibility();
    }

    @Override
    protected void onStop() {
        super.onStop();
        sMainVisible = false;
        sSettingsDialogOpen = false;
        try {
            unregisterReceiver(cameraRouteReceiver);
        } catch (Throwable ignored) {
        }
    }

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

        SharedPreferences prefs = getSharedPreferences("rec_prefs", MODE_PRIVATE);
        SharedPreferences avmPrefs = getSharedPreferences(AVM_PREFS_NAME, MODE_PRIVATE);
        Switch swOverlay = dialog.findViewById(R.id.switchOverlayOnSignal);
        Switch swSafetyWarning = dialog.findViewById(R.id.switchSafetyWarning);
        Button btnOpenDevTuning = dialog.findViewById(R.id.btnOpenDevTuning);
        TextView tabSettings = dialog.findViewById(R.id.tabSettings);
        TextView tabCredits = dialog.findViewById(R.id.tabCredits);
        View sectionSettings = dialog.findViewById(R.id.sectionSettings);
        View sectionCredits = dialog.findViewById(R.id.sectionCredits);

        swOverlay.setChecked(prefs.getBoolean("overlayOnSignal", false));
        swOverlay.setOnCheckedChangeListener((buttonView, isChecked) -> {
            prefs.edit().putBoolean("overlayOnSignal", isChecked).apply();
            if (!isChecked) {
                OverlayService.hideOverlay(MainActivity.this);
            }
        });

        swSafetyWarning.setChecked(avmPrefs.getBoolean(KEY_SAFETY_WARNING, true));
        swSafetyWarning.setOnCheckedChangeListener((buttonView, isChecked) -> {
            avmPrefs.edit().putBoolean(KEY_SAFETY_WARNING, isChecked).apply();
            applyWarningVisibility();
        });

        btnOpenDevTuning.setOnClickListener(v -> {
            dialog.dismiss();
            showDevTuningPanel();
        });

        bindSimpleSettingsTab(tabSettings, tabCredits, sectionSettings, sectionCredits, true);
        tabSettings.setOnClickListener(v ->
                bindSimpleSettingsTab(tabSettings, tabCredits, sectionSettings, sectionCredits, true));
        tabCredits.setOnClickListener(v ->
                bindSimpleSettingsTab(tabSettings, tabCredits, sectionSettings, sectionCredits, false));

        TextView tvDialogVersion = dialog.findViewById(R.id.tvDialogVersion);
        TextView tvDialogVersionBeta = dialog.findViewById(R.id.tvDialogVersionBeta);
        try {
            String version = getPackageManager()
                    .getPackageInfo(getPackageName(), 0)
                    .versionName;
            tvDialogVersion.setText(getString(R.string.settings_version_format, version));
        } catch (Exception e) {
            tvDialogVersion.setText(R.string.settings_version_unknown);
        }
        tvDialogVersionBeta.setVisibility(BuildConfig.IS_BETA ? View.VISIBLE : View.GONE);

        dialog.findViewById(R.id.btnClose).setOnClickListener(v -> dialog.dismiss());
        dialog.setOnDismissListener(d -> {
            sSettingsDialogOpen = false;
            SignalService.requestRecheck();
        });
        dialog.show();

        Window shownWindow = dialog.getWindow();
        if (shownWindow != null) {
            float density = getResources().getDisplayMetrics().density;
            shownWindow.setLayout((int) (700 * density), (int) (560 * density));
        }
    }

    private void bindSimpleSettingsTab(
            TextView tabSettings,
            TextView tabCredits,
            View sectionSettings,
            View sectionCredits,
            boolean showSettings
    ) {
        sectionSettings.setVisibility(showSettings ? View.VISIBLE : View.GONE);
        sectionCredits.setVisibility(showSettings ? View.GONE : View.VISIBLE);
        styleSimpleTab(tabSettings, showSettings);
        styleSimpleTab(tabCredits, !showSettings);
    }

    private void styleSimpleTab(TextView tab, boolean active) {
        tab.setTextColor(active ? 0xFFFFFFFF : 0xFF777777);
        tab.setTextSize(20f);
        tab.setTypeface(tab.getTypeface(), android.graphics.Typeface.BOLD);
    }

    private void applyWarningVisibility() {
        boolean show = getSharedPreferences(AVM_PREFS_NAME, MODE_PRIVATE)
                .getBoolean(KEY_SAFETY_WARNING, true);
        int visibility = show ? View.VISIBLE : View.GONE;
        View bg = findViewById(R.id.bg_tishi);
        View banner = findViewById(R.id.warningBanner);
        if (bg != null) bg.setVisibility(visibility);
        if (banner != null) banner.setVisibility(visibility);
    }

    private void startPreviewIfReady() {
        if (surfaceHolder == null || surfaceHolder.getSurface() == null ||
                !surfaceHolder.getSurface().isValid()) {
            if (tvStatus != null) tvStatus.setText(R.string.main_surface_not_ready);
            return;
        }
        stopPreview();
        boolean ok = CameraProbe.startPreview(currentVideoIndex, surfaceHolder.getSurface());
        previewRunning = ok;
        if (ok) {
            CameraProbe.setUndistortParams(devBalance, devFovScale);
        }
        if (tvStatus != null) {
            tvStatus.setText(ok
                    ? getString(R.string.main_preview_status, cameraLabel(currentVideoIndex))
                    : getString(R.string.main_preview_stopped));
        }
    }

    private void stopPreview() {
        if (previewRunning) {
            CameraProbe.stopPreview();
            previewRunning = false;
            if (tvStatus != null) tvStatus.setText(R.string.main_preview_stopped);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        sMainVisible = false;
        stopPreview();
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        // Start the initial camera as soon as the surface is ready.
        startPreviewIfReady();
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        // Restart here if needed.
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

    private void setupDevTuningPanel() {
        if (devTuningPanel == null || seekDevBalance == null || seekDevFovScale == null) {
            return;
        }

        ImageButton btnSettings = findViewById(R.id.btnSettings);
        Button btnCloseDevTuning = findViewById(R.id.btnCloseDevTuning);
        if (btnCloseDevTuning != null) {
            btnCloseDevTuning.setOnClickListener(v -> hideDevTuningPanel());
        }
        if (btnSettings != null) {
            devTuningPanel.bringToFront();
        }

        seekDevBalance.setProgress(Math.round(devBalance * 100f));
        seekDevFovScale.setProgress(Math.round((devFovScale - 1.0f) * 100f));
        updateDevTuningLabels();

        seekDevBalance.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                devBalance = progress / 100.0f;
                updateDevTuningLabels();
                applyDevTuningParams();
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        seekDevFovScale.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                devFovScale = 1.0f + (progress / 100.0f);
                updateDevTuningLabels();
                applyDevTuningParams();
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {}
        });
    }

    private void showDevTuningPanel() {
        if (devTuningPanel != null) {
            devTuningPanel.setVisibility(View.VISIBLE);
            devTuningPanel.bringToFront();
        }
    }

    private void hideDevTuningPanel() {
        if (devTuningPanel != null) {
            devTuningPanel.setVisibility(View.GONE);
        }
    }

    private void updateDevTuningLabels() {
        if (tvDevBalance != null) {
            tvDevBalance.setText(getString(R.string.dev_tuning_balance, devBalance));
        }
        if (tvDevFovScale != null) {
            tvDevFovScale.setText(getString(R.string.dev_tuning_fov_scale, devFovScale));
        }
    }

    private void loadDevTuningPrefs() {
        SharedPreferences prefs = getSharedPreferences(DEV_TUNING_PREFS_NAME, MODE_PRIVATE);
        devBalance = clamp(prefs.getFloat(KEY_DEV_BALANCE, 0.0f), 0.0f, 1.0f);
        devFovScale = clamp(prefs.getFloat(KEY_DEV_FOV_SCALE, 1.0f), 1.0f, 2.0f);
    }

    private void applyDevTuningParams() {
        CameraProbe.setUndistortParams(devBalance, devFovScale);
        getSharedPreferences(DEV_TUNING_PREFS_NAME, MODE_PRIVATE)
                .edit()
                .putFloat(KEY_DEV_BALANCE, devBalance)
                .putFloat(KEY_DEV_FOV_SCALE, devFovScale)
                .apply();
    }

    private float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}
