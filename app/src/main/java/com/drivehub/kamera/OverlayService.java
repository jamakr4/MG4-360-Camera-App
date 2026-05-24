package com.drivehub.kamera;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.IBinder;
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

    private WindowManager windowManager;
    private View overlayView;
    private TextureView textureView;
    private Surface textureSurface;
    private WindowManager.LayoutParams overlayParams;
    private int cameraIndex = 15; // Default: front
    private int attachedPreviewCameraIndex = -1;

    /** Current window size, updated via pinch gestures. */
    private int overlayWidthPx = DEFAULT_OVERLAY_WIDTH_PX;
    private int overlayHeightPx = DEFAULT_OVERLAY_HEIGHT_PX;

    private ScaleGestureDetector scaleGestureDetector;

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
                }
            };

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
        uiPrefs = UiPrefs.getPrefs(this);
        uiPrefs.registerOnSharedPreferenceChangeListener(prefListener);
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
                .setContentTitle(getString(R.string.app_name))
                .setContentText(getString(R.string.notification_overlay_text))
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
        startForeground(NOTIF_ID, notif);

        if (overlayView == null) {
            showFloatingWindow();
        } else {
            applyOverlayCornerRadius();
            updateOverlayPresentation(false);
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
                        applyPreviewTransform();
                        return true;
                    }

                    @Override
                    public void onScaleEnd(ScaleGestureDetector detector) {
                        saveOverlayLayoutPrefs();
                    }
                });

        windowManager.addView(overlayView, overlayParams);
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
        btnDismissOverlay.setOnClickListener(v -> hideOverlay(OverlayService.this));
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

    /** Keeps the active overlay aspect ratio while clamping size to screen and min/max bounds. */
    private int[] clampOverlaySize(int w) {
        float aspect = getActiveOverlayAspect();
        int maxW = OVERLAY_MAX_WIDTH_PX;
        int maxH = OVERLAY_MAX_WIDTH_PX;
        int[] screenSize = getAvailableScreenSizePx();
        if (screenSize != null) {
            maxW = Math.min(maxW, screenSize[0]);
            maxH = Math.min(maxH, screenSize[1]);
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
        return uiPrefs != null
                && UiPrefs.isOverlayRotationToDrivingDirectionEnabled(uiPrefs)
                && (cameraIndex == 14 || cameraIndex == 16);
    }

    private float getPreviewRotationDegrees() {
        if (!shouldRotatePreviewToDrivingDirection()) {
            return 0f;
        }
        return cameraIndex == 16 ? -90f : 90f;
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
        if (textureView == null) return;
        textureView.post(() -> {
            if (textureView == null) return;
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
            textureView.setLayoutParams(params);
            textureView.setScaleX(1f);
            textureView.setScaleY(1f);
            textureView.setRotation(getPreviewRotationDegrees());
        });
    }

    private void clampOverlayPositionToScreen() {
        if (windowManager == null || overlayParams == null) return;
        int[] screenSize = getAvailableScreenSizePx();
        if (screenSize == null) return;
        int maxX = Math.max(0, screenSize[0] - overlayParams.width);
        int maxY = Math.max(0, screenSize[1] - overlayParams.height);
        if (overlayParams.x < 0) overlayParams.x = 0;
        if (overlayParams.y < 0) overlayParams.y = 0;
        if (overlayParams.x > maxX) overlayParams.x = maxX;
        if (overlayParams.y > maxY) overlayParams.y = maxY;
    }

    // Android Auto can expose a smaller "current" app viewport than the actual interactive
    // overlay space. Maximum window metrics are a better fit for drag/resize bounds here.
    private int[] getAvailableScreenSizePx() {
        if (windowManager == null) return null;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Rect bounds = windowManager.getMaximumWindowMetrics().getBounds();
            return new int[]{bounds.width(), bounds.height()};
        }

        android.util.DisplayMetrics dm = new android.util.DisplayMetrics();
        windowManager.getDefaultDisplay().getRealMetrics(dm);
        return new int[]{dm.widthPixels, dm.heightPixels};
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
                    .apply();
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (uiPrefs != null) {
            uiPrefs.unregisterOnSharedPreferenceChangeListener(prefListener);
        }
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
