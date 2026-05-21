package com.drivehub.kamera;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Environment;
import android.os.IBinder;
import android.os.SystemClock;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;

public class RecordingService extends Service {

    public static final String ACTION_START = "start_recording";
    public static final String ACTION_STOP = "stop_recording";
    public static final String ACTION_RECORD_TEST_30S = "record_test_30s";
    public static final String ACTION_STATUS_CHANGED = "com.drivehub.kamera.ACTION_RECORDING_STATUS_CHANGED";
    public static final String EXTRA_STATUS = "status";
    public static final String EXTRA_ACTIVE_CAMERAS = "active_cameras";
    public static final String EXTRA_TOTAL_CAMERAS = "total_cameras";
    public static final String EXTRA_LAST_ERROR = "last_error";
    public static final String STATUS_OFF = "off";
    public static final String STATUS_STARTING = "starting";
    public static final String STATUS_RECORDING = "recording";
    public static final String STATUS_PARTIAL = "partial";
    public static final String STATUS_ERROR = "error";

    private static final String PREFS_NAME = "rec_prefs";
    private static final String KEY_ENABLED = "enabled";
    private static final String KEY_SEGMENT_MIN = "segmentMin";
    private static final String KEY_TOTAL_MIN = "totalMin";
    private static final String KEY_STATUS = "recordingStatus";
    private static final String KEY_ACTIVE_CAMERAS = "recordingActiveCameras";
    private static final String KEY_TOTAL_CAMERAS = "recordingTotalCameras";
    private static final String KEY_LAST_ERROR = "recordingLastError";

    private static final String CHANNEL_ID = "mg4_recording";
    private static final int NOTIF_ID = 42;
    private static final int TOTAL_CAMERAS = 4;
    private static final long TEST_RECORDING_MS = 30_000L;

    private static volatile boolean sServiceRunning = false;

    private Thread worker;
    private volatile boolean stopRequested = false;

    public static boolean isRunning() {
        return sServiceRunning;
    }

