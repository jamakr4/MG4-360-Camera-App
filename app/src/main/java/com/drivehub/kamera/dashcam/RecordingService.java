package com.drivehub.kamera.dashcam;

import com.drivehub.kamera.R;

import com.drivehub.kamera.CameraProbe;
import com.drivehub.kamera.dev.DevRuntimeLog;
import com.drivehub.kamera.settings.UiPrefs;

import android.app.Notification;
import android.app.NotificationChannel;
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

public class RecordingService extends Service {
    private static final String TAG = "RecordingService";

    public static final String ACTION_START = "start_recording";
    public static final String ACTION_STOP = "stop_recording";
    public static final String ACTION_RECORD_TEST_30S = "record_test_30s";
    public static final String ACTION_TRIGGER_EVENT_SAVE = "trigger_event_save";
    public static final String ACTION_PAUSE_FOR_OEM_REQUEST = "pause_for_oem_request";
    public static final String ACTION_RESUME_AFTER_OEM_REQUEST = "resume_after_oem_request";
    public static final String ACTION_STATUS_CHANGED = "com.drivehub.kamera.ACTION_RECORDING_STATUS_CHANGED";
    public static final String EXTRA_STATUS = "status";
    public static final String EXTRA_ACTIVE_CAMERAS = "active_cameras";
    public static final String EXTRA_TOTAL_CAMERAS = "total_cameras";
    public static final String EXTRA_LAST_ERROR = "last_error";
    public static final String STATUS_OFF = "off";
    public static final String STATUS_STARTING = "starting";
    public static final String STATUS_RECORDING = "recording";
    public static final String STATUS_PAUSED_OEM = "paused_oem";
    public static final String STATUS_PARTIAL = "partial";
    public static final String STATUS_ERROR = "error";

    private static final String KEY_STATUS = "recordingStatus";
    private static final String KEY_ACTIVE_CAMERAS = "recordingActiveCameras";
    private static final String KEY_TOTAL_CAMERAS = "recordingTotalCameras";
    private static final String KEY_LAST_ERROR = "recordingLastError";

    private static final String CHANNEL_ID = "mg4_recording";
    private static final int NOTIF_ID = 42;
    private static final int TOTAL_CAMERAS = 4;
    private static final long TEST_RECORDING_MS = 10_000L;
    private static final int EVENT_SEGMENTS_BEFORE_CURRENT = 2;
    private static final int EVENT_SEGMENTS_AFTER_CURRENT = 2;
    private static final long ERROR_OVERLAY_DELAY_MS = 5_000L;

    private static volatile boolean sServiceRunning = false;

    private final Object eventLock = new Object();
    private final Object stateLock = new Object();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private Thread worker;
    private volatile boolean stopRequested = false;
    private volatile boolean segmentStopRequested = false;
    private volatile boolean oemPauseRequested = false;
    private volatile int pendingErrorSubtitleResId = 0;
    private volatile int pendingErrorNotificationResId = 0;
    private volatile int pendingErrorGeneration = 0;
    private volatile boolean errorOverlayShown = false;
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

    public static void startTestClip(Context context) {
        Intent i = new Intent(context, RecordingService.class);
        i.setAction(ACTION_RECORD_TEST_30S);
        context.startForegroundService(i);
    }

    public static void triggerEventSave(Context context) {
        Intent i = new Intent(context, RecordingService.class);
        i.setAction(ACTION_TRIGGER_EVENT_SAVE);
        context.startService(i);
    }

    public static void pauseForOemRequest(Context context) {
        SharedPreferences prefs = UiPrefs.getPrefs(context);
        if (!prefs.getBoolean(DashcamSettingsController.KEY_ENABLED, false) && !isRunning()) {
            return;
        }
        Intent i = new Intent(context, RecordingService.class);
        i.setAction(ACTION_PAUSE_FOR_OEM_REQUEST);
        context.startForegroundService(i);
    }

