package com.drivehub.kamera.camera;

import android.content.Context;
import android.graphics.Point;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Display;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.WindowManager;

/** Measures the actual window origin, following MG4Control's top-bar button approach. */
public final class FloatingWindowGeometry implements AutoCloseable {
    private static final long POLL_MS = 500;
    private final WindowManager windows;
    private final View view;
    private final WindowManager.LayoutParams params;
    private final Runnable onChange;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Rect screen = new Rect(), visible = new Rect();
    private int originX, originY;
    private boolean measured, closed, paused, layoutPending, notificationPending, notificationNeeded;

    public FloatingWindowGeometry(WindowManager windows, View view,
            WindowManager.LayoutParams params, Runnable onChange) {
        this.windows = windows;
        this.view = view;
        this.params = params;
        this.onChange = onChange;
        view.getViewTreeObserver().addOnPreDrawListener(preDraw);
        main.post(poll);
    }

    private final ViewTreeObserver.OnPreDrawListener preDraw = () -> {
        // updateViewLayout is asynchronous. Only measure the new params after
        // the corresponding traversal, never against the old on-screen position.
        layoutPending = false;
        refresh();
        return true;
    };

    private final Runnable poll = new Runnable() {
        @Override public void run() {
            if (closed) return;
            refresh();
            main.postDelayed(this, POLL_MS);
        }
    };

    public int originX() { return originX; }
    public int originY() { return originY; }
    public boolean isMeasured() { return measured; }
    public Rect screenBounds() { return new Rect(screen); }
    public Rect visibleBounds() { return new Rect(visible); }

    public void setPaused(boolean paused) {
        this.paused = paused;
        if (!paused) refresh();
    }

    public void updateLayout() {
        if (closed) return;
        layoutPending = true;
        try {
            windows.updateViewLayout(view, params);
        } catch (RuntimeException e) {
            layoutPending = false;
            Log.w("FloatingGeometry", "Could not update floating window", e);
        }
    }

    @SuppressWarnings("deprecation")
    public void refresh() {
        if (closed || paused || layoutPending || !view.isAttachedToWindow()
                || view.isLayoutRequested() || view.getWidth() <= 0 || view.getHeight() <= 0) return;
        int[] location = new int[2];
        view.getLocationOnScreen(location);
        int x = location[0] - params.x;
        int y = location[1] - params.y;
        Point size = new Point();
        Display display = view.getDisplay();
        if (display == null) display = windows.getDefaultDisplay();
        display.getRealSize(size);
        if (size.x <= 0 || size.y <= 0) return;
        Rect newScreen = new Rect(0, 0, size.x, size.y);
        Rect newVisible = new Rect();
        view.getWindowVisibleDisplayFrame(newVisible);
        if (!newVisible.intersect(newScreen)) newVisible.set(newScreen);
        // Some firmware reports the full frame while the window still starts after the rail.
        newVisible.left = Math.max(newVisible.left, Math.max(0, x));
        newVisible.top = Math.max(newVisible.top, Math.max(0, y));
        if (newVisible.isEmpty()) newVisible.set(newScreen);
        boolean changed = !measured || x != originX || y != originY
                || !screen.equals(newScreen) || !visible.equals(newVisible);
        originX = x;
        originY = y;
        screen.set(newScreen);
        visible.set(newVisible);
        measured = true;
        notificationNeeded |= changed;
        if (notificationNeeded && !notificationPending) {
            notificationPending = true;
            main.post(() -> {
                notificationPending = false;
                if (closed || paused || layoutPending) return;
                notificationNeeded = false;
                onChange.run();
            });
        }
    }

    @SuppressWarnings("deprecation")
    public static Point screenSize(Context context) {
        WindowManager windows = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        Point size = new Point();
        if (windows != null) windows.getDefaultDisplay().getRealSize(size);
        if (size.x <= 0 || size.y <= 0) {
            size.set(Math.max(1, context.getResources().getDisplayMetrics().widthPixels),
                    Math.max(1, context.getResources().getDisplayMetrics().heightPixels));
        }
        return size;
    }

    @Override public void close() {
        closed = true;
        main.removeCallbacksAndMessages(null);
        ViewTreeObserver observer = view.getViewTreeObserver();
        if (observer.isAlive()) observer.removeOnPreDrawListener(preDraw);
    }
}
