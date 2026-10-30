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
import com.drivehub.kamera.camera.ProjectionForeground;
import com.drivehub.kamera.settings.UiPrefs;

/** Optional, non-interactive status dot positioned on the full MG4 display. */
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
    private WindowManager.LayoutParams params;
    private DotView view;
    private String shownStatus;
    private final Runnable positionPoll = new Runnable() {
        @Override public void run() {
            if (view == null || windowManager == null || params == null) return;
            repositionNow();
            main.postDelayed(this, UiPrefs.getDevForegroundModePollMs(UiPrefs.getPrefs(context)));
        }
    };

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

    public void reposition() {
        main.post(this::repositionNow);
    }

    private void repositionNow() {
        if (view == null || windowManager == null || params == null) return;
        int x = horizontalOffset(params.width);
        int y = getPositionY(context);
        if (params.x == x && params.y == y) return;
        params.x = x;
        params.y = y;
        try {
            windowManager.updateViewLayout(view, params);
        } catch (RuntimeException e) {
            Log.w(TAG, "Could not reposition status dot", e);
        }
    }

    private void removeNow() {
        main.removeCallbacks(positionPoll);
        if (windowManager != null && view != null) {
            try {
                windowManager.removeView(view);
            } catch (RuntimeException e) {
                Log.w(TAG, "Could not remove status dot", e);
            }
        }
        view = null;
        windowManager = null;
        params = null;
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
        int barHeight = statusBarHeightPx(context);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                iconWidthPx(context),
                barHeight, WINDOW_TYPES[0],
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        lp.x = horizontalOffset(lp.width);
        lp.y = getPositionY(context);
        for (int type : WINDOW_TYPES) {
            lp.type = type;
            try {
                wm.addView(dot, lp);
                windowManager = wm;
                view = dot;
                params = lp;
                shownStatus = status;
                main.postDelayed(positionPoll, UiPrefs.getDevForegroundModePollMs(UiPrefs.getPrefs(context)));
                DevRuntimeLog.add(TAG, "status dot=" + status + " windowType=" + type
                        + " size=" + lp.width + "x" + lp.height);
                return;
            } catch (RuntimeException e) {
                DevRuntimeLog.add(TAG, "status dot windowType=" + type + " refused: " + e);
            }
        }
    }

    private int horizontalOffset(int iconWidth) {
        int launcherWidth = ProjectionForeground.isActive(context)
                ? 0 : ProjectionForeground.LAUNCHER_BAR_WIDTH_PX;
        return StatusIconPosition.windowX(getPositionX(context), iconWidth, launcherWidth);
    }

    static int iconWidthPx(Context context) {
        return Math.max(1, Math.round(statusBarHeightPx(context)
                * (2 * SIDE_PAD + 2 * DOT_RADIUS)));
    }

    static int maxPositionX(Context context) {
        return Math.max(0, StatusIconPosition.SCREEN_WIDTH - iconWidthPx(context));
    }

    static int maxPositionY(Context context) {
        return Math.max(0, StatusIconPosition.SCREEN_HEIGHT - statusBarHeightPx(context));
    }

    static int getPositionX(Context context) {
        int defaultX = (StatusIconPosition.SCREEN_WIDTH + ProjectionForeground.LAUNCHER_BAR_WIDTH_PX
                - iconWidthPx(context)) / 2 + Math.round(statusBarHeightPx(context) * 1.8f);
        return StatusIconPosition.clamp(UiPrefs.getPrefs(context).getInt(
                UiPrefs.KEY_DASHCAM_STATUS_ICON_X, defaultX),
                StatusIconPosition.SCREEN_WIDTH, iconWidthPx(context));
    }

    static int getPositionY(Context context) {
        return StatusIconPosition.clamp(UiPrefs.getPrefs(context).getInt(
                UiPrefs.KEY_DASHCAM_STATUS_ICON_Y, 0),
                StatusIconPosition.SCREEN_HEIGHT, statusBarHeightPx(context));
    }

    private static int statusBarHeightPx(Context context) {
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
