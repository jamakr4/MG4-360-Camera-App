package com.drivehub.kamera.dashcam;

import com.drivehub.kamera.R;

import com.drivehub.kamera.CameraProbe;
import com.drivehub.kamera.dev.DevRuntimeLog;
import com.drivehub.kamera.helper.app.NotificationChannelHelper;
import com.drivehub.kamera.helper.vehiclesensors.VehicleSpeedReader;
import com.drivehub.kamera.settings.UiPrefs;
import com.drivehub.kamera.remote.RemoteRecording;

import android.app.Notification;
import android.app.ActivityManager;
import android.content.BroadcastReceiver;
import android.content.IntentFilter;
import android.media.MediaExtractor;
import com.drivehub.kamera.signal.SignalService;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.json.JSONArray;
import org.json.JSONObject;

public class RecordingService extends Service {
    private static final String TAG = "RecordingService";

    public static final String ACTION_START = "start_recording";
    public static final String ACTION_STOP = "stop_recording";
    public static final String ACTION_RECORD_TEST = "record_test";
    public static final String ACTION_EJECT_USB = "eject_usb";
    public static final String ACTION_TRIGGER_EVENT_SAVE = "trigger_event_save";
    public static final String ACTION_PAUSE_FOR_OEM_REQUEST = "pause_for_oem_request";
    public static final String ACTION_RESUME_AFTER_OEM_REQUEST = "resume_after_oem_request";
    public static final String ACTION_STATUS_CHANGED = "com.drivehub.kamera.ACTION_RECORDING_STATUS_CHANGED";
    public static final String ACTION_USB_EJECT_READY = "com.drivehub.kamera.ACTION_USB_EJECT_READY";
    public static final String EXTRA_STATUS = "status";
    public static final String EXTRA_ACTIVE_CAMERAS = "active_cameras";
    public static final String EXTRA_TOTAL_CAMERAS = "total_cameras";
    public static final String EXTRA_LAST_ERROR = "last_error";
    public static final String EXTRA_TEST_RECORD_DURATION_SEC = "test_record_duration_sec";
    public static final String EXTRA_USB_EJECT_SAFE_TO_REMOVE = "usb_eject_safe_to_remove";
    public static final String EXTRA_USB_EJECT_MESSAGE_RES = "usb_eject_message_res";
    public static final String EXTRA_EVENT_ALLOW_FUTURE_ONLY = "event_allow_future_only";
    public static final String STATUS_OFF = "off";
    public static final String STATUS_STARTING = "starting";
    public static final String STATUS_RECORDING = "recording";
    public static final String STATUS_PAUSED_OEM = "paused_oem";
    public static final String STATUS_PARTIAL = "partial";
    public static final String STATUS_ERROR = "error";

    private static final String ERROR_STORAGE_NOT_WRITABLE = "storage not writable";
    private static final String ERROR_GRID_START_FAILED = "grid start failed";
    private static final String ERROR_GRID_STOP_TIMEOUT = "grid stop timeout";
    private static final String ERROR_USB_STORAGE = "usb storage unavailable";

    private static final String KEY_STATUS = "recordingStatus";
    private static final String KEY_ACTIVE_CAMERAS = "recordingActiveCameras";
    private static final String KEY_TOTAL_CAMERAS = "recordingTotalCameras";
    private static final String KEY_LAST_ERROR = "recordingLastError";
    private static final String KEY_EVENT_COMPLETED_SEGMENT_COUNT = "eventCompletedSegmentCount";
    private static final String KEY_EVENT_RECENT_SEGMENTS = "eventRecentSegments";
    private static final String KEY_EVENT_PENDING_REQUESTS = "eventPendingRequests";

    private static final String CHANNEL_ID = "mg4_recording";
    private static final int NOTIF_ID = 42;
    private static final int TOTAL_CAMERAS = 4;
    private static final int EVENT_SEGMENTS_BEFORE_CURRENT = 2;
    private static final int EVENT_SEGMENTS_AFTER_CURRENT = 2;
    private static final int FUTURE_ONLY_EVENT_SEGMENTS = 3;
    private static final long ERROR_OVERLAY_DELAY_MS = 5_000L;

    private static volatile boolean sServiceRunning = false;
    private static volatile RecordingService instance;
    // A service can be recreated while a blocked worker from onDestroy is still releasing V4L2.
    private static final java.util.concurrent.atomic.AtomicReference<Thread> recorderOwner =
            new java.util.concurrent.atomic.AtomicReference<>();

    private final Object eventLock = new Object();
    private final Object stateLock = new Object();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService eventCopyExecutor = Executors.newSingleThreadExecutor();
    private volatile Thread worker;
    private volatile boolean stopRequested = false;
    private volatile boolean segmentStopRequested = false;
    private volatile boolean oemPauseRequested = false;
    private volatile int pendingErrorSubtitleResId = 0;
    private volatile int pendingErrorNotificationResId = 0;
    private volatile int pendingErrorGeneration = 0;
    private volatile boolean errorOverlayShown = false;
    private volatile File activeBaseDir;
    private volatile boolean activeBaseIsUsb;
    private volatile boolean usbEjectInProgress = false;
    private volatile boolean futureOnlyEventSession = false;
    private volatile SurveillanceSession surveillanceSession;
    private volatile boolean surveillanceOwnsRecorder;
    private volatile boolean modeTransition;
    private volatile boolean destroyed;
    private volatile boolean testRecording;
    private volatile String remoteState = "starting";
    private volatile String remoteReason = "requested";
    private volatile long remoteFreeBytes = -1;
    private volatile long lastStorageCheck;
    private volatile String eventBoundaryError = "event_copy_timeout";
    private final Runnable surveillanceDeadline = () -> requestSurveillanceStop("timeout", false, false);
    private volatile long lastSegmentAt;
    private volatile long expectedSegmentMs = 30_000;
    private volatile long usbRetryAfter;
    private volatile long oemAskedAt;
    private volatile long oemGoneAt;
    private volatile long oemSeenAt;
    private volatile boolean watchdogFailed;
    private volatile File recordingFile;
    private final ExecutorService supervisor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean supervising = new AtomicBoolean();
    private BroadcastReceiver shutdownReceiver;
    private StatusIconOverlay statusIcon;
    private final SharedPreferences.OnSharedPreferenceChangeListener iconPreferenceListener = (prefs, key) -> {
        if ("dashcamStatusIcon".equals(key) && statusIcon != null)
            statusIcon.update(prefs.getBoolean(key, false) ? prefs.getString(KEY_STATUS, STATUS_OFF) : null);
        if ((UiPrefs.KEY_DASHCAM_STATUS_ICON_X.equals(key)
                || UiPrefs.KEY_DASHCAM_STATUS_ICON_Y.equals(key)) && statusIcon != null)
            statusIcon.reposition();
    };
    private long remaining(long deadline) { return Math.max(0, deadline - SystemClock.elapsedRealtime()); }

    private Thread recordingWorker(Runnable work, String name) {
        lastSegmentAt = SystemClock.elapsedRealtime();
        watchdogFailed = false;
        return new Thread(() -> {
            boolean acquired = recorderOwner.compareAndSet(null, Thread.currentThread());
            boolean resumeNormal = false;
            SurveillanceSession attempted = null;
            try {
                if (!acquired) {
                    attempted = surveillanceSession;
                    throw new IOException("recorder_busy");
                }
                Runnable next = work;
                while (!destroyed && !usbEjectInProgress) {
                    attempted = surveillanceSession;
                    if (attempted != null) {
                        recordSurveillanceLoop(attempted);
                        releaseRecorder();
                        resumeNormal = finishSurveillance(attempted);
                        attempted = null;
                        if (!resumeNormal) break;
                        stopRequested = segmentStopRequested = watchdogFailed = false;
                        next = this::recordLoop;
                    } else {
                        resumeNormal = false;
                        next.run();
                        releaseRecorder();
                        if (surveillanceSession == null) break;
                        stopRequested = segmentStopRequested = false;
                    }
                }
            }
            catch (Throwable error) {
                DevRuntimeLog.add(TAG, "worker failed: " + error);
                if (attempted != null) attempted.stop(error instanceof IOException
                        ? error.getMessage() : "recording_failed", true, false);
                publishStatus(STATUS_ERROR, 0, TOTAL_CAMERAS, error.toString());
            } finally {
                if (acquired) releaseRecorder();
                // Shutdown/eject can interrupt before the driver enters its surveillance branch.
                if (attempted == null && usbEjectInProgress) attempted = surveillanceSession;
                if (attempted != null) resumeNormal = finishSurveillance(attempted);
                if (!acquired) { resumeNormal = false; stopRequested = true; }
                else recorderOwner.compareAndSet(Thread.currentThread(), null);
                final boolean restart = resumeNormal;
                mainHandler.post(() -> {
                    worker = null;
                    testRecording = false;
                    if (!destroyed && !usbEjectInProgress && (surveillanceSession != null
                            || (restart && prefs().getBoolean(DashcamSettingsController.KEY_ENABLED, false)))) {
                        stopRequested = segmentStopRequested = watchdogFailed = false;
                        worker = recordingWorker(this::recordLoop, "RecordingServiceWorker");
                        worker.start();
                    } else {
                        modeTransition = false;
                        stopServiceIfNotEjecting();
                    }
                });
            }
        }, name);
    }

    private void releaseRecorder() {
        try { CameraProbe.stopCombinedMp4Record(false, oemPauseRequested); }
        catch (Throwable error) { DevRuntimeLog.add(TAG, "stop failed: " + error); }
        try { CameraProbe.releaseCombinedCameras(); }
        catch (Throwable error) { DevRuntimeLog.add(TAG, "release failed: " + error); }
        File unfinished = recordingFile;
        if (unfinished != null && !isReadableClip(unfinished)) discardClip(unfinished);
        recordingFile = null;
    }

    public static boolean isSurveillanceActive(Context context) {
        return SurveillanceStore.isActive(UiPrefs.getPrefs(context));
    }

    /** Reports surveillance only; normal dashcam recording does not count as an active session. */
    public static void querySurveillanceState(Context context, String requestId) {
        RecordingService live = instance;
        if (live != null) {
            synchronized (live.stateLock) {
                if (instance == live && !live.destroyed) {
                    SurveillanceSession session = live.surveillanceSession;
                    if (session == null) {
                        RemoteRecording.state(context, "stopped", "idle", "", -1, requestId);
                    } else {
                        // Use the live observations; a disk probe could block a status-only query.
                        boolean failed = session.failed();
                        RemoteRecording.state(context, failed ? "error" : live.remoteState,
                                failed ? session.stopReason() : live.remoteReason,
                                session.directory, live.remoteFreeBytes, requestId);
                    }
                    return;
                }
            }
        }
        SharedPreferences prefs = UiPrefs.getPrefs(context);
        boolean pendingRecovery = SurveillanceStore.isActive(prefs);
        String directory = "";
        if (pendingRecovery) {
            // Do not use SurveillanceStore.read(): its corrupt-state recovery changes settings.
            try { directory = new JSONObject(prefs.getString(SurveillanceStore.KEY_SESSION, ""))
                    .optString("directory", ""); }
            catch (Exception ignored) { /* Preserve the descriptor for the normal recovery path. */ }
        }
        // Persisted "prepared"/recording flags are not proof that any camera is still recording.
        RemoteRecording.state(context, "stopped", pendingRecovery ? "service_not_running" : "idle",
                directory, -1, requestId);
    }

