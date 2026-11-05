package com.drivehub.kamera.dashcam;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;

import androidx.annotation.Nullable;

import com.drivehub.kamera.dev.DevRuntimeLog;
import com.drivehub.kamera.camera.FloatingWindowGeometry;
import com.drivehub.kamera.camera.FloatingWindowPlacement;
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
    private FloatingWindowGeometry geometry;

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
        Point size = FloatingWindowGeometry.screenSize(context);
        Rect bounds = geometry != null && geometry.isMeasured()
                ? geometry.screenBounds() : new Rect(0, 0, size.x, size.y);
        FloatingWindowPlacement place = FloatingWindowPlacement.calculate(
                getPositionX(context), getPositionY(context), params.width, params.height,
                geometry == null ? 0 : geometry.originX(), geometry == null ? 0 : geometry.originY(),
                bounds.left, bounds.top, bounds.right, bounds.bottom);
        if (params.x != place.windowX || params.y != place.windowY) {
            params.x = place.windowX;
            params.y = place.windowY;
            if (geometry != null) geometry.updateLayout();
        }
        if (geometry != null && geometry.isMeasured()) view.setAlpha(1f);
    }

    private void removeNow() {
        if (geometry != null) { geometry.close(); geometry = null; }
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
        dot.setAlpha(0f);
        int barHeight = statusBarHeightPx(context);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                iconWidthPx(context),
                barHeight, WINDOW_TYPES[0],
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = getPositionX(context);
        lp.y = getPositionY(context);
        for (int type : WINDOW_TYPES) {
            lp.type = type;
            try {
                wm.addView(dot, lp);
                windowManager = wm;
                view = dot;
                params = lp;
                shownStatus = status;
                geometry = new FloatingWindowGeometry(wm, dot, lp, this::repositionNow);
                DevRuntimeLog.add(TAG, "status dot=" + status + " windowType=" + type
                        + " size=" + lp.width + "x" + lp.height);
                return;
            } catch (RuntimeException e) {
                DevRuntimeLog.add(TAG, "status dot windowType=" + type + " refused: " + e);
            }
        }
    }

    static int iconWidthPx(Context context) {
        return Math.max(1, Math.round(statusBarHeightPx(context)
                * (2 * SIDE_PAD + 2 * DOT_RADIUS)));
    }

    static int maxPositionX(Context context) {
        return Math.max(0, FloatingWindowGeometry.screenSize(context).x - iconWidthPx(context));
    }

    static int maxPositionY(Context context) {
        return Math.max(0, FloatingWindowGeometry.screenSize(context).y - statusBarHeightPx(context));
    }

    static int getPositionX(Context context) {
        return Math.max(0, Math.min(maxPositionX(context), UiPrefs.getPrefs(context).getInt(
                UiPrefs.KEY_DASHCAM_STATUS_ICON_X, 1166)));
    }

    static int getPositionY(Context context) {
        return Math.max(0, Math.min(maxPositionY(context), UiPrefs.getPrefs(context).getInt(
                UiPrefs.KEY_DASHCAM_STATUS_ICON_Y, 0)));
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
