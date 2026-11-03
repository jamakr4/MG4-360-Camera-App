package com.drivehub.kamera.camera;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Matrix;
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
import java.util.Locale;

import androidx.core.content.ContextCompat;
import com.drivehub.kamera.CameraProbe;
import com.drivehub.kamera.dev.DevRuntimeLog;
import com.drivehub.kamera.settings.UiPrefs;

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
    private volatile int sessionGeneration;
    private boolean closed, readPending, routePending, healthPending;
    private volatile boolean screenOn;
    private Integer tab;
    private IpkRoutePolicy routing = new IpkRoutePolicy();
    private long lastTabRead, tabObservedAt, retryAfter, windowCreated, lastFreshFrame, signalStarted, lastPersist;
    private String detail = "bereit", routeDetail = "keine Anforderung";
    private final IpkStatusState statusState;
    private volatile String status;
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
        statusState = new IpkStatusState(prefs.getString(UiPrefs.KEY_IPK_LAST_SESSION, null));
        status = statusState.snapshot();
        displays = (DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE);
        PowerManager power = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        screenOn = power == null || power.isInteractive();
        thread.start();
        carThread.start();
        io = new Handler(thread.getLooper());
        carIo = new Handler(carThread.getLooper());
        car = new IpkCarClient(this.context, carIo, new IpkCarClient.Listener() {
            @Override public void onTab(Integer value, String message) {
                main.post(() -> {
                    readPending = false;
                    if (closed) return;
                    if (value == null ? tab != null : !value.equals(tab)) event(message);
                    tab = value;
                    tabObservedAt = SystemClock.elapsedRealtime();
                    if (desiredCamera >= 0) {
                        routing.observeTab(value);
                        if (IpkRoutePolicy.supportsCamera(value) && routing.requests() > 0)
                            routeDetail = "Wechsel bestätigt: Seite " + value;
                        if (root == null || value == null) detail = message;
                    }
                    scheduleNow();
                });
            }
            @Override public void onMessage(String message) {
                main.post(() -> {
                    if (closed) return;
                    event(message);
                    if (desiredCamera >= 0) { routeDetail = message; publish(false); }
                });
            }
            @Override public void onDiagnostics(String trace) {
                main.post(() -> {
                    if (closed) return;
                    prefs.edit().putString(UiPrefs.KEY_IPK_DIAGNOSTIC_TRACE, trace).apply();
                    event(trace);
                });
            }
        });
        if (displays != null) displays.registerDisplayListener(displayListener, main);
        prefs.registerOnSharedPreferenceChangeListener(preferenceListener);
        IntentFilter filter = new IntentFilter(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_SCREEN_ON);
        ContextCompat.registerReceiver(this.context, screenReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED);
        scheduleNow();
    }

    public String status() { return withDiagnostics(status, prefs); }

    public static String savedStatus(Context context) {
        SharedPreferences prefs = UiPrefs.getPrefs(context);
        return withDiagnostics(new IpkStatusState(prefs.getString(UiPrefs.KEY_IPK_LAST_SESSION, null)).snapshot(), prefs);
    }

    private static String withDiagnostics(String status, SharedPreferences prefs) {
        String trace = prefs.getString(UiPrefs.KEY_IPK_DIAGNOSTIC_TRACE, "");
        return trace.isEmpty() ? status : status + "\n\n" + trace;
    }

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
                signalStarted = SystemClock.elapsedRealtime();
                if (signalStarted - tabObservedAt > 750) { tab = null; lastTabRead = 0; }
                routing.observeTab(tab);
                detail = "Kamera startet";
                routeDetail = "keine Anforderung";
            }
            publish(true);
        }
        scheduleNow();
    }

    public void hide() {
        if (desiredCamera >= 0) publish(true);
        sessionGeneration++;
        desiredCamera = -1;
        main.removeCallbacks(tick);
        destroyWindow();
        statusState.idle();
        status = statusState.snapshot();
        scheduleNow();
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
        } else if (UiPrefs.KEY_SIGNAL_COMPACT_ENABLED.equals(key)
                || UiPrefs.KEY_SIGNAL_COMPACT_LEFT.equals(key)
                || UiPrefs.KEY_SIGNAL_COMPACT_RIGHT.equals(key)) {
            main.post(() -> {
                if (!closed && root != null && texture != null)
                    applyPreviewFraming(texture, root.getLayoutParams().width);
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
        if (!closed && (desiredCamera >= 0 || (screenOn
                && prefs.getBoolean(UiPrefs.KEY_IPK_CAMERA_ENABLED, false)))) main.post(tick);
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (closed) return;
            long now = SystemClock.elapsedRealtime();
            boolean enabled = prefs.getBoolean(UiPrefs.KEY_IPK_CAMERA_ENABLED, false);
            boolean active = desiredCamera >= 0;
            if (enabled && screenOn && !readPending
                    && (lastTabRead == 0 || now - lastTabRead >= (active ? 250 : 500))) {
                readPending = true;
                lastTabRead = now;
                boolean autoRoute = prefs.getBoolean(UiPrefs.KEY_IPK_AUTO_ROUTE, true);
                carIo.post(() -> car.prepare(autoRoute));
            }
            if (!active) {
                if (enabled && screenOn) main.postDelayed(this, 500);
                return;
            }
            if (!enabled || !screenOn
                    || UiPrefs.isOemAvmActive(prefs)) {
                destroyWindow();
                detail = !screenOn ? "Bildschirm aus" : "deaktiviert / OEM AVM";
            } else {
                // Main-thread watchdog still removes a frozen image if an IO call
                // stalls and no health callback can get back to us.
                if (root != null && now - lastFreshFrame > FRAME_TIMEOUT_MS) {
                    detail = "camera watchdog expired; overlay released";
                    event(detail);
                    retryAfter = now + 2000;
                    destroyWindow();
                }
                if (now - tabObservedAt > 1000) tab = null;
                if (IpkRoutePolicy.supportsCamera(tab)) {
                    if (root == null && now >= retryAfter) createWindow();
                } else {
                    destroyWindow();
                    maybeRequestProjection(now);
                }
            }
            publish(false);
            main.postDelayed(this, POLL_MS);
        }
    };

    private void maybeRequestProjection(long now) {
        if (routePending || !routing.shouldRequest(tab,
                prefs.getBoolean(UiPrefs.KEY_IPK_AUTO_ROUTE, true), now)) return;
        routePending = true;
        final int token = sessionGeneration;
        final boolean fallback = routing.requests() > 0;
        carIo.post(() -> {
            IpkRoutePolicy.RequestResult result = car.requestProjection(fallback,
                    () -> token == sessionGeneration && desiredCamera >= 0 && screenOn
                            && prefs.getBoolean(UiPrefs.KEY_IPK_CAMERA_ENABLED, false)
                            && prefs.getBoolean(UiPrefs.KEY_IPK_AUTO_ROUTE, true) && !UiPrefs.isOemAvmActive(prefs));
            main.post(() -> {
                routePending = false;
                if (closed || desiredCamera < 0 || token != sessionGeneration) return;
                routing.completed(result, SystemClock.elapsedRealtime());
                if (result == IpkRoutePolicy.RequestResult.WAITING)
                    routeDetail = "MapService verbindet; noch keine Anforderung gesendet";
                else if (result != IpkRoutePolicy.RequestResult.CANCELLED)
                    event("route=" + routing.requests() + "/2 result=" + result + " direct=" + fallback);
                publish(true);
            });
        });
    }

    private void publish(boolean force) {
        if (desiredCamera < 0) return;
        String route = routeDetail;
        if (!IpkRoutePolicy.supportsCamera(tab) && routing.requests() >= IpkRoutePolicy.MAX_REQUESTS)
            route += " · Wechsel noch unbestätigt";
        statusState.update("display=" + displayId + " · tab=" + (tab == null ? "?" : tab)
                + " · route=" + routing.requests() + "/2 · " + route + " · " + detail);
        status = statusState.snapshot();
        long now = SystemClock.elapsedRealtime();
        if (force || now - lastPersist >= 500) {
            prefs.edit().putString(UiPrefs.KEY_IPK_LAST_SESSION, statusState.lastSession()).apply();
            lastPersist = now;
        }
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
            // Let the cluster content show through outside the camera rectangle.
            view.setBackgroundColor(Color.TRANSPARENT);
            view.setAlpha(0f);
            TextureView preview = new TextureView(displayContext);
            preview.setOpaque(false);
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
                            publish(true);
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

    private void applyPreviewFraming(TextureView preview, int side) {
        boolean compact = prefs.getBoolean(UiPrefs.KEY_SIGNAL_COMPACT_ENABLED, false);
        IpkPreviewGeometry image = IpkPreviewGeometry.calculate(side,
                desiredCamera == CameraIndex.LEFT.getVideoIndex(),
                UiPrefs.isOverlayRotationToDrivingDirectionEnabled(prefs),
                compact ? UiPrefs.getSignalCompactLeft(prefs) : 0,
                compact ? UiPrefs.getSignalCompactRight(prefs) : 0);
        preview.setLayoutParams(new FrameLayout.LayoutParams(image.width, image.height, Gravity.CENTER));
        float[] source = {0f, 0f, image.width, 0f, image.width, image.height, 0f, image.height};
        Matrix transform = new Matrix();
        transform.setPolyToPoly(source, 0, image.contentCorners, 0, 4);
        // Rotate/crop the content inside its viewport, so Android cannot clip an
        // oversized TextureView before its quarter turn (same approach as the main overlay).
        preview.setTransform(transform);
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
                            sink.firstFrameMs = now - signalStarted;
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
                    publish(false);
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