    private boolean recordingEnabled() {
        return prefs().getBoolean(DashcamSettingsController.KEY_ENABLED, false)
                || futureOnlyEventSession || surveillanceSession != null;
    }

    private void handleRemoteCommand(Intent intent) {
        // STOP also uses startForegroundService: an idle/cold service must satisfy its contract.
        try {
            startForeground(NOTIF_ID, buildNotification(getString(R.string.notification_surveillance_starting)));
        } catch (RuntimeException error) {
            DevRuntimeLog.add(TAG, "foreground promotion rejected: " + error);
            RemoteRecording.state(this, "error", "foreground_start_rejected", "", -1,
                    RemoteRecording.string(intent, "request_id"));
            if (surveillanceSession != null) {
                requestSurveillanceStop("foreground_start_rejected", true, false);
                SurveillanceStore.restore(this);
                if (worker == null) surveillanceSession = null;
            }
            if (worker == null) stopSelf();
            return;
        }
        synchronized (stateLock) {
            SurveillanceSession current = surveillanceSession;
            if (RemoteRecording.STOP.equals(intent.getAction())) {
                if (current == null) {
                    RemoteRecording.state(this, "stopped", "idle", "", -1, RemoteRecording.string(intent, "request_id"));
                    if (worker == null) {
                        publishStatus(STATUS_OFF, 0, TOTAL_CAMERAS, "");
                        stopRequested = true;
                        stopServiceIfNotEjecting();
                    }
                    else publishCurrentStatus();
                    return;
                }
                current.stopRequestId = RemoteRecording.string(intent, "request_id");
                requestSurveillanceStop("requested", false, false);
            } else if (current == null) {
                if (usbEjectInProgress || testRecording || futureOnlyEventSession) {
                    RemoteRecording.state(this, "error", "recorder_busy", "", -1, RemoteRecording.string(intent, "request_id"));
                    publishCurrentStatus();
                    return;
                }
                try {
                    String policy = SurveillancePolicy.policy(RemoteRecording.string(intent, "storage_policy"));
                    String directory = RemoteRecording.string(intent, "directory").trim();
                    if (directory.isEmpty()) directory = prefs().getString(SurveillanceStore.KEY_DIRECTORY, "").trim();
                    if (!directory.isEmpty() && !new File(directory).isAbsolute())
                        throw new IllegalArgumentException("invalid_directory");
                    current = new SurveillanceSession(SystemClock.elapsedRealtime()
                            + SurveillancePolicy.minutes(RemoteRecording.string(intent, "max_minutes")) * 60_000L,
                            SurveillanceStore.bootCount(this), prefs().getBoolean(DashcamSettingsController.KEY_ENABLED, false),
                            policy, directory, RemoteRecording.string(intent, "request_id"));
                    if (!SurveillanceStore.save(this, current, false)) throw new IllegalStateException("session_persist_failed");
                    surveillanceSession = current;
                    modeTransition = true;
                    remoteState = "starting";
                    remoteReason = "requested";
                    remoteFreeBytes = -1;
                    emitRemoteState(current.requestId);
                } catch (RuntimeException error) {
                    if (SurveillanceStore.isActive(prefs())) SurveillanceStore.restore(this);
                    RemoteRecording.state(this, "error", error.getMessage(), "", -1, RemoteRecording.string(intent, "request_id"));
                    if (worker == null) {
                        stopRequested = true;
                        stopServiceIfNotEjecting();
                    }
                    return;
                }
            } else {
                emitRemoteState(RemoteRecording.string(intent, "request_id"));
            }
            mainHandler.removeCallbacks(surveillanceDeadline);
            if (current.stopReason() == null)
                mainHandler.postDelayed(surveillanceDeadline, remaining(current.deadline));
            if (worker == null) {
                StorageIoGate.reopen();
                stopRequested = segmentStopRequested = false;
                worker = recordingWorker(this::recordLoop, "RecordingServiceSurveillanceWorker");
                worker.start();
            } else if (!surveillanceOwnsRecorder || current.stopReason() != null) {
                segmentStopRequested = true;
                worker.interrupt();
            }
        }
    }

    private void requestSurveillanceStop(String reason, boolean error, boolean deferRestart) {
        requestSurveillanceStop(null, reason, error, deferRestart);
    }

    private void requestSurveillanceStop(SurveillanceSession expected, String reason, boolean error, boolean deferRestart) {
        synchronized (stateLock) {
            SurveillanceSession session = surveillanceSession;
            if (session == null || (expected != null && expected != session)) return;
            session.stop(reason, error, deferRestart);
            SurveillanceStore.save(this, session, false);
            segmentStopRequested = true;
            stateLock.notifyAll();
            Thread current = worker;
            if (current != null && current != Thread.currentThread()) current.interrupt();
        }
    }

    private void recordSurveillanceLoop(SurveillanceSession session) throws Exception {
        surveillanceOwnsRecorder = true;
        modeTransition = true;
        if (session.stopReason() != null) return;
        if (!session.canResume(SurveillanceStore.bootCount(this), SystemClock.elapsedRealtime())) {
            session.stop("timeout", false, false);
            return;
        }
        if (!finishEventBoundary()) throw new IOException(eventBoundaryError);
        if (!StorageIoGate.begin()) throw new IOException("storage_busy");
        File directory;
        try { directory = SurveillanceStorage.resolve(this, session); }
        finally { StorageIoGate.end(); }
        activeBaseDir = directory;
        activeBaseIsUsb = session.usingUsb;
        updateSurveillanceStorage();
        Set<String> protectedBases;
        synchronized (eventLock) { protectedBases = collectProtectedBasesLocked(); }
        SurveillanceStorage.prepare(this, session, protectedBases, () -> session.stopReason() != null);
        if (session.stopReason() != null) return;
        session.prepared = true;
        if (!SurveillanceStore.save(this, session, true)) throw new IOException("session_persist_failed");
        modeTransition = false;
        prefs().edit().putString("surveillanceActualPath", directory.getAbsolutePath()).apply();
        stopRequested = segmentStopRequested = watchdogFailed = false;
        // Recover the last interrupted container without making partial files recycle candidates.
        File[] partials = directory.listFiles((dir, name) -> name.endsWith(".mp4.part")
                && SurveillancePolicy.isClip(name.substring(0, name.length() - 5)));
        if (partials != null) for (File partial : partials) {
            if (!partial.isFile() || java.nio.file.Files.isSymbolicLink(partial.toPath())) continue;
            if (isReadableClip(partial)) {
                File complete = new File(directory, partial.getName().substring(0, partial.getName().length() - 5));
                if (!complete.exists() && !partial.renameTo(complete)) throw new IOException("clip_finalize_failed");
            } else discardClip(partial);
        }
        while (!stopRequested && session.stopReason() == null) {
            if (remaining(session.deadline) == 0) { session.stop("timeout", false, false); break; }
            if (!waitForOemPauseToClear(prefs())) break;
            if (session.stopReason() != null) break;
            synchronized (eventLock) { protectedBases = collectProtectedBasesLocked(); }
            updateSurveillanceStorage();
            SurveillanceStorage.prepare(this, session, protectedBases, () -> session.stopReason() != null);
            if (session.stopReason() != null) break;
            long duration = Math.min(30_000, remaining(session.deadline));
            if (duration == 0) { session.stop("timeout", false, false); break; }
            String base = "surv_" + makeTimestampBase(System.currentTimeMillis(), "yyyyMMddHHmmssSSS")
                    + "_" + java.util.UUID.randomUUID().toString().substring(0, 8);
            long startedAt = System.currentTimeMillis();
            if (!recordClip(directory, duration, base, -1)) {
                session.stop("recording_failed", true, false);
                break;
            }
            if (new File(directory, base + ".mp4").isFile())
                onSegmentCompleted(directory, base, startedAt, System.currentTimeMillis(), 500);
        }
    }

    /** Do not let pending event jobs follow activeBaseDir across a mode change. */
    private boolean finishEventBoundary() {
        try {
            // Interrupts wake the encoder, not this drain barrier.
            Thread.interrupted();
            Future<?> barrier = eventCopyExecutor.submit(() -> {});
            long deadline = SystemClock.elapsedRealtime() + 7500;
            while (true) {
                try {
                    barrier.get(Math.max(1, remaining(deadline)), TimeUnit.MILLISECONDS);
                    break;
                } catch (InterruptedException wakeup) {
                    if (remaining(deadline) == 0) return false;
                }
            }
            synchronized (eventLock) {
                for (EventCaptureRequest request : pendingEventRequests) {
                    for (SegmentInfo segment : recentSegments) {
                        if (segment.ordinal >= request.firstSegmentOrdinal && segment.ordinal <= request.lastSegmentOrdinal
                                && (!request.copiedBaseNames.contains(segment.baseName)
                                || request.inFlightBaseNames.contains(segment.baseName)))
                            throw new IOException("event_copy_failed");
                    }
                }
                pendingEventRequests.clear();
                recentSegments.clear();
                persistEventStateLocked();
            }
            return true;
        } catch (Exception error) {
            eventBoundaryError = error instanceof IOException ? error.getMessage() : "event_copy_timeout";
            DevRuntimeLog.add(TAG, "event boundary did not drain: " + error);
            return false;
        }
    }

    private boolean finishSurveillance(SurveillanceSession session) {
        modeTransition = true;
        boolean drained = finishEventBoundary();
        if (session.stopReason() == null) session.stop(drained ? "completed" : eventBoundaryError, !drained, false);
        synchronized (stateLock) {
            if (surveillanceSession != session) return false;
            mainHandler.removeCallbacks(surveillanceDeadline);
            boolean restored = SurveillanceStore.restore(this);
            String finalState = session.failed() || !restored || !drained ? "error" : "stopped";
            String reason = !restored ? "restore_failed" : !drained ? eventBoundaryError : session.stopReason();
            RemoteRecording.state(this, finalState, reason, session.directory, remoteFreeBytes, session.requestId);
            if (!session.stopRequestId.isEmpty() && !session.stopRequestId.equals(session.requestId))
                RemoteRecording.state(this, finalState, reason, session.directory, remoteFreeBytes, session.stopRequestId);
            publishStatus(session.failed() || !restored || !drained ? STATUS_ERROR : STATUS_OFF,
                    0, TOTAL_CAMERAS, session.failed() ? session.stopReason() : "");
            surveillanceOwnsRecorder = false;
            surveillanceSession = null;
            if (!usbEjectInProgress) {
                activeBaseDir = null;
                activeBaseIsUsb = false;
            }
            boolean resume = restored && drained && session.originalEnabled && !session.deferRestart()
                    && !usbEjectInProgress && !destroyed;
            modeTransition = resume;
            if (!resume) stopRequested = true;
            return resume;
        }
    }

