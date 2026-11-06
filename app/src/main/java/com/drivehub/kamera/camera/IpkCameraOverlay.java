package com.drivehub.kamera.camera;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Outline;
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
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.WindowManager;
import android.widget.FrameLayout;
import java.util.Locale;

import androidx.core.content.ContextCompat;
import com.drivehub.kamera.CameraProbe;
import com.drivehub.kamera.R;
import com.drivehub.kamera.dev.DevRuntimeLog;
import com.drivehub.kamera.settings.UiPrefs;
import com.drivehub.kamera.signal.DigitalRearviewPolicy;

/** Main-thread window state, with independent preview and vehicle IO handlers. */
public final class IpkCameraOverlay implements AutoCloseable {
    private static final String TAG = "IpkCamera";
    private static final long POLL_MS = 100;
    private static final long FRAME_TIMEOUT_MS = 1500;
    private final Context context;
    private final SharedPreferences prefs;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final HandlerThread thread = new HandlerThread("ipk-preview-io");
    private final HandlerThread carThread = new HandlerThread("ipk-car-io");
    private final Handler io;
    private final Handler carIo;
    private final DisplayManager displays;
    private final IpkCarClient car;
    private FrameLayout root;
    private TextureView texture;
    private WindowManager windows;
    private int displayId = -1;
    private volatile int desiredCamera = -1;
    private int nextSinkId;
    private volatile int generation;
    private boolean closed, readPending, healthPending;
    private volatile boolean screenOn;
    private Integer tab;
    private long lastTabRead, tabObservedAt, retryAfter, windowCreated, lastFreshFrame, mirrorStarted;
    private String detail = "ready";
    private Output output;

    private static final class Output {
        final int camera, sink;
        final Surface surface;
        boolean attached;
        long sampledAt, sampledFrames, firstFrameMs = -1;
        double fps = -1;
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
        carThread.start();
        io = new Handler(thread.getLooper());
        carIo = new Handler(carThread.getLooper());
        car = new IpkCarClient(this.context, carIo, (value, message) -> main.post(() -> {
            readPending = false;
            if (closed) return;
            if (value == null ? tab != null : !value.equals(tab)) event(message);
            tab = value;
            tabObservedAt = SystemClock.elapsedRealtime();
            scheduleNow();
        }));
        if (displays != null) displays.registerDisplayListener(displayListener, main);
        prefs.registerOnSharedPreferenceChangeListener(preferenceListener);
        IntentFilter filter = new IntentFilter(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_SCREEN_ON);
        ContextCompat.registerReceiver(this.context, screenReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED);
        scheduleNow();
    }

    public void show() {
        if (closed || desiredCamera >= 0) return;
        if (desiredCamera < 0) {
            desiredCamera = CameraIndex.REAR.getVideoIndex();
            destroyWindow();
            retryAfter = 0;
            mirrorStarted = SystemClock.elapsedRealtime();
            if (mirrorStarted - tabObservedAt > 750) { tab = null; lastTabRead = 0; }
        }
        scheduleNow();
    }

    public void hide() {
        if (desiredCamera < 0) return;
        desiredCamera = -1;
        main.removeCallbacks(tick);
        destroyWindow();
    }

