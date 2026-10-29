package com.drivehub.kamera.dev;

import com.drivehub.kamera.R;
import com.drivehub.kamera.settings.UiPrefs;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.TextView;

import java.util.Locale;

/** Owns the permanent, non-interactive performance overlay and its sampling thread. */
public final class SystemPerformanceOverlay
        implements SharedPreferences.OnSharedPreferenceChangeListener {

    private static final String TAG = "SystemPerformance";
    private static final long SAMPLE_INTERVAL_MS = 1_000L;
    private static final int SLOW_METRIC_INTERVAL_SAMPLES = 5;

    private final Context context;
    private final SharedPreferences prefs;
    private final WindowManager windowManager;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private volatile boolean active;
    private HandlerThread sampleThread;
    private Handler sampleHandler;
    private SystemPerformanceSampler sampler;
    private TextView overlayText;
    private WindowManager.LayoutParams overlayParams;
    private int sampleCount;
    private int sampleGeneration;

    public SystemPerformanceOverlay(Context context) {
        this.context = context.getApplicationContext();
        prefs = UiPrefs.getPrefs(this.context);
        windowManager = (WindowManager) this.context.getSystemService(Context.WINDOW_SERVICE);
    }

    /** Must be called on the service/main thread. Safe to call repeatedly. */
    public void start() {
        if (active) return;
        if (!showOverlayWindow()) return;

        active = true;
        int generation = ++sampleGeneration;
        sampleCount = 0;
        sampler = new SystemPerformanceSampler(context);
        prefs.registerOnSharedPreferenceChangeListener(this);
        sampleThread = new HandlerThread("SystemPerformanceSampler");
        sampleThread.start();
        sampleHandler = new Handler(sampleThread.getLooper());
        sampleHandler.post(() -> sampleOnce(generation));
    }

    /** Must be called on the service/main thread. Safe to call repeatedly. */
    public void stop() {
        active = false;
        sampleGeneration++;
        prefs.unregisterOnSharedPreferenceChangeListener(this);
        if (sampleHandler != null) {
            sampleHandler.removeCallbacksAndMessages(null);
            sampleHandler = null;
        }
        if (sampleThread != null) {
            sampleThread.quitSafely();
            sampleThread = null;
        }
        sampler = null;
        mainHandler.removeCallbacksAndMessages(null);
        removeOverlayWindow();
    }

    public boolean isActive() {
        return active;
    }

    private void sampleOnce(int generation) {
        SystemPerformanceSampler currentSampler = sampler;
        if (!active || generation != sampleGeneration || currentSampler == null) return;
        PerformanceMetrics.Snapshot snapshot = currentSampler.sample(
                sampleCount++ % SLOW_METRIC_INTERVAL_SAMPLES == 0);
        mainHandler.post(() -> render(snapshot));
        Handler handler = sampleHandler;
        if (active && generation == sampleGeneration && handler != null) {
            handler.postDelayed(() -> sampleOnce(generation), SAMPLE_INTERVAL_MS);
        }
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
        if (!active || !UiPrefs.KEY_DEV_OVERLAY_TOP_INSET_PX.equals(key)) return;
        mainHandler.post(this::updateOverlayPosition);
    }

    private boolean showOverlayWindow() {
        if (windowManager == null || overlayText != null) return overlayText != null;
        TextView view = new TextView(context);
        view.setText(PerformanceMetrics.formatCpuLine(Double.NaN, Double.NaN)
                + "\n" + PerformanceMetrics.formatRamLine(-1L, -1L, -1L)
                + "\n" + PerformanceMetrics.formatLoadLine(Double.NaN, null));
        view.setTextColor(Color.rgb(0x39, 0xFF, 0x14));
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f);
        view.setTypeface(Typeface.MONOSPACE);
        view.setGravity(Gravity.START);
        view.setIncludeFontPadding(false);
        int paddingHorizontal = dpToPx(10f);
        int paddingVertical = dpToPx(8f);
        view.setPadding(paddingHorizontal, paddingVertical, paddingHorizontal, paddingVertical);

        GradientDrawable background = new GradientDrawable();
        background.setColor(0xC0000000);
        background.setCornerRadius(dpToPx(6f));
        view.setBackground(background);

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        params.x = dpToPx(8f);
        params.y = UiPrefs.getDevOverlayTopInsetPx(prefs) + dpToPx(8f);

        try {
            windowManager.addView(view, params);
            overlayText = view;
            overlayParams = params;
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "Unable to add performance overlay", t);
            return false;
        }
    }

    private void render(PerformanceMetrics.Snapshot snapshot) {
        if (!active || overlayText == null || snapshot == null) return;
        overlayText.setText(snapshot.format(formatThermal(snapshot)));
    }

    private String formatThermal(PerformanceMetrics.Snapshot snapshot) {
        if (PerformanceMetrics.isFiniteInRange(snapshot.cpuTemperatureCelsius, -50d, 250d)) {
            return String.format(Locale.US, "CPU %.0f °C", snapshot.cpuTemperatureCelsius);
        }
        switch (snapshot.thermalStatus) {
            case PowerManager.THERMAL_STATUS_NONE:
                return context.getString(R.string.performance_thermal_normal);
            case PowerManager.THERMAL_STATUS_LIGHT:
                return context.getString(R.string.performance_thermal_light);
            case PowerManager.THERMAL_STATUS_MODERATE:
                return context.getString(R.string.performance_thermal_moderate);
            case PowerManager.THERMAL_STATUS_SEVERE:
                return context.getString(R.string.performance_thermal_severe);
            case PowerManager.THERMAL_STATUS_CRITICAL:
                return context.getString(R.string.performance_thermal_critical);
            case PowerManager.THERMAL_STATUS_EMERGENCY:
                return context.getString(R.string.performance_thermal_emergency);
            case PowerManager.THERMAL_STATUS_SHUTDOWN:
                return context.getString(R.string.performance_thermal_shutdown);
            default:
                return context.getString(R.string.performance_temperature_unavailable);
        }
    }

    private void updateOverlayPosition() {
        if (!active || windowManager == null || overlayText == null || overlayParams == null) return;
        overlayParams.y = UiPrefs.getDevOverlayTopInsetPx(prefs) + dpToPx(8f);
        try {
            windowManager.updateViewLayout(overlayText, overlayParams);
        } catch (Throwable t) {
            Log.w(TAG, "Unable to reposition performance overlay", t);
        }
    }

    private void removeOverlayWindow() {
        if (windowManager != null && overlayText != null) {
            try {
                windowManager.removeView(overlayText);
            } catch (Throwable t) {
                Log.w(TAG, "Unable to remove performance overlay", t);
            }
        }
        overlayText = null;
        overlayParams = null;
    }

    private int dpToPx(float dp) {
        return Math.round(dp * context.getResources().getDisplayMetrics().density);
    }
}
