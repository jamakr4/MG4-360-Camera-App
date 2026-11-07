package com.drivehub.kamera.side;

import android.content.Context;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.SurfaceHolder;

import com.drivehub.kamera.dev.DevRuntimeLog;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/** Displays two camera textures as either two sides or four wheel crops. */
public final class SideCameraView extends GLSurfaceView implements GLSurfaceView.Renderer {
    interface Listener {
        void onFreshSides(int mask);
    }

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!active) return;
            requestRender();
            handler.postDelayed(this, 80);
        }
    };
    private volatile boolean active, yielded, wheels;
    private volatile int lastMask = -1;
    private Listener listener;
    // Owned by the GL thread.
    private SideCameraRenderer renderer;
    private int width, height;
    private long lastAttachAttempt;

    public SideCameraView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setEGLContextClientVersion(2);
        setEGLConfigChooser(8, 8, 8, 0, 0, 0);
        setPreserveEGLContextOnPause(false);
        setRenderer(this);
        setRenderMode(RENDERMODE_WHEN_DIRTY);
    }

    void setListener(Listener value) {
        listener = value;
    }

    void setWheelsMode(boolean value) {
        wheels = value;
        requestRender();
    }

    void resumeDisplay() {
        active = true;
        if (!yielded) SideCameraNative.enableFrames();
        super.onResume();
        handler.removeCallbacks(tick);
        handler.post(tick);
    }

    void yieldToOem(boolean value) {
        if (yielded == value) return;
        yielded = value;
        if (value) {
            SideCameraNative.detachFrames();
            publishState(0);
        } else if (active) {
            SideCameraNative.enableFrames();
        }
        queueEvent(() -> lastAttachAttempt = 0);
        requestRender();
    }

    void pauseDisplay() {
        active = false;
        handler.removeCallbacks(tick);
        SideCameraNative.detachFrames();
        queueEvent(this::releaseGl);
        super.onPause();
    }

    @Override public void surfaceCreated(SurfaceHolder holder) {
        if (active && !yielded) SideCameraNative.enableFrames();
        super.surfaceCreated(holder);
    }

    @Override public void surfaceDestroyed(SurfaceHolder holder) {
        SideCameraNative.detachFrames();
        // GLSurfaceView can retain its EGL context while replacing only the surface.
        // Keep its textures until onPause; otherwise no onSurfaceCreated would rebuild them.
        publishState(0);
        super.surfaceDestroyed(holder);
    }

    @Override public void onSurfaceCreated(GL10 gl, EGLConfig config) {
        renderer = null;
        lastAttachAttempt = 0;
        lastMask = -1;
        try {
            renderer = new SideCameraRenderer();
        } catch (RuntimeException error) {
            DevRuntimeLog.add("Side camera renderer", error.toString());
        }
    }

    @Override public void onSurfaceChanged(GL10 gl, int w, int h) {
        width = w;
        height = h;
    }

    @Override public void onDrawFrame(GL10 gl) {
        GLES20.glDisable(GLES20.GL_SCISSOR_TEST);
        GLES20.glClearColor(0, 0, 0, 1);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        if (!active || yielded || renderer == null) {
            publishState(0);
            return;
        }
        long now = SystemClock.elapsedRealtime();
        if (lastAttachAttempt == 0 || now - lastAttachAttempt >= 2000) {
            SideCameraNative.attachFrames(lastAttachAttempt != 0);
            lastAttachAttempt = now;
        }
        int mask = renderer.uploadFrames();
        renderer.draw(wheels, mask, width, height);
        publishState(mask);
    }

    private void publishState(int mask) {
        if (lastMask == mask) return;
        lastMask = mask;
        handler.post(() -> {
            if (active && listener != null) listener.onFreshSides(lastMask);
        });
    }

    private void releaseGl() {
        if (renderer != null) renderer.release();
        renderer = null;
        lastAttachAttempt = 0;
    }
}