    public static void resumeAfterOemRequest(Context context) {
        SharedPreferences prefs = UiPrefs.getPrefs(context);
        if (!prefs.getBoolean(DashcamSettingsController.KEY_ENABLED, false) && !isRunning()) {
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
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null)
            return START_STICKY;
        String action = intent.getAction();

        if (ACTION_STOP.equals(action)) {
            DevRuntimeLog.add("RecordingService", "ACTION_STOP");
            stopRequested = true;
            segmentStopRequested = true;
            oemPauseRequested = false;
            synchronized (stateLock) {
                stateLock.notifyAll();
            }
            publishStatus(STATUS_OFF, 0, TOTAL_CAMERAS, "");
            // If recording is still running, stop the native side immediately too.
            // The 4 slots are fixed: 0=F (15), 1=R (17), 2=X (16), 3=Y (14)
            try {
                for (int s = 0; s < 4; s++) {
                    CameraProbe.stopMp4Record(s);
                }
                CameraProbe.stopCombinedMp4Record();
            } catch (Throwable ignored) {
                // Even if the native layer fails, still continue shutting down the service.
            }
            // Interrupt the worker thread in case it is sleeping.
            if (worker != null) {
                worker.interrupt();
            }
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }

        if (ACTION_PAUSE_FOR_OEM_REQUEST.equals(action)) {
            DevRuntimeLog.add("RecordingService", "ACTION_PAUSE_FOR_OEM_REQUEST");
            boolean enabled = prefs().getBoolean(DashcamSettingsController.KEY_ENABLED, false);
            boolean changed = !oemPauseRequested;
            oemPauseRequested = true;
            segmentStopRequested = true;
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
            if (worker == null || !prefs().getBoolean(DashcamSettingsController.KEY_ENABLED, false)) {
                return START_STICKY;
            }
            armEventCapture();
            DashcamEventOverlayService.showConfirmation(this);
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
        if (ACTION_RECORD_TEST_30S.equals(action)) {
            worker = new Thread(this::recordTestClip, "RecordingServiceTestWorker");
        } else {
            worker = new Thread(this::recordLoop, "RecordingServiceWorker");
        }
        worker.start();
        return START_STICKY;
    }

    private void recordTestClip() {
        File baseDir = requireBaseDir();
        if (baseDir == null) {
            worker = null;
            stopForeground(true);
            stopSelf();
            return;
        }

        boolean startedAnyCamera = recordClip(baseDir, TEST_RECORDING_MS,
                "test_" + makeTimestampBase(System.currentTimeMillis(), "yyMMddHHmmss"), -1);
        worker = null;
        if (startedAnyCamera) {
            publishStatus(STATUS_OFF, 0, TOTAL_CAMERAS, "");
        }
        stopForeground(true);
        stopSelf();
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

        File baseDir = requireBaseDir();
        if (baseDir == null) {
            worker = null;
            stopSelf();
            return;
        }

        long segmentMs = segmentSec * 1000L;

        // Convert total retention into a segment count, e.g. segmentSec=30, retention=5 min => keep 10 segments.
        int keepSegments = Math.max(1,
                (int) ((DashcamSettingsController.DEFAULT_TOTAL_RETENTION_MIN * 60L) / segmentSec));
        boolean endedWithFatalError = false;

        while (!stopRequested) {
            if (!waitForOemPauseToClear(prefs)) {
                break;
            }
            long segmentStartWallMs = System.currentTimeMillis();
            String baseName = makeTimestampBase(segmentStartWallMs, "yyMMddHHmmss");
            boolean startedAnyCamera = recordClip(baseDir, segmentMs, baseName, keepSegments);
            if (!startedAnyCamera) {
                endedWithFatalError = true;
                break;
            }
            onSegmentCompleted(baseDir, baseName, segmentStartWallMs, System.currentTimeMillis(), keepSegments);

            // Check whether recording has been disabled in prefs.
            enabled = prefs.getBoolean(DashcamSettingsController.KEY_ENABLED, false);
            if (!enabled)
                break;
        }

        worker = null;
        if (!endedWithFatalError) {
            publishStatus(STATUS_OFF, 0, TOTAL_CAMERAS, "");
        }
        stopForeground(true);
        stopSelf();
    }

    private boolean recordClip(File baseDir, long durationMs, String baseName, int keepSegments) {
        SharedPreferences prefs = prefs();
        int recordingFps = DashcamSettingsController.getRecordingFps(prefs);
        String signature = DashcamSettingsController.getRecordingSignature(prefs);
        boolean showSpeed = DashcamSettingsController.shouldShowSpeed(prefs);
        File outputFile = new File(baseDir, baseName + ".mp4");
        boolean started = CameraProbe.startCombinedMp4Record(
                outputFile.getAbsolutePath(),
                720,
                240,
                recordingFps,
                9_000_000,
                signature,
                showSpeed);

        if (!started) {
            publishStatus(STATUS_ERROR, 0, TOTAL_CAMERAS, "grid start failed");
            return false;
        }

        publishStatus(STATUS_RECORDING, TOTAL_CAMERAS, TOTAL_CAMERAS, "");

        long start = SystemClock.elapsedRealtime();
        while (!stopRequested
                && !segmentStopRequested
                && (SystemClock.elapsedRealtime() - start) < durationMs) {
            if (showSpeed) {
                CameraProbe.updateCombinedRecordingSpeed(readSpeedKmhFromSystemProperty());
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException ignored) {
            }
        }

        if (!CameraProbe.stopCombinedMp4Record()) {
            publishStatus(STATUS_ERROR, 0, TOTAL_CAMERAS, "grid stop timeout");
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

    private int readSpeedKmhFromSystemProperty() {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            java.lang.reflect.Method get = sp.getMethod("get", String.class, String.class);
            String value = (String) get.invoke(null, "arcsoft.avm.mCurCarSpeed", "");
            if (value == null || value.isEmpty()) {
                return 0;
            }
            return Math.max(0, Math.round(Float.parseFloat(value)));
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private void onSegmentCompleted(File baseDir, String baseName, long startMs, long endMs, int keepSegments) {
        List<EventCopyJob> completedJobs;
        synchronized (eventLock) {
            long segmentOrdinal = ++completedSegmentCount;
            recentSegments.add(new SegmentInfo(segmentOrdinal, baseName, startMs, endMs));
            while (recentSegments.size() > keepSegments + 6) {
                recentSegments.remove(0);
            }
            completedJobs = finalizeReadyEventRequestsLocked(segmentOrdinal);
            Set<String> protectedBases = collectProtectedBasesLocked();
            cleanupOldSegments(baseDir, keepSegments, protectedBases);
        }
        for (EventCopyJob job : completedJobs) {
            copyEventSegments(job);
        }
    }

    private void armEventCapture() {
        long now = System.currentTimeMillis();
        synchronized (eventLock) {
            long currentSegmentOrdinal = completedSegmentCount + 1L;
            pendingEventRequests.add(new EventCaptureRequest(
                    "event_" + makeTimestampBase(now, "yyMMddHHmmssSSS"),
                    Math.max(1L, currentSegmentOrdinal - EVENT_SEGMENTS_BEFORE_CURRENT),
                    currentSegmentOrdinal + EVENT_SEGMENTS_AFTER_CURRENT
            ));
        }
    }

    private List<EventCopyJob> finalizeReadyEventRequestsLocked(long completedThroughOrdinal) {
        List<EventCaptureRequest> completed = new ArrayList<>();
        List<EventCopyJob> jobs = new ArrayList<>();
        for (EventCaptureRequest request : pendingEventRequests) {
            if (completedThroughOrdinal < request.lastSegmentOrdinal) {
                continue;
            }
            jobs.add(buildEventCopyJobLocked(request));
            completed.add(request);
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

    private EventCopyJob buildEventCopyJobLocked(EventCaptureRequest request) {
        List<String> baseNames = new ArrayList<>();
        Set<String> copiedBases = new HashSet<>();
        for (SegmentInfo segment : recentSegments) {
            if (segment.ordinal < request.firstSegmentOrdinal
                    || segment.ordinal > request.lastSegmentOrdinal) {
                continue;
            }
            if (copiedBases.add(segment.baseName)) {
                baseNames.add(segment.baseName);
            }
        }
        return new EventCopyJob(request.eventBaseName, baseNames);
    }

    private void copyEventSegments(EventCopyJob job) {
        if (job.baseNames.isEmpty()) {
            Log.w(TAG, "Skipping empty event copy job for " + job.eventBaseName);
            return;
        }
        File eventDir = new File(getEventsBaseDir(), job.eventBaseName);
        // noinspection ResultOfMethodCallIgnored
        eventDir.mkdirs();
        for (String baseName : job.baseNames) {
            copySegmentGroup(DashcamSettingsController.getRecordsBaseDir(this), eventDir, baseName);
        }
    }

    private void copySegmentGroup(File sourceDir, File targetDir, String baseName) {
        File combinedSource = new File(sourceDir, baseName + ".mp4");
        if (combinedSource.exists()) {
            copyFile(combinedSource, new File(targetDir, combinedSource.getName()));
            return;
        }

        char[] suffixes = new char[] { 'F', 'R', 'X', 'Y' };
        for (char suffix : suffixes) {
            File source = new File(sourceDir, baseName + "_" + suffix + ".mp4");
            if (!source.exists()) {
                continue;
            }
            File target = new File(targetDir, source.getName());
            copyFile(source, target);
        }
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
            return true;
        } catch (Throwable t) {
            Log.e(TAG, "Failed to copy " + source.getAbsolutePath() + " -> " + target.getAbsolutePath(), t);
            // noinspection ResultOfMethodCallIgnored
            temp.delete();
        }
        return false;
    }

    private void cleanupOldSegments(File baseDir, int keepSegments, Set<String> protectedBases) {
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

    private File getEventsBaseDir() {
        File dir = new File(DashcamSettingsController.getRecordsBaseDir(this), "events");
        // noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        return dir;
    }

    private File requireBaseDir() {
        File baseDir = DashcamSettingsController.getRecordsBaseDir(this);
        // noinspection ResultOfMethodCallIgnored
        baseDir.mkdirs();
        if (baseDir.exists() && baseDir.canWrite()) {
            return baseDir;
        }
        publishStatus(STATUS_ERROR, 0, TOTAL_CAMERAS, "storage not writable");
        return null;
    }

    private String makeTimestampBase(long epochMs, String pattern) {
        return new SimpleDateFormat(pattern, Locale.US).format(epochMs);
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
        if ("storage not writable".equals(lastError)) {
            return new OverlayMessageSpec(
                    R.string.dashcam_recording_error_overlay_subtitle_storage,
                    R.string.notification_dashcam_recording_error_storage_text);
        }
        if ("grid start failed".equals(lastError)) {
            return new OverlayMessageSpec(
                    R.string.dashcam_recording_error_overlay_subtitle_start_failed,
                    R.string.notification_dashcam_recording_error_start_failed_text);
        }
        if ("grid stop timeout".equals(lastError)) {
            return new OverlayMessageSpec(
                    R.string.dashcam_recording_error_overlay_subtitle_stop_failed,
                    R.string.notification_dashcam_recording_error_stop_failed_text);
        }
        return new OverlayMessageSpec(
                R.string.dashcam_recording_error_overlay_subtitle_generic,
                R.string.notification_dashcam_recording_error_text);
    }

    private SharedPreferences prefs() {
        return UiPrefs.getPrefs(this);
    }

    private void createNotificationChannel() {
        NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_recording),
                NotificationManager.IMPORTANCE_LOW);
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null)
            nm.createNotificationChannel(ch);
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
        if (worker != null) {
            worker.interrupt();
            try {
                worker.join(1000);
            } catch (InterruptedException ignored) {
            }
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

        SegmentInfo(long ordinal, String baseName, long startMs, long endMs) {
            this.ordinal = ordinal;
            this.baseName = baseName;
            this.startMs = startMs;
            this.endMs = endMs;
        }
    }

    private static final class EventCaptureRequest {
        final String eventBaseName;
        final long firstSegmentOrdinal;
        final long lastSegmentOrdinal;

        EventCaptureRequest(String eventBaseName, long firstSegmentOrdinal, long lastSegmentOrdinal) {
            this.eventBaseName = eventBaseName;
            this.firstSegmentOrdinal = firstSegmentOrdinal;
            this.lastSegmentOrdinal = lastSegmentOrdinal;
        }
    }

    private static final class EventCopyJob {
        final String eventBaseName;
        final List<String> baseNames;

        EventCopyJob(String eventBaseName, List<String> baseNames) {
            this.eventBaseName = eventBaseName;
            this.baseNames = baseNames;
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