    private final SharedPreferences.OnSharedPreferenceChangeListener preferenceListener = (p, key) -> {
        if (UiPrefs.KEY_DIGITAL_REARVIEW_DISPLAY_TARGET.equals(key)
                || UiPrefs.KEY_DIGITAL_REARVIEW_IPK_SCREEN.equals(key)) {
            main.post(() -> {
                if (closed) return;
                destroyWindow();
                retryAfter = 0;
                scheduleNow();
            });
        } else if (UiPrefs.KEY_TILE_CORNER_RADIUS.equals(key)) {
            main.post(() -> {
                if (!closed && texture != null) texture.invalidateOutline();
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
            if (screenOn && !readPending && (lastTabRead == 0 || now - lastTabRead >= 250)) {
                readPending = true;
                lastTabRead = now;
                carIo.post(car::readTab);
            }
            if (!screenOn || !UiPrefs.isDigitalRearviewEnabled(prefs)
                    || !DigitalRearviewPolicy.usesIpk(
                            UiPrefs.getDigitalRearviewDisplayTarget(prefs))
                    || UiPrefs.isOemAvmActive(prefs)) {
                destroyWindow();
            } else {
                // Release a frozen image even if an IO call stalls.
                if (root != null && now - lastFreshFrame > FRAME_TIMEOUT_MS) {
                    event("camera watchdog expired; overlay released");
                    retryAfter = now + 2000;
                    destroyWindow();
                }
                if (now - tabObservedAt > 1000) tab = null;
                if (IpkRoutePolicy.isSelectedScreen(tab, UiPrefs.getDigitalRearviewIpkScreen(prefs))) {
                    if (root == null && now >= retryAfter) createWindow();
                } else {
                    destroyWindow();
                }
            }
            main.postDelayed(this, POLL_MS);
        }
    };

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
            // Cover the entire OEM content area, including the letterbox bands and
            // rounded preview corners. Match SAIC's screen_background_dark, as used
            // by MG4Control, so the covering panel blends into the cluster background.
            view.setBackgroundColor(ContextCompat.getColor(displayContext, R.color.ipk_content_background));
            view.setAlpha(0f);
            TextureView preview = new TextureView(displayContext);
            preview.setOpaque(false);
            preview.setOutlineProvider(new ViewOutlineProvider() {
                @Override public void getOutline(View view, Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(),
                            UiPrefs.getCornerRadiusPx(view, prefs));
                }
            });
            preview.setClipToOutline(true);
            preview.addOnLayoutChangeListener((v, l, t, r, b, oldL, oldT, oldR, oldB) ->
                    v.invalidateOutline());
            applyPreviewFraming(preview, bounds[2]);
            view.addView(preview);
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
                            else main.post(checkFrames);
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
            params.setTitle("MG4 digital rearview IPK");
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

    private void applyPreviewFraming(TextureView preview, int side) {
        // Show the full rear image. Side-camera crop and rotation settings do not apply.
        preview.setLayoutParams(new FrameLayout.LayoutParams(
                side, IpkRoutePolicy.previewHeight(side), Gravity.CENTER));
    }

    private final Runnable checkFrames = new Runnable() {
        @Override public void run() {
            if (closed || root == null || output == null || !output.attached || healthPending) return;
            Output sink = output;
            healthPending = true;
            io.post(() -> {
                long[] stats = CameraProbe.previewSinkStats(sink.camera, sink.sink);
                main.post(() -> {
                    healthPending = false;
                    if (closed || output != sink || root == null) {
                        if (!closed && output != null) main.post(this);
                        return;
                    }
                    long now = SystemClock.elapsedRealtime();
                    long age = stats == null ? -1 : stats[0];
                    if (age >= 0 && age <= FRAME_TIMEOUT_MS) {
                        if (root.getAlpha() == 0f) {
                            sink.firstFrameMs = now - mirrorStarted;
                            event("camera visible on IPK, tab=" + tab + " start=" + sink.firstFrameMs + " ms");
                        }
                        root.setAlpha(1f);
                        lastFreshFrame = now - age;
                        if (sink.sampledAt == 0) {
                            sink.sampledAt = now;
                            sink.sampledFrames = stats[1];
                        } else if (now - sink.sampledAt >= 500) {
                            sink.fps = (stats[1] - sink.sampledFrames) * 1000.0 / (now - sink.sampledAt);
                            sink.sampledAt = now;
                            sink.sampledFrames = stats[1];
                        }
                        detail = "live · " + (sink.fps < 0 ? "FPS misst…" : String.format(Locale.ROOT, "%.1f FPS", sink.fps))
                                + " · Start " + sink.firstFrameMs + " ms · Alter " + age
                                + " ms · Ausgabe " + stats[2] + "/" + stats[3] + " ms";
                    } else if (now - windowCreated > FRAME_TIMEOUT_MS) {
                        detail = "no fresh camera frame; overlay released";
                        event(detail);
                        retryAfter = now + 2000;
                        destroyWindow();
                    }
                    if (root != null) main.postDelayed(this, root.getAlpha() == 0f ? 40 : 250);
                });
            });
        }
    };

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
        main.removeCallbacks(checkFrames);
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
        main.removeCallbacks(tick);
        prefs.unregisterOnSharedPreferenceChangeListener(preferenceListener);
        if (displays != null) displays.unregisterDisplayListener(displayListener);
        context.unregisterReceiver(screenReceiver);
        io.post(thread::quitSafely);
        carIo.post(() -> { car.close(); carThread.quitSafely(); });
    }
}
