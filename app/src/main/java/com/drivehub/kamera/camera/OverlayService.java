package com.drivehub.kamera.camera;

import com.drivehub.kamera.CameraProbe;
import com.drivehub.kamera.R;

import com.drivehub.kamera.helper.app.NotificationChannelHelper;
import com.drivehub.kamera.signal.SignalService;
import com.drivehub.kamera.settings.UiPrefs;

import android.app.ActivityManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageButton;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

/**
 * Draggable floating camera window shown above other content.
 * For now it takes a camera index directly; later it can be triggered from turn-signal state.
 */
public class OverlayService extends Service implements TextureView.SurfaceTextureListener {

    public static final String EXTRA_CAMERA_INDEX = "camera_index";
    public static final String EXTRA_OVERLAY_REASON = "overlay_reason";
    public static final String OVERLAY_REASON_SIGNAL = "signal";
    public static final String OVERLAY_REASON_DIGITAL_REARVIEW = "digital_rearview";
    public static final String OVERLAY_REASON_MANEUVER = "maneuver";

    private static final String CHANNEL_ID = "mg4_overlay";
    private static final int NOTIF_ID = 99;

    /** Default overlay size in px; the aspect ratio is preserved. */
    private static final int DEFAULT_OVERLAY_WIDTH_PX = 1000;
    private static final int DEFAULT_OVERLAY_HEIGHT_PX = 480;
    /** Min/max bounds for two-finger pinch resizing. */
    private static final int OVERLAY_MIN_WIDTH_PX = 240;
    private static final int OVERLAY_MAX_WIDTH_PX = 3840;
    private static final int MG4_DISPLAY_WIDTH_PX = 1920;
    private static final int MG4_DISPLAY_HEIGHT_PX = 720;
    private static final int MG4_LAUNCHER_BAR_WIDTH_PX = 142;
    private static final int MG4_SAIC_WORK_AREA_WIDTH_PX = MG4_DISPLAY_WIDTH_PX - MG4_LAUNCHER_BAR_WIDTH_PX;
    private static final int OVERLAY_MODE_SAIC = 0;
    private static final int OVERLAY_MODE_FULLSCREEN = 1;
    private static final String ANDROID_AUTO_PACKAGE = "com.allgo.app.androidauto";
    private static final String CARPLAY_PACKAGE = "com.allgo.remoteui.mediabrowserservice";

    private static final String PREFS_NAME = "overlay_prefs";
    private static final String KEY_LAST_X = "last_x";
    private static final String KEY_LAST_Y = "last_y";
    private static final String KEY_OVERLAY_W = "overlay_w";
    private static final String KEY_OVERLAY_H = "overlay_h";
    private static final String KEY_LAST_OVERLAY_MODE = "last_overlay_mode";

    private static volatile boolean sOverlayVisible = false;
    private static volatile String sOverlayReason = "";

    private WindowManager windowManager;
    private View overlayView;
    private TextureView textureView;
    private Surface textureSurface;
    private WindowManager.LayoutParams overlayParams;
    private int cameraIndex = CameraIndex.FRONT.getVideoIndex();
    private int attachedPreviewCameraIndex = -1;
    private String overlayReason = OVERLAY_REASON_SIGNAL;

    /** Current window size, updated via pinch gestures. */
    private int overlayWidthPx = DEFAULT_OVERLAY_WIDTH_PX;
    private int overlayHeightPx = DEFAULT_OVERLAY_HEIGHT_PX;
    private int overlayMode = OVERLAY_MODE_SAIC;

    private ScaleGestureDetector scaleGestureDetector;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Runnable foregroundModePollRunnable = new Runnable() {
        @Override
        public void run() {
            refreshOverlayMode(false);
            if (overlayView != null) {
                mainHandler.postDelayed(this, getForegroundModePollMs());
            }
        }
    };

    private float initialX;
    private float initialY;
    private float initialTouchX;
    private float initialTouchY;
    private android.content.SharedPreferences uiPrefs;
    private final android.content.SharedPreferences.OnSharedPreferenceChangeListener prefListener =
            (sharedPreferences, key) -> {
                if (UiPrefs.KEY_TILE_CORNER_RADIUS.equals(key)) {
                    applyOverlayCornerRadius();
                } else if (UiPrefs.KEY_OVERLAY_ROTATE_TO_DRIVING_DIRECTION.equals(key)) {
                    updateOverlayPresentation(true);
                } else if (UiPrefs.KEY_DEV_OVERLAY_TOP_INSET_PX.equals(key)) {
                    updateOverlayPresentation(true);
                } else if (UiPrefs.KEY_DEV_FOREGROUND_MODE_POLL_MS.equals(key)) {
                    restartForegroundModePolling();
                }
            };

