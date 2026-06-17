package com.drivehub.kamera.dashcam;

import com.drivehub.kamera.R;
import com.drivehub.kamera.helper.app.NotificationChannelHelper;
import com.drivehub.kamera.settings.UiPrefs;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.AssetFileDescriptor;
import android.content.res.ColorStateList;
import android.graphics.PixelFormat;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

public class DashcamEventOverlayService extends Service {

    private static final String CHANNEL_ID = "mg4_dashcam_event_overlay";
    private static final int NOTIF_ID = 103;
    private static final long AUTO_HIDE_MS = 4_000L;
    private static final String EXTRA_TYPE_ORDINAL = "banner_type_ordinal";
    private static final String EXTRA_SUBTITLE_RES_ID = "subtitle_res_id";
    private static final String EXTRA_NOTIFICATION_TEXT_RES_ID = "notification_text_res_id";

    // Base dimensions (SMALL = 1.0x). Apply scale factor for MEDIUM (1.6x) and LARGE (2.2x).
    private static final float TITLE_BASE_SP = 16f;
    private static final float SUBTITLE_BASE_SP = 13f;
    private static final float DOT_BASE_DP = 12f;
    private static final float PADDING_H_BASE_DP = 18f;
    private static final float PADDING_V_BASE_DP = 14f;
    private static final float DOT_TEXT_GAP_BASE_DP = 12f;
    private static final float SUBTITLE_TOP_BASE_DP = 2f;
    private static final float[] BANNER_SIZE_SCALES = {1.0f, 1.6f, 2.2f};

    public enum BannerType {
        EVENT(
                DashcamSettingsController.BannerGroup.EVENT,
                0xFFFF453A,
                R.string.dashcam_event_overlay_title,
                R.string.dashcam_event_overlay_subtitle,
                R.string.notification_dashcam_event_overlay_text),
        OEM_PAUSE(
                DashcamSettingsController.BannerGroup.PAUSE_RESUME,
                0xFF0A84FF,
                R.string.dashcam_oem_pause_overlay_title,
                R.string.dashcam_oem_pause_overlay_subtitle,
                R.string.notification_dashcam_oem_pause_overlay_text),
        OEM_RESUME(
                DashcamSettingsController.BannerGroup.PAUSE_RESUME,
                0xFF30D158,
                R.string.dashcam_oem_resume_overlay_title,
                R.string.dashcam_oem_resume_overlay_subtitle,
                R.string.notification_dashcam_oem_resume_overlay_text),
        RECORDING_ERROR(
                DashcamSettingsController.BannerGroup.ERROR_RECOVERED,
                0xFFFF9F0A,
                R.string.dashcam_recording_error_overlay_title,
                R.string.dashcam_recording_error_overlay_subtitle_generic,
                R.string.notification_dashcam_recording_error_text),
        RECORDING_RECOVERED(
                DashcamSettingsController.BannerGroup.ERROR_RECOVERED,
                0xFF30D158,
                R.string.dashcam_recording_recovered_overlay_title,
                R.string.dashcam_recording_recovered_overlay_subtitle,
                R.string.notification_dashcam_recording_recovered_text);

        final DashcamSettingsController.BannerGroup group;
        final int dotColor;
        final int titleRes;
        final int subtitleRes;
        final int notificationRes;

        BannerType(DashcamSettingsController.BannerGroup group, int dotColor,
                int titleRes, int subtitleRes, int notificationRes) {
            this.group = group;
            this.dotColor = dotColor;
            this.titleRes = titleRes;
            this.subtitleRes = subtitleRes;
            this.notificationRes = notificationRes;
        }
    }

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Runnable hideRunnable = this::stopSelf;

    private WindowManager windowManager;
    private View overlayView;
    private View dotView;
    private View textContainerView;
    private TextView titleView;
    private TextView subtitleView;
    private MediaPlayer bannerTonePlayer;

    // ---------- Public API: respects per-group enabled toggle ----------

    public static void showConfirmation(Context context) {
        show(context, BannerType.EVENT, BannerType.EVENT.subtitleRes, BannerType.EVENT.notificationRes, false);
    }

    public static void showFutureOnlyConfirmation(Context context) {
        show(
                context,
                BannerType.EVENT,
                R.string.dashcam_event_overlay_subtitle_future_only,
                R.string.notification_dashcam_event_overlay_future_only_text,
                false);
    }

