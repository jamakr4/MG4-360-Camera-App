package com.drivehub.kamera.camera;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.SurfaceTexture;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;
import android.view.Display;
import android.view.Gravity;
import android.view.Surface;
import android.view.TextureView;
import android.view.WindowManager;
import android.widget.FrameLayout;

import androidx.core.content.ContextCompat;
import com.drivehub.kamera.CameraProbe;
import com.drivehub.kamera.dev.DevRuntimeLog;
import com.drivehub.kamera.settings.UiPrefs;

/** Owned by SignalService. Main-thread window state; camera/Binder work on a serial IO handler. */
public final class IpkCameraOverlay implements AutoCloseable {
    private static final String TAG = "IpkCamera";
    private static final long POLL_MS = 250;
    private static final long FRAME_TIMEOUT_MS = 1500;
    private final Context context;
    private final SharedPreferences prefs;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final HandlerThread thread = new HandlerThread("ipk-camera-io");
    private final Handler io;
    private final DisplayManager displays;
    private final IpkCarClient car;
    private FrameLayout root;
    private TextureView texture;
    private WindowManager windows;
    private int displayId = -1;
    private volatile int desiredCamera = -1;
    private int nextSinkId;
    private volatile int generation;
    private volatile int sessionGeneration;
    private boolean closed, readPending, routePending, healthPending;
    private boolean screenOn;
    private Integer tab;
    private IpkRoutePolicy routing = new IpkRoutePolicy();
    private long lastTabRead, retryAfter, windowCreated, lastFreshFrame;
    private String detail = "idle";
    private volatile String status = "IPK idle";
    private String lastSessionStatus = "no signal yet";
    private Output output;

    private static final class Output {
        final int camera, sink;
        final Surface surface;
        boolean attached;
        Output(int camera, int sink, Surface surface) {
            this.camera = camera; this.sink = sink; this.surface = surface;
        }
    }

    public IpkCameraOverlay(Context context) {
        this.context = context.getApplicationContext();
        prefs = UiPrefs.getPrefs(context);
        displays = (DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE);
        PowerManager power = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        screenOn = power == null || power.isInteractive();
        thread.start();
        io = new Handler(thread.getLooper());
        car = new IpkCarClient(this.context, io, new IpkCarClient.Listener() {
            @Override public void onTab(Integer value, String message) {
                main.post(() -> {
                    readPending = false;
                    if (closed || desiredCamera < 0) return;
                    if (value == null ? tab != null : !value.equals(tab)) event(message);
                    tab = value;
                    routing.observeTab(value);
                    if (root == null || value == null) detail = message;
                });
            }
            @Override public void onMessage(String message) {
                main.post(() -> { if (!closed && desiredCamera >= 0) { detail = message; event(message); } });
            }
        });
        if (displays != null) displays.registerDisplayListener(displayListener, main);
        prefs.registerOnSharedPreferenceChangeListener(preferenceListener);
        IntentFilter filter = new IntentFilter(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_SCREEN_ON);
        ContextCompat.registerReceiver(this.context, screenReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED);
    }

    public String status() { return status; }

    public void show(int camera) {
        if (closed) return;
        CameraIndex index = CameraIndex.fromVideoIndex(camera);
        if (index == null || !index.isSide()) { hide(); return; }
        if (desiredCamera != camera) {
            boolean freshSignal = desiredCamera < 0;
            desiredCamera = camera;
            destroyWindow();
            retryAfter = 0;
            if (freshSignal) {
                sessionGeneration++;
                routing = new IpkRoutePolicy();
                tab = null;
                lastTabRead = 0;
                detail = "reading current IPK tab";
            }
        }
        scheduleNow();
    }

    public void hide() {
        sessionGeneration++;
        desiredCamera = -1;
        main.removeCallbacks(tick);
        destroyWindow();
        status = "IPK idle · " + lastSessionStatus;
        // Deliberately do not send guidance-finish or fabricate a tab restore: a
        // real navigation session may be running, and no generic restore is verified.
    }

    private final SharedPreferences.OnSharedPreferenceChangeListener preferenceListener = (p, key) -> {
        if (UiPrefs.KEY_IPK_CAMERA_ENABLED.equals(key) || UiPrefs.KEY_IPK_AUTO_ROUTE.equals(key)
                || UiPrefs.KEY_OVERLAY_ROTATE_TO_DRIVING_DIRECTION.equals(key)) {
            main.post(() -> {
                destroyWindow();
                retryAfter = 0;
                scheduleNow();
            });
        }
    };

