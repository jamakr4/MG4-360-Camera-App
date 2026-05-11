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
import android.text.Editable;
import android.text.TextWatcher;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.ImageButton;
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

public class MainActivity extends AppCompatActivity implements SurfaceHolder.Callback {

    private static final String AVM_PREFS_NAME = "AVM_Settings";
    private static final String KEY_SAFETY_WARNING = "ShowSafetyWarning";
    private static final String REC_PREFS_NAME = "rec_prefs";
    private static final String KEY_TILE_CORNER_RADIUS = "tileCornerRadius";
    private static final String KEY_SOFT_SNAP_ENABLED = "softSnapEnabled";
    private static final String KEY_SOFT_SNAP_PADDING_X = "softSnapPaddingX";
    private static final String KEY_SOFT_SNAP_PADDING_Y = "softSnapPaddingY";
    private static final int DEFAULT_TILE_CORNER_RADIUS = 16;
    private static final int DEFAULT_SOFT_SNAP_PADDING_X = 32;
    private static final int DEFAULT_SOFT_SNAP_PADDING_Y = 64;

    private SurfaceHolder surfaceHolder;
    private TextView tvStatus;
    private FrameLayout softSnapFullscreenPreview;
    private View softSnapFullscreenTopRight;
    // Initial camera when the app opens: front camera (v15)
    private int currentVideoIndex = 15;
    private boolean previewRunning = false;

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
        softSnapFullscreenPreview = findViewById(R.id.softSnapFullscreenPreview);
        softSnapFullscreenTopRight = findViewById(R.id.softSnapFullscreenTopRight);
        ImageButton btnSettings = findViewById(R.id.btnSettings);

        btnSettings.setOnClickListener(v -> showSettingsDialog());

        ImageButton btnClose = findViewById(R.id.btnClose);
        btnClose.setOnClickListener(v -> finishAndRemoveTask());

        // Show the initial status label.
        if (tvStatus != null) {
            tvStatus.setText(getString(R.string.main_preview_status, cameraLabel(currentVideoIndex)));
        }
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

        SharedPreferences prefs = getSharedPreferences(REC_PREFS_NAME, MODE_PRIVATE);
        SharedPreferences avmPrefs = getSharedPreferences(AVM_PREFS_NAME, MODE_PRIVATE);
        Switch swOverlay = dialog.findViewById(R.id.switchOverlayOnSignal);
        Switch swSafetyWarning = dialog.findViewById(R.id.switchSafetyWarning);
        Switch swSoftSnap = dialog.findViewById(R.id.switchSoftSnap);
        TextView tabSettings = dialog.findViewById(R.id.tabSettings);
        TextView tabOptik = dialog.findViewById(R.id.tabOptik);
        TextView tabCredits = dialog.findViewById(R.id.tabCredits);
        View sectionSettings = dialog.findViewById(R.id.sectionSettings);
        View sectionOptik = dialog.findViewById(R.id.sectionOptik);
        View sectionCredits = dialog.findViewById(R.id.sectionCredits);
        SeekBar seekSoftSnapPaddingX = dialog.findViewById(R.id.seekSoftSnapPaddingX);
        SeekBar seekSoftSnapPaddingY = dialog.findViewById(R.id.seekSoftSnapPaddingY);
        TextView tvSoftSnapPaddingXValue = dialog.findViewById(R.id.tvSoftSnapPaddingXValue);
        TextView tvSoftSnapPaddingYValue = dialog.findViewById(R.id.tvSoftSnapPaddingYValue);

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

        SeekBar seekCorner = dialog.findViewById(R.id.seekCornerRadius);
        EditText etCorner = dialog.findViewById(R.id.etCornerRadius);
        int savedRadius = prefs.getInt(KEY_TILE_CORNER_RADIUS, DEFAULT_TILE_CORNER_RADIUS);
        seekCorner.setProgress(savedRadius);
        etCorner.setText(String.valueOf(savedRadius));

