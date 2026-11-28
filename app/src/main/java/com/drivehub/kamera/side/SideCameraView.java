package com.drivehub.kamera.side;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.util.Log;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.ViewGroup;

import com.drivehub.kamera.camera.CameraHandoffController;
import com.drivehub.kamera.dev.DevRuntimeLog;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Two native window outputs using the same RGBA frames as the main camera preview. */
public final class SideCameraView extends ViewGroup {
    interface Listener {
        void onFreshSides(int mask);
    }

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final SurfaceView[] surfaces = new SurfaceView[2];
    // Native side outputs are process-wide. Serialize old-window cleanup before a new attach.
    private static final ExecutorService IO = Executors.newSingleThreadExecutor(job -> new Thread(job, "Side-preview"));
    private static SideCameraView owner; // UI thread, only while resumed
    private final boolean[] available = new boolean[2];
    private final SidePoll[] polls = {new SidePoll(), new SidePoll()};

    private static final class SidePoll {
        volatile boolean cancelled;
        long lastAttempt; // Worker only
        boolean pending; // UI thread below
        long postedAt = -1;
        long handoff = -1;
    }
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
                    available[index] = true;
                    if (active && !yielded) refresh();
                }
                @Override public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
                    // A size/inset change must not detach or pre-warm a healthy preview.
                }
                @Override public void surfaceDestroyed(SurfaceHolder holder) {
                    available[index] = false;
                    resetPoll(index);
                    if (owner != SideCameraView.this) return;
                    CameraHandoffController.demand("side-" + index, false);
                    IO.execute(() -> SideCameraNative.detachPreview(index));
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
        if (owner != null && owner != this) owner.pauseDisplay();
        owner = this;
        active = true;
        if (!yielded) IO.execute(SideCameraNative::enablePreviews);
        handler.removeCallbacks(tick);
        handler.post(tick);
    }

    void yieldToOem(boolean value) {
        // A second unblocked notification can follow release of the native gate.
        if (yielded == value && (value || !active)) return;
        yielded = value;
        if (!active) return;
        resetPoll(0);
        resetPoll(1);
        if (value) {
            IO.execute(SideCameraNative::detachPreviews);
            publishState(0);
        } else {
            IO.execute(SideCameraNative::enablePreviews);
            refresh();
        }
    }

    void pauseDisplay() {
        if (!active) return;
        active = false;
        owner = null;
        resetPoll(0);
        resetPoll(1);
        CameraHandoffController.demand("side-0", false);
        CameraHandoffController.demand("side-1", false);
        handler.removeCallbacks(tick);
        IO.execute(SideCameraNative::detachPreviews);
    }

    private void resetPoll(int side) {
        polls[side].cancelled = true;
        polls[side] = new SidePoll();
    }

    private void refresh() {
        long handoff = CameraHandoffController.generation();
        long now = SystemClock.elapsedRealtime();
        int mask = 0;
        for (int side = 0; side < 2; side++) {
            Surface surface = surfaces[side].getHolder().getSurface();
            boolean needed = available[side] && surface.isValid();
            String key = "side-" + side;
            CameraHandoffController.demand(key, needed);
            SidePoll poll = polls[side];
            boolean fresh = needed && !yielded && poll.handoff == handoff
                    && poll.postedAt >= 0 && now - poll.postedAt <= 500;
            if (fresh) mask |= 1 << side;
            CameraHandoffController.ready(key, fresh, handoff);
            if (needed && !yielded && !poll.pending) {
                poll.pending = true;
                int index = side;
                IO.execute(() -> pollSide(index, surface, poll, handoff));
            }
        }
        publishState(mask);
    }

    private void pollSide(int side, Surface surface, SidePoll poll, long handoff) {
        long postedAt = -1;
        try {
            if (poll.cancelled || handoff != CameraHandoffController.generation() || !surface.isValid()) return;
            long age = SideCameraNative.previewAgeMs(side);
            long now = SystemClock.elapsedRealtime();
            if (age >= 0) postedAt = now - age;
            if ((age < 0 || age >= 1500) && (poll.lastAttempt == 0 || now - poll.lastAttempt >= 2000)
                    && !poll.cancelled && handoff == CameraHandoffController.generation()) {
                boolean firstAttempt = poll.lastAttempt == 0;
                poll.lastAttempt = now;
                boolean ok = SideCameraNative.attachPreview(side, surface, SideCameraGeometry.previewCrops(side == 0));
                long duration = SystemClock.elapsedRealtime() - now;
                if (firstAttempt || duration >= 250) {
                    String timing = "side " + side + " attach " + (ok ? "ready" : "failed") + " in " + duration + " ms";
                    Log.i("PreviewStartup", timing);
                    DevRuntimeLog.add("Side preview", timing);
                }
            }
        } catch (RuntimeException failure) {
            Log.w("SidePreview", "Cannot update side " + side, failure);
        } finally {
            long frameTime = postedAt;
            handler.post(() -> {
                if (poll.cancelled) return;
                poll.pending = false;
                poll.handoff = handoff;
                poll.postedAt = frameTime;
            });
        }
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