    public static void showOverlay(Context context, int cameraIndex) {
        showOverlay(context, cameraIndex, OVERLAY_REASON_SIGNAL);
    }

    public static void showOverlay(Context context, int cameraIndex, String overlayReason) {
        Intent i = new Intent(context, OverlayService.class);
        i.putExtra(EXTRA_CAMERA_INDEX, cameraIndex);
        i.putExtra(EXTRA_OVERLAY_REASON, overlayReason);
        context.startForegroundService(i);
    }

    public static void hideOverlay(Context context) {
        Intent i = new Intent(context, OverlayService.class);
        context.stopService(i);
    }

    public static boolean isVisible() {
        return sOverlayVisible;
    }

    public static boolean isVisibleForReason(String reason) {
        return sOverlayVisible && reason != null && reason.equals(sOverlayReason);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        uiPrefs = UiPrefs.getPrefs(this);
        uiPrefs.registerOnSharedPreferenceChangeListener(prefListener);
        NotificationChannelHelper.ensureChannel(this, CHANNEL_ID, "MG4 Overlay");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && intent.hasExtra(EXTRA_CAMERA_INDEX)) {
            cameraIndex = intent.getIntExtra(EXTRA_CAMERA_INDEX, CameraIndex.FRONT.getVideoIndex());
        }
        if (intent != null && intent.hasExtra(EXTRA_OVERLAY_REASON)) {
            overlayReason = intent.getStringExtra(EXTRA_OVERLAY_REASON);
        }
        sOverlayReason = overlayReason == null ? "" : overlayReason;
        // Run as a foreground service so the overlay survives while the app is in the background.
        Notification notif = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(getOverlayNotificationText())
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
        startForeground(NOTIF_ID, notif);