    private synchronized void updateSurveillanceStorage() {
        SurveillanceSession session = surveillanceSession;
        if (session == null || session.directory.isEmpty()) return;
        long free = DashcamStorageManager.availableBytes(new File(session.directory));
        String previous = SurveillancePolicy.storageLevel(remoteFreeBytes);
        remoteFreeBytes = free;
        lastStorageCheck = SystemClock.elapsedRealtime();
        if (!previous.equals(SurveillancePolicy.storageLevel(free))) {
            emitRemoteState(session.requestId);
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) nm.notify(NOTIF_ID, buildNotification(""));
        }
    }

    private void emitRemoteState(String requestId) {
        SurveillanceSession session = surveillanceSession;
        if (session != null) RemoteRecording.state(this, remoteState, remoteReason, session.directory,
                remoteFreeBytes, requestId == null || requestId.isEmpty() ? session.requestId : requestId);
    }

    private boolean isReadableClip(File file) {
        MediaExtractor extractor = new MediaExtractor();
        try {
            extractor.setDataSource(file.getAbsolutePath());
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                String mime = extractor.getTrackFormat(i).getString("mime");
                if (mime != null && mime.startsWith("video/")) {
                    extractor.selectTrack(i);
                    return extractor.getSampleTime() >= 0;
                }
            }
        } catch (Exception e) { DevRuntimeLog.add(TAG, "unreadable " + file + ": " + e); }
        finally { extractor.release(); }
        return false;
    }

    private void discardClip(File file) {
        if (file.exists()) DashcamStorageManager.deleteFreeingSpace(file);
    }

    private final Runnable superviseTick = new Runnable() {
        @Override public void run() {
            if (!sServiceRunning) return;
            if (supervising.compareAndSet(false, true)) supervisor.execute(() -> {
                try { supervise(); }
                catch (Throwable e) { DevRuntimeLog.add(TAG, "supervisor: " + e); }
                finally { supervising.set(false); }
            });
            mainHandler.postDelayed(this, 250);
        }
    };

    private void supervise() {
        if (worker == null || stopRequested) return;
        long now = SystemClock.elapsedRealtime();
        SurveillanceSession session = surveillanceSession;
        if (session != null) {
            if (now >= session.deadline) requestSurveillanceStop(session, "timeout", false, false);
            if (surveillanceOwnsRecorder && session.prepared && now - lastStorageCheck >= 1000) {
                try { SurveillanceStorage.validatePinned(new File(session.directory), session.device); }
                catch (Exception error) { requestSurveillanceStop(session, "storage_unavailable", true, false); }
                updateSurveillanceStorage();
            }
        }
        if (UiPrefs.isOemAvmCoexistEnabled(prefs())) {
            Boolean foreground = null;
            try {
                ActivityManager am = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
                List<ActivityManager.RunningTaskInfo> tasks = am.getRunningTasks(1);
                if (!tasks.isEmpty() && tasks.get(0).topActivity != null)
                    foreground = "com.saicmotor.hmi.aroundview".equals(tasks.get(0).topActivity.getPackageName());
            } catch (Exception e) { /* Unknown is different from definitely absent. */ }
            if (Boolean.TRUE.equals(foreground)) {
                oemGoneAt = 0;
                if (oemSeenAt == 0) oemSeenAt = now;
                if (!oemPauseRequested && VehicleSpeedReader.readSpeedKmh() <= UiPrefs.getDevOemAvmMaxSpeedKmh(prefs())) {
                    SignalService.setOemAvmActive(this, true);
                    pauseForOemRequest(this);
                }
            } else if (oemPauseRequested) {
                if (oemGoneAt == 0) oemGoneAt = now;
                long grace = oemSeenAt == 0 || oemGoneAt - oemSeenAt < 1500 ? 8000 : 2000;
                if (OemPausePolicy.shouldResume(foreground, now, oemAskedAt, oemGoneAt, grace)) {
                    SignalService.setOemAvmActive(this, false);
                    resumeAfterOemRequest(this);
                }
            }
        } else if (oemPauseRequested) {
            SignalService.setOemAvmActive(this, false);
            resumeAfterOemRequest(this);
        }
        if (lastSegmentAt > 0 && RecordingHealth.segmentOverdue(now - lastSegmentAt,
                expectedSegmentMs, oemPauseRequested)) {
            watchdogFailed = true;
            stopRequested = true;
            segmentStopRequested = true;
            publishStatus(STATUS_ERROR, 0, TOTAL_CAMERAS, "watchdog: no completed segment");
            Thread current = worker;
            if (current != null) current.interrupt();
        }
    }

    private long completedSegmentCount = 0L;

    public static boolean isSupervisingOem() {
        RecordingService live = instance;
        return live != null && live.worker != null && !live.stopRequested;
    }

    public static boolean isRunning() {
        return sServiceRunning;
    }

    public static void startIfDashcamEnabled(Context context) {
        if (instance != null && instance.surveillanceSession != null) return;
        SharedPreferences prefs = UiPrefs.getPrefs(context);
        SurveillanceSession session = SurveillanceStore.read(context);
        if (session != null) {
            if (session.canResume(SurveillanceStore.bootCount(context), SystemClock.elapsedRealtime())) {
                context.startForegroundService(new Intent(context, RecordingService.class).setAction(RemoteRecording.RECOVER));
                return;
            }
            SurveillanceStore.restore(context);
            RemoteRecording.state(context, "stopped", "interrupted", session.directory, -1, session.requestId);
        }
        boolean enabled = prefs.getBoolean(DashcamSettingsController.KEY_ENABLED, false);
        if (!enabled)
            return;
        Intent i = new Intent(context, RecordingService.class);
        i.setAction(ACTION_START);
        context.startForegroundService(i);
    }

    public static void stopIfRunning(Context context) {
        Intent i = new Intent(context, RecordingService.class);
        i.setAction(ACTION_STOP);
        context.startService(i);
    }

    public static void startTestClip(Context context, int durationSec) {
        Intent i = new Intent(context, RecordingService.class);
        i.setAction(ACTION_RECORD_TEST);
        i.putExtra(EXTRA_TEST_RECORD_DURATION_SEC, durationSec);
        context.startForegroundService(i);
    }

    public static void triggerEventSave(Context context) {
        Intent i = new Intent(context, RecordingService.class);
        i.setAction(ACTION_TRIGGER_EVENT_SAVE);
        context.startService(i);
    }

    public static void triggerEventSaveOrFutureOnly(Context context) {
        Intent i = new Intent(context, RecordingService.class);
        i.setAction(ACTION_TRIGGER_EVENT_SAVE);
        i.putExtra(EXTRA_EVENT_ALLOW_FUTURE_ONLY, true);
        context.startForegroundService(i);
    }

    public static void requestUsbEject(Context context) {
        Intent i = new Intent(context, RecordingService.class);
        i.setAction(ACTION_EJECT_USB);
        context.startService(i);
    }

    public static void pauseForOemRequest(Context context) {
        SharedPreferences prefs = UiPrefs.getPrefs(context);
        if ((!prefs.getBoolean(DashcamSettingsController.KEY_ENABLED, false)
                && !(instance != null && (instance.futureOnlyEventSession || instance.surveillanceSession != null))) || !isRunning()) {
            return;
        }
        RecordingService live = instance;
        if (live != null) {
            if (!live.oemPauseRequested) live.oemAskedAt = SystemClock.elapsedRealtime();
            live.oemPauseRequested = true;
            live.segmentStopRequested = true;
            Thread current = live.worker;
            if (current != null) current.interrupt();
        }
        Intent i = new Intent(context, RecordingService.class);
        i.setAction(ACTION_PAUSE_FOR_OEM_REQUEST);
        context.startForegroundService(i);
    }

    public static void resumeAfterOemRequest(Context context) {
        SharedPreferences prefs = UiPrefs.getPrefs(context);
        if ((!prefs.getBoolean(DashcamSettingsController.KEY_ENABLED, false)
                && !(instance != null && (instance.futureOnlyEventSession || instance.surveillanceSession != null))) || !isRunning()) {
            return;
        }
        Intent i = new Intent(context, RecordingService.class);
        i.setAction(ACTION_RESUME_AFTER_OEM_REQUEST);
        context.startForegroundService(i);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        sServiceRunning = true;
        instance = this;
        NotificationChannelHelper.ensureChannel(this, CHANNEL_ID, R.string.notification_channel_recording);
        surveillanceSession = SurveillanceStore.read(this);
        if (surveillanceSession != null) {
            modeTransition = true;
            SurveillanceSession session = surveillanceSession;
            if (!session.canResume(SurveillanceStore.bootCount(this), SystemClock.elapsedRealtime()))
                session.stop(session.bootCount == SurveillanceStore.bootCount(this) ? "timeout" : "reboot", false, false);
            if (session.prepared) {
                activeBaseDir = new File(session.directory);
                activeBaseIsUsb = session.usingUsb;
            } else {
                String previous = prefs().getString("dashcamActualPath", "");
                if (!previous.isEmpty()) activeBaseDir = new File(previous);
            }
        }
        restoreEventState();
        statusIcon = new StatusIconOverlay(this);
        prefs().registerOnSharedPreferenceChangeListener(iconPreferenceListener);
        mainHandler.post(superviseTick);
        shutdownReceiver = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                PendingResult pending = goAsync();
                usbEjectInProgress = true;
                requestSurveillanceStop("shutdown", false, true);
                if (surveillanceSession != null) SurveillanceStore.restore(RecordingService.this);
                StorageIoGate.close();
                shutdownRecordingServiceWithoutStopSelf();
                AtomicBoolean finished = new AtomicBoolean();
                mainHandler.postDelayed(() -> {
                    if (finished.compareAndSet(false, true)) {
                        DevRuntimeLog.add(TAG, "Android shutdown deadline exceeded; durability unknown");
                        pending.finish();
                    }
                }, 8500);
                new Thread(() -> {
                    boolean quiet = awaitShutdownQuiescence();
                    DevRuntimeLog.add(TAG, "Android shutdown drained=" + quiet);
                    if (finished.compareAndSet(false, true)) pending.finish();
                }, "DashcamAndroidShutdown").start();
            }
        };
        androidx.core.content.ContextCompat.registerReceiver(this, shutdownReceiver,
                new IntentFilter(Intent.ACTION_SHUTDOWN), androidx.core.content.ContextCompat.RECEIVER_EXPORTED);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            if (surveillanceSession != null) {
                intent = new Intent(this, RecordingService.class).setAction(RemoteRecording.RECOVER);
            } else {
                if (!prefs().getBoolean(DashcamSettingsController.KEY_ENABLED, false)) {
                    stopSelf();
                    return START_NOT_STICKY;
                }
                intent = new Intent(this, RecordingService.class).setAction(ACTION_START);
            }
        }
        String action = intent.getAction();
        if (RemoteRecording.START.equals(action) || RemoteRecording.STOP.equals(action)
                || RemoteRecording.RECOVER.equals(action)) {
            if (RemoteRecording.RECOVER.equals(action) && surveillanceSession == null) {
                startForeground(NOTIF_ID, buildNotification(""));
                if (worker == null) {
                    stopRequested = true;
                    stopServiceIfNotEjecting();
                }
            } else handleRemoteCommand(intent);
            return surveillanceSession != null || prefs().getBoolean(DashcamSettingsController.KEY_ENABLED, false)
                    ? START_STICKY : START_NOT_STICKY;
        }
        if (usbEjectInProgress) return START_NOT_STICKY;
        if (surveillanceSession != null && worker == null)
            handleRemoteCommand(new Intent(this, RecordingService.class).setAction(RemoteRecording.RECOVER));

        if (surveillanceSession != null && (ACTION_START.equals(action) || ACTION_STOP.equals(action)
                || ACTION_RECORD_TEST.equals(action))) {
            publishCurrentStatus();
            return START_STICKY;
        }

        if (ACTION_STOP.equals(action)) {
            DevRuntimeLog.add("RecordingService", "ACTION_STOP");
            shutdownRecordingService();
            return START_NOT_STICKY;
        }

        if (ACTION_EJECT_USB.equals(action)) {
            DevRuntimeLog.add("RecordingService", "ACTION_EJECT_USB");
            // Decide on the worker thread: resolve() touches the filesystem for every target
            // except INTERNAL_ONLY. That was harmless while the preference was pinned to
            // internal storage; now that it is honoured, this would be main-thread IO.
            usbEjectInProgress = true;
            new Thread(() -> {
                if (activeBaseDir == null) {
                    DashcamStorageManager.Resolution resolution = DashcamStorageManager.resolve(this);
                    activeBaseDir = resolution.baseDir;
                    activeBaseIsUsb = resolution.usingUsb;
                }
                boolean usingUsb = activeBaseIsUsb;
                if (!usingUsb) {
                    usbEjectInProgress = false;
                    broadcastUsbEjectReady(
                            false, R.string.settings_dashcam_storage_eject_unavailable_message);
                    return;
                }
                usbEjectInProgress = true;
                requestSurveillanceStop("usb_eject", false, true);
                if (surveillanceSession != null) SurveillanceStore.restore(RecordingService.this);
                StorageIoGate.close();
                shutdownRecordingServiceWithoutStopSelf();
                boolean safeToRemove = awaitShutdownQuiescence();
                broadcastUsbEjectReady(
                        safeToRemove,
                        safeToRemove
                                ? R.string.settings_dashcam_storage_eject_ready_message
                                : R.string.settings_dashcam_storage_eject_failed_message);
                stopForeground(true);
                stopSelf();
            }, "RecordingServiceUsbEject").start();
            return START_NOT_STICKY;
        }

        if (ACTION_PAUSE_FOR_OEM_REQUEST.equals(action)) {
            DevRuntimeLog.add("RecordingService", "ACTION_PAUSE_FOR_OEM_REQUEST");
            boolean enabled = recordingEnabled();
            boolean changed = !oemPauseRequested;
            if (changed) oemAskedAt = SystemClock.elapsedRealtime();
            oemPauseRequested = true;
            segmentStopRequested = true;
            // The worker sleeps in 200 ms ticks inside recordClip; interrupt wakes it instantly
            // so stopCombinedMp4Record runs before AVM's V4l2_Init hits "Device or resource busy".
            synchronized (stateLock) {
                stateLock.notifyAll();
            }
            if (worker != null) {
                worker.interrupt();
            }
            if (enabled) {
                if (worker == null) {
                    stopRequested = false;
                    startForeground(NOTIF_ID, buildNotification(getString(R.string.notification_recording_paused_oem)));
                }
                publishStatus(STATUS_PAUSED_OEM, 0, TOTAL_CAMERAS, "");
                if (surveillanceSession == null) DashcamEventOverlayService.showOemPause(this);
            }
            return START_STICKY;
        }

        if (ACTION_RESUME_AFTER_OEM_REQUEST.equals(action)) {
            DevRuntimeLog.add("RecordingService", "ACTION_RESUME_AFTER_OEM_REQUEST");
            boolean wasPaused = STATUS_PAUSED_OEM.equals(prefs().getString(KEY_STATUS, STATUS_OFF));
            oemPauseRequested = false;
            oemAskedAt = oemGoneAt = oemSeenAt = 0;
            lastSegmentAt = SystemClock.elapsedRealtime();
            segmentStopRequested = false;
            synchronized (stateLock) {
                stateLock.notifyAll();
            }
            boolean enabled = recordingEnabled();
            if (!enabled) {
                if (worker == null) {
                    publishStatus(STATUS_OFF, 0, TOTAL_CAMERAS, "");
                    stopForeground(true);
                    stopSelf();
                }
                return START_NOT_STICKY;
            }
            if (worker == null) {
                stopRequested = false;
                startForeground(NOTIF_ID, buildNotification(getString(R.string.notification_recording_starting)));
                publishStatus(STATUS_STARTING, 0, TOTAL_CAMERAS, "");
                if (wasPaused && surveillanceSession == null) {
                    DashcamEventOverlayService.showOemResume(this);
                }
                worker = recordingWorker(this::recordLoop, "RecordingServiceWorker");
                worker.start();
            } else if (wasPaused) {
                publishStatus(STATUS_STARTING, 0, TOTAL_CAMERAS, "");
                if (surveillanceSession == null) DashcamEventOverlayService.showOemResume(this);
            }
            return START_STICKY;
        }

        if (ACTION_TRIGGER_EVENT_SAVE.equals(action)) {
            DevRuntimeLog.add("RecordingService", "ACTION_TRIGGER_EVENT_SAVE");
            boolean allowFutureOnly = intent.getBooleanExtra(EXTRA_EVENT_ALLOW_FUTURE_ONLY, false);
            if (modeTransition) return START_STICKY;
            if (surveillanceSession != null && (modeTransition || !surveillanceSession.prepared
                    || surveillanceSession.stopReason() != null)) return START_STICKY;
            if (worker == null) {
                if (allowFutureOnly && startFutureOnlyEventSession()) {
                    DashcamEventOverlayService.showFutureOnlyConfirmation(this);
                } else if (allowFutureOnly) {
                    startForeground(NOTIF_ID, buildNotification(""));
                    stopForeground(true);
                    stopSelf();
                }
                return allowFutureOnly ? START_NOT_STICKY : START_STICKY;
            }
            if (futureOnlyEventSession) {
                DashcamEventOverlayService.showFutureOnlyConfirmation(this);
                return START_STICKY;
            }
            if (!recordingEnabled()) {
                return START_STICKY;
            }
            if (armEventCapture(EVENT_SEGMENTS_BEFORE_CURRENT, EVENT_SEGMENTS_AFTER_CURRENT)) {
                DashcamEventOverlayService.showConfirmation(this);
            }
            return START_STICKY;
        }

        if (worker != null) {
            // Do not start again if it is already running.
            publishCurrentStatus();
            return START_STICKY;
        }

        if (usbEjectInProgress) return START_NOT_STICKY;
        StorageIoGate.reopen();
        stopRequested = false;
        DevRuntimeLog.add("RecordingService", action == null ? "ACTION_START(null)" : action);
        startForeground(NOTIF_ID, buildNotification(getString(R.string.notification_recording_starting)));
        publishStatus(STATUS_STARTING, 0, TOTAL_CAMERAS, "");
        if (ACTION_RECORD_TEST.equals(action)) {
            testRecording = true;
            int durationSec = intent.getIntExtra(
                    EXTRA_TEST_RECORD_DURATION_SEC,
                    DashcamSettingsController.getTestRecordDurationSec(prefs()));
            long durationMs = Math.max(0L, durationSec * 1000L);
            worker = recordingWorker(() -> recordTestClip(durationMs), "RecordingServiceTestWorker");
        } else {
            worker = recordingWorker(this::recordLoop, "RecordingServiceWorker");
        }
        worker.start();
        return START_STICKY;
    }

    private void recordTestClip(long durationMs) {
        File recordsBase = resolveActiveBaseDir(true);
        if (recordsBase == null) {
            stopForeground(true);
            stopServiceIfNotEjecting();
            return;
        }
        // Write test clips into a separate subdirectory so they never participate
        // in ring-buffer cleanup and don't accumulate as phantom entries there.
        File testDir = new File(recordsBase, "test");
        if (!ensureDirectoryExists(testDir, "test dir")) {
            stopForeground(true);
            stopServiceIfNotEjecting();
            return;
        }
        boolean startedAnyCamera = recordClip(testDir, durationMs,
                makeTimestampBase(System.currentTimeMillis(), "yyMMddHHmmssSSS"), -1);
        if (startedAnyCamera) {
            publishStatus(STATUS_OFF, 0, TOTAL_CAMERAS, "");
        }
        stopServiceIfNotEjecting();
    }

    private void recordLoop() {
        // NOTE: For now we only record MP4 clips, not speed or turn-signal data.
        SharedPreferences prefs = prefs();
        boolean enabled = prefs.getBoolean(DashcamSettingsController.KEY_ENABLED, false);
        int segmentSec = DashcamSettingsController.getSegmentDurationSec();

        if (!enabled || segmentSec <= 0) {
            publishStatus(STATUS_OFF, 0, TOTAL_CAMERAS, "");
            stopServiceIfNotEjecting();
            return;
        }

        File baseDir = resolveActiveBaseDir(true);
        if (baseDir == null) {
            stopServiceIfNotEjecting();
            return;
        }

        long segmentMs = segmentSec * 1000L;
        modeTransition = false;

        boolean endedWithFatalError = false;

        while (!stopRequested && surveillanceSession == null) {
            if (!waitForOemPauseToClear(prefs)) {
                break;
            }
            // Re-resolve between segments so USB hot-plug/unplug and settings changes are
            // picked up. AUTO mode switches targets with a banner; USB_ONLY mode turns a
            // missing medium into a fatal error.
            File resolved = resolveActiveBaseDir(false);
            if (resolved == null) {
                endedWithFatalError = true;
                break;
            }
            baseDir = resolved;

            // Read every iteration so settings edits take effect between segments. USB and
            // internal storage carry separate retention limits.
            int keepSegments = DashcamStorageManager.getActiveRetentionClipCount(prefs, activeBaseIsUsb);
            long segmentStartWallMs = System.currentTimeMillis();
            String baseName = makeTimestampBase(segmentStartWallMs, "yyMMddHHmmssSSS");
            boolean startedAnyCamera = recordClip(baseDir, segmentMs, baseName, keepSegments);
            if (!startedAnyCamera) {
                if (isRecoverableUsbFailure()) {
                    // AUTO mode: the segment failed because USB died. The next loop pass
                    // re-resolves to internal storage and shows the fallback banner.
                    continue;
                }
                endedWithFatalError = true;
                break;
            }
            if (new File(baseDir, baseName + ".mp4").isFile())
                onSegmentCompleted(baseDir, baseName, segmentStartWallMs, System.currentTimeMillis(), keepSegments);

            // Check whether recording has been disabled in prefs.
            enabled = prefs.getBoolean(DashcamSettingsController.KEY_ENABLED, false);
            if (!enabled)
                break;
        }

        if (!endedWithFatalError && !watchdogFailed) {
            publishStatus(STATUS_OFF, 0, TOTAL_CAMERAS, "");
        }
        stopServiceIfNotEjecting();
    }

    private void recordFutureOnlyEventLoop() {
        int segmentSec = DashcamSettingsController.getSegmentDurationSec();
        if (segmentSec <= 0) {
            publishStatus(STATUS_OFF, 0, TOTAL_CAMERAS, "");
            futureOnlyEventSession = false;
            stopServiceIfNotEjecting();
            return;
        }

        File baseDir = resolveActiveBaseDir(true);
        if (baseDir == null) {
            futureOnlyEventSession = false;
            stopServiceIfNotEjecting();
            return;
        }

        long segmentMs = segmentSec * 1000L;
        boolean endedWithFatalError = false;
        int remainingSegments = FUTURE_ONLY_EVENT_SEGMENTS;

        while (!stopRequested && remainingSegments > 0) {
            if (!waitForOemPauseToClear(prefs())) break;
            File resolved = resolveActiveBaseDir(false);
            if (resolved == null) {
                endedWithFatalError = true;
                break;
            }
            baseDir = resolved;

            int keepSegments = DashcamStorageManager.getActiveRetentionClipCount(prefs(), activeBaseIsUsb);
            long segmentStartWallMs = System.currentTimeMillis();
            String baseName = makeTimestampBase(segmentStartWallMs, "yyMMddHHmmssSSS");
            boolean startedAnyCamera = recordClip(baseDir, segmentMs, baseName, keepSegments);
            if (!startedAnyCamera) {
                if (isRecoverableUsbFailure()) {
                    continue;
                }
                endedWithFatalError = true;
                break;
            }
            if (new File(baseDir, baseName + ".mp4").isFile())
                onSegmentCompleted(baseDir, baseName, segmentStartWallMs, System.currentTimeMillis(), keepSegments);
            if (new File(baseDir, baseName + ".mp4").isFile()) remainingSegments--;
        }

        futureOnlyEventSession = false;
        if (!endedWithFatalError && !watchdogFailed) {
            publishStatus(STATUS_OFF, 0, TOTAL_CAMERAS, "");
        }
        stopServiceIfNotEjecting();
    }

    private boolean recordClip(File baseDir, long durationMs, String baseName, int keepSegments) {
        expectedSegmentMs = Math.max(1000, durationMs);
        SharedPreferences prefs = prefs();
        int recordingFps = DashcamSettingsController.getRecordingFps(prefs);
        String signature = DashcamSettingsController.getRecordingSignature(prefs);
        boolean showSpeed = DashcamSettingsController.shouldShowSpeed(prefs);
        int cameraMask = DashcamSettingsController.getRecordingCameraMask(prefs);
        int selectedCameraCount = DashcamSettingsController.getRecordingCameraCount(cameraMask);
        if (keepSegments > 0) {
            synchronized (eventLock) {
                cleanupOldSegments(baseDir, Math.max(0, keepSegments - 1), collectProtectedBasesLocked());
            }
        }
        boolean surveillance = surveillanceOwnsRecorder;
        File outputFile = new File(baseDir, baseName + (surveillance ? ".mp4.part" : ".mp4"));
        recordingFile = outputFile;
        publishStatus(STATUS_STARTING, 0, TOTAL_CAMERAS, "");
        boolean started = CameraProbe.startCombinedMp4Record(
                outputFile.getAbsolutePath(),
                720,
                480,
                recordingFps,
                9_000_000,
                signature,
                showSpeed,
                cameraMask);

        if (!started) {
            discardClip(outputFile);
            recordingFile = null;
            publishStatus(STATUS_ERROR, 0, TOTAL_CAMERAS, ERROR_GRID_START_FAILED);
            return false;
        }

        long lastSamples = 0;
        long lastProgressAt = SystemClock.elapsedRealtime();
        boolean announced = false;

        long start = SystemClock.elapsedRealtime();
        while (!stopRequested
                && !segmentStopRequested
                && (SystemClock.elapsedRealtime() - start) < durationMs) {
            long samples = CameraProbe.writtenSamples();
            if (samples > lastSamples) {
                lastSamples = samples;
                lastProgressAt = SystemClock.elapsedRealtime();
                if (!announced && !oemPauseRequested) {
                    publishStatus(STATUS_RECORDING, selectedCameraCount, TOTAL_CAMERAS, "");
                    announced = true;
                }
            }
            if (RecordingHealth.evaluate(samples, SystemClock.elapsedRealtime() - lastProgressAt) == RecordingHealth.State.ERROR) {
                watchdogFailed = true;
                publishStatus(STATUS_ERROR, 0, TOTAL_CAMERAS, "encoder stalled or write failed");
                break;
            }
            if (showSpeed) {
                CameraProbe.updateCombinedRecordingSpeed(VehicleSpeedReader.readSpeedKmh());
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException ignored) {
            }
        }

        boolean urgent = oemPauseRequested;
        if (urgent) CameraProbe.detachAllPreviews();
        long stoppingAt = SystemClock.elapsedRealtime();
        boolean stopped = CameraProbe.stopCombinedMp4Record(
                !stopRequested && !segmentStopRequested && !watchdogFailed, urgent);
        DevRuntimeLog.add(TAG, "segment stop/finalize ms=" + (SystemClock.elapsedRealtime() - stoppingAt)
                + " oem=" + urgent + "; device release time: " + CameraProbe.describeCameraFormats());
        if (!stopped || watchdogFailed || !isReadableClip(outputFile)) {
            discardClip(outputFile);
            recordingFile = null;
            if (urgent && stopped && !watchdogFailed) {
                publishStatus(STATUS_PAUSED_OEM, 0, TOTAL_CAMERAS, "");
                return true; // An OEM interruption before the first frame is not a recorder failure.
            }
            publishStatus(STATUS_ERROR, 0, TOTAL_CAMERAS, ERROR_GRID_STOP_TIMEOUT);
            return false;
        }

        recordingFile = null;
        if (surveillance) {
            File completed = new File(baseDir, baseName + ".mp4");
            if (completed.exists() || !outputFile.renameTo(completed)
                    || !DashcamStorageManager.syncDirectory(baseDir)) {
                publishStatus(STATUS_ERROR, 0, TOTAL_CAMERAS, "clip_finalize_failed");
                return false;
            }
        }
        lastSegmentAt = SystemClock.elapsedRealtime();
        return true;
    }

    private boolean waitForOemPauseToClear(SharedPreferences prefs) {
        boolean announcedPause = false;
        while (!stopRequested
                && recordingEnabled()
                && oemPauseRequested) {
            if (surveillanceSession != null && !surveillanceOwnsRecorder) return false;
            SurveillanceSession session = surveillanceSession;
            if (session != null && (session.stopReason() != null || remaining(session.deadline) == 0)) {
                if (session.stopReason() == null) requestSurveillanceStop("timeout", false, false);
                return false;
            }
            segmentStopRequested = true;
            if (!announcedPause) {
                CameraProbe.releaseCombinedCameras();
                publishStatus(STATUS_PAUSED_OEM, 0, TOTAL_CAMERAS, "");
                announcedPause = true;
            }
            synchronized (stateLock) {
                try {
                    stateLock.wait(1000L);
                } catch (InterruptedException ignored) {
                }
            }
        }
        if (stopRequested || !recordingEnabled()) {
            return false;
        }
        segmentStopRequested = false;
        if (announcedPause) {
            publishStatus(STATUS_STARTING, 0, TOTAL_CAMERAS, "");
        }
        return true;
    }

    private void onSegmentCompleted(File baseDir, String baseName, long startMs, long endMs, int keepSegments) {
        List<EventCopyJob> copyJobs;
        synchronized (eventLock) {
            long segmentOrdinal = ++completedSegmentCount;
            recentSegments.add(new SegmentInfo(segmentOrdinal, baseName, startMs, endMs,
                    baseDir.getAbsolutePath()));
            while (recentSegments.size() > keepSegments + 6) {
                recentSegments.remove(0);
            }
            copyJobs = collectEventCopyJobsLocked(segmentOrdinal);
            Set<String> protectedBases = collectProtectedBasesLocked();
            if (!surveillanceOwnsRecorder) cleanupOldSegments(baseDir, keepSegments, protectedBases);
            persistEventStateLocked();
        }
        enqueueEventCopyJobs(copyJobs);
    }

    private boolean startFutureOnlyEventSession() {
        if (resolveActiveBaseDir(true) == null) {
            return false;
        }
        if (!armEventCapture(0, FUTURE_ONLY_EVENT_SEGMENTS - 1)) {
            return false;
        }
        futureOnlyEventSession = true;
        stopRequested = false;
        segmentStopRequested = false;
        oemPauseRequested = false;
        startForeground(NOTIF_ID, buildNotification(getString(R.string.notification_recording_starting)));
        publishStatus(STATUS_STARTING, 0, TOTAL_CAMERAS, "");
        worker = recordingWorker(this::recordFutureOnlyEventLoop, "RecordingServiceFutureEventWorker");
        worker.start();
        return true;
    }

    private boolean armEventCapture(int segmentsBeforeCurrent, int segmentsAfterCurrent) {
        long now = System.currentTimeMillis();
        String eventBaseName = "event_" + makeTimestampBase(now, "yyMMddHHmmssSSS");
        File eventsBaseDir = getEventsBaseDir();
        if (eventsBaseDir == null || !eventsBaseDir.canWrite()) {
            DevRuntimeLog.add("RecordingService", "Event capture failed: events base dir unavailable or not writable");
            Log.e(TAG, "Failed to arm event capture because events base dir is unavailable or not writable");
            notifyEventStorageFailure();
            return false;
        }
        File eventDir = new File(eventsBaseDir, eventBaseName);
        if (!ensureDirectoryExists(eventDir, "event dir")) {
            DevRuntimeLog.add("RecordingService", "Event capture failed: mkdir " + eventDir.getAbsolutePath());
            Log.e(TAG, "Failed to create event dir " + eventDir.getAbsolutePath());
            notifyEventStorageFailure();
            return false;
        }
        List<EventCopyJob> copyJobs;
        synchronized (eventLock) {
            long currentSegmentOrdinal = completedSegmentCount + 1L;
            long firstSegmentOrdinal = Math.max(1L, currentSegmentOrdinal - Math.max(0, segmentsBeforeCurrent));
            long lastSegmentOrdinal = currentSegmentOrdinal + Math.max(0, segmentsAfterCurrent);
            pendingEventRequests.add(new EventCaptureRequest(
                    eventBaseName,
                    firstSegmentOrdinal,
                    lastSegmentOrdinal
            ));
            trimOldEventDirsLocked(eventsBaseDir);
            copyJobs = collectEventCopyJobsLocked(completedSegmentCount);
            persistEventStateLocked();
            DevRuntimeLog.add(
                    "RecordingService",
                    "Event armed: " + eventBaseName
                            + " ordinals " + firstSegmentOrdinal
                            + "-" + lastSegmentOrdinal);
        }
        enqueueEventCopyJobs(copyJobs);
        return true;
    }

    private List<EventCopyJob> collectEventCopyJobsLocked(long completedThroughOrdinal) {
        List<EventCopyJob> jobs = new ArrayList<>();
        List<EventCaptureRequest> completed = new ArrayList<>();
        for (EventCaptureRequest request : pendingEventRequests) {
            List<SegmentCopyRef> segments = new ArrayList<>();
            for (SegmentInfo segment : recentSegments) {
                if (segment.ordinal < request.firstSegmentOrdinal
                        || segment.ordinal > request.lastSegmentOrdinal) {
                    continue;
                }
                if (!request.copiedBaseNames.contains(segment.baseName)
                        && request.inFlightBaseNames.add(segment.baseName)) {
                    segments.add(new SegmentCopyRef(segment.baseName, segment.sourceDirPath));
                }
            }
            if (!segments.isEmpty()) {
                jobs.add(new EventCopyJob(request.eventBaseName, segments));
            }
            if (isRequestCompleteLocked(request, completedThroughOrdinal)) {
                completed.add(request);
            }
        }
        pendingEventRequests.removeAll(completed);
        return jobs;
    }

    private Set<String> collectProtectedBasesLocked() {
        Set<String> protectedBases = new HashSet<>();
        for (EventCaptureRequest request : pendingEventRequests) {
            for (SegmentInfo segment : recentSegments) {
                if (segment.ordinal >= request.firstSegmentOrdinal
                        && segment.ordinal <= request.lastSegmentOrdinal) {
                    protectedBases.add(segment.baseName);
                }
            }
        }
        return protectedBases;
    }

    private void enqueueEventCopyJobs(List<EventCopyJob> jobs) {
        for (EventCopyJob job : jobs) {
            eventCopyExecutor.execute(() -> copyEventSegments(job));
        }
    }

    private boolean isRequestCompleteLocked(EventCaptureRequest request, long completedThroughOrdinal) {
        if (completedThroughOrdinal < request.lastSegmentOrdinal) {
            return false;
        }
        for (SegmentInfo segment : recentSegments) {
            if (segment.ordinal < request.firstSegmentOrdinal
                    || segment.ordinal > request.lastSegmentOrdinal) {
                continue;
            }
            if (!request.copiedBaseNames.contains(segment.baseName)
                    || request.inFlightBaseNames.contains(segment.baseName)) {
                return false;
            }
        }
        return true;
    }

    private void copyEventSegments(EventCopyJob job) {
        if (job.segments.isEmpty()) {
            Log.w(TAG, "Skipping empty event copy job for " + job.eventBaseName);
            onEventCopyFinished(job, new ArrayList<>());
            return;
        }
        File eventsBaseDir = getEventsBaseDir();
        if (eventsBaseDir == null || !eventsBaseDir.canWrite()) {
            DevRuntimeLog.add("RecordingService", "Event copy failed: events base dir unavailable or not writable");
            Log.e(TAG, "Failed to copy event segments because events base dir is unavailable or not writable");
            mainHandler.post(this::notifyEventStorageFailure);
            onEventCopyFinished(job, new ArrayList<>());
            return;
        }
        File eventDir = new File(eventsBaseDir, job.eventBaseName);
        if (!ensureDirectoryExists(eventDir, "event dir")) {
            DevRuntimeLog.add("RecordingService", "Event copy failed: mkdir " + eventDir.getAbsolutePath());
            Log.e(TAG, "Failed to create event dir " + eventDir.getAbsolutePath());
            mainHandler.post(this::notifyEventStorageFailure);
            onEventCopyFinished(job, new ArrayList<>());
            return;
        }
        DevRuntimeLog.add("RecordingService", "Event copy: " + job.eventBaseName + " files " + job.segments.size());
        List<String> copiedBaseNames = new ArrayList<>();
        for (SegmentCopyRef ref : job.segments) {
            // Read each segment from the root it was recorded into — after a USB→internal
            // fallback an event can span both roots. Legacy persisted entries without a
            // source path fall back to the currently active root.
            File sourceDir = ref.sourceDirPath.isEmpty()
                    ? getActiveRecordsBaseDir()
                    : new File(ref.sourceDirPath);
            if (copySegmentGroup(sourceDir, eventDir, ref.baseName)) {
                copiedBaseNames.add(ref.baseName);
            }
        }
        onEventCopyFinished(job, copiedBaseNames);
    }

    private void onEventCopyFinished(EventCopyJob job, List<String> copiedBaseNames) {
        synchronized (eventLock) {
            EventCaptureRequest request = findPendingEventRequestLocked(job.eventBaseName);
            if (request == null) {
                return;
            }
            request.inFlightBaseNames.removeAll(job.baseNames());
            request.copiedBaseNames.addAll(copiedBaseNames);
            if (isRequestCompleteLocked(request, completedSegmentCount)) {
                pendingEventRequests.remove(request);
            }
            persistEventStateLocked();
        }
    }

    private EventCaptureRequest findPendingEventRequestLocked(String eventBaseName) {
        for (EventCaptureRequest request : pendingEventRequests) {
            if (request.eventBaseName.equals(eventBaseName)) {
                return request;
            }
        }
        return null;
    }

    private boolean copySegmentGroup(File sourceDir, File targetDir, String baseName) {
        File combinedSource = new File(sourceDir, baseName + ".mp4");
        if (combinedSource.exists()) {
            return copyFile(combinedSource, new File(targetDir, combinedSource.getName()));
        }

        char[] suffixes = new char[] { 'F', 'R', 'X', 'Y' };
        boolean copiedAny = false;
        for (char suffix : suffixes) {
            File source = new File(sourceDir, baseName + "_" + suffix + ".mp4");
            if (!source.exists()) {
                continue;
            }
            File target = new File(targetDir, source.getName());
            copiedAny |= copyFile(source, target);
        }
        return copiedAny;
    }

    private boolean copyFile(File source, File target) {
        byte[] buffer = new byte[64 * 1024];
        File temp = new File(target.getParentFile(), target.getName() + ".tmp");
        if (temp.exists() && !temp.delete()) {
            Log.w(TAG, "Could not delete stale temp file " + temp.getAbsolutePath());
        }
        try (FileInputStream in = new FileInputStream(source);
                FileOutputStream out = new FileOutputStream(temp)) {
            int read;
            while ((read = in.read(buffer)) >= 0) {
                if (read == 0) {
                    continue;
                }
                out.write(buffer, 0, read);
            }
            out.flush();
            out.getFD().sync();
            if (target.exists() && !target.delete()) {
                throw new IOException("delete target failed: " + target.getAbsolutePath());
            }
            if (!temp.renameTo(target)) {
                throw new IOException("rename failed: " + temp.getAbsolutePath() + " -> " + target.getAbsolutePath());
            }
            if (!DashcamStorageManager.syncDirectory(target.getParentFile())) throw new IOException("event directory sync failed");
            return true;
        } catch (Throwable t) {
            Log.e(TAG, "Failed to copy " + source.getAbsolutePath() + " -> " + target.getAbsolutePath(), t);
            // noinspection ResultOfMethodCallIgnored
            temp.delete();
        }
        return false;
    }

    private void cleanupOldSegments(File baseDir, int keepSegments, Set<String> protectedBases) {
        boolean changed = false;
        for (File file : RecordingFiles.deletionCandidates(baseDir.listFiles(), keepSegments,
                protectedBases == null ? java.util.Collections.emptySet() : protectedBases)) {
            if (DashcamStorageManager.deleteFreeingSpace(file)) changed = true;
            else publishStatus(STATUS_ERROR, 0, TOTAL_CAMERAS, "Could not delete " + file);
        }
        if (changed && !DashcamStorageManager.syncDirectory(baseDir)) {
            publishStatus(STATUS_ERROR, 0, TOTAL_CAMERAS, "Deletion sync failed: " + baseDir);
        }
    }

    private void trimOldEventDirsLocked(File eventsBaseDir) {
        if (eventsBaseDir == null) return;
        File[] files = eventsBaseDir.listFiles();
        if (files == null) return;
        List<File> eventDirs = new ArrayList<>();
        for (File f : files) {
            if (f.isDirectory() && f.getName().startsWith("event_")) {
                eventDirs.add(f);
            }
        }
        int maxRetained = DashcamStorageManager.getActiveMaxRetainedEventDirs(prefs(), activeBaseIsUsb);
        if (eventDirs.size() <= maxRetained) return;
        // event_<yyMMddHHmmssSSS> sorts chronologically by name.
        eventDirs.sort(Comparator.comparing(File::getName));
        Set<String> pendingNames = new HashSet<>();
        for (EventCaptureRequest req : pendingEventRequests) {
            pendingNames.add(req.eventBaseName);
        }
        int toDelete = eventDirs.size() - maxRetained;
        int deleted = 0;
        for (File dir : eventDirs) {
            if (deleted >= toDelete) break;
            // Never delete a dir whose copy jobs may still write into it.
            if (pendingNames.contains(dir.getName())) continue;
            if (deleteDirRecursive(dir)) {
                DevRuntimeLog.add("RecordingService", "Event cap: deleted " + dir.getName());
                deleted++;
            } else {
                Log.w(TAG, "Failed to delete old event dir " + dir.getAbsolutePath());
            }
        }
    }

    private boolean deleteDirRecursive(File f) {
        if (f == null) return false;
        if (f.isDirectory()) {
            File[] children = f.listFiles();
            if (children != null) {
                for (File c : children) {
                    deleteDirRecursive(c);
                }
            }
        }
        return f.isDirectory() ? f.delete() : DashcamStorageManager.deleteFreeingSpace(f);
    }

    private File getEventsBaseDir() {
        File dir = new File(getActiveRecordsBaseDir(), "events");
        return ensureDirectoryExists(dir, "events base dir") ? dir : null;
    }

    private void notifyEventStorageFailure() {
        DashcamEventOverlayService.showRecordingError(
                this,
                R.string.dashcam_recording_error_overlay_subtitle_storage,
                R.string.notification_dashcam_recording_error_storage_text);
    }

    /**
     * Resolves the recording target via {@link DashcamStorageManager} and updates the
     * service-wide active dir. Returns null when no usable target exists (which also
     * publishes the matching error status).
     *
     * @param initial true on the first resolution of a recording session — suppresses
     *                the USB↔internal transition banner that only makes sense mid-session.
     */
    private File resolveActiveBaseDir(boolean initial) {
        DashcamStorageManager.Resolution res = SystemClock.elapsedRealtime() < usbRetryAfter
                && DashcamStorageManager.getStorageTarget(prefs()) == DashcamStorageManager.TARGET_AUTO
                ? DashcamStorageManager.internalAfterWriteFailure(this) : DashcamStorageManager.resolve(this);
        if (res.baseDir == null) {
            DevRuntimeLog.add("RecordingService", "Storage resolve failed: " + res.usbState);
            publishStatus(STATUS_ERROR, 0, TOTAL_CAMERAS, ERROR_USB_STORAGE + ": " + res.usbState);
            return null;
        }
        if (!ensureDirectoryExists(res.baseDir, "records base dir") || !res.baseDir.canWrite()) {
            publishStatus(STATUS_ERROR, 0, TOTAL_CAMERAS, ERROR_STORAGE_NOT_WRITABLE);
            return null;
        }
        boolean wasUsb = activeBaseIsUsb;
        boolean hadPrevious = activeBaseDir != null;
        activeBaseDir = res.baseDir;
        activeBaseIsUsb = res.usingUsb;
        prefs().edit().putString("dashcamActualPath", res.baseDir.getAbsolutePath())
                .putString("dashcamStorageDiagnostic", res.usbState.toString())
                .putBoolean("dashcamActualUsb", res.usingUsb).apply();
        if (!initial && hadPrevious) {
            if (wasUsb && !res.usingUsb) {
                DevRuntimeLog.add("RecordingService",
                        "USB storage lost (" + res.usbState + ") => falling back to internal");
                DashcamEventOverlayService.showRecordingError(
                        this,
                        R.string.dashcam_recording_error_overlay_subtitle_usb_fallback,
                        R.string.notification_dashcam_recording_error_usb_fallback_text);
            } else if (!wasUsb && res.usingUsb) {
                DevRuntimeLog.add("RecordingService", "USB storage available again => switching back to USB");
            }
        }
        return res.baseDir;
    }

    /**
     * Called after a failed segment. Decides whether the failure was caused by the USB medium
     * (as opposed to the camera pipeline) and whether the configured mode allows recovering
     * from it by falling back to internal storage.
     */
    private boolean isRecoverableUsbFailure() {
        if (!activeBaseIsUsb) {
            return false;
        }
        if (CameraProbe.writtenSamples() >= 0 && DashcamStorageManager.isUsbStillWritable(activeBaseDir)) {
            // Storage is fine — this is a genuine camera/encoder failure.
            return false;
        }
        int target = DashcamStorageManager.getStorageTarget(prefs());
        if (target == DashcamStorageManager.TARGET_USB_ONLY) {
            DevRuntimeLog.add("RecordingService", "USB storage failed in USB-only mode => stopping");
            publishStatus(STATUS_ERROR, 0, TOTAL_CAMERAS, ERROR_USB_STORAGE);
            return false;
        }
        usbRetryAfter = SystemClock.elapsedRealtime() + 60_000;
        watchdogFailed = false;
        DevRuntimeLog.add("RecordingService", "USB storage failed in auto mode => internal; retry USB in 60s");
        return true;
    }

    private File getActiveRecordsBaseDir() {
        File dir = activeBaseDir;
        return dir != null ? dir : DashcamSettingsController.getRecordsBaseDir(this);
    }

    private boolean ensureDirectoryExists(File dir, String label) {
        if (dir == null) {
            return false;
        }
        if (dir.exists()) {
            if (dir.isDirectory()) {
                return true;
            }
            Log.e(TAG, label + " exists but is not a directory: " + dir.getAbsolutePath());
            return false;
        }
        if (dir.mkdirs()) {
            return true;
        }
        boolean created = dir.exists() && dir.isDirectory();
        if (!created) {
            Log.e(TAG, "Failed to create " + label + ": " + dir.getAbsolutePath());
        }
        return created;
    }

    private String makeTimestampBase(long epochMs, String pattern) {
        return new SimpleDateFormat(pattern, Locale.US).format(epochMs);
    }

    private void shutdownRecordingService() {
        usbEjectInProgress = true; // Keep the instance alive until its writer has released ownership.
        shutdownRecordingServiceWithoutStopSelf();
        new Thread(() -> {
            boolean quiet = awaitShutdownQuiescence();
            DevRuntimeLog.add(TAG, "stop drained=" + quiet);
            Thread current = worker;
            if (!quiet && current != null && current.isAlive()) {
                publishStatus(STATUS_ERROR, 0, TOTAL_CAMERAS, "Recorder still stopping; writes not quiescent");
                return; // Do not allow a second worker to acquire the same native recorder.
            }
            stopForeground(true);
            stopSelf();
        }, "RecordingServiceStop").start();
    }

    private void shutdownRecordingServiceWithoutStopSelf() {
        stopRequested = true;
        segmentStopRequested = true;
        oemPauseRequested = false;
        synchronized (stateLock) {
            stateLock.notifyAll();
        }
        publishStatus(STATUS_OFF, 0, TOTAL_CAMERAS, "");
        if (worker != null) {
            worker.interrupt();
        }
    }

    private boolean awaitShutdownQuiescence() {
        long deadline = SystemClock.elapsedRealtime() + 7500;
        Thread current = worker;
        try {
            if (current != null) current.join(Math.max(1, remaining(deadline)));
            if (current != null && current.isAlive()) return false;
            eventCopyExecutor.shutdown();
            if (!eventCopyExecutor.awaitTermination(remaining(deadline), TimeUnit.MILLISECONDS)) return false;
            if (!StorageIoGate.awaitIdle(remaining(deadline))) return false;
            if (!CameraProbe.awaitPendingFlushes((int) remaining(deadline))) return false;
            ExecutorService sync = Executors.newSingleThreadExecutor();
            try {
                Future<Boolean> result = sync.submit(() -> DashcamStorageManager.syncDirectory(activeBaseDir));
                return result.get(Math.max(1, remaining(deadline)), TimeUnit.MILLISECONDS);
            } finally { sync.shutdown(); }
        } catch (Exception e) {
            DevRuntimeLog.add(TAG, "drain incomplete: " + e);
            return false;
        }
    }

    private void stopServiceIfNotEjecting() {
        if (worker != null || surveillanceSession != null || usbEjectInProgress
                || (!stopRequested && STATUS_ERROR.equals(prefs().getString(KEY_STATUS, STATUS_OFF)))) {
            return;
        }
        stopForeground(true);
        stopSelf();
    }

    private void broadcastUsbEjectReady(boolean safeToRemove, int messageRes) {
        Intent intent = new Intent(ACTION_USB_EJECT_READY).setPackage(getPackageName());
        intent.putExtra(EXTRA_USB_EJECT_SAFE_TO_REMOVE, safeToRemove);
        intent.putExtra(EXTRA_USB_EJECT_MESSAGE_RES, messageRes);
        sendBroadcast(intent);
    }

    private Notification buildNotification(String text) {
        if (surveillanceSession != null) {
            int levelRes;
            switch (SurveillancePolicy.storageLevel(remoteFreeBytes)) {
                case "very_low": levelRes = R.string.surveillance_storage_very_low; break;
                case "low": levelRes = R.string.surveillance_storage_low; break;
                case "limited": levelRes = R.string.surveillance_storage_limited; break;
                case "ok": levelRes = R.string.surveillance_storage_ok; break;
                default: levelRes = R.string.surveillance_storage_unknown;
            }
            int stateRes;
            switch (remoteState) {
                case "started": stateRes = R.string.surveillance_state_started; break;
                case "paused": stateRes = R.string.surveillance_state_paused; break;
                case "error": stateRes = R.string.surveillance_state_error; break;
                default: stateRes = R.string.surveillance_state_starting;
            }
            text = getString(R.string.notification_surveillance_status, getString(stateRes), getString(levelRes));
        }
        NotificationCompat.Builder b = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(text)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW);
        if (surveillanceSession != null) b.setStyle(new NotificationCompat.BigTextStyle().bigText(text));
        return b.build();
    }

    public static final class PersistedStatus {
        public final String status;
        public final int activeCameras;
        public final int totalCameras;
        public final String lastError;

        PersistedStatus(String status, int activeCameras, int totalCameras, String lastError) {
            this.status = status;
            this.activeCameras = activeCameras;
            this.totalCameras = totalCameras;
            this.lastError = lastError;
        }
    }

    public static PersistedStatus readPersistedStatus(SharedPreferences prefs) {
        return new PersistedStatus(
                prefs.getString(KEY_STATUS, STATUS_OFF),
                prefs.getInt(KEY_ACTIVE_CAMERAS, 0),
                prefs.getInt(KEY_TOTAL_CAMERAS, TOTAL_CAMERAS),
                prefs.getString(KEY_LAST_ERROR, ""));
    }

    public static String formatStatusText(Context context, String status, int activeCameras, int totalCameras, String lastError) {
        if (status == null || STATUS_OFF.equals(status)) {
            return context.getString(R.string.settings_dashcam_status_off);
        }
        if (STATUS_RECORDING.equals(status)) {
            return context.getString(R.string.settings_dashcam_status_recording, activeCameras, totalCameras);
        }
        if (STATUS_PAUSED_OEM.equals(status)) {
            return context.getString(R.string.settings_dashcam_status_paused_oem);
        }
        if (STATUS_STARTING.equals(status)) {
            return context.getString(R.string.settings_dashcam_status_starting);
        }
        String error = lastError == null || lastError.trim().isEmpty() ? status : lastError.trim();
        return context.getString(R.string.settings_dashcam_status_error, error);
    }

    /**
     * If the prefs say we were recording but the service isn't actually running (e.g. crash,
     * OOM kill), reset the persisted state so the UI doesn't show a stale "RECORDING" pill.
     */
    public static void resetPersistedStatusIfStale(SharedPreferences prefs) {
        String status = prefs.getString(KEY_STATUS, STATUS_OFF);
        if (status == null || STATUS_OFF.equals(status) || isRunning()) return;
        prefs.edit()
                .putString(KEY_STATUS, STATUS_OFF)
                .putInt(KEY_ACTIVE_CAMERAS, 0)
                .putInt(KEY_TOTAL_CAMERAS, TOTAL_CAMERAS)
                .putString(KEY_LAST_ERROR, "")
                .apply();
    }

    private void publishCurrentStatus() {
        PersistedStatus s = readPersistedStatus(prefs());
        publishStatus(s.status, s.activeCameras, s.totalCameras, s.lastError);
    }

    private void publishStatus(String status, int activeCameras, int totalCameras, String lastError) {
        if (status == null)
            status = STATUS_OFF;
        if (lastError == null)
            lastError = "";
        SurveillanceSession session = surveillanceSession;
        if (surveillanceOwnsRecorder && session != null && session.stopReason() == null) {
            String next = remoteState;
            if (STATUS_RECORDING.equals(status)) next = "started";
            else if (STATUS_PAUSED_OEM.equals(status)) next = "paused";
            else if (STATUS_STARTING.equals(status) && !"started".equals(remoteState)) next = "starting";
            else if (STATUS_ERROR.equals(status)) {
                next = "error";
                requestSurveillanceStop(ERROR_GRID_START_FAILED.equals(lastError) ? "camera_start_failed" : "recording_failed", true, false);
            }
            if (!next.equals(remoteState)) {
                remoteState = next;
                remoteReason = "error".equals(next) ? "recording_failed" : "paused".equals(next) ? "oem_camera" : "";
                emitRemoteState(session.requestId);
            }
        }
        prefs().edit()
                .putString(KEY_STATUS, status)
                .putInt(KEY_ACTIVE_CAMERAS, Math.max(0, activeCameras))
                .putInt(KEY_TOTAL_CAMERAS, Math.max(0, totalCameras))
                .putString(KEY_LAST_ERROR, lastError)
                .apply();

        String notificationText;
        if (STATUS_RECORDING.equals(status)) {
            notificationText = getString(R.string.notification_recording_status, activeCameras, totalCameras);
        } else if (STATUS_PAUSED_OEM.equals(status)) {
            notificationText = getString(R.string.notification_recording_paused_oem);
        } else if (STATUS_PARTIAL.equals(status) || STATUS_ERROR.equals(status)) {
            notificationText = getString(R.string.notification_recording_error, lastError);
        } else if (STATUS_STARTING.equals(status)) {
            notificationText = getString(R.string.notification_recording_starting);
        } else {
            notificationText = "";
        }

        if (!notificationText.isEmpty()) {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) {
                nm.notify(NOTIF_ID, buildNotification(notificationText));
            }
        }

        if (surveillanceSession == null) updateDashcamOverlayState(status, lastError);
        if (statusIcon != null) statusIcon.update(prefs().getBoolean("dashcamStatusIcon", false) ? status : null);

        Intent intent = new Intent(ACTION_STATUS_CHANGED);
        intent.setPackage(getPackageName());
        intent.putExtra(EXTRA_STATUS, status);
        intent.putExtra(EXTRA_ACTIVE_CAMERAS, activeCameras);
        intent.putExtra(EXTRA_TOTAL_CAMERAS, totalCameras);
        intent.putExtra(EXTRA_LAST_ERROR, lastError);
        sendBroadcast(intent);
    }

    private void updateDashcamOverlayState(String status, String lastError) {
        if (STATUS_ERROR.equals(status) || STATUS_PARTIAL.equals(status)) {
            scheduleDelayedErrorOverlay(lastError);
            return;
        }

        cancelPendingErrorOverlay();
        if (errorOverlayShown && STATUS_RECORDING.equals(status)) {
            errorOverlayShown = false;
            DashcamEventOverlayService.showRecordingRecovered(this);
        } else if (!STATUS_RECORDING.equals(status)) {
            errorOverlayShown = false;
        }
    }

    private void scheduleDelayedErrorOverlay(String lastError) {
        OverlayMessageSpec spec = mapRecordingError(lastError);
        if (spec == null) {
            cancelPendingErrorOverlay();
            return;
        }
        if (errorOverlayShown
                && pendingErrorSubtitleResId == spec.subtitleResId
                && pendingErrorNotificationResId == spec.notificationTextResId) {
            return;
        }

        pendingErrorSubtitleResId = spec.subtitleResId;
        pendingErrorNotificationResId = spec.notificationTextResId;
        final int generation = ++pendingErrorGeneration;
        mainHandler.removeCallbacksAndMessages(this);
        mainHandler.postAtTime(() -> {
            if (generation != pendingErrorGeneration) {
                return;
            }
            errorOverlayShown = true;
            DashcamEventOverlayService.showRecordingError(
                    RecordingService.this,
                    pendingErrorSubtitleResId,
                    pendingErrorNotificationResId);
        }, this, SystemClock.uptimeMillis() + ERROR_OVERLAY_DELAY_MS);
    }

    private void cancelPendingErrorOverlay() {
        pendingErrorGeneration++;
        pendingErrorSubtitleResId = 0;
        pendingErrorNotificationResId = 0;
        mainHandler.removeCallbacksAndMessages(this);
    }

    private OverlayMessageSpec mapRecordingError(String lastError) {
        if (ERROR_STORAGE_NOT_WRITABLE.equals(lastError)) {
            return new OverlayMessageSpec(
                    R.string.dashcam_recording_error_overlay_subtitle_storage,
                    R.string.notification_dashcam_recording_error_storage_text);
        }
        if (ERROR_GRID_START_FAILED.equals(lastError)) {
            return new OverlayMessageSpec(
                    R.string.dashcam_recording_error_overlay_subtitle_start_failed,
                    R.string.notification_dashcam_recording_error_start_failed_text);
        }
        if (ERROR_GRID_STOP_TIMEOUT.equals(lastError)) {
            return new OverlayMessageSpec(
                    R.string.dashcam_recording_error_overlay_subtitle_stop_failed,
                    R.string.notification_dashcam_recording_error_stop_failed_text);
        }
        if (ERROR_USB_STORAGE.equals(lastError)) {
            return new OverlayMessageSpec(
                    R.string.dashcam_recording_error_overlay_subtitle_usb_unavailable,
                    R.string.notification_dashcam_recording_error_usb_unavailable_text);
        }
        return new OverlayMessageSpec(
                R.string.dashcam_recording_error_overlay_subtitle_generic,
                R.string.notification_dashcam_recording_error_text);
    }

    private SharedPreferences prefs() {
        return UiPrefs.getPrefs(this);
    }

    private void restoreEventState() {
        SharedPreferences prefs = prefs();
        List<EventCopyJob> copyJobs;
        synchronized (eventLock) {
            completedSegmentCount = prefs.getLong(KEY_EVENT_COMPLETED_SEGMENT_COUNT, 0L);
            recentSegments.clear();
            pendingEventRequests.clear();

            try {
                JSONArray segmentsJson = new JSONArray(prefs.getString(KEY_EVENT_RECENT_SEGMENTS, "[]"));
                for (int i = 0; i < segmentsJson.length(); i++) {
                    JSONObject obj = segmentsJson.optJSONObject(i);
                    if (obj == null) {
                        continue;
                    }
                    String baseName = obj.optString("baseName", "");
                    long ordinal = obj.optLong("ordinal", -1L);
                    long startMs = obj.optLong("startMs", 0L);
                    long endMs = obj.optLong("endMs", 0L);
                    String sourceDir = obj.optString("sourceDir", "");
                    if (baseName.isEmpty() || ordinal <= 0L) {
                        continue;
                    }
                    recentSegments.add(new SegmentInfo(ordinal, baseName, startMs, endMs, sourceDir));
                }
            } catch (Throwable t) {
                Log.w(TAG, "Failed to restore recent segment state", t);
                recentSegments.clear();
            }

            try {
                JSONArray requestsJson = new JSONArray(prefs.getString(KEY_EVENT_PENDING_REQUESTS, "[]"));
                for (int i = 0; i < requestsJson.length(); i++) {
                    JSONObject obj = requestsJson.optJSONObject(i);
                    if (obj == null) {
                        continue;
                    }
                    String eventBaseName = obj.optString("eventBaseName", "");
                    long firstSegmentOrdinal = obj.optLong("firstSegmentOrdinal", -1L);
                    long lastSegmentOrdinal = obj.optLong("lastSegmentOrdinal", -1L);
                    if (eventBaseName.isEmpty() || firstSegmentOrdinal <= 0L || lastSegmentOrdinal < firstSegmentOrdinal) {
                        continue;
                    }
                    EventCaptureRequest request =
                            new EventCaptureRequest(eventBaseName, firstSegmentOrdinal, lastSegmentOrdinal);
                    JSONArray copiedJson = obj.optJSONArray("copiedBaseNames");
                    if (copiedJson != null) {
                        for (int j = 0; j < copiedJson.length(); j++) {
                            String copiedBaseName = copiedJson.optString(j, "");
                            if (!copiedBaseName.isEmpty()) {
                                request.copiedBaseNames.add(copiedBaseName);
                            }
                        }
                    }
                    pendingEventRequests.add(request);
                }
            } catch (Throwable t) {
                Log.w(TAG, "Failed to restore pending event requests", t);
                pendingEventRequests.clear();
            }
            copyJobs = collectEventCopyJobsLocked(completedSegmentCount);
            persistEventStateLocked();
        }
        enqueueEventCopyJobs(copyJobs);
    }

    private void persistEventStateLocked() {
        JSONArray segmentsJson = new JSONArray();
        for (SegmentInfo segment : recentSegments) {
            JSONObject obj = new JSONObject();
            try {
                obj.put("ordinal", segment.ordinal);
                obj.put("baseName", segment.baseName);
                obj.put("startMs", segment.startMs);
                obj.put("endMs", segment.endMs);
                obj.put("sourceDir", segment.sourceDirPath);
                segmentsJson.put(obj);
            } catch (Throwable ignored) {
            }
        }

        JSONArray requestsJson = new JSONArray();
        for (EventCaptureRequest request : pendingEventRequests) {
            JSONObject obj = new JSONObject();
            try {
                obj.put("eventBaseName", request.eventBaseName);
                obj.put("firstSegmentOrdinal", request.firstSegmentOrdinal);
                obj.put("lastSegmentOrdinal", request.lastSegmentOrdinal);
                JSONArray copiedJson = new JSONArray();
                for (String copiedBaseName : request.copiedBaseNames) {
                    copiedJson.put(copiedBaseName);
                }
                obj.put("copiedBaseNames", copiedJson);
                requestsJson.put(obj);
            } catch (Throwable ignored) {
            }
        }

        prefs().edit()
                .putLong(KEY_EVENT_COMPLETED_SEGMENT_COUNT, completedSegmentCount)
                .putString(KEY_EVENT_RECENT_SEGMENTS, segmentsJson.toString())
                .putString(KEY_EVENT_PENDING_REQUESTS, requestsJson.toString())
                .apply();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        destroyed = true;
        requestSurveillanceStop("service_destroyed", true, true);
        sServiceRunning = false;
        instance = null;
        mainHandler.removeCallbacks(superviseTick);
        mainHandler.removeCallbacks(surveillanceDeadline);
        supervisor.shutdown();
        prefs().unregisterOnSharedPreferenceChangeListener(iconPreferenceListener);
        if (statusIcon != null) statusIcon.hide();
        if (shutdownReceiver != null) unregisterReceiver(shutdownReceiver);
        stopRequested = true;
        cancelPendingErrorOverlay();
        eventCopyExecutor.shutdown();
        if (worker != null) {
            worker.interrupt();
            try {
                worker.join(1000);
            } catch (InterruptedException ignored) {
            }
        }
        try {
            eventCopyExecutor.awaitTermination(1000, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ignored) {
        }
        super.onDestroy();
    }

    private final List<SegmentInfo> recentSegments = new ArrayList<>();
    private final List<EventCaptureRequest> pendingEventRequests = new ArrayList<>();

    private static final class SegmentInfo {
        final long ordinal;
        final String baseName;
        final long startMs;
        final long endMs;
        // Absolute path of the records root this segment was written into. Segments recorded
        // before a USB→internal fallback live in a different root than the currently active
        // one, so event copies must read from here, not from the active dir.
        final String sourceDirPath;

        SegmentInfo(long ordinal, String baseName, long startMs, long endMs, String sourceDirPath) {
            this.ordinal = ordinal;
            this.baseName = baseName;
            this.startMs = startMs;
            this.endMs = endMs;
            this.sourceDirPath = sourceDirPath == null ? "" : sourceDirPath;
        }
    }

    private static final class SegmentCopyRef {
        final String baseName;
        final String sourceDirPath;

        SegmentCopyRef(String baseName, String sourceDirPath) {
            this.baseName = baseName;
            this.sourceDirPath = sourceDirPath == null ? "" : sourceDirPath;
        }
    }

    private static final class EventCaptureRequest {
        final String eventBaseName;
        final long firstSegmentOrdinal;
        final long lastSegmentOrdinal;
        final Set<String> copiedBaseNames = new HashSet<>();
        final Set<String> inFlightBaseNames = new HashSet<>();

        EventCaptureRequest(String eventBaseName, long firstSegmentOrdinal, long lastSegmentOrdinal) {
            this.eventBaseName = eventBaseName;
            this.firstSegmentOrdinal = firstSegmentOrdinal;
            this.lastSegmentOrdinal = lastSegmentOrdinal;
        }
    }

    private static final class EventCopyJob {
        final String eventBaseName;
        final List<SegmentCopyRef> segments;

        EventCopyJob(String eventBaseName, List<SegmentCopyRef> segments) {
            this.eventBaseName = eventBaseName;
            this.segments = segments;
        }

        List<String> baseNames() {
            List<String> names = new ArrayList<>(segments.size());
            for (SegmentCopyRef ref : segments) {
                names.add(ref.baseName);
            }
            return names;
        }
    }

    private static final class OverlayMessageSpec {
        final int subtitleResId;
        final int notificationTextResId;

        OverlayMessageSpec(int subtitleResId, int notificationTextResId) {
            this.subtitleResId = subtitleResId;
            this.notificationTextResId = notificationTextResId;
        }
    }
}