    private final DisplayManager.DisplayListener displayListener = new DisplayManager.DisplayListener() {
        @Override public void onDisplayAdded(int id) { if (desiredCamera >= 0) scheduleNow(); }
        @Override public void onDisplayRemoved(int id) {
            if (id == displayId) { destroyWindow(); scheduleNow(); }
        }
        @Override public void onDisplayChanged(int id) {
            if (id == displayId) { destroyWindow(); scheduleNow(); }
        }
    };

    private final BroadcastReceiver screenReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent intent) {
            screenOn = Intent.ACTION_SCREEN_ON.equals(intent.getAction());
            if (!screenOn) destroyWindow();
            scheduleNow();
        }
    };

    private void scheduleNow() {
        main.removeCallbacks(tick);
        if (!closed && desiredCamera >= 0) main.post(tick);
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (closed || desiredCamera < 0) return;
            long now = SystemClock.elapsedRealtime();
            if (!prefs.getBoolean(UiPrefs.KEY_IPK_CAMERA_ENABLED, false) || !screenOn
                    || UiPrefs.isOemAvmActive(prefs)) {
                destroyWindow();
                status = "IPK " + (!screenOn ? "screen off" : "disabled / OEM AVM");
            } else {
                // Main-thread watchdog still removes a frozen image if an IO call
                // stalls and no health callback can get back to us.
                if (root != null && root.getAlpha() > 0f && now - lastFreshFrame > FRAME_TIMEOUT_MS) {
                    detail = "camera watchdog expired; overlay released";
                    event(detail);
                    retryAfter = now + 2000;
                    destroyWindow();
                }
                if (!readPending && (lastTabRead == 0 || now - lastTabRead >= 500)) {
                    readPending = true;
                    lastTabRead = now;
                    io.post(car::readTab);
                }
                if (IpkRoutePolicy.supportsCamera(tab)) {
                    if (root == null && now >= retryAfter) createWindow();
                    checkFrames();
                } else {
                    destroyWindow();
                    maybeRequestProjection(now);
                }
                status = "display=" + displayId + " · tab=" + (tab == null ? "?" : tab)
                        + " · route=" + routing.requests() + "/" + IpkRoutePolicy.MAX_REQUESTS
                        + " · " + detail;
            }
            lastSessionStatus = status;
            main.postDelayed(this, POLL_MS);
        }
    };

    private void maybeRequestProjection(long now) {
        if (routePending || !routing.shouldRequest(tab,
                prefs.getBoolean(UiPrefs.KEY_IPK_AUTO_ROUTE, true), now)) return;
        // Count the entire attempt, including a refused bind, to keep retries bounded.
        routing.requested(now);
        routePending = true;
        final int token = sessionGeneration;
        event("request navigation from tab=" + tab + " attempt=" + routing.requests());
        io.post(() -> {
            boolean sent = token == sessionGeneration && desiredCamera >= 0
                    && prefs.getBoolean(UiPrefs.KEY_IPK_AUTO_ROUTE, true) && car.requestProjection();
            main.post(() -> {
                routePending = false;
                if (!closed && desiredCamera >= 0 && token == sessionGeneration)
                    detail = sent ? "request sent; waiting for tab 1/2" : "projection not sent; see IPK log";
            });
        });
    }

    private Display clusterDisplay() {
        if (displays == null) return null;
        Display alternative = null;
        for (Display display : displays.getDisplays()) {
            if (display.getDisplayId() == Display.DEFAULT_DISPLAY || !display.isValid()) continue;
            Integer type = null;
            try { type = (Integer) Display.class.getMethod("getType").invoke(display); }
            catch (Exception ignored) { }
            if (type != null && type != 1 && type != 2) continue;
            if (display.getDisplayId() == 1) return display;
            if (type != null && alternative == null) alternative = display;
        }
        return alternative;
    }

    private void createWindow() {
        Display display = clusterDisplay();
        if (display == null) { detail = "no physical cluster display"; return; }
        Point size = new Point();
        display.getRealSize(size);
        int[] bounds = IpkRoutePolicy.bounds(size.x, size.y);
        if (bounds[2] == 0) return;
        final int token = generation;
        try {
            Context displayContext = context.createDisplayContext(display);
            windows = (WindowManager) displayContext.getSystemService(Context.WINDOW_SERVICE);
            FrameLayout view = new FrameLayout(displayContext);
            view.setBackgroundColor(Color.BLACK);
            view.setAlpha(0f);
            TextureView preview = new TextureView(displayContext);
            // Preserve the full logical 720x480 camera view. Fast preview has half
            // the vertical samples; its display aspect is still 3:2.
            int w = bounds[2], h = Math.round(w * 2f / 3f);
            boolean rotate = UiPrefs.isOverlayRotationToDrivingDirectionEnabled(prefs);
            if (rotate) { h = bounds[2]; w = Math.round(h * 2f / 3f); }
            FrameLayout.LayoutParams image = new FrameLayout.LayoutParams(
                    rotate ? h : w, rotate ? w : h, Gravity.CENTER);
            preview.setRotation(rotate ? (desiredCamera == CameraIndex.LEFT.getVideoIndex() ? -90f : 90f) : 0f);
            view.addView(preview, image);
            preview.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
                @Override public void onSurfaceTextureAvailable(SurfaceTexture st, int w, int h) {
                    if (texture != preview || token != generation) return;
                    Surface surface = new Surface(st);
                    Output sink = new Output(desiredCamera, nextSinkId++ % 32, surface);
                    output = sink;
                    io.post(() -> {
                        boolean ok = token == generation && CameraProbe.attachPreviewSink(sink.camera, sink.sink, surface);
                        main.post(() -> {
                            if (output != sink || token != generation) return;
                            sink.attached = ok;
                            detail = ok ? "waiting for first camera frame" : "camera attach failed";
                            if (!ok) { event(detail); retryAfter = SystemClock.elapsedRealtime() + 2000; destroyWindow(); }
                        });
                    });
                }
                @Override public void onSurfaceTextureSizeChanged(SurfaceTexture st, int w, int h) { }
                @Override public void onSurfaceTextureUpdated(SurfaceTexture st) { }
                @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
                    if (texture == preview) {
                        detachOutput();
                        main.post(() -> {
                            if (texture == preview) {
                                destroyWindow();
                                retryAfter = SystemClock.elapsedRealtime() + 500;
                            }
                        });
                    }
                    return true;
                }
            });
            WindowManager.LayoutParams params = new WindowManager.LayoutParams(bounds[2], bounds[2],
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT);
            params.gravity = Gravity.TOP | Gravity.START;
            params.x = bounds[0]; params.y = bounds[1];
            params.setTitle("MG4 signal camera IPK");
            root = view;
            texture = preview;
            displayId = display.getDisplayId();
            windowCreated = SystemClock.elapsedRealtime();
            lastFreshFrame = windowCreated;
            windows.addView(view, params);
            event("window display=" + displayId + " " + size.x + "x" + size.y
                    + " rect=" + bounds[0] + "," + bounds[1] + "," + bounds[2]);
        } catch (Exception e) {
            detail = "overlay: " + e.getClass().getSimpleName() + ": " + e.getMessage();
            event(detail);
            retryAfter = SystemClock.elapsedRealtime() + 3000;
            destroyWindow();
        }
    }

    private void checkFrames() {
        if (root == null || output == null || !output.attached || healthPending) return;
        Output sink = output;
        healthPending = true;
        io.post(() -> {
            long age = CameraProbe.previewSinkFrameAgeMs(sink.camera, sink.sink);
            main.post(() -> {
                healthPending = false;
                if (output != sink || root == null) return;
                long now = SystemClock.elapsedRealtime();
                if (age >= 0 && age <= FRAME_TIMEOUT_MS) {
                    if (root.getAlpha() == 0f) event("camera visible on IPK, tab=" + tab);
                    root.setAlpha(1f);
                    lastFreshFrame = now - age;
                    detail = "camera live (" + age + " ms)";
                } else if (now - windowCreated > FRAME_TIMEOUT_MS) {
                    detail = "no fresh camera frame; overlay released";
                    event(detail);
                    retryAfter = now + 2000;
                    destroyWindow();
                }
            });
        });
    }

    private void detachOutput() {
        Output sink = output;
        output = null;
        if (sink != null) io.post(() -> {
            CameraProbe.detachPreviewSink(sink.camera, sink.sink);
            sink.surface.release();
        });
    }

    private void destroyWindow() {
        generation++;
        detachOutput();
        FrameLayout view = root;
        root = null; texture = null; displayId = -1;
        if (view != null && windows != null) {
            try { windows.removeViewImmediate(view); } catch (Exception ignored) { }
        }
        windows = null;
    }

    private static void event(String message) {
        Log.i(TAG, message);
        DevRuntimeLog.add(TAG, message);
    }

    @Override public void close() {
        if (closed) return;
        hide();
        closed = true;
        prefs.unregisterOnSharedPreferenceChangeListener(preferenceListener);
        if (displays != null) displays.unregisterDisplayListener(displayListener);
        context.unregisterReceiver(screenReceiver);
        io.post(() -> { car.close(); thread.quitSafely(); });
    }
}
