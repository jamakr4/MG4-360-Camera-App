package com.drivehub.kamera.dashcam;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;

import androidx.annotation.Nullable;

import com.drivehub.kamera.dev.DevRuntimeLog;

/** Optional, non-interactive status dot above the factory status bar. */
public final class StatusIconOverlay {
    private static final String TAG = "StatusIcon";
    private static final float DOT_RADIUS = 0.14f;
    private static final float SIDE_PAD = 0.16f;
    // Platform window types first: the factory status bar can cover ordinary app overlays.
    private static final int[] WINDOW_TYPES = {
            2006, // TYPE_SYSTEM_OVERLAY
            2017, // TYPE_STATUS_BAR_PANEL
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
    };

    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private WindowManager windowManager;
    private DotView view;
    private String shownStatus;

    public StatusIconOverlay(Context context) {
        this.context = context.getApplicationContext();
    }

    public void update(@Nullable String status) {
        main.post(() -> {
            if (status == null) {
                removeNow();
                return;
            }
            show(status);
        });
    }

    public void hide() {
        main.post(this::removeNow);
    }

    private void removeNow() {
        if (windowManager != null && view != null) {
            try {
                windowManager.removeView(view);
            } catch (RuntimeException e) {
                Log.w(TAG, "Could not remove status dot", e);
            }
        }
        view = null;
        windowManager = null;
        shownStatus = null;
    }

    private int colourFor(String status) {
        if (RecordingService.STATUS_RECORDING.equals(status)) {
            return DashcamEventOverlayService.BannerType.RECORDING_RECOVERED.dotColor;
        }
        if (RecordingService.STATUS_STARTING.equals(status)
                || RecordingService.STATUS_PAUSED_OEM.equals(status)) {
            return DashcamEventOverlayService.BannerType.OEM_PAUSE.dotColor;
        }
        if (RecordingService.STATUS_ERROR.equals(status)
                || RecordingService.STATUS_PARTIAL.equals(status)) {
            return DashcamEventOverlayService.BannerType.RECORDING_ERROR.dotColor;
        }
        return Color.GRAY; // No banner exists for the inactive state.
    }

    private void show(String status) {
        int colour = colourFor(status);
        if (view != null) {
            if (!status.equals(shownStatus)) {
                shownStatus = status;
                DevRuntimeLog.add(TAG, "status dot=" + status);
            }
            view.setColour(colour);
            return;
        }
        WindowManager wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        if (wm == null) return;
        DotView dot = new DotView(context);
        dot.setColour(colour);
        int barHeight = statusBarHeightPx();
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                Math.max(1, Math.round(barHeight * (2 * SIDE_PAD + 2 * DOT_RADIUS))),
                barHeight, WINDOW_TYPES[0],
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        // Place the dot just to the right of the factory climate status panel.
        lp.x = Math.round(barHeight * 1.6f);
        lp.y = 0;
        for (int type : WINDOW_TYPES) {
            lp.type = type;
            try {
                wm.addView(dot, lp);
                windowManager = wm;
                view = dot;
                shownStatus = status;
                DevRuntimeLog.add(TAG, "status dot=" + status + " windowType=" + type
                        + " size=" + lp.width + "x" + lp.height);
                return;
            } catch (RuntimeException e) {
                DevRuntimeLog.add(TAG, "status dot windowType=" + type + " refused: " + e);
            }
        }
    }

    private int statusBarHeightPx() {
        int id = context.getResources().getIdentifier("status_bar_height", "dimen", "android");
        int height = id > 0 ? context.getResources().getDimensionPixelSize(id) : 0;
        return height > 0 ? height : Math.round(28 * context.getResources().getDisplayMetrics().density);
    }

    private static final class DotView extends View {
        private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);

        DotView(Context context) {
            super(context);
            dot.setStyle(Paint.Style.FILL);
        }

        void setColour(int colour) {
            dot.setColor(colour);
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            canvas.drawCircle(getWidth() / 2f, getHeight() / 2f, getHeight() * DOT_RADIUS, dot);
        }
    }
}
