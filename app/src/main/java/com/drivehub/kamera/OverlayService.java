package com.drivehub.kamera;

import android.animation.ValueAnimator;
import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.Intent;
import android.graphics.SurfaceTexture;
import android.graphics.PixelFormat;
import android.os.IBinder;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.Surface;
import android.view.TextureView;
import android.view.VelocityTracker;
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

    private static final String CHANNEL_ID = "mg4_overlay";
    private static final int NOTIF_ID = 99;

    /** Default overlay size in px; the aspect ratio is preserved. */
    private static final int DEFAULT_OVERLAY_WIDTH_PX = 1000;
    private static final int DEFAULT_OVERLAY_HEIGHT_PX = 480;
    /** Min/max bounds for two-finger pinch resizing. */
    private static final int OVERLAY_MIN_WIDTH_PX = 240;
    private static final int OVERLAY_MAX_WIDTH_PX = 3840;

    private static final String PREFS_NAME = "overlay_prefs";
    private static final String KEY_LAST_X = "last_x";
    private static final String KEY_LAST_Y = "last_y";
    private static final String KEY_OVERLAY_W = "overlay_w";
    private static final String KEY_OVERLAY_H = "overlay_h";
    private static final String KEY_SOFT_SNAP_ENABLED = "softSnapEnabled";
    private static final String KEY_SOFT_SNAP_PADDING_X = "softSnapPaddingX";
    private static final String KEY_SOFT_SNAP_PADDING_Y = "softSnapPaddingY";
    private static final int DEFAULT_SOFT_SNAP_PADDING_X = 32;
    private static final int DEFAULT_SOFT_SNAP_PADDING_Y = 64;
    private static final int SOFT_SNAP_MIN_FLING_VELOCITY_PX = 900;
    private static final float SOFT_SNAP_PROJECTION_TIME_SECONDS = 0.18f;

    private WindowManager windowManager;
    private View overlayView;
    private TextureView textureView;
    private Surface textureSurface;
    private WindowManager.LayoutParams overlayParams;
    private int cameraIndex = 15; // Default: front

    /** Current window size, updated via pinch gestures. */
    private int overlayWidthPx = DEFAULT_OVERLAY_WIDTH_PX;
    private int overlayHeightPx = DEFAULT_OVERLAY_HEIGHT_PX;

    private ScaleGestureDetector scaleGestureDetector;

    private float initialX;
    private float initialY;
    private float initialTouchX;
    private float initialTouchY;
    private VelocityTracker velocityTracker;
    private ValueAnimator snapAnimator;

    public static void showOverlay(Context context, int cameraIndex) {
        Intent i = new Intent(context, OverlayService.class);
        i.putExtra(EXTRA_CAMERA_INDEX, cameraIndex);
        context.startForegroundService(i);
    }

    public static void hideOverlay(Context context) {
        Intent i = new Intent(context, OverlayService.class);
        context.stopService(i);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && intent.hasExtra(EXTRA_CAMERA_INDEX)) {
            cameraIndex = intent.getIntExtra(EXTRA_CAMERA_INDEX, 15);
        }
        // Run as a foreground service so the overlay survives while the app is in the background.
        Notification notif = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setContentTitle("Drivehub Kamera")
                .setContentText("Sinyal kamerası overlay açık")
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
        startForeground(NOTIF_ID, notif);

        if (overlayView == null) {
            showFloatingWindow();
        } else {
            // If the overlay is already open and only the camera index changed, switch the feed.
            if (textureSurface != null && textureSurface.isValid()) {
                startPreview();
            }
        }
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
        } catch (Throwable ignored) {
        }

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
        } catch (Throwable ignored) {
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
                        return true;
                    }

                    @Override
                    public void onScaleEnd(ScaleGestureDetector detector) {
                        saveOverlayLayoutPrefs();
                    }
                });

        windowManager.addView(overlayView, overlayParams);

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
                    stopSnapAnimation();
                    obtainVelocityTracker().addMovement(event);
                    initialX = overlayParams.x;
                    initialY = overlayParams.y;
                    initialTouchX = event.getRawX();
                    initialTouchY = event.getRawY();
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (scaleGestureDetector.isInProgress()) {
                        return true;
                    }
                    if (velocityTracker != null) {
                        velocityTracker.addMovement(event);
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
                    if (velocityTracker != null) {
                        velocityTracker.addMovement(event);
                    }
                    maybeSoftSnapAfterRelease();
                    saveOverlayLayoutPrefs();
                    recycleVelocityTracker();
                    v.performClick();
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    recycleVelocityTracker();
                    saveOverlayLayoutPrefs();
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
        //card.setBackgroundResource(R.drawable.bg_overlay_tile);
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
        //btnDismissOverlay.setBackgroundResource(R.drawable.bg_close_button);
        btnDismissOverlay.setImageResource(R.drawable.ic_close);
        btnDismissOverlay.setColorFilter(0xFFFFFFFF);
        btnDismissOverlay.setPadding(dpToPx(20), dpToPx(20), dpToPx(20), dpToPx(20));
        btnDismissOverlay.setContentDescription("Close overlay");
        btnDismissOverlay.setOnClickListener(v -> hideOverlay(OverlayService.this));
        card.addView(btnDismissOverlay);

        return card;
    }

    private int dpToPx(int dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
    }

    /** Keeps the 1000:480 aspect ratio while clamping size to screen and min/max bounds. */
    private int[] clampOverlaySize(int w) {
        float aspect = (float) DEFAULT_OVERLAY_WIDTH_PX / (float) DEFAULT_OVERLAY_HEIGHT_PX;
        int maxW = OVERLAY_MAX_WIDTH_PX;
        int maxH = OVERLAY_MAX_WIDTH_PX;
        if (windowManager != null) {
            android.util.DisplayMetrics dm = new android.util.DisplayMetrics();
            windowManager.getDefaultDisplay().getMetrics(dm);
            maxW = Math.min(maxW, dm.widthPixels);
            maxH = Math.min(maxH, dm.heightPixels);
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

    private void clampOverlayPositionToScreen() {
        if (windowManager == null || overlayParams == null) return;
        android.util.DisplayMetrics dm = new android.util.DisplayMetrics();
        windowManager.getDefaultDisplay().getMetrics(dm);
        int maxX = Math.max(0, dm.widthPixels - overlayParams.width);
        int maxY = Math.max(0, dm.heightPixels - overlayParams.height);
        if (overlayParams.x < 0) overlayParams.x = 0;
        if (overlayParams.y < 0) overlayParams.y = 0;
        if (overlayParams.x > maxX) overlayParams.x = maxX;
        if (overlayParams.y > maxY) overlayParams.y = maxY;
    }

    private void maybeSoftSnapAfterRelease() {
        if (overlayParams == null || windowManager == null || !isSoftSnapEnabled()) {
            return;
        }
        if (velocityTracker == null) {
            return;
        }

        velocityTracker.computeCurrentVelocity(1000);
        float velocityX = velocityTracker.getXVelocity();
        float velocityY = velocityTracker.getYVelocity();
        float speed = (float) Math.hypot(velocityX, velocityY);
        if (speed < SOFT_SNAP_MIN_FLING_VELOCITY_PX) {
            return;
        }

        int projectedX = Math.round(overlayParams.x + velocityX * SOFT_SNAP_PROJECTION_TIME_SECONDS);
        int projectedY = Math.round(overlayParams.y + velocityY * SOFT_SNAP_PROJECTION_TIME_SECONDS);
        SnapTarget target = findSnapTarget(projectedX, projectedY);
        if (target != null) {
            animateOverlayTo(target.x, target.y);
        }
    }

    private boolean isSoftSnapEnabled() {
        return getSharedPreferences("rec_prefs", MODE_PRIVATE)
                .getBoolean(KEY_SOFT_SNAP_ENABLED, false);
    }

    private SnapTarget findSnapTarget(int projectedX, int projectedY) {
        android.util.DisplayMetrics dm = new android.util.DisplayMetrics();
        windowManager.getDefaultDisplay().getMetrics(dm);
        int screenWidth = dm.widthPixels;
        int screenHeight = dm.heightPixels;

        int snapPaddingX = getSharedPreferences("rec_prefs", MODE_PRIVATE)
                .getInt(KEY_SOFT_SNAP_PADDING_X, DEFAULT_SOFT_SNAP_PADDING_X);
        int snapPaddingY = getSharedPreferences("rec_prefs", MODE_PRIVATE)
                .getInt(KEY_SOFT_SNAP_PADDING_Y, DEFAULT_SOFT_SNAP_PADDING_Y);

        int leftTarget = clampX(snapPaddingX, screenWidth);
        int rightTarget = clampX(screenWidth - overlayParams.width - snapPaddingX, screenWidth);
        int topTarget = clampY(snapPaddingY, screenHeight);
        int bottomTarget = clampY(screenHeight - overlayParams.height - snapPaddingY, screenHeight);

        int zoneWidth = Math.max(dpToPx(160), overlayParams.width / 2);
        int zoneHeight = Math.max(dpToPx(110), overlayParams.height / 2);

        if (isWithinSnapZone(projectedX, projectedY, leftTarget, topTarget, zoneWidth, zoneHeight)) {
            return new SnapTarget(leftTarget, topTarget);
        }
        if (isWithinSnapZone(projectedX, projectedY, rightTarget, topTarget, zoneWidth, zoneHeight)) {
            return new SnapTarget(rightTarget, topTarget);
        }
        if (isWithinSnapZone(projectedX, projectedY, leftTarget, bottomTarget, zoneWidth, zoneHeight)) {
            return new SnapTarget(leftTarget, bottomTarget);
        }
        if (isWithinSnapZone(projectedX, projectedY, rightTarget, bottomTarget, zoneWidth, zoneHeight)) {
            return new SnapTarget(rightTarget, bottomTarget);
        }
        return null;
    }

    private boolean isWithinSnapZone(
            int projectedX,
            int projectedY,
            int targetX,
            int targetY,
            int zoneWidth,
            int zoneHeight
    ) {
        return projectedX >= targetX - zoneWidth
                && projectedX <= targetX + zoneWidth
                && projectedY >= targetY - zoneHeight
                && projectedY <= targetY + zoneHeight;
    }

    private void animateOverlayTo(int targetX, int targetY) {
        if (overlayView == null || overlayParams == null || windowManager == null) return;
        stopSnapAnimation();
        final int startX = overlayParams.x;
        final int startY = overlayParams.y;
        snapAnimator = ValueAnimator.ofFloat(0f, 1f);
        snapAnimator.setDuration(160);
        snapAnimator.addUpdateListener(animation -> {
            float progress = (float) animation.getAnimatedValue();
            overlayParams.x = Math.round(startX + (targetX - startX) * progress);
            overlayParams.y = Math.round(startY + (targetY - startY) * progress);
            clampOverlayPositionToScreen();
            windowManager.updateViewLayout(overlayView, overlayParams);
        });
        snapAnimator.addListener(new AnimatorListenerAdapter() {
            private boolean cancelled;

            @Override
            public void onAnimationCancel(Animator animation) {
                cancelled = true;
            }

            @Override
            public void onAnimationEnd(Animator animation) {
                if (!cancelled) {
                    overlayParams.x = targetX;
                    overlayParams.y = targetY;
                    clampOverlayPositionToScreen();
                    saveOverlayLayoutPrefs();
                }
                snapAnimator = null;
            }
        });
        snapAnimator.start();
    }

    private void stopSnapAnimation() {
        if (snapAnimator != null) {
            snapAnimator.cancel();
            snapAnimator = null;
        }
    }

    private VelocityTracker obtainVelocityTracker() {
        if (velocityTracker == null) {
            velocityTracker = VelocityTracker.obtain();
        } else {
            velocityTracker.clear();
        }
        return velocityTracker;
    }

    private void recycleVelocityTracker() {
        if (velocityTracker != null) {
            velocityTracker.recycle();
            velocityTracker = null;
        }
    }

    private int clampX(int desiredX, int screenWidth) {
        return Math.max(0, Math.min(desiredX, Math.max(0, screenWidth - overlayParams.width)));
    }

    private int clampY(int desiredY, int screenHeight) {
        return Math.max(0, Math.min(desiredY, Math.max(0, screenHeight - overlayParams.height)));
    }

    private void saveOverlayLayoutPrefs() {
        if (overlayParams == null) return;
        try {
            SharedPreferences sp = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
            sp.edit()
                    .putInt(KEY_LAST_X, overlayParams.x)
                    .putInt(KEY_LAST_Y, overlayParams.y)
                    .putInt(KEY_OVERLAY_W, overlayWidthPx)
                    .putInt(KEY_OVERLAY_H, overlayHeightPx)
                    .apply();
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        stopSnapAnimation();
        recycleVelocityTracker();
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
    }

    private void createNotificationChannel() {
        NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID,
                "MG4 Overlay",
                NotificationManager.IMPORTANCE_LOW
        );
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.createNotificationChannel(ch);
        }
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
        startPreview();
    }

    @Override
    public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
        // no-op
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
        CameraProbe.stopPreview();
        CameraProbe.startPreview(cameraIndex, textureSurface);
    }

    private void stopPreview() {
        CameraProbe.stopPreview();
    }

    private static final class SnapTarget {
        final int x;
        final int y;

        SnapTarget(int x, int y) {
            this.x = x;
            this.y = y;
        }
    }
}