        seekCorner.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                prefs.edit().putInt(KEY_TILE_CORNER_RADIUS, progress).apply();
                if (fromUser) {
                    etCorner.setText(String.valueOf(progress));
                    etCorner.setSelection(etCorner.getText().length());
                }
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        etCorner.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) {
                if (s.length() == 0) return;
                try {
                    int value = Math.min(100, Math.max(0, Integer.parseInt(s.toString())));
                    String normalized = String.valueOf(value);
                    if (!normalized.contentEquals(s)) {
                        etCorner.setText(normalized);
                        etCorner.setSelection(etCorner.getText().length());
                        return;
                    }
                    prefs.edit().putInt(KEY_TILE_CORNER_RADIUS, value).apply();
                    if (seekCorner.getProgress() != value) {
                        seekCorner.setProgress(value);
                    }
                } catch (NumberFormatException ignored) {}
            }
        });

        swSoftSnap.setChecked(prefs.getBoolean(KEY_SOFT_SNAP_ENABLED, false));
        swSoftSnap.setOnCheckedChangeListener((buttonView, isChecked) ->
                prefs.edit().putBoolean(KEY_SOFT_SNAP_ENABLED, isChecked).apply());

        int paddingX = prefs.getInt(KEY_SOFT_SNAP_PADDING_X, DEFAULT_SOFT_SNAP_PADDING_X);
        int paddingY = prefs.getInt(KEY_SOFT_SNAP_PADDING_Y, DEFAULT_SOFT_SNAP_PADDING_Y);
        seekSoftSnapPaddingX.setProgress(paddingX);
        seekSoftSnapPaddingY.setProgress(paddingY);
        tvSoftSnapPaddingXValue.setText(getString(R.string.settings_soft_snap_padding_value, paddingX));
        tvSoftSnapPaddingYValue.setText(getString(R.string.settings_soft_snap_padding_value, paddingY));

        SeekBar.OnSeekBarChangeListener softSnapPaddingListener = new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (seekBar.getId() == R.id.seekSoftSnapPaddingX) {
                    prefs.edit().putInt(KEY_SOFT_SNAP_PADDING_X, progress).apply();
                    tvSoftSnapPaddingXValue.setText(
                            getString(R.string.settings_soft_snap_padding_value, progress));
                } else {
                    prefs.edit().putInt(KEY_SOFT_SNAP_PADDING_Y, progress).apply();
                    tvSoftSnapPaddingYValue.setText(
                            getString(R.string.settings_soft_snap_padding_value, progress));
                }
                updateSoftSnapFullscreenPreview(
                        seekSoftSnapPaddingX.getProgress(),
                        seekSoftSnapPaddingY.getProgress()
                );
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        };
        seekSoftSnapPaddingX.setOnSeekBarChangeListener(softSnapPaddingListener);
        seekSoftSnapPaddingY.setOnSeekBarChangeListener(softSnapPaddingListener);

        softSnapFullscreenPreview.post(() -> updateSoftSnapFullscreenPreview(
                seekSoftSnapPaddingX.getProgress(),
                seekSoftSnapPaddingY.getProgress()
        ));

        bindSettingsTab(tabSettings, tabOptik, tabCredits, sectionSettings, sectionOptik, sectionCredits, 0);
        tabSettings.setOnClickListener(v ->
                bindSettingsTab(tabSettings, tabOptik, tabCredits, sectionSettings, sectionOptik, sectionCredits, 0));
        tabOptik.setOnClickListener(v ->
                bindSettingsTab(tabSettings, tabOptik, tabCredits, sectionSettings, sectionOptik, sectionCredits, 1));
        tabCredits.setOnClickListener(v ->
                bindSettingsTab(tabSettings, tabOptik, tabCredits, sectionSettings, sectionOptik, sectionCredits, 2));

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
            setSoftSnapFullscreenPreviewVisible(false);
            SignalService.requestRecheck();
        });
        dialog.show();

        Window shownWindow = dialog.getWindow();
        if (shownWindow != null) {
            float density = getResources().getDisplayMetrics().density;
            shownWindow.setLayout((int) (700 * density), (int) (560 * density));
        }
    }

    private void bindSettingsTab(
            TextView tabSettings, TextView tabOptik, TextView tabCredits,
            View sectionSettings, View sectionOptik, View sectionCredits,
            int active
    ) {
        sectionSettings.setVisibility(active == 0 ? View.VISIBLE : View.GONE);
        sectionOptik.setVisibility(active == 1 ? View.VISIBLE : View.GONE);
        sectionCredits.setVisibility(active == 2 ? View.VISIBLE : View.GONE);
        setSoftSnapFullscreenPreviewVisible(active == 1);
        styleSettingsTab(tabSettings, active == 0);
        styleSettingsTab(tabOptik, active == 1);
        styleSettingsTab(tabCredits, active == 2);
    }

    private void styleSettingsTab(TextView tab, boolean active) {
        tab.setTextColor(active ? 0xFFFFFFFF : 0xFF777777);
        tab.setTextSize(20f);
        tab.setTypeface(tab.getTypeface(), android.graphics.Typeface.BOLD);
    }

    private void updateSoftSnapFullscreenPreview(int paddingX, int paddingY) {
        if (softSnapFullscreenPreview == null) return;
        int previewWidth = softSnapFullscreenPreview.getWidth();
        int previewHeight = softSnapFullscreenPreview.getHeight();
        if (previewWidth <= 0 || previewHeight <= 0) return;

        int zoneWidth = dpToPx(160);
        int zoneHeight = dpToPx(110);
        int top = Math.max(0, Math.min(paddingY, Math.max(0, previewHeight - zoneHeight)));
        int right = Math.max(0, Math.min(previewWidth - zoneWidth - paddingX, Math.max(0, previewWidth - zoneWidth)));
        positionPreviewZone(softSnapFullscreenTopRight, right, top, zoneWidth, zoneHeight);
    }

    private void setSoftSnapFullscreenPreviewVisible(boolean visible) {
        if (softSnapFullscreenPreview == null) return;
        softSnapFullscreenPreview.setVisibility(visible ? View.VISIBLE : View.GONE);
        if (visible) {
            SharedPreferences prefs = getSharedPreferences(REC_PREFS_NAME, MODE_PRIVATE);
            int paddingX = prefs.getInt(KEY_SOFT_SNAP_PADDING_X, DEFAULT_SOFT_SNAP_PADDING_X);
            int paddingY = prefs.getInt(KEY_SOFT_SNAP_PADDING_Y, DEFAULT_SOFT_SNAP_PADDING_Y);
            softSnapFullscreenPreview.post(() ->
                    updateSoftSnapFullscreenPreview(paddingX, paddingY));
        }
    }

    private void positionPreviewZone(View zone, int left, int top, int width, int height) {
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) zone.getLayoutParams();
        params.width = width;
        params.height = height;
        params.leftMargin = left;
        params.topMargin = top;
        zone.setLayoutParams(params);
    }

    private int dpToPx(int dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
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
}
