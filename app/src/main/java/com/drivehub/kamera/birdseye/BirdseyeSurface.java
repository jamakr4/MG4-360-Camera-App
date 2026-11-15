package com.drivehub.kamera.birdseye;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.view.TextureView;
import android.view.Choreographer;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** One bounded render queue and owned EGL thread. Even EGL startup failures are
 * contained here; no GL/driver wait or thread join occurs on capture or UI threads. */
@android.annotation.SuppressLint("ViewConstructor") // Programmatic only: requires a renderer instance.
public final class BirdseyeSurface extends TextureView implements TextureView.SurfaceTextureListener {
    public interface Listener { void available(boolean ready); }
    private final Handler main = new Handler(Looper.getMainLooper());
    private final BirdseyeRenderer renderer;
    private final Listener listener;
    private SurfaceTexture texture;
    private Run run;
    private boolean resumed, continuous = true, dirty = true, framePosted;
    private final Choreographer choreographer = Choreographer.getInstance();
    private long displayCallbacks;
    private static volatile String latestPresentation = "Birdseye output: no completed timing sample";
    private static volatile long presentationAtMs;
    static String presentationStatus() {
        return latestPresentation + "\nOutput timing updated " + Math.max(0,
                android.os.SystemClock.elapsedRealtime()-presentationAtMs) + " ms ago";
    }
    private final Choreographer.FrameCallback frame = time -> {
        framePosted = false;
        if (canDraw() && (continuous || dirty)) {
            displayCallbacks++;
            dirty = false;
            run.draw(time); // One pending GL job at most; a slow GPU never builds a queue.
        }
        scheduleFrame();
    };
    private volatile int width, height;

    public BirdseyeSurface(Context context, BirdseyeRenderer renderer, Listener listener) {
        super(context); this.renderer = renderer; this.listener = listener;
        setOpaque(true); setSurfaceTextureListener(this);
    }

    public void onResume() { resumed = true; dirty = true; startIfReady(); scheduleFrame(); }
    public void onPause() { resumed = false; cancelFrame(); if (run != null) run.stop(); }
    public void requestRender() { dirty = true; scheduleFrame(); }
    public void setContinuousRendering(boolean enabled) { continuous = enabled; scheduleFrame(); }
    long displayCallbacks() { return displayCallbacks; }
    private boolean canDraw() { return resumed && texture != null && run != null && !run.stopped; }
    private void scheduleFrame() {
        if (!framePosted && canDraw() && (continuous || dirty)) {
            framePosted = true; choreographer.postFrameCallback(frame);
        }
    }
    private void cancelFrame() {
        choreographer.removeFrameCallback(frame); framePosted = false;
    }

    private void startIfReady() {
        // Wait for an old producer to disconnect before reusing its SurfaceTexture.
        if (!resumed || texture == null || run != null) return;
        renderer.error = null;
        run = new Run(texture);
        run.start(); scheduleFrame();
    }

