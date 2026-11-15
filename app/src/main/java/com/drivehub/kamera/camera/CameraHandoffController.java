package com.drivehub.kamera.camera;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;

import com.drivehub.kamera.CameraProbe;
import com.drivehub.kamera.dashcam.RecordingService;
import com.drivehub.kamera.dev.DevRuntimeLog;
import com.drivehub.kamera.settings.UiPrefs;
import com.drivehub.kamera.signal.SignalService;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** One global external pause; independent of OEM lifecycle and foreground heuristics. */
public final class CameraHandoffController {
    public static final String ACTION_RELEASE = "com.drivehub.kamera.action.RELEASE_CAMERAS";
    public static final String ACTION_RESUME = "com.drivehub.kamera.action.RESUME_CAMERAS";
    private static final String KEY_BOOT = "externalCameraPauseBoot";
    private static final CameraHandoffState STATE = new CameraHandoffState();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final java.util.concurrent.ExecutorService COMMANDS = Executors.newSingleThreadExecutor();
    // Independent of camera teardown and the UI thread: every receiver has a bounded lifetime.
    private static final ScheduledExecutorService REPLIES = Executors.newSingleThreadScheduledExecutor();
    private static boolean initialized;

    private CameraHandoffController() { }

    public static synchronized void initialize(Context context) {
        if (initialized) return;
        SharedPreferences prefs = UiPrefs.getPrefs(context);
        boolean paused = CameraHandoffState.restorePause(
                prefs.getBoolean(UiPrefs.KEY_EXTERNAL_CAMERA_PAUSED, false),
                prefs.getInt(KEY_BOOT, -1), bootCount(context));
        STATE.transition(paused);
        CameraProbe.setExternalCameraAccessBlocked(paused);
        if (!paused) prefs.edit().remove(UiPrefs.KEY_EXTERNAL_CAMERA_PAUSED).remove(KEY_BOOT).apply();
        initialized = true;
    }

    public static void onBoot(Context context) {
        initialize(context);
        // A current-boot handoff may already have arrived before BOOT_COMPLETED.
        // Only firmware without BOOT_COUNT needs this fallback cleanup.
        if (bootCount(context) >= 0) return;
        UiPrefs.getPrefs(context).edit().remove(UiPrefs.KEY_EXTERNAL_CAMERA_PAUSED).remove(KEY_BOOT).commit();
        STATE.transition(false);
        CameraProbe.setExternalCameraAccessBlocked(false);
    }

    private static int bootCount(Context context) {
        try { return Settings.Global.getInt(context.getContentResolver(), Settings.Global.BOOT_COUNT, -1); }
        catch (RuntimeException ignored) { return -1; }
    }

    public static long generation() { return STATE.generation(); }
    public static void demand(String key, boolean needed) { STATE.require(key, needed); }
    public static void ready(String key, boolean ready, long token) { STATE.ready(key, ready, token); }

    public static void failed(String key, long token) { STATE.failed(key, token); }

    static void request(Context context, boolean release, boolean ordered, BroadcastReceiver.PendingResult pending) {
        Context app = context.getApplicationContext();
        Request request = new Request(release, ordered, pending);
        REPLIES.schedule(() -> request.reply(true), 4800, TimeUnit.MILLISECONDS);
        COMMANDS.execute(() -> {
            try {
                initialize(app);
                request.token = STATE.transition(release);
                // Closing the native gate precedes preferences, listeners and worker teardown.
                CameraProbe.setExternalCameraAccessBlocked(release);
                if (!UiPrefs.getPrefs(app).edit().putBoolean(UiPrefs.KEY_EXTERNAL_CAMERA_PAUSED, release)
                        .putInt(KEY_BOOT, bootCount(app)).commit()) throw new IllegalStateException("Pause persistence failed");
                if (release) {
                    RecordingService.pauseForOemRequest(app);
                    CameraProbe.releaseCameraPreviewsForHandoff();
                }
                MAIN.post(() -> {
                    if (request.token != generation()) return;
                    try {
                        SignalService.onExternalCameraAccessChanged(app);
                        if (!release && !UiPrefs.isOemCameraAccessBlocked(UiPrefs.getPrefs(app))) {
                            if (UiPrefs.isDashcamEnabled(UiPrefs.getPrefs(app))) demand("recording", true);
                            if (RecordingService.isRunning()) RecordingService.resumeAfterOemRequest(app);
                            else RecordingService.startIfDashcamEnabled(app);
                        }
                        // Preference notifications and synchronous demand registration precede
                        // this barrier; actual surface/recorder start confirmations may follow.
                        MAIN.post(() -> request.prepared = true);
                    } catch (RuntimeException error) { request.error = true; }
                });
                REPLIES.execute(() -> poll(request));
            } catch (Throwable error) {
                DevRuntimeLog.add("CameraHandoff", "handoff failed: " + error);
                request.error = true;
                REPLIES.execute(() -> request.reply(false));
            }
        });
    }

    private static void poll(Request request) {
        if (request.done.get()) return;
        request.reply(false);
        if (!request.done.get()) REPLIES.schedule(() -> poll(request), 25, TimeUnit.MILLISECONDS);
    }

    private static final class Request {
        final boolean release, ordered;
        final BroadcastReceiver.PendingResult pending;
        final AtomicBoolean done = new AtomicBoolean();
        volatile long token = -1;
        volatile boolean prepared, error;

        Request(boolean release, boolean ordered, BroadcastReceiver.PendingResult pending) {
            this.release = release;
            this.ordered = ordered;
            this.pending = pending;
        }
        void reply(boolean expired) {
            if (done.get()) return;
            Bundle extras = new Bundle();
            String status;
            try {
                int[] snapshot = CameraProbe.cameraOwnershipSnapshot();
                if (snapshot == null || snapshot.length != 3) throw new IllegalStateException("No camera snapshot");
                extras.putBoolean("claimed", snapshot[0] > 0);
                extras.putBoolean("blocked", snapshot[2] != 0);
                status = error ? "error" : token < 0 ? (expired ? "timeout" : null)
                        : STATE.evaluate(release, token, snapshot[0], snapshot[1], snapshot[2] != 0, prepared, expired);
            } catch (Throwable failure) {
                status = "error";
                extras.putBoolean("blocked", true);
            }
            if (status == null || !done.compareAndSet(false, true)) return;
            boolean success = "released".equals(status) || "claimed".equals(status) || "idle".equals(status);
            extras.putBoolean("success", success);
            extras.putString("status", status);
            try {
                if (ordered) pending.setResult(success ? Activity.RESULT_OK : Activity.RESULT_CANCELED, status, extras);
                DevRuntimeLog.add("CameraHandoff", (release ? "release" : "resume") + " => " + status);
            } finally { pending.finish(); }
        }
    }
}