        if (overlayView == null) {
            showFloatingWindow();
        } else {
            sOverlayVisible = true;
            refreshOverlayMode(false);
            applyOverlayCornerRadius();
            updateOverlayPresentation(false);
            // If the overlay is already open and only the camera index changed, switch the feed.
            if (textureSurface != null && textureSurface.isValid()) {
                startPreview();
            }
        }
        restartForegroundModePolling();
        return START_STICKY;
    }

    private void showFloatingWindow() {
        if (windowManager == null) return;

        int layoutType = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;

        // Size: use the saved value or the default while keeping the aspect ratio fixed.
        try {
            android.content.SharedPreferences sp =
                    getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
            int w = sp.getInt(KEY_OVERLAY_W, DEFAULT_OVERLAY_WIDTH_PX);
            int h = sp.getInt(KEY_OVERLAY_H, DEFAULT_OVERLAY_HEIGHT_PX);
            if (w >= OVERLAY_MIN_WIDTH_PX && h >= 1) {
                overlayWidthPx = w;
                overlayHeightPx = h;
            }
        } catch (Throwable e) {
            Log.w("OverlayService", "Failed to read overlay size prefs", e);
        }
        normalizeOverlaySizeForCurrentMode();

        overlayParams = new WindowManager.LayoutParams(
                overlayWidthPx,
                overlayHeightPx,
                layoutType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
        );
        overlayParams.gravity = Gravity.TOP | Gravity.START;

        // Read the last saved position from prefs, or fall back to the default.
        int defaultX = 32;
        int defaultY = 120;
        try {
            android.content.SharedPreferences sp =
                    getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
            defaultX = sp.getInt(KEY_LAST_X, defaultX);
            defaultY = sp.getInt(KEY_LAST_Y, defaultY);
            int savedMode = sp.getInt(KEY_LAST_OVERLAY_MODE, OVERLAY_MODE_SAIC);
            overlayMode = resolveOverlayMode();
            defaultX = translateXBetweenModes(defaultX, savedMode, overlayMode);
            defaultY = translateCoordinate(defaultY, 0, 0);
        } catch (Throwable e) {
            Log.w("OverlayService", "Failed to read overlay position prefs", e);
            overlayMode = resolveOverlayMode();
        }
        overlayParams.x = defaultX;
        overlayParams.y = defaultY;
        clampOverlayPositionToScreen();

        overlayView = createOverlayCard();

        scaleGestureDetector = new ScaleGestureDetector(this,
                new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    @Override
                    public boolean onScale(ScaleGestureDetector detector) {
                        float factor = detector.getScaleFactor();
                        if (factor <= 0f || Float.isNaN(factor)) return true;
                        int newW = Math.round(overlayWidthPx * factor);
                        int[] wh = clampOverlaySize(newW);
                        overlayWidthPx = wh[0];
                        overlayHeightPx = wh[1];
                        overlayParams.width = overlayWidthPx;
                        overlayParams.height = overlayHeightPx;
                        clampOverlayPositionToScreen();
                        windowManager.updateViewLayout(overlayView, overlayParams);
                        applyPreviewTransform();
                        return true;
                    }

                    @Override
                    public void onScaleEnd(ScaleGestureDetector detector) {
                        saveOverlayLayoutPrefs();
                    }
                });

        windowManager.addView(overlayView, overlayParams);
        sOverlayVisible = true;
        sOverlayReason = overlayReason == null ? "" : overlayReason;
        applyPreviewTransform();

        overlayView.setOnTouchListener((v, event) -> {
            // Two fingers: pinch to resize, with no dragging.
            scaleGestureDetector.onTouchEvent(event);

            int action = event.getActionMasked();
            int pointerCount = event.getPointerCount();

            if (pointerCount >= 2) {
                if (action == MotionEvent.ACTION_POINTER_UP
                        || action == MotionEvent.ACTION_CANCEL
                        || action == MotionEvent.ACTION_UP) {
                    saveOverlayLayoutPrefs();
                }
                return true;
            }

            switch (action) {
                case MotionEvent.ACTION_DOWN:
                    initialX = overlayParams.x;
                    initialY = overlayParams.y;
                    initialTouchX = event.getRawX();
                    initialTouchY = event.getRawY();
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (scaleGestureDetector.isInProgress()) {
                        return true;
                    }
                    float dx = event.getRawX() - initialTouchX;
                    float dy = event.getRawY() - initialTouchY;
                    int newX = (int) (initialX + dx);
                    int newY = (int) (initialY + dy);
                    overlayParams.x = newX;
                    overlayParams.y = newY;
                    clampOverlayPositionToScreen();
                    windowManager.updateViewLayout(overlayView, overlayParams);
                    return true;
                case MotionEvent.ACTION_UP:
                    saveOverlayLayoutPrefs();
                    float tapDx = event.getRawX() - initialTouchX;
                    float tapDy = event.getRawY() - initialTouchY;
                    boolean isTap = (tapDx * tapDx + tapDy * tapDy) < (float) (dpToPx(12) * dpToPx(12));
                    if (isTap && uiPrefs != null) {
                        if (OVERLAY_REASON_DIGITAL_REARVIEW.equals(overlayReason)
                                && UiPrefs.isDigitalRearviewTapToHideEnabled(uiPrefs)) {
                            SignalService.startRearviewTempHide(OverlayService.this);
                        } else if (OVERLAY_REASON_SIGNAL.equals(overlayReason)
                                && UiPrefs.isSignalOverlayTapToHideEnabled(uiPrefs)) {
                            SignalService.startSignalTempHide(OverlayService.this);
                        }
                    }
                    v.performClick();
                    return true;
                default:
                    return false;
            }
        });
    }

    private View createOverlayCard() {
        FrameLayout card = new FrameLayout(this);
        card.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));
        card.setBackgroundResource(R.drawable.bg_overlay_tile);
        card.setClipToOutline(true);
        card.setOutlineProvider(ViewOutlineProvider.BACKGROUND);

        textureView = new TextureView(this);
        textureView.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));
        textureView.setOpaque(false);
        textureView.setSurfaceTextureListener(this);
        card.addView(textureView);

        ImageButton btnDismissOverlay = new ImageButton(this);
        FrameLayout.LayoutParams closeParams = new FrameLayout.LayoutParams(
                dpToPx(84),
                dpToPx(84),
                Gravity.TOP | Gravity.START
        );
        closeParams.leftMargin = dpToPx(3);
        closeParams.topMargin = dpToPx(3);
        btnDismissOverlay.setLayoutParams(closeParams);
        btnDismissOverlay.setBackground(null);
        btnDismissOverlay.setImageResource(R.drawable.ic_close);
        btnDismissOverlay.setColorFilter(0xFFFFFFFF);
        btnDismissOverlay.setPadding(dpToPx(20), dpToPx(20), dpToPx(20), dpToPx(20));
        btnDismissOverlay.setContentDescription("Close overlay");
        btnDismissOverlay.setOnClickListener(v -> {
            if (OVERLAY_REASON_DIGITAL_REARVIEW.equals(overlayReason) && uiPrefs != null) {
                UiPrefs.setDigitalRearviewEnabled(uiPrefs, false);
                SignalService.requestRecheck();
            }
            hideOverlay(OverlayService.this);
        });
        card.addView(btnDismissOverlay);
        applyOverlayCornerRadius(card);

        return card;
    }

    private void applyOverlayCornerRadius() {
        applyOverlayCornerRadius(overlayView);
    }

    private void applyOverlayCornerRadius(View target) {
        if (target == null || uiPrefs == null) return;
        target.post(() -> {
            if (!(target.getBackground() instanceof android.graphics.drawable.GradientDrawable)) {
                return;
            }
            android.graphics.drawable.GradientDrawable background =
                    (android.graphics.drawable.GradientDrawable) target.getBackground().mutate();
            background.setCornerRadius(UiPrefs.getCornerRadiusPx(target, uiPrefs));
            target.invalidateOutline();
        });
    }

    private int dpToPx(int dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
    }

    private String getOverlayNotificationText() {
        if (OVERLAY_REASON_DIGITAL_REARVIEW.equals(overlayReason)) {
            return getString(R.string.notification_digital_rearview_text);
        }
        return getString(R.string.notification_overlay_text);
    }

    /** Keeps the active overlay aspect ratio while clamping size to screen and min/max bounds. */
    private int[] clampOverlaySize(int w) {
        float aspect = getActiveOverlayAspect();
        int maxW = OVERLAY_MAX_WIDTH_PX;
        int maxH = OVERLAY_MAX_WIDTH_PX;
        Rect bounds = getAvailableOverlayBoundsPx();
        if (bounds != null) {
            maxW = Math.min(maxW, bounds.width());
            maxH = Math.min(maxH, bounds.height());
        }
        if (w < OVERLAY_MIN_WIDTH_PX) {
            w = OVERLAY_MIN_WIDTH_PX;
        }
        if (w > maxW) {
            w = maxW;
        }
        int h = Math.round(w / aspect);
        if (h > maxH) {
            h = maxH;
            w = Math.round(h * aspect);
            if (w < OVERLAY_MIN_WIDTH_PX) {
                w = OVERLAY_MIN_WIDTH_PX;
            }
            if (w > maxW) {
                w = maxW;
            }
            h = Math.round(w / aspect);
        }
        return new int[]{w, h};
    }

    private void normalizeOverlaySizeForCurrentMode() {
        boolean shouldRotate = shouldRotatePreviewToDrivingDirection();
        boolean isLandscape = overlayWidthPx >= overlayHeightPx;
        if (shouldRotate == isLandscape) {
            int swappedWidth = overlayHeightPx;
            overlayHeightPx = overlayWidthPx;
            overlayWidthPx = swappedWidth;
        }
        int[] clamped = clampOverlaySize(overlayWidthPx);
        overlayWidthPx = clamped[0];
        overlayHeightPx = clamped[1];
    }

    private float getActiveOverlayAspect() {
        return shouldRotatePreviewToDrivingDirection()
                ? (float) DEFAULT_OVERLAY_HEIGHT_PX / (float) DEFAULT_OVERLAY_WIDTH_PX
                : (float) DEFAULT_OVERLAY_WIDTH_PX / (float) DEFAULT_OVERLAY_HEIGHT_PX;
    }

    private boolean shouldRotatePreviewToDrivingDirection() {
        CameraIndex ci = CameraIndex.fromVideoIndex(cameraIndex);
        return uiPrefs != null
                && UiPrefs.isOverlayRotationToDrivingDirectionEnabled(uiPrefs)
                && ci != null && ci.isSide();
    }

    private float getPreviewRotationDegrees() {
        if (!shouldRotatePreviewToDrivingDirection()) {
            return 0f;
        }
        return CameraIndex.fromVideoIndex(cameraIndex) == CameraIndex.LEFT ? -90f : 90f;
    }

    private void updateOverlayPresentation(boolean persist) {
        normalizeOverlaySizeForCurrentMode();
        if (overlayParams != null) {
            overlayParams.width = overlayWidthPx;
            overlayParams.height = overlayHeightPx;
            clampOverlayPositionToScreen();
            if (windowManager != null && overlayView != null) {
                windowManager.updateViewLayout(overlayView, overlayParams);
            }
        }
        applyPreviewTransform();
        if (persist) {
            saveOverlayLayoutPrefs();
        }
    }

    private void applyPreviewTransform() {
        final TextureView tv = textureView;
        if (tv == null) return;
        tv.post(() -> {
            FrameLayout.LayoutParams params;
            if (shouldRotatePreviewToDrivingDirection()) {
                params = new FrameLayout.LayoutParams(
                        overlayHeightPx,
                        overlayWidthPx,
                        Gravity.CENTER
                );
            } else {
                params = new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        Gravity.CENTER
                );
            }
            tv.setLayoutParams(params);
            tv.setScaleX(1f);
            tv.setScaleY(1f);
            tv.setRotation(getPreviewRotationDegrees());
        });
    }

    private void clampOverlayPositionToScreen() {
        clampOverlayPositionToBounds(getAvailableOverlayBoundsPx());
    }

    private void clampOverlayPositionToBounds(Rect bounds) {
        if (windowManager == null || overlayParams == null) return;
        if (bounds == null) return;
        int maxX = Math.max(bounds.left, bounds.right - overlayParams.width);
        int maxY = Math.max(bounds.top, bounds.bottom - overlayParams.height);
        if (overlayParams.x < bounds.left) overlayParams.x = bounds.left;
        if (overlayParams.y < bounds.top) overlayParams.y = bounds.top;
        if (overlayParams.x > maxX) overlayParams.x = maxX;
        if (overlayParams.y > maxY) overlayParams.y = maxY;
    }

    private Rect getAvailableOverlayBoundsPx() {
        return getBoundsForMode(overlayMode);
    }

    private Rect getBoundsForMode(int mode) {
        int topInsetPx = uiPrefs == null ? UiPrefs.DEFAULT_DEV_OVERLAY_TOP_INSET_PX
                : UiPrefs.getDevOverlayTopInsetPx(uiPrefs);
        if (mode == OVERLAY_MODE_FULLSCREEN) {
            return new Rect(0, topInsetPx, MG4_DISPLAY_WIDTH_PX, MG4_DISPLAY_HEIGHT_PX);
        }
        return new Rect(0, topInsetPx, MG4_SAIC_WORK_AREA_WIDTH_PX, MG4_DISPLAY_HEIGHT_PX);
    }

    private int resolveOverlayMode() {
        return isProjectionAppInForeground() ? OVERLAY_MODE_FULLSCREEN : OVERLAY_MODE_SAIC;
    }

    private void refreshOverlayMode(boolean persist) {
        int newMode = resolveOverlayMode();
        if (newMode == overlayMode) {
            return;
        }
        int oldMode = overlayMode;
        Rect oldBounds = getBoundsForMode(overlayMode);
        Rect newBounds = getBoundsForMode(newMode);
        overlayMode = newMode;
        if (overlayParams == null || oldBounds == null || newBounds == null) {
            return;
        }
        overlayParams.x = translateXBetweenModes(overlayParams.x, oldMode, newMode);
        overlayParams.y = translateCoordinate(overlayParams.y, oldBounds.top, newBounds.top);
        int[] clampedSize = clampOverlaySize(overlayWidthPx);
        overlayWidthPx = clampedSize[0];
        overlayHeightPx = clampedSize[1];
        overlayParams.width = overlayWidthPx;
        overlayParams.height = overlayHeightPx;
        clampOverlayPositionToBounds(newBounds);
        if (windowManager != null && overlayView != null) {
            windowManager.updateViewLayout(overlayView, overlayParams);
            applyPreviewTransform();
        }
        if (persist) {
            saveOverlayLayoutPrefs();
        }
    }

    private int translateCoordinate(int value, int oldOrigin, int newOrigin) {
        return newOrigin + (value - oldOrigin);
    }

    private int translateXBetweenModes(int value, int oldMode, int newMode) {
        return value + getVisualLeftInsetForMode(oldMode) - getVisualLeftInsetForMode(newMode);
    }

    private int getVisualLeftInsetForMode(int mode) {
        return mode == OVERLAY_MODE_FULLSCREEN ? 0 : MG4_LAUNCHER_BAR_WIDTH_PX;
    }

    private boolean isProjectionAppInForeground() {
        try {
            ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
            if (am == null) return false;
            // NOTE: getRunningTasks() returns foreign tasks only because this APK
            // is signed with platform keys. Deprecated since API 21 but functional
            // on privileged installs. Would break on a non-privileged install.
            java.util.List<ActivityManager.RunningTaskInfo> tasks = am.getRunningTasks(1);
            if (tasks == null || tasks.isEmpty()) return false;
            ActivityManager.RunningTaskInfo task = tasks.get(0);
            ComponentName topActivity = task.topActivity;
            if (topActivity == null) return false;
            String packageName = topActivity.getPackageName();
            return ANDROID_AUTO_PACKAGE.equals(packageName) || CARPLAY_PACKAGE.equals(packageName);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private void restartForegroundModePolling() {
        mainHandler.removeCallbacks(foregroundModePollRunnable);
        if (overlayView != null) {
            mainHandler.postDelayed(foregroundModePollRunnable, getForegroundModePollMs());
        }
    }

    private int getForegroundModePollMs() {
        return uiPrefs == null ? UiPrefs.DEFAULT_DEV_FOREGROUND_MODE_POLL_MS
                : UiPrefs.getDevForegroundModePollMs(uiPrefs);
    }

    private void saveOverlayLayoutPrefs() {
        if (overlayParams == null) return;
        try {
            android.content.SharedPreferences sp =
                    getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
            sp.edit()
                    .putInt(KEY_LAST_X, overlayParams.x)
                    .putInt(KEY_LAST_Y, overlayParams.y)
                    .putInt(KEY_OVERLAY_W, overlayWidthPx)
                    .putInt(KEY_OVERLAY_H, overlayHeightPx)
                    .putInt(KEY_LAST_OVERLAY_MODE, overlayMode)
                    .apply();
        } catch (Throwable e) {
            Log.w("OverlayService", "Failed to save overlay prefs", e);
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (uiPrefs != null) {
            uiPrefs.unregisterOnSharedPreferenceChangeListener(prefListener);
        }
        mainHandler.removeCallbacks(foregroundModePollRunnable);
        stopPreview();
        if (textureSurface != null) {
            textureSurface.release();
            textureSurface = null;
        }
        if (windowManager != null && overlayView != null) {
            windowManager.removeView(overlayView);
        }
        overlayView = null;
        textureView = null;
        sOverlayVisible = false;
        sOverlayReason = "";
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // Texture callbacks

    @Override
    public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
        textureSurface = new Surface(surface);
        applyPreviewTransform();
        startPreview();
    }

    @Override
    public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
        applyPreviewTransform();
    }

    @Override
    public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
        stopPreview();
        if (textureSurface != null) {
            textureSurface.release();
            textureSurface = null;
        }
        return true;
    }

    @Override
    public void onSurfaceTextureUpdated(SurfaceTexture surface) {
        // no-op
    }

    private void startPreview() {
        if (textureSurface == null || !textureSurface.isValid()) {
            return;
        }
        applyPreviewTransform();
        if (attachedPreviewCameraIndex != -1 && attachedPreviewCameraIndex != cameraIndex) {
            CameraProbe.detachPreview(attachedPreviewCameraIndex);
            attachedPreviewCameraIndex = -1;
        }
        if (attachedPreviewCameraIndex == cameraIndex) {
            return;
        }
        if (CameraProbe.attachPreview(cameraIndex, textureSurface)) {
            attachedPreviewCameraIndex = cameraIndex;
        }
    }

    private void stopPreview() {
        if (attachedPreviewCameraIndex == -1) return;
        CameraProbe.detachPreview(attachedPreviewCameraIndex);
        attachedPreviewCameraIndex = -1;
    }
}
