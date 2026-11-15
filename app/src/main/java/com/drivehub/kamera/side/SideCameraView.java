package com.drivehub.kamera.side;

import android.content.Context;
import com.drivehub.kamera.camera.CameraHandoffController;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.ViewGroup;

/** Two native window outputs using the same RGBA frames as the main camera preview. */
public final class SideCameraView extends ViewGroup {
    interface Listener {
        void onFreshSides(int mask);
    }

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final SurfaceView[] surfaces = new SurfaceView[2];
    private final long[] lastAttempt = new long[2];
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!active) return;
            refresh();
            handler.postDelayed(this, 200);
        }
    };
    private boolean active, yielded;
    private int lastMask = -1;
    private Listener listener;

    public SideCameraView(Context context, AttributeSet attrs) {
        super(context, attrs);
        for (int side = 0; side < 2; side++) {
            final int index = side;
            SurfaceView view = new SurfaceView(context);
            surfaces[side] = view;
            // Window resizes only scale the existing output, without reallocating buffers.
            view.getHolder().setFixedSize(SideCameraGeometry.TILE_WIDTH, SideCameraGeometry.SIDE_HEIGHT);
            view.getHolder().addCallback(new SurfaceHolder.Callback() {
                @Override public void surfaceCreated(SurfaceHolder holder) {
                    lastAttempt[index] = 0;
                    if (active && !yielded) refresh();
                }
                @Override public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
                    // A size/inset change must not detach or pre-warm a healthy preview.
                }
                @Override public void surfaceDestroyed(SurfaceHolder holder) {
                    CameraHandoffController.demand("side-" + index, false);
                    SideCameraNative.detachPreview(index);
                    lastAttempt[index] = 0;
                    publishState(Math.max(0, lastMask) & ~(1 << index));
                }
            });
            addView(view);
        }
    }

    void setListener(Listener value) {
        listener = value;
    }

    void setWheelsMode(boolean value) {
        SideCameraNative.setWheelsMode(value);
    }

    void resumeDisplay() {
        active = true;
        if (!yielded) SideCameraNative.enablePreviews();
        lastAttempt[0] = lastAttempt[1] = 0;
        handler.removeCallbacks(tick);
        handler.post(tick);
    }

    void yieldToOem(boolean value) {
        // A coexistence toggle can arrive before the native gate is released. The subsequent
        // ownership update must retry immediately even if the UI already considers it unblocked.
        if (yielded == value && (value || !active)) return;
        yielded = value;
        if (value) {
            SideCameraNative.detachPreviews();
            publishState(0);
        } else if (active) {
            SideCameraNative.enablePreviews();
            lastAttempt[0] = lastAttempt[1] = 0;
            refresh();
        }
    }

    void pauseDisplay() {
        active = false;
        CameraHandoffController.demand("side-0", false);
        CameraHandoffController.demand("side-1", false);
        handler.removeCallbacks(tick);
        SideCameraNative.detachPreviews();
    }

    private void refresh() {
        for (int side = 0; side < 2; side++) {
            CameraHandoffController.demand("side-" + side, active && surfaces[side].getHolder().getSurface().isValid());
        }
        long handoffGeneration = CameraHandoffController.generation();
        int mask = 0;
        if (!yielded) {
            long now = SystemClock.elapsedRealtime();
            for (int side = 0; side < 2; side++) {
                SurfaceHolder holder = surfaces[side].getHolder();
                if (!holder.getSurface().isValid()) continue;
                long age = SideCameraNative.previewAgeMs(side);
                if (age >= 0 && age <= 500) mask |= 1 << side;
                if ((age < 0 || age >= 1500)
                        && (lastAttempt[side] == 0 || now - lastAttempt[side] >= 2000)) {
                    lastAttempt[side] = now;
                    SideCameraNative.attachPreview(side, holder.getSurface(),
                            SideCameraGeometry.previewCrops(side == 0));
                }
            }
        }
        for (int side = 0; side < 2; side++) {
            CameraHandoffController.ready("side-" + side, (mask & (1 << side)) != 0, handoffGeneration);
        }
        publishState(mask);
    }

    private void publishState(int mask) {
        if (lastMask == mask) return;
        lastMask = mask;
        if (active && listener != null) listener.onFreshSides(mask);
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int width = MeasureSpec.getSize(widthSpec), height = MeasureSpec.getSize(heightSpec);
        setMeasuredDimension(width, height);
        for (SurfaceView view : surfaces) {
            view.measure(MeasureSpec.makeMeasureSpec(Math.round(254 * width / 540f), MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(Math.round(456 * height / 640f), MeasureSpec.EXACTLY));
        }
    }

    @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
        for (int side = 0; side < 2; side++) {
            SurfaceView view = surfaces[side];
            int x = Math.round((12 + side * 262) * (r - l) / 540f);
            int y = Math.round(76 * (b - t) / 640f);
            view.layout(x, y, x + view.getMeasuredWidth(), y + view.getMeasuredHeight());
        }
    }
}
