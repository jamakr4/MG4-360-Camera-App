package com.drivehub.kamera;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

public class DashcamEventOverlayService extends Service {

    private static final String CHANNEL_ID = "mg4_dashcam_event_overlay";
    private static final int NOTIF_ID = 103;
    private static final long AUTO_HIDE_MS = 4_000L;
    private static final String EXTRA_TITLE_RES_ID = "title_res_id";
    private static final String EXTRA_SUBTITLE_RES_ID = "subtitle_res_id";
    private static final String EXTRA_NOTIFICATION_TEXT_RES_ID = "notification_text_res_id";

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Runnable hideRunnable = this::stopSelf;

    private WindowManager windowManager;
    private View overlayView;
    private android.widget.TextView titleView;
    private android.widget.TextView subtitleView;

    // Show the standard dashcam event confirmation banner using string resources.
    public static void showConfirmation(Context context) {
        showBanner(
                context,
                R.string.dashcam_event_overlay_title,
                R.string.dashcam_event_overlay_subtitle,
                R.string.notification_dashcam_event_overlay_text);
    }

    // Show the OEM pause banner when recording must pause while the OEM app is
    // foregrounded.
    public static void showOemPause(Context context) {
        showBanner(
                context,
                R.string.dashcam_oem_pause_overlay_title,
                R.string.dashcam_oem_pause_overlay_subtitle,
                R.string.notification_dashcam_oem_pause_overlay_text);
    }

    public static void showOemResume(Context context) {
        showBanner(
                context,
                R.string.dashcam_oem_resume_overlay_title,
                R.string.dashcam_oem_resume_overlay_subtitle,
                R.string.notification_dashcam_oem_resume_overlay_text);
    }

    public static void showRecordingError(Context context, int subtitleResId, int notificationTextResId) {
        showBanner(
                context,
                R.string.dashcam_recording_error_overlay_title,
                subtitleResId,
                notificationTextResId);
    }

    public static void showRecordingRecovered(Context context) {
        showBanner(
                context,
                R.string.dashcam_recording_recovered_overlay_title,
                R.string.dashcam_recording_recovered_overlay_subtitle,
                R.string.notification_dashcam_recording_recovered_text);
    }

    private static void showBanner(Context context, int titleResId, int subtitleResId, int notificationTextResId) {
        Intent intent = new Intent(context, DashcamEventOverlayService.class);
        intent.putExtra(EXTRA_TITLE_RES_ID, titleResId);
        intent.putExtra(EXTRA_SUBTITLE_RES_ID, subtitleResId);
        intent.putExtra(EXTRA_NOTIFICATION_TEXT_RES_ID, notificationTextResId);
        context.startForegroundService(intent);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        int titleResId = intent != null
                ? intent.getIntExtra(EXTRA_TITLE_RES_ID, R.string.dashcam_event_overlay_title)
                : R.string.dashcam_event_overlay_title;
        int subtitleResId = intent != null
                ? intent.getIntExtra(EXTRA_SUBTITLE_RES_ID, R.string.dashcam_event_overlay_subtitle)
                : R.string.dashcam_event_overlay_subtitle;
        int notificationTextResId = intent != null
                ? intent.getIntExtra(EXTRA_NOTIFICATION_TEXT_RES_ID, R.string.notification_dashcam_event_overlay_text)
                : R.string.notification_dashcam_event_overlay_text;
        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(getString(notificationTextResId))
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
        startForeground(NOTIF_ID, notification);

        if (overlayView == null) {
            showOverlayWindow();
        }
        bindText(titleResId, subtitleResId);
        mainHandler.removeCallbacks(hideRunnable);
        mainHandler.postDelayed(hideRunnable, AUTO_HIDE_MS);
        return START_NOT_STICKY;
    }

    private void showOverlayWindow() {
        if (windowManager == null) {
            return;
        }
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        params.y = dpToPx(32);

        overlayView = LayoutInflater.from(this).inflate(R.layout.overlay_dashcam_event, null, false);
        titleView = overlayView.findViewById(R.id.tvDashcamOverlayTitle);
        subtitleView = overlayView.findViewById(R.id.tvDashcamOverlaySubtitle);
        windowManager.addView(overlayView, params);
    }

    private void bindText(int titleResId, int subtitleResId) {
        if (titleView != null) {
            titleView.setText(titleResId);
        }
        if (subtitleView != null) {
            subtitleView.setText(subtitleResId);
        }
    }

    private int dpToPx(int dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
    }

    @Override
    public void onDestroy() {
        mainHandler.removeCallbacks(hideRunnable);
        if (windowManager != null && overlayView != null) {
            windowManager.removeView(overlayView);
        }
        overlayView = null;
        titleView = null;
        subtitleView = null;
        stopForeground(true);
        super.onDestroy();
    }

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_dashcam_event_overlay),
                NotificationManager.IMPORTANCE_LOW);
        NotificationManager notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (notificationManager != null) {
            notificationManager.createNotificationChannel(channel);
        }
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