    public static void showOemPause(Context context) {
        show(context, BannerType.OEM_PAUSE, BannerType.OEM_PAUSE.subtitleRes, BannerType.OEM_PAUSE.notificationRes, false);
    }

    public static void showOemResume(Context context) {
        show(context, BannerType.OEM_RESUME, BannerType.OEM_RESUME.subtitleRes, BannerType.OEM_RESUME.notificationRes, false);
    }

    public static void showRecordingError(Context context, int subtitleResId, int notificationTextResId) {
        show(context, BannerType.RECORDING_ERROR, subtitleResId, notificationTextResId, false);
    }

    public static void showRecordingRecovered(Context context) {
        show(context, BannerType.RECORDING_RECOVERED, BannerType.RECORDING_RECOVERED.subtitleRes,
                BannerType.RECORDING_RECOVERED.notificationRes, false);
    }

    // ---------- Test API: bypasses enabled check, used by settings preview buttons ----------

    public static void showConfirmationForced(Context context) {
        show(context, BannerType.EVENT, BannerType.EVENT.subtitleRes, BannerType.EVENT.notificationRes, true);
    }

    public static void showFutureOnlyConfirmationForced(Context context) {
        show(
                context,
                BannerType.EVENT,
                R.string.dashcam_event_overlay_subtitle_future_only,
                R.string.notification_dashcam_event_overlay_future_only_text,
                true);
    }

    public static void showOemPauseForced(Context context) {
        show(context, BannerType.OEM_PAUSE, BannerType.OEM_PAUSE.subtitleRes, BannerType.OEM_PAUSE.notificationRes, true);
    }

    public static void showOemResumeForced(Context context) {
        show(context, BannerType.OEM_RESUME, BannerType.OEM_RESUME.subtitleRes, BannerType.OEM_RESUME.notificationRes, true);
    }

    public static void showRecordingErrorForced(Context context, int subtitleResId, int notificationTextResId) {
        show(context, BannerType.RECORDING_ERROR, subtitleResId, notificationTextResId, true);
    }

    public static void showRecordingRecoveredForced(Context context) {
        show(context, BannerType.RECORDING_RECOVERED, BannerType.RECORDING_RECOVERED.subtitleRes,
                BannerType.RECORDING_RECOVERED.notificationRes, true);
    }

    private static void show(Context context, BannerType type, int subtitleResId,
            int notificationTextResId, boolean force) {
        if (!force) {
            SharedPreferences prefs = UiPrefs.getPrefs(context);
            if (!DashcamSettingsController.isBannerEnabled(prefs, type.group)) {
                return;
            }
        }
        Intent intent = new Intent(context, DashcamEventOverlayService.class);
        intent.putExtra(EXTRA_TYPE_ORDINAL, type.ordinal());
        intent.putExtra(EXTRA_SUBTITLE_RES_ID, subtitleResId);
        intent.putExtra(EXTRA_NOTIFICATION_TEXT_RES_ID, notificationTextResId);
        context.startForegroundService(intent);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        NotificationChannelHelper.ensureChannel(this, CHANNEL_ID, R.string.notification_channel_dashcam_event_overlay);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        BannerType type = readBannerType(intent);
        int subtitleResId = intent != null
                ? intent.getIntExtra(EXTRA_SUBTITLE_RES_ID, type.subtitleRes)
                : type.subtitleRes;
        int notificationTextResId = intent != null
                ? intent.getIntExtra(EXTRA_NOTIFICATION_TEXT_RES_ID, type.notificationRes)
                : type.notificationRes;

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
        SharedPreferences prefs = UiPrefs.getPrefs(this);
        int sizeIndex = DashcamSettingsController.getBannerSize(prefs, type.group);
        applyBannerSize(sizeIndex);
        applyDotColor(type.dotColor);
        bindText(type.titleRes, subtitleResId);
        playBannerTone(type.group, prefs);
        mainHandler.removeCallbacks(hideRunnable);
        mainHandler.postDelayed(hideRunnable, AUTO_HIDE_MS);
        return START_NOT_STICKY;
    }

    private BannerType readBannerType(Intent intent) {
        if (intent == null) return BannerType.EVENT;
        int ordinal = intent.getIntExtra(EXTRA_TYPE_ORDINAL, BannerType.EVENT.ordinal());
        BannerType[] all = BannerType.values();
        if (ordinal < 0 || ordinal >= all.length) {
            return BannerType.EVENT;
        }
        return all[ordinal];
    }