    public static void startIfNeeded(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        boolean enabled = prefs.getBoolean(KEY_ENABLED, false);
        if (!enabled) return;
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

    @Override
    public void onCreate() {
        super.onCreate();
        sServiceRunning = true;
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_STICKY;
        String action = intent.getAction();

        if (ACTION_STOP.equals(action)) {
            stopRequested = true;
            publishStatus(STATUS_OFF, 0, TOTAL_CAMERAS, "");
            // If recording is still running, stop the native side immediately too.
            // The 4 slots are fixed: 0=F (15), 1=R (17), 2=X (16), 3=Y (14)
            try {
                for (int s = 0; s < 4; s++) {
                    CameraProbe.stopMp4Record(s);
                }
            } catch (Throwable ignored) {
                // Even if the native layer fails, still continue shutting down the service.
            }
            // Interrupt the worker thread in case it is sleeping.
            if (worker != null) {
                worker.interrupt();
            }
            stopForeground(true);
            return START_NOT_STICKY;
        }

        if (worker != null) {
            // Do not start again if it is already running.
            publishCurrentStatus();
            return START_STICKY;
        }

        stopRequested = false;
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
        File baseDir = getRecordsBaseDir();
        //noinspection ResultOfMethodCallIgnored
        baseDir.mkdirs();
        if (!baseDir.exists() || !baseDir.canWrite()) {
            publishStatus(STATUS_ERROR, 0, TOTAL_CAMERAS, "storage not writable");
            worker = null;
            stopForeground(true);
            stopSelf();
            return;
        }

        boolean startedAnyCamera = recordClip(baseDir, TEST_RECORDING_MS, "test_" + makeTimestampBaseWithSeconds(System.currentTimeMillis()), -1);
        worker = null;
        if (startedAnyCamera) {
            publishStatus(STATUS_OFF, 0, TOTAL_CAMERAS, "");
        }
        stopForeground(true);
        stopSelf();
    }

    private void recordLoop() {
        // NOTE: For now we only record MP4 clips, not speed or turn-signal data.
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        boolean enabled = prefs.getBoolean(KEY_ENABLED, false);
        int segmentMin = prefs.getInt(KEY_SEGMENT_MIN, 3);
        int totalMin = prefs.getInt(KEY_TOTAL_MIN, 30);

        if (!enabled || segmentMin <= 0) {
            publishStatus(STATUS_OFF, 0, TOTAL_CAMERAS, "");
            worker = null;
            stopSelf();
            return;
        }

        File baseDir = getRecordsBaseDir();
        //noinspection ResultOfMethodCallIgnored
        baseDir.mkdirs();
        if (!baseDir.exists() || !baseDir.canWrite()) {
            publishStatus(STATUS_ERROR, 0, TOTAL_CAMERAS, "storage not writable");
            worker = null;
            stopSelf();
            return;
        }

        long segmentMs = segmentMin * 60L * 1000L;

        // Convert total duration into a segment count: segmentMin=3, totalMin=30 => keep 10 segments.
        int keepSegments = Math.max(1, totalMin / segmentMin);
        boolean endedWithFatalError = false;

        while (!stopRequested) {
            boolean startedAnyCamera = recordClip(baseDir, segmentMs, makeTimestampBase(System.currentTimeMillis()), keepSegments);
            if (!startedAnyCamera) {
                endedWithFatalError = true;
                break;
            }

            // Check whether recording has been disabled in prefs.
            enabled = prefs.getBoolean(KEY_ENABLED, false);
            if (!enabled) break;
        }

        worker = null;
        if (!endedWithFatalError) {
            publishStatus(STATUS_OFF, 0, TOTAL_CAMERAS, "");
        }
        stopForeground(true);
        stopSelf();
    }

    private boolean recordClip(File baseDir, long durationMs, String baseName, int keepSegments) {
        // Output names for the 4 cameras.
        // F = v15 (front), R = v17 (rear), X = v16 (left), Y = v14 (right)
        int[] slots = new int[]{0, 1, 2, 3};
        int[] videoIndices = new int[]{15, 17, 16, 14};
        char[] names = new char[]{'F', 'R', 'X', 'Y'};
        int recordingFps = DashcamSettingsController.getRecordingFps(
                getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        );

        String[] outPaths = new String[4];
        for (int i = 0; i < 4; i++) {
            String fileName = baseName + "_" + names[i] + ".mp4";
            File out = new File(baseDir, fileName);
            outPaths[i] = out.getAbsolutePath();
        }

        int activeCameras = 0;
        StringBuilder failedCameras = new StringBuilder();
        for (int i = 0; i < 4; i++) {
            boolean started = CameraProbe.startMp4Record(
                    slots[i], videoIndices[i], outPaths[i], 720, 240, recordingFps, 2500000
            );
            if (started) {
                activeCameras++;
            } else {
                if (failedCameras.length() > 0) failedCameras.append(", ");
                failedCameras.append(names[i]).append(" /dev/video").append(videoIndices[i]);
            }
        }

        if (activeCameras == TOTAL_CAMERAS) {
            publishStatus(STATUS_RECORDING, activeCameras, TOTAL_CAMERAS, "");
        } else if (activeCameras > 0) {
            publishStatus(STATUS_PARTIAL, activeCameras, TOTAL_CAMERAS,
                    "camera start failed: " + failedCameras);
        } else {
            publishStatus(STATUS_ERROR, 0, TOTAL_CAMERAS,
                    "all camera starts failed: " + failedCameras);
            return false;
        }

        long start = SystemClock.elapsedRealtime();
        while (!stopRequested && (SystemClock.elapsedRealtime() - start) < durationMs) {
            try {
                Thread.sleep(200);
            } catch (InterruptedException ignored) {
            }
        }

        for (int slot : slots) {
            CameraProbe.stopMp4Record(slot);
        }

        if (keepSegments > 0) {
            cleanupOldSegments(baseDir, keepSegments);
        }
        return true;
    }

    private void cleanupOldSegments(File baseDir, int keepSegments) {
        File[] files = baseDir.listFiles();
        if (files == null) return;

        // baseName => earliestModified
        Map<String, Long> groupTime = new HashMap<>();
        for (File f : files) {
            String name = f.getName();
            if (!name.endsWith(".mp4")) continue;
            // yymmddhhmm_X.mp4
            int underscore = name.indexOf('_');
            if (underscore <= 0) continue;
            String base = name.substring(0, underscore);
            long t = f.lastModified();
            groupTime.merge(base, t, Math::min);
        }

        List<Map.Entry<String, Long>> groups = new ArrayList<>(groupTime.entrySet());
        groups.sort(Comparator.comparingLong(Map.Entry::getValue));

        if (groups.size() <= keepSegments) return;
        int deleteCount = groups.size() - keepSegments;

        char[] suffixes = new char[]{'F', 'R', 'X', 'Y'};
        for (int i = 0; i < deleteCount; i++) {
            String base = groups.get(i).getKey();
            for (char s : suffixes) {
                File f = new File(baseDir, base + "_" + s + ".mp4");
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
        }
    }

    private File getRecordsBaseDir() {
        // Android 9: write directly into the Downloads folder.
        File downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        File dir = new File(downloads, "mg4_cam_records");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        return dir;
    }

    private String makeTimestampBase(long epochMs) {
        java.util.Calendar cal = java.util.Calendar.getInstance();
        cal.setTimeInMillis(epochMs);
        int yy = cal.get(java.util.Calendar.YEAR) % 100;
        int aa = cal.get(java.util.Calendar.MONTH) + 1;
        int gg = cal.get(java.util.Calendar.DAY_OF_MONTH);
        int ss = cal.get(java.util.Calendar.HOUR_OF_DAY);
        int dd = cal.get(java.util.Calendar.MINUTE);
        return String.format(Locale.US, "%02d%02d%02d%02d%02d", yy, aa, gg, ss, dd);
    }

    private String makeTimestampBaseWithSeconds(long epochMs) {
        java.util.Calendar cal = java.util.Calendar.getInstance();
        cal.setTimeInMillis(epochMs);
        int yy = cal.get(java.util.Calendar.YEAR) % 100;
        int aa = cal.get(java.util.Calendar.MONTH) + 1;
        int gg = cal.get(java.util.Calendar.DAY_OF_MONTH);
        int ss = cal.get(java.util.Calendar.HOUR_OF_DAY);
        int dd = cal.get(java.util.Calendar.MINUTE);
        int sec = cal.get(java.util.Calendar.SECOND);
        return String.format(Locale.US, "%02d%02d%02d%02d%02d%02d", yy, aa, gg, ss, dd, sec);
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

    private void publishCurrentStatus() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        publishStatus(
                prefs.getString(KEY_STATUS, STATUS_OFF),
                prefs.getInt(KEY_ACTIVE_CAMERAS, 0),
                prefs.getInt(KEY_TOTAL_CAMERAS, TOTAL_CAMERAS),
                prefs.getString(KEY_LAST_ERROR, "")
        );
    }

    private void publishStatus(String status, int activeCameras, int totalCameras, String lastError) {
        if (status == null) status = STATUS_OFF;
        if (lastError == null) lastError = "";
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                .putString(KEY_STATUS, status)
                .putInt(KEY_ACTIVE_CAMERAS, Math.max(0, activeCameras))
                .putInt(KEY_TOTAL_CAMERAS, Math.max(0, totalCameras))
                .putString(KEY_LAST_ERROR, lastError)
                .apply();

        String notificationText;
        if (STATUS_RECORDING.equals(status)) {
            notificationText = getString(R.string.notification_recording_status, activeCameras, totalCameras);
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

        Intent intent = new Intent(ACTION_STATUS_CHANGED);
        intent.setPackage(getPackageName());
        intent.putExtra(EXTRA_STATUS, status);
        intent.putExtra(EXTRA_ACTIVE_CAMERAS, activeCameras);
        intent.putExtra(EXTRA_TOTAL_CAMERAS, totalCameras);
        intent.putExtra(EXTRA_LAST_ERROR, lastError);
        sendBroadcast(intent);
    }

    private void createNotificationChannel() {
        NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_recording),
                NotificationManager.IMPORTANCE_LOW
        );
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.createNotificationChannel(ch);
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
        if (worker != null) {
            try {
                worker.join(1000);
            } catch (InterruptedException ignored) {
            }
        }
        super.onDestroy();
    }
}