    @Override public void onSurfaceTextureAvailable(SurfaceTexture surface, int w, int h) {
        texture = surface; width = w; height = h;
        startIfReady(); if (listener != null) listener.available(true);
    }
    @Override public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int w, int h) { width=w; height=h; requestRender(); }
    @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
        texture = null; cancelFrame();
        if (listener != null) listener.available(false);
        renderer.invalidateFrames();
        if (run != null && run.input == surface) { run.releaseTexture = true; run.stop(); }
        else surface.release();
        // We release it only after the old GL producer has finished/disconnected.
        return false;
    }
    @Override public void onSurfaceTextureUpdated(SurfaceTexture surface) {}

    /** Retry without depth while retaining the GLES2/window requirements for the working 2D path. */
    static EGLConfig chooseWindowConfig(EGLDisplay display,int preferredDepth) {
        EGLConfig[] configs=new EGLConfig[1];int[] count=new int[1];
        for(int depth:new int[]{preferredDepth,0}) {
            int[] attrs={EGL14.EGL_RED_SIZE,8,EGL14.EGL_GREEN_SIZE,8,EGL14.EGL_BLUE_SIZE,8,
                    EGL14.EGL_DEPTH_SIZE,depth,EGL14.EGL_RENDERABLE_TYPE,4,EGL14.EGL_SURFACE_TYPE,EGL14.EGL_WINDOW_BIT,EGL14.EGL_NONE};
            if(EGL14.eglChooseConfig(display,attrs,0,configs,0,1,count,0)&&count[0]>0)return configs[0];
        }
        throw new IllegalStateException("EGL config: 0x"+Integer.toHexString(EGL14.eglGetError()));
    }

    private final class Run {
        final SurfaceTexture input;
        final HandlerThread thread = new HandlerThread("Birdseye-EGL");
        final AtomicBoolean queued = new AtomicBoolean();
        final AtomicLong busyCallbacks = new AtomicLong();
        long statsStartNs, statsFrames, statsQueueNs, statsSwapNs, maxSwapNs, statsCallbackNs, previousBusy;
        Handler handler;
        volatile boolean stopped, releaseTexture;
        boolean closing;
        EGLDisplay display = EGL14.EGL_NO_DISPLAY;
        EGLContext context = EGL14.EGL_NO_CONTEXT;
        EGLSurface surface = EGL14.EGL_NO_SURFACE;
        int lastWidth, lastHeight;

        Run(SurfaceTexture input) { this.input = input; }
        void start() {
            // A private handler also contains failures before/around Looper dispatch.
            thread.setUncaughtExceptionHandler((t, error) -> { fail(error); stopped=true; closeOnThread(); });
            thread.start(); handler = new Handler(thread.getLooper());
            handler.post(() -> {
                try {
                    if (stopped) return;
                    display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
                    require(EGL14.eglInitialize(display, new int[2],0,new int[2],0), "initialize");
                    EGLConfig config=chooseWindowConfig(display,16);
                    context = EGL14.eglCreateContext(display,config,EGL14.EGL_NO_CONTEXT,
                            new int[]{EGL14.EGL_CONTEXT_CLIENT_VERSION,2,EGL14.EGL_NONE},0);
                    require(context != null && context != EGL14.EGL_NO_CONTEXT, "context");
                    surface = EGL14.eglCreateWindowSurface(display,config,input,new int[]{EGL14.EGL_NONE},0);
                    require(surface != null && surface != EGL14.EGL_NO_SURFACE, "surface");
                    require(EGL14.eglMakeCurrent(display,surface,surface,context), "makeCurrent");
                    renderer.onSurfaceCreated(null,null);
                } catch (Throwable error) { fail(error); stopped=true; closeOnThread(); }
            });
        }
        void draw(long frameNs) {
            if (stopped) return;
            if (!queued.compareAndSet(false,true)) { busyCallbacks.incrementAndGet(); return; }
            final long requestedNs = System.nanoTime();
            handler.post(() -> {
                try {
                    if (stopped || surface == EGL14.EGL_NO_SURFACE) return;
                    long startedNs = System.nanoTime();
                    if (width != lastWidth || height != lastHeight) {
                        lastWidth=width; lastHeight=height;
                        renderer.onSurfaceChanged(null,lastWidth,lastHeight);
                    }
                    renderer.onDrawFrame(null);
                    if (renderer.error != null) { stopped=true; closeOnThread(); return; }
                    long swapStartNs = System.nanoTime();
                    require(EGL14.eglSwapBuffers(display,surface), "swapBuffers");
                    recordPresentation(frameNs, requestedNs, startedNs, swapStartNs, System.nanoTime());
                } catch (Throwable error) { fail(error); stopped=true; closeOnThread(); }
                finally { queued.set(false); }
            });
        }
        // Wall times, not GPU timestamps. In particular, swap can include GPU and
        // BufferQueue waits that the renderer's existing CPU submission time omits.
        void recordPresentation(long frameNs,long requestedNs,long startedNs,long swapStartNs,long endNs) {
            if(statsStartNs==0)statsStartNs=startedNs;
            statsFrames++;
            statsQueueNs+=Math.max(0,startedNs-requestedNs);
            statsCallbackNs+=Math.max(0,requestedNs-frameNs);
            long swapNs=endNs-swapStartNs;
            statsSwapNs+=swapNs;maxSwapNs=Math.max(maxSwapNs,swapNs);
            double seconds=(endNs-statsStartNs)/1e9;
            if(seconds<1)return;
            long busy=busyCallbacks.get();
            latestPresentation=String.format(java.util.Locale.ROOT,
                    "EGL: %.1f swaps/s; swap wait %.2f ms avg / %.2f ms max\n"
                            + "UI callback delay %.2f ms; GL queue %.2f ms; busy skips %d",
                    statsFrames/seconds,statsSwapNs/(1e6*statsFrames),maxSwapNs/1e6,
                    statsCallbackNs/(1e6*statsFrames),statsQueueNs/(1e6*statsFrames),busy-previousBusy);
            presentationAtMs=android.os.SystemClock.elapsedRealtime();
            statsStartNs=endNs;previousBusy=busy;
            statsFrames=statsQueueNs=statsSwapNs=maxSwapNs=statsCallbackNs=0;
        }
        void stop() {
            stopped=true;
            renderer.invalidateFrames();
            handler.post(this::closeOnThread);
        }
        void fail(Throwable error) {
            renderer.error = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
            renderer.invalidateFrames();
        }
        void require(boolean ok, String operation) {
            if (!ok) throw new IllegalStateException("EGL "+operation+": 0x"+Integer.toHexString(EGL14.eglGetError()));
        }
        void closeOnThread() {
            if (closing) return;
            closing=true;
            try {
                if (display != EGL14.EGL_NO_DISPLAY) {
                    EGL14.eglMakeCurrent(display,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_CONTEXT);
                    if (surface != null && surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display,surface);
                    if (context != null && context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display,context);
                    EGL14.eglTerminate(display); EGL14.eglReleaseThread();
                }
            } catch (Throwable error) { fail(error); }
            finally {
                thread.quit();
                main.post(() -> {
                    if (releaseTexture) input.release();
                    if (run == this) {
                        cancelFrame(); run=null;
                        // An explicit pause/resume or a new Surface can recover a failed run.
                        if (renderer.error == null || texture != input) startIfReady();
                    }
                });
            }
        }
    }
}