    private void playBannerTone(DashcamSettingsController.BannerGroup group, SharedPreferences prefs) {
        int volumePct = DashcamSettingsController.getBannerVolume(prefs, group);
        if (volumePct <= 0) {
            releaseBannerTone();
            return;
        }
        releaseBannerTone();

        // No audio focus request: requesting AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK reliably ducks
        // Android Auto, but its volume sometimes doesn't recover when we abandon focus until the
        // user pauses + resumes playback. For a sub-second notification tone, mixing over music is
        // good enough — user can compensate via the per-group banner volume.
        AudioAttributes attributes = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build();

        MediaPlayer player = new MediaPlayer();
        try (AssetFileDescriptor afd =
                     getResources().openRawResourceFd(R.raw.notification_sound_7062_henrycena82595)) {
            if (afd == null) {
                player.release();
                return;
            }
            player.setAudioAttributes(attributes);
            player.setDataSource(afd.getFileDescriptor(), afd.getStartOffset(), afd.getLength());
            float volume = volumePct / 100f;
            player.setVolume(volume, volume);
            player.setOnCompletionListener(mp -> releaseBannerTone());
            player.setOnErrorListener((mp, what, extra) -> {
                releaseBannerTone();
                return true;
            });
            player.prepare();
            player.start();
            bannerTonePlayer = player;
        } catch (Throwable t) {
            try {
                player.release();
            } catch (Throwable ignored) {
            }
        }
    }

    private void releaseBannerTone() {
        if (bannerTonePlayer != null) {
            try {
                bannerTonePlayer.reset();
            } catch (Throwable ignored) {
            }
            try {
                bannerTonePlayer.release();
            } catch (Throwable ignored) {
            }
            bannerTonePlayer = null;
        }
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
        dotView = overlayView.findViewById(R.id.dashcamOverlayDot);
        textContainerView = overlayView.findViewById(R.id.dashcamOverlayTextContainer);
        titleView = overlayView.findViewById(R.id.tvDashcamOverlayTitle);
        subtitleView = overlayView.findViewById(R.id.tvDashcamOverlaySubtitle);
        windowManager.addView(overlayView, params);
    }

    private void applyBannerSize(int sizeIndex) {
        if (overlayView == null) return;
        float scale = BANNER_SIZE_SCALES[
                Math.max(0, Math.min(BANNER_SIZE_SCALES.length - 1, sizeIndex))];

        if (titleView != null) {
            titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, TITLE_BASE_SP * scale);
        }
        if (subtitleView != null) {
            subtitleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, SUBTITLE_BASE_SP * scale);
            ViewGroup.MarginLayoutParams subParams =
                    (ViewGroup.MarginLayoutParams) subtitleView.getLayoutParams();
            if (subParams != null) {
                subParams.topMargin = dpToPx(SUBTITLE_TOP_BASE_DP * scale);
                subtitleView.setLayoutParams(subParams);
            }
        }
        if (dotView != null) {
            int dotPx = dpToPx(DOT_BASE_DP * scale);
            ViewGroup.LayoutParams dotParams = dotView.getLayoutParams();
            dotParams.width = dotPx;
            dotParams.height = dotPx;
            dotView.setLayoutParams(dotParams);
        }
        if (textContainerView != null) {
            ViewGroup.MarginLayoutParams textParams =
                    (ViewGroup.MarginLayoutParams) textContainerView.getLayoutParams();
            if (textParams != null) {
                textParams.setMarginStart(dpToPx(DOT_TEXT_GAP_BASE_DP * scale));
                textContainerView.setLayoutParams(textParams);
            }
        }
        int padH = dpToPx(PADDING_H_BASE_DP * scale);
        int padV = dpToPx(PADDING_V_BASE_DP * scale);
        overlayView.setPadding(padH, padV, padH, padV);
    }

    private void applyDotColor(int colorArgb) {
        if (dotView == null) return;
        dotView.setBackgroundTintList(ColorStateList.valueOf(colorArgb));
    }

    private void bindText(int titleResId, int subtitleResId) {
        if (titleView != null) {
            titleView.setText(titleResId);
        }
        if (subtitleView != null) {
            subtitleView.setText(subtitleResId);
        }
    }

    private int dpToPx(float dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        mainHandler.removeCallbacks(hideRunnable);
        releaseBannerTone();
        if (windowManager != null && overlayView != null) {
            windowManager.removeView(overlayView);
        }
        overlayView = null;
        dotView = null;
        textContainerView = null;
        titleView = null;
        subtitleView = null;
        stopForeground(true);
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
