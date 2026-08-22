package com.drivehub.kamera.dashcam;

import com.drivehub.kamera.R;

import com.drivehub.kamera.CameraProbe;
import com.drivehub.kamera.dev.DevRuntimeLog;
import com.drivehub.kamera.helper.app.NotificationChannelHelper;
import com.drivehub.kamera.helper.vehiclesensors.VehicleSpeedReader;
import com.drivehub.kamera.settings.UiPrefs;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.documentfile.provider.DocumentFile;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
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
    private volatile DashcamStorageManager.Resolution activeStorage;
    private volatile boolean activeBaseIsUsb;
    private volatile boolean usbEjectInProgress = false;
    private volatile boolean futureOnlyEventSession = false;
    private long completedSegmentCount = 0L;

    public static boolean isRunning() {
        return sServiceRunning;
    }

    public static void startIfDashcamEnabled(Context context) {
        SharedPreferences prefs = UiPrefs.getPrefs(context);
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
        if (!prefs.getBoolean(DashcamSettingsController.KEY_ENABLED, false) || !isRunning()) {
            return;
        }
        Intent i = new Intent(context, RecordingService.class);
        i.setAction(ACTION_PAUSE_FOR_OEM_REQUEST);
        context.startForegroundService(i);
    }

    public static void resumeAfterOemRequest(Context context) {
        SharedPreferences prefs = UiPrefs.getPrefs(context);
        if (!prefs.getBoolean(DashcamSettingsController.KEY_ENABLED, false) || !isRunning()) {
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
        NotificationChannelHelper.ensureChannel(this, CHANNEL_ID, R.string.notification_channel_recording);
        restoreEventState();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null)
            return START_STICKY;
        String action = intent.getAction();

        if (ACTION_STOP.equals(action)) {
            DevRuntimeLog.add("RecordingService", "ACTION_STOP");
            usbEjectInProgress = false;
            shutdownRecordingService();
            return START_NOT_STICKY;
        }

        if (ACTION_EJECT_USB.equals(action)) {
            DevRuntimeLog.add("RecordingService", "ACTION_EJECT_USB");
            boolean usingUsb = activeBaseIsUsb;
            if (!usingUsb) {
                DashcamStorageManager.Resolution res = DashcamStorageManager.resolve(this);
                usingUsb = res.usingUsb;
            }
            if (!usingUsb) {
                broadcastUsbEjectReady(false, R.string.settings_dashcam_storage_eject_unavailable_message);
                return START_NOT_STICKY;
            }
            usbEjectInProgress = true;
            shutdownRecordingServiceWithoutStopSelf();
            new Thread(() -> {
                boolean safeToRemove = awaitShutdownQuiescence();
                broadcastUsbEjectReady(
                        safeToRemove,
                        safeToRemove
                                ? R.string.settings_dashcam_storage_eject_ready_message
                                : R.string.settings_dashcam_storage_eject_unavailable_message);
                stopForeground(true);
                stopSelf();
            }, "RecordingServiceUsbEject").start();
            return START_NOT_STICKY;
        }

        if (ACTION_PAUSE_FOR_OEM_REQUEST.equals(action)) {
            DevRuntimeLog.add("RecordingService", "ACTION_PAUSE_FOR_OEM_REQUEST");
            boolean enabled = prefs().getBoolean(DashcamSettingsController.KEY_ENABLED, false);
            boolean changed = !oemPauseRequested;
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
                if (changed) {
                    DashcamEventOverlayService.showOemPause(this);
                }
            }
            return START_STICKY;
        }

        if (ACTION_RESUME_AFTER_OEM_REQUEST.equals(action)) {
            DevRuntimeLog.add("RecordingService", "ACTION_RESUME_AFTER_OEM_REQUEST");
            boolean wasPaused = STATUS_PAUSED_OEM.equals(prefs().getString(KEY_STATUS, STATUS_OFF));
            oemPauseRequested = false;
            segmentStopRequested = false;
            synchronized (stateLock) {
                stateLock.notifyAll();
            }
            boolean enabled = prefs().getBoolean(DashcamSettingsController.KEY_ENABLED, false);
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
                if (wasPaused) {
                    DashcamEventOverlayService.showOemResume(this);
                }
                worker = new Thread(this::recordLoop, "RecordingServiceWorker");
                worker.start();
            } else if (wasPaused) {
                publishStatus(STATUS_STARTING, 0, TOTAL_CAMERAS, "");
                DashcamEventOverlayService.showOemResume(this);
            }
            return START_STICKY;
        }

        if (ACTION_TRIGGER_EVENT_SAVE.equals(action)) {
            DevRuntimeLog.add("RecordingService", "ACTION_TRIGGER_EVENT_SAVE");
            boolean allowFutureOnly = intent.getBooleanExtra(EXTRA_EVENT_ALLOW_FUTURE_ONLY, false);
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
            if (!prefs().getBoolean(DashcamSettingsController.KEY_ENABLED, false)) {
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

        stopRequested = false;
        DevRuntimeLog.add("RecordingService", action == null ? "ACTION_START(null)" : action);
        startForeground(NOTIF_ID, buildNotification(getString(R.string.notification_recording_starting)));
        publishStatus(STATUS_STARTING, 0, TOTAL_CAMERAS, "");
        if (ACTION_RECORD_TEST.equals(action)) {
            int durationSec = intent.getIntExtra(
                    EXTRA_TEST_RECORD_DURATION_SEC,
                    DashcamSettingsController.getTestRecordDurationSec(prefs()));
            long durationMs = Math.max(0L, durationSec * 1000L);
            worker = new Thread(() -> recordTestClip(durationMs), "RecordingServiceTestWorker");
        } else {
            worker = new Thread(this::recordLoop, "RecordingServiceWorker");
        }
        worker.start();
        return START_STICKY;
    }

    private void recordTestClip(long durationMs) {
        DashcamStorageManager.Resolution storage = resolveActiveStorage(true);
        if (storage == null) {
            worker = null;
            stopForeground(true);
            stopSelf();
            return;
        }
        boolean startedAnyCamera = recordClip(storage, "test", durationMs,
                makeTimestampBase(System.currentTimeMillis(), "yyMMddHHmmss"));
        worker = null;
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
            worker = null;
            stopSelf();
            return;
        }

        DashcamStorageManager.Resolution storage = resolveActiveStorage(true);
        if (storage == null) {
            worker = null;
            stopSelf();
            return;
        }

        long segmentMs = segmentSec * 1000L;

        boolean endedWithFatalError = false;

        while (!stopRequested) {
            if (!waitForOemPauseToClear(prefs)) {
                break;
            }
            // Re-resolve between segments so USB hot-plug/unplug and settings changes are
            // picked up. AUTO mode switches targets with a banner; USB_ONLY mode turns a
            // missing medium into a fatal error.
            DashcamStorageManager.Resolution resolved = resolveActiveStorage(false);
            if (resolved == null) {
                endedWithFatalError = true;
                break;
            }
            storage = resolved;

            // Read every iteration so settings edits take effect between segments. USB and
            // internal storage carry separate retention limits.
            int keepSegments = DashcamStorageManager.getActiveRetentionClipCount(prefs, activeBaseIsUsb);
            long segmentStartWallMs = System.currentTimeMillis();
            String baseName = makeTimestampBase(segmentStartWallMs, "yyMMddHHmmss");
            boolean startedAnyCamera = recordClip(storage, null, segmentMs, baseName);
            if (!startedAnyCamera) {
                if (isRecoverableUsbFailure()) {
                    // AUTO mode: the segment failed because USB died. The next loop pass
                    // re-resolves to internal storage and shows the fallback banner.
                    continue;
                }
                endedWithFatalError = true;
                break;
            }
            onSegmentCompleted(storage, baseName, segmentStartWallMs, System.currentTimeMillis(), keepSegments);

            // Check whether recording has been disabled in prefs.
            enabled = prefs.getBoolean(DashcamSettingsController.KEY_ENABLED, false);
            if (!enabled)
                break;
        }

        worker = null;
        if (!endedWithFatalError) {
            publishStatus(STATUS_OFF, 0, TOTAL_CAMERAS, "");
        }
        stopServiceIfNotEjecting();
    }

    private void recordFutureOnlyEventLoop() {
        int segmentSec = DashcamSettingsController.getSegmentDurationSec();
        if (segmentSec <= 0) {
            publishStatus(STATUS_OFF, 0, TOTAL_CAMERAS, "");
            futureOnlyEventSession = false;
            worker = null;
            stopSelf();
            return;
        }

        DashcamStorageManager.Resolution storage = resolveActiveStorage(true);
        if (storage == null) {
            futureOnlyEventSession = false;
            worker = null;
            stopSelf();
            return;
        }

        long segmentMs = segmentSec * 1000L;
        boolean endedWithFatalError = false;
        int remainingSegments = FUTURE_ONLY_EVENT_SEGMENTS;

        while (!stopRequested && remainingSegments > 0) {
            DashcamStorageManager.Resolution resolved = resolveActiveStorage(false);
            if (resolved == null) {
                endedWithFatalError = true;
                break;
            }
            storage = resolved;

            int keepSegments = DashcamStorageManager.getActiveRetentionClipCount(prefs(), activeBaseIsUsb);
            long segmentStartWallMs = System.currentTimeMillis();
            String baseName = makeTimestampBase(segmentStartWallMs, "yyMMddHHmmss");
            boolean startedAnyCamera = recordClip(storage, null, segmentMs, baseName);
            if (!startedAnyCamera) {
                if (isRecoverableUsbFailure()) {
                    continue;
                }
                endedWithFatalError = true;
                break;
            }
            onSegmentCompleted(storage, baseName, segmentStartWallMs, System.currentTimeMillis(), keepSegments);
            remainingSegments--;
        }

        futureOnlyEventSession = false;
        worker = null;
        if (!endedWithFatalError) {
            publishStatus(STATUS_OFF, 0, TOTAL_CAMERAS, "");
        }
        stopServiceIfNotEjecting();
    }

    private boolean recordClip(DashcamStorageManager.Resolution storage, @Nullable String subdir,
            long durationMs, String baseName) {
        SharedPreferences prefs = prefs();
        int recordingFps = DashcamSettingsController.getRecordingFps(prefs);
        String signature = DashcamSettingsController.getRecordingSignature(prefs);
        boolean showSpeed = DashcamSettingsController.shouldShowSpeed(prefs);
        int cameraMask = DashcamSettingsController.getRecordingCameraMask(prefs);
        int selectedCameraCount = DashcamSettingsController.getRecordingCameraCount(cameraMask);
        String fileName = baseName + ".mp4";
        File outputFile = null;
        DocumentFile outputDocument = null;
        boolean started = false;
        try {
            if (storage.isSaf()) {
                DocumentFile outputDir = resolveDocumentDirectory(storage.treeUri, subdir, true);
                if (outputDir == null) {
                    publishStatus(STATUS_ERROR, 0, TOTAL_CAMERAS, ERROR_STORAGE_NOT_WRITABLE);
                    return false;
                }
                DocumentFile existing = outputDir.findFile(fileName);
                if (existing != null) existing.delete();
                outputDocument = outputDir.createFile("video/mp4", fileName);
                if (outputDocument == null) {
                    publishStatus(STATUS_ERROR, 0, TOTAL_CAMERAS, ERROR_STORAGE_NOT_WRITABLE);
                    return false;
                }
                try (ParcelFileDescriptor pfd = getContentResolver()
                        .openFileDescriptor(outputDocument.getUri(), "rw")) {
                    if (pfd != null) {
                        started = CameraProbe.startCombinedMp4RecordFd(
                                pfd.getFd(), 720, 240, recordingFps, 9_000_000,
                                signature, showSpeed, cameraMask);
                    }
                }
            } else {
                File outputDir = subdir == null ? storage.baseDir : new File(storage.baseDir, subdir);
                if (!ensureDirectoryExists(outputDir, subdir == null ? "records base dir" : subdir + " dir")) {
                    publishStatus(STATUS_ERROR, 0, TOTAL_CAMERAS, ERROR_STORAGE_NOT_WRITABLE);
                    return false;
                }
                outputFile = new File(outputDir, fileName);
                started = CameraProbe.startCombinedMp4Record(
                        outputFile.getAbsolutePath(), 720, 240, recordingFps, 9_000_000,
                        signature, showSpeed, cameraMask);
            }
        } catch (Throwable t) {
            Log.e(TAG, "Could not open recording output " + storage.locationKey(), t);
            started = false;
        }

        if (!started) {
            if (outputDocument != null) outputDocument.delete();
            if (outputFile != null) outputFile.delete();
            publishStatus(STATUS_ERROR, 0, TOTAL_CAMERAS, ERROR_GRID_START_FAILED);
            return false;
        }

        publishStatus(STATUS_RECORDING, selectedCameraCount, TOTAL_CAMERAS, "");

        long start = SystemClock.elapsedRealtime();
        while (!stopRequested
                && !segmentStopRequested
                && (SystemClock.elapsedRealtime() - start) < durationMs) {
            if (showSpeed) {
                CameraProbe.updateCombinedRecordingSpeed(VehicleSpeedReader.readSpeedKmh());
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException ignored) {
            }
        }

        if (!CameraProbe.stopCombinedMp4Record()) {
            publishStatus(STATUS_ERROR, 0, TOTAL_CAMERAS, ERROR_GRID_STOP_TIMEOUT);
            return false;
        }

        return true;
    }

    private boolean waitForOemPauseToClear(SharedPreferences prefs) {
        boolean announcedPause = false;
        while (!stopRequested
                && prefs.getBoolean(DashcamSettingsController.KEY_ENABLED, false)
                && oemPauseRequested) {
            segmentStopRequested = true;
            if (!announcedPause) {
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
        if (stopRequested || !prefs.getBoolean(DashcamSettingsController.KEY_ENABLED, false)) {
            return false;
        }
        segmentStopRequested = false;
        if (announcedPause) {
            publishStatus(STATUS_STARTING, 0, TOTAL_CAMERAS, "");
        }
        return true;
    }

    private void onSegmentCompleted(DashcamStorageManager.Resolution storage,
            String baseName, long startMs, long endMs, int keepSegments) {
        List<EventCopyJob> copyJobs;
        synchronized (eventLock) {
            long segmentOrdinal = ++completedSegmentCount;
            recentSegments.add(new SegmentInfo(segmentOrdinal, baseName, startMs, endMs,
                    storage.locationKey()));
            while (recentSegments.size() > keepSegments + 6) {
                recentSegments.remove(0);
            }
            copyJobs = collectEventCopyJobsLocked(segmentOrdinal);
            Set<String> protectedBases = collectProtectedBasesLocked();
            cleanupOldSegments(storage, keepSegments, protectedBases);
            persistEventStateLocked();
        }
        enqueueEventCopyJobs(copyJobs);
    }

    private boolean startFutureOnlyEventSession() {
        if (resolveActiveStorage(true) == null) {
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
        worker = new Thread(this::recordFutureOnlyEventLoop, "RecordingServiceFutureEventWorker");
        worker.start();
        return true;
    }

    private boolean armEventCapture(int segmentsBeforeCurrent, int segmentsAfterCurrent) {
        long now = System.currentTimeMillis();
        String eventBaseName = "event_" + makeTimestampBase(now, "yyMMddHHmmssSSS");
        String eventTargetLocation = getActiveLocationKey();
        if (!ensureEventDirectory(eventTargetLocation, eventBaseName)) {
            DevRuntimeLog.add("RecordingService", "Event capture failed: events base dir unavailable or not writable");
            Log.e(TAG, "Failed to arm event capture because events base dir is unavailable or not writable");
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
                    lastSegmentOrdinal,
                    eventTargetLocation
            ));
            trimOldEventDirsLocked(eventTargetLocation);
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
                jobs.add(new EventCopyJob(request.eventBaseName, request.targetLocation, segments));
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
        String targetLocation = job.targetLocation.isEmpty() ? getActiveLocationKey() : job.targetLocation;
        if (!ensureEventDirectory(targetLocation, job.eventBaseName)) {
            DevRuntimeLog.add("RecordingService", "Event copy failed: events base dir unavailable or not writable");
            Log.e(TAG, "Failed to copy event segments because events base dir is unavailable or not writable");
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
            String sourceLocation = ref.sourceDirPath.isEmpty()
                    ? getActiveLocationKey()
                    : ref.sourceDirPath;
            if (copySegmentGroup(sourceLocation, targetLocation, job.eventBaseName, ref.baseName)) {
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

    private boolean copySegmentGroup(String sourceLocation, String targetLocation,
            String eventBaseName, String baseName) {
        String combinedName = baseName + ".mp4";
        if (recordingFileExists(sourceLocation, combinedName)) {
            return copyRecordingFile(sourceLocation, targetLocation, eventBaseName, combinedName);
        }
        boolean copiedAny = false;
        for (char suffix : new char[] { 'F', 'R', 'X', 'Y' }) {
            String name = baseName + "_" + suffix + ".mp4";
            if (recordingFileExists(sourceLocation, name)) {
                copiedAny |= copyRecordingFile(sourceLocation, targetLocation, eventBaseName, name);
            }
        }
        return copiedAny;
    }

    private boolean recordingFileExists(String location, String fileName) {
        if (isContentLocation(location)) {
            DocumentFile root = DashcamStorageManager.resolveTree(this, Uri.parse(location));
            DocumentFile file = root == null ? null : root.findFile(fileName);
            return file != null && file.isFile();
        }
        return !location.isEmpty() && new File(location, fileName).isFile();
    }

    private boolean copyRecordingFile(String sourceLocation, String targetLocation,
            String eventBaseName, String fileName) {
        try (InputStream in = openRecordingInput(sourceLocation, fileName)) {
            if (in == null) return false;
            if (isContentLocation(targetLocation)) {
                DocumentFile eventDir = resolveDocumentDirectory(
                        Uri.parse(targetLocation), "events/" + eventBaseName, true);
                if (eventDir == null) return false;
                DocumentFile existing = eventDir.findFile(fileName);
                if (existing != null) existing.delete();
                DocumentFile target = eventDir.createFile("video/mp4", fileName);
                if (target == null) return false;
                try (OutputStream out = getContentResolver().openOutputStream(target.getUri(), "w")) {
                    if (out == null) {
                        target.delete();
                        return false;
                    }
                    copyStream(in, out);
                    out.flush();
                    return true;
                } catch (Throwable t) {
                    target.delete();
                    throw t;
                }
            }
            File eventDir = new File(new File(targetLocation, "events"), eventBaseName);
            if (!ensureDirectoryExists(eventDir, "event dir")) return false;
            return copyStreamToFile(in, new File(eventDir, fileName));
        } catch (Throwable t) {
            Log.e(TAG, "Failed event copy " + sourceLocation + "/" + fileName
                    + " -> " + targetLocation, t);
            return false;
        }
    }

    @Nullable
    private InputStream openRecordingInput(String location, String fileName) throws IOException {
        if (isContentLocation(location)) {
            DocumentFile root = DashcamStorageManager.resolveTree(this, Uri.parse(location));
            DocumentFile source = root == null ? null : root.findFile(fileName);
            return source == null ? null : getContentResolver().openInputStream(source.getUri());
        }
        File source = new File(location, fileName);
        return source.isFile() ? new FileInputStream(source) : null;
    }

    private boolean copyStreamToFile(InputStream in, File target) {
        File temp = new File(target.getParentFile(), target.getName() + ".tmp");
        if (temp.exists()) temp.delete();
        try (FileOutputStream out = new FileOutputStream(temp)) {
            copyStream(in, out);
            out.flush();
            out.getFD().sync();
            if (target.exists() && !target.delete()) {
                throw new IOException("delete target failed: " + target.getAbsolutePath());
            }
            if (!temp.renameTo(target)) {
                throw new IOException("rename failed: " + temp.getAbsolutePath() + " -> " + target.getAbsolutePath());
            }
            return true;
        } catch (Throwable t) {
            Log.e(TAG, "Failed to write event target " + target.getAbsolutePath(), t);
            temp.delete();
            return false;
        }
    }

    private void copyStream(InputStream in, OutputStream out) throws IOException {
        byte[] buffer = new byte[64 * 1024];
        int read;
        while ((read = in.read(buffer)) >= 0) {
            if (read > 0) out.write(buffer, 0, read);
        }
    }

    private boolean ensureEventDirectory(String location, String eventBaseName) {
        if (location == null || location.isEmpty()) return false;
        if (isContentLocation(location)) {
            return resolveDocumentDirectory(Uri.parse(location),
                    "events/" + eventBaseName, true) != null;
        }
        return ensureDirectoryExists(new File(new File(location, "events"), eventBaseName), "event dir");
    }

    @Nullable
    private DocumentFile resolveDocumentDirectory(@Nullable Uri treeUri,
            @Nullable String relativePath, boolean create) {
        DocumentFile current = DashcamStorageManager.resolveTree(this, treeUri);
        if (current == null || !current.isDirectory()) return null;
        if (relativePath == null || relativePath.trim().isEmpty()) return current;
        for (String part : relativePath.split("/")) {
            if (part.isEmpty()) continue;
            DocumentFile next = current.findFile(part);
            if (next == null && create) next = current.createDirectory(part);
            if (next == null || !next.isDirectory()) return null;
            current = next;
        }
        return current;
    }

    private boolean isContentLocation(String location) {
        return location != null && location.startsWith("content://");
    }

    private void cleanupOldSegments(DashcamStorageManager.Resolution storage,
            int keepSegments, Set<String> protectedBases) {
        if (storage.isSaf()) {
            cleanupOldDocumentSegments(storage.treeUri, keepSegments, protectedBases);
            return;
        }
        File baseDir = storage.baseDir;
        if (baseDir == null) return;
        File[] files = baseDir.listFiles();
        if (files == null)
            return;

        // baseName => earliestModified
        Map<String, Long> groupTime = new HashMap<>();
        for (File f : files) {
            String name = f.getName();
            if (!name.endsWith(".mp4"))
                continue;
            int underscore = name.indexOf('_');
            String base;
            if (underscore > 0) {
                base = name.substring(0, underscore);
            } else {
                base = name.substring(0, name.length() - 4);
            }
            long t = f.lastModified();
            groupTime.merge(base, t, Math::min);
        }

        List<Map.Entry<String, Long>> groups = new ArrayList<>(groupTime.entrySet());
        groups.sort(Comparator.comparingLong(Map.Entry::getValue));

        if (groups.size() <= keepSegments)
            return;
        int deleteCount = groups.size() - keepSegments;

        int deleted = 0;
        for (int i = 0; i < groups.size() && deleted < deleteCount; i++) {
            String base = groups.get(i).getKey();
            if (protectedBases != null && protectedBases.contains(base)) {
                continue;
            }
            File combinedFile = new File(baseDir, base + ".mp4");
            if (combinedFile.exists()) {
                // noinspection ResultOfMethodCallIgnored
                combinedFile.delete();
            } else {
                char[] suffixes = new char[] { 'F', 'R', 'X', 'Y' };
                for (char s : suffixes) {
                    File f = new File(baseDir, base + "_" + s + ".mp4");
                    // noinspection ResultOfMethodCallIgnored
                    f.delete();
                }
            }
            deleted++;
        }
    }

    private void cleanupOldDocumentSegments(Uri treeUri, int keepSegments, Set<String> protectedBases) {
        DocumentFile root = DashcamStorageManager.resolveTree(this, treeUri);
        if (root == null) return;
        Map<String, List<DocumentFile>> groups = new HashMap<>();
        try {
            for (DocumentFile file : root.listFiles()) {
                String name = file.getName();
                if (!file.isFile() || name == null || !name.endsWith(".mp4")) continue;
                int underscore = name.indexOf('_');
                String base = underscore > 0
                        ? name.substring(0, underscore)
                        : name.substring(0, name.length() - 4);
                groups.computeIfAbsent(base, ignored -> new ArrayList<>()).add(file);
            }
        } catch (Throwable t) {
            Log.w(TAG, "Could not list SAF recording segments", t);
            return;
        }
        List<String> bases = new ArrayList<>(groups.keySet());
        bases.sort(String::compareTo);
        int toDelete = Math.max(0, bases.size() - keepSegments);
        int deleted = 0;
        for (String base : bases) {
            if (deleted >= toDelete) break;
            if (protectedBases != null && protectedBases.contains(base)) continue;
            boolean ok = true;
            for (DocumentFile file : groups.get(base)) ok &= file.delete();
            if (ok) deleted++;
        }
    }

    private void trimOldEventDirsLocked(String location) {
        if (isContentLocation(location)) {
            trimOldDocumentEventDirs(location);
            return;
        }
        File eventsBaseDir = new File(location, "events");
        if (!eventsBaseDir.isDirectory()) return;
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
        return f.delete();
    }

    private void trimOldDocumentEventDirs(String location) {
        DocumentFile eventsDir = resolveDocumentDirectory(Uri.parse(location), "events", false);
        if (eventsDir == null) return;
        List<DocumentFile> eventDirs = new ArrayList<>();
        try {
            for (DocumentFile file : eventsDir.listFiles()) {
                String name = file.getName();
                if (file.isDirectory() && name != null && name.startsWith("event_")) eventDirs.add(file);
            }
        } catch (Throwable t) {
            Log.w(TAG, "Could not list SAF event directories", t);
            return;
        }
        int maxRetained = DashcamStorageManager.getActiveMaxRetainedEventDirs(prefs(), true);
        if (eventDirs.size() <= maxRetained) return;
        eventDirs.sort(Comparator.comparing(file -> file.getName() == null ? "" : file.getName()));
        Set<String> pendingNames = new HashSet<>();
        for (EventCaptureRequest req : pendingEventRequests) pendingNames.add(req.eventBaseName);
        int toDelete = eventDirs.size() - maxRetained;
        int deleted = 0;
        for (DocumentFile dir : eventDirs) {
            if (deleted >= toDelete) break;
            if (pendingNames.contains(dir.getName())) continue;
            if (deleteDocumentRecursive(dir)) deleted++;
        }
    }

    private boolean deleteDocumentRecursive(DocumentFile file) {
        if (file == null) return false;
        try {
            if (file.isDirectory()) {
                for (DocumentFile child : file.listFiles()) deleteDocumentRecursive(child);
            }
            return file.delete();
        } catch (Throwable t) {
            Log.w(TAG, "Could not delete SAF document " + file.getUri(), t);
            return false;
        }
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
    private DashcamStorageManager.Resolution resolveActiveStorage(boolean initial) {
        DashcamStorageManager.Resolution res = DashcamStorageManager.resolve(this, initial);
        if (!res.isUsable()) {
            DevRuntimeLog.add("RecordingService", "Storage resolve failed: " + res.usbState);
            publishStatus(STATUS_ERROR, 0, TOTAL_CAMERAS, ERROR_USB_STORAGE);
            return null;
        }
        if (res.isSaf()) {
            DocumentFile tree = DashcamStorageManager.resolveTree(this, res.treeUri);
            if (tree == null || !tree.exists() || !tree.isDirectory() || !tree.canWrite()) {
                publishStatus(STATUS_ERROR, 0, TOTAL_CAMERAS, ERROR_STORAGE_NOT_WRITABLE);
                return null;
            }
        } else {
            if (!ensureDirectoryExists(res.baseDir, "records base dir") || !res.baseDir.canWrite()) {
                publishStatus(STATUS_ERROR, 0, TOTAL_CAMERAS, ERROR_STORAGE_NOT_WRITABLE);
                return null;
            }
        }
        boolean wasUsb = activeBaseIsUsb;
        boolean hadPrevious = activeStorage != null;
        activeStorage = res;
        activeBaseIsUsb = res.usingUsb;
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
        return res;
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
        if (DashcamStorageManager.isUsbStillWritable(this, activeStorage)) {
            // Storage is fine — this is a genuine camera/encoder failure.
            return false;
        }
        int target = DashcamStorageManager.getStorageTarget(prefs());
        if (target == DashcamStorageManager.TARGET_USB_ONLY) {
            DevRuntimeLog.add("RecordingService", "USB storage failed in USB-only mode => stopping");
            publishStatus(STATUS_ERROR, 0, TOTAL_CAMERAS, ERROR_USB_STORAGE);
            return false;
        }
        DevRuntimeLog.add("RecordingService", "USB storage failed in auto mode => retry on internal");
        return true;
    }

    private String getActiveLocationKey() {
        DashcamStorageManager.Resolution storage = activeStorage;
        return storage != null
                ? storage.locationKey()
                : DashcamSettingsController.getRecordsBaseDir(this).getAbsolutePath();
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
        shutdownRecordingServiceWithoutStopSelf();
        stopForeground(true);
        stopSelf();
    }

    private void shutdownRecordingServiceWithoutStopSelf() {
        stopRequested = true;
        segmentStopRequested = true;
        oemPauseRequested = false;
        synchronized (stateLock) {
            stateLock.notifyAll();
        }
        publishStatus(STATUS_OFF, 0, TOTAL_CAMERAS, "");
        try {
            for (int s = 0; s < 4; s++) {
                CameraProbe.stopMp4Record(s);
            }
            CameraProbe.stopCombinedMp4Record();
        } catch (Throwable ignored) {
        }
        if (worker != null) {
            worker.interrupt();
        }
    }

    private boolean awaitShutdownQuiescence() {
        Thread workerThread = worker;
        if (workerThread != null) {
            try {
                workerThread.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        eventCopyExecutor.shutdown();
        try {
            return eventCopyExecutor.awaitTermination(5000, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private void stopServiceIfNotEjecting() {
        if (usbEjectInProgress) {
            return;
        }
        stopForeground(true);
        stopSelf();
    }

    private void broadcastUsbEjectReady(boolean safeToRemove, int messageRes) {
        Intent intent = new Intent(ACTION_USB_EJECT_READY);
        intent.setPackage(getPackageName());
        intent.putExtra(EXTRA_USB_EJECT_SAFE_TO_REMOVE, safeToRemove);
        intent.putExtra(EXTRA_USB_EJECT_MESSAGE_RES, messageRes);
        sendBroadcast(intent);
    }

    private Notification buildNotification(String text) {
        NotificationCompat.Builder b = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(text)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW);
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

        updateDashcamOverlayState(status, lastError);

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
                    String targetLocation = obj.optString("targetLocation", "");
                    if (eventBaseName.isEmpty() || firstSegmentOrdinal <= 0L || lastSegmentOrdinal < firstSegmentOrdinal) {
                        continue;
                    }
                    EventCaptureRequest request =
                            new EventCaptureRequest(eventBaseName, firstSegmentOrdinal,
                                    lastSegmentOrdinal, targetLocation);
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
                obj.put("targetLocation", request.targetLocation);
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
        sServiceRunning = false;
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
        // Absolute path or persisted content:// tree URI of the records root. Segments recorded
        // before a USB→internal fallback can live in a different root than the active one.
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
        final String targetLocation;
        final Set<String> copiedBaseNames = new HashSet<>();
        final Set<String> inFlightBaseNames = new HashSet<>();

        EventCaptureRequest(String eventBaseName, long firstSegmentOrdinal,
                long lastSegmentOrdinal, String targetLocation) {
            this.eventBaseName = eventBaseName;
            this.firstSegmentOrdinal = firstSegmentOrdinal;
            this.lastSegmentOrdinal = lastSegmentOrdinal;
            this.targetLocation = targetLocation == null ? "" : targetLocation;
        }
    }

    private static final class EventCopyJob {
        final String eventBaseName;
        final String targetLocation;
        final List<SegmentCopyRef> segments;

        EventCopyJob(String eventBaseName, String targetLocation, List<SegmentCopyRef> segments) {
            this.eventBaseName = eventBaseName;
            this.targetLocation = targetLocation == null ? "" : targetLocation;
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
