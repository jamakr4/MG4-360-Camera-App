package com.drivehub.kamera.birdseye;

import android.content.Context;
import android.opengl.GLSurfaceView;
import android.os.SystemClock;
import android.view.SurfaceHolder;

import javax.microedition.khronos.egl.EGL10;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.egl.EGLDisplay;
import javax.microedition.khronos.opengles.GL10;

/** The framework GL thread draws continuously while the camera source is active. */
@android.annotation.SuppressLint("ViewConstructor")
public final class BirdseyeSurface extends GLSurfaceView {
    public interface Listener { void available(boolean ready); }

    private final BirdseyeRenderer renderer;
    private final Listener listener;
    private boolean resumed, surfaceReady, trackingPose;
    private Runnable updatePose, onImageChanged;
    private volatile PresentedImage presented;
    private volatile long lastPresentedDrawMs;

    private static final class PresentedImage {
        final int generation, mask;
        final long observedAt;

        PresentedImage(int generation, int mask) {
            this.generation = generation;
            this.mask = mask;
            observedAt = SystemClock.elapsedRealtime();
        }
    }

    public BirdseyeSurface(Context context, BirdseyeRenderer renderer, Listener listener) {
        super(context);
        this.renderer = renderer;
        this.listener = listener;
        setEGLContextClientVersion(2);
        setEGLConfigChooser((egl, display) -> chooseWindowConfig(egl, display, 16));
        setRenderer(new WindowRenderer());
        setRenderMode(RENDERMODE_CONTINUOUSLY);
    }

    // Keep UI-owned steering state current independently of the GL draw loop.
    private final Runnable poseTick = new Runnable() {
        @Override public void run() {
            if (!trackingPose) return;
            updatePose.run();
            postOnAnimation(this);
        }
    };

    void setPoseUpdate(Runnable update) {
        updatePose = update;
        updatePoseTracking();
    }

    private void updatePoseTracking() {
        boolean active = resumed && surfaceReady && updatePose != null && getRenderMode() == RENDERMODE_CONTINUOUSLY;
        if (trackingPose == active) return;
        trackingPose = active;
        if (active) postOnAnimation(poseTick);
        else removeCallbacks(poseTick);
    }

    public void setContinuousRendering(boolean enabled) {
        int mode = enabled ? RENDERMODE_CONTINUOUSLY : RENDERMODE_WHEN_DIRTY;
        if (getRenderMode() != mode) setRenderMode(mode);
        updatePoseTracking();
    }

    @Override public void onResume() {
        super.onResume();
        resumed = true;
        updatePoseTracking();
    }

    @Override public void onPause() {
        resumed = false;
        updatePoseTracking();
        super.onPause();
        renderer.invalidateFrames();
        presented = null;
    }

    @Override public void surfaceCreated(SurfaceHolder holder) {
        super.surfaceCreated(holder);
        surfaceReady = true;
        updatePoseTracking();
        if (listener != null) listener.available(true);
    }

    @Override public void surfaceDestroyed(SurfaceHolder holder) {
        surfaceReady = false;
        updatePoseTracking();
        if (listener != null) listener.available(false);
        super.surfaceDestroyed(holder);
        renderer.invalidateFrames();
        presented = null;
    }

    void setOnImageChanged(Runnable callback) { onImageChanged = callback; }

    int presentedMask() {
        PresentedImage image = presented;
        if (!resumed || !surfaceReady || image == null || !renderer.isCurrentGeneration(image.generation)
                || SystemClock.elapsedRealtime() - lastPresentedDrawMs > BirdseyeFrameHealth.DISPLAY_HOLD_US / 1000)
            return 0;
        return image.mask;
    }

    long presentationDelayMs() {
        PresentedImage image = presented;
        return image == null ? -1 : SystemClock.elapsedRealtime() - image.observedAt;
    }

    static EGLConfig chooseWindowConfig(EGL10 egl, EGLDisplay display, int preferredDepth) {
        EGLConfig[] configs = new EGLConfig[1];
        int[] count = new int[1];
        for (int depth : new int[]{preferredDepth, 0}) {
            int[] attrs = {EGL10.EGL_RED_SIZE, 8, EGL10.EGL_GREEN_SIZE, 8, EGL10.EGL_BLUE_SIZE, 8,
                    EGL10.EGL_DEPTH_SIZE, depth, EGL10.EGL_RENDERABLE_TYPE, 4,
                    EGL10.EGL_SURFACE_TYPE, EGL10.EGL_WINDOW_BIT, EGL10.EGL_NONE};
            if (egl.eglChooseConfig(display, attrs, configs, 1, count) && count[0] > 0) return configs[0];
        }
        throw new IllegalStateException("EGL config: 0x" + Integer.toHexString(egl.eglGetError()));
    }

    private final class WindowRenderer implements Renderer {
        private int previousGeneration, previousMask;
        private long previousDrawMs;

        @Override public void onSurfaceCreated(GL10 gl, EGLConfig config) {
            previousDrawMs = 0;
            renderer.onSurfaceCreated(gl, config);
        }

        @Override public void onSurfaceChanged(GL10 gl, int width, int height) {
            renderer.onSurfaceChanged(gl, width, height);
        }

        @Override public void onDrawFrame(GL10 gl) {
            // GLSurfaceView swaps after onDrawFrame returns. On the next callback the
            // previous swap has finished, so the loading overlay can release that image.
            if (previousDrawMs != 0 && renderer.isCurrentGeneration(previousGeneration)) {
                lastPresentedDrawMs = previousDrawMs;
                PresentedImage image = presented;
                if (image == null || image.generation != previousGeneration || image.mask != previousMask) {
                    presented = new PresentedImage(previousGeneration, previousMask);
                    post(() -> { if (onImageChanged != null) onImageChanged.run(); });
                }
            }
            long before = renderer.renderedFrames;
            renderer.onDrawFrame(gl);
            previousMask = before == renderer.renderedFrames ? 0 : renderer.displayMask;
            previousGeneration = renderer.drawnGeneration();
            previousDrawMs = SystemClock.elapsedRealtime();
        }
    }
}
