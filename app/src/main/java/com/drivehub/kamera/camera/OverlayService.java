package com.drivehub.kamera.camera;

import com.drivehub.kamera.CameraProbe;
import com.drivehub.kamera.R;

import com.drivehub.kamera.helper.app.NotificationChannelHelper;
import com.drivehub.kamera.signal.SignalService;
import com.drivehub.kamera.settings.UiPrefs;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Rect;
import android.graphics.Point;
import android.graphics.Matrix;
import android.graphics.SurfaceTexture;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import android.view.Choreographer;
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
    private static final String EXTRA_HIDE_WITH_FADE = "hide_with_fade";
    public static final String OVERLAY_REASON_SIGNAL = "signal";
    public static final String OVERLAY_REASON_DIGITAL_REARVIEW = "digital_rearview";

    private static final String CHANNEL_ID = "mg4_overlay";
    private static final int NOTIF_ID = 99;

    /** Default overlay size in px; the aspect ratio is preserved. */
    private static final int DEFAULT_OVERLAY_WIDTH_PX = 1000;
    private static final int DEFAULT_OVERLAY_HEIGHT_PX = 480;
    /** Min/max bounds for two-finger pinch resizing. */
    private static final int OVERLAY_MIN_WIDTH_PX = 240;
    private static final int OVERLAY_MAX_WIDTH_PX = 3840;
    private static final long SIGNAL_FADE_IN_MS = 110L;
    private static final long SIGNAL_FADE_OUT_MS = 90L;
    private static final float SIGNAL_FADE_IN_START_ALPHA = 0.18f;

    private static final String PREFS_NAME = "overlay_prefs";
    private static final String KEY_LAST_X = "last_x";
    private static final String KEY_LAST_Y = "last_y";
    private static final String KEY_OVERLAY_W = "overlay_w";
    private static final String KEY_OVERLAY_H = "overlay_h";
    private static final String KEY_SCREEN_X = "screen_x";
    private static final String KEY_SCREEN_Y = "screen_y";

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
    private boolean fadeOutPending;

    /** Current window size, updated via pinch gestures. */
    private int overlayWidthPx = DEFAULT_OVERLAY_WIDTH_PX;
    private int overlayHeightPx = DEFAULT_OVERLAY_HEIGHT_PX;
    private int preferredWidthPx = DEFAULT_OVERLAY_WIDTH_PX;
    private int preferredHeightPx = DEFAULT_OVERLAY_HEIGHT_PX;
    private int preferredScreenX = 32, preferredScreenY = 120;
    private boolean restoreLegacyPosition;
    private boolean awaitingInitialGeometry;
    private FloatingWindowGeometry geometry;

    private ScaleGestureDetector scaleGestureDetector;
    private float resizeTargetWidthPx = DEFAULT_OVERLAY_WIDTH_PX;
    private boolean resizePending;
    private boolean resizeFrameScheduled;
    private boolean saveLayoutAfterResizeFrame;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Choreographer.FrameCallback resizeFrameCallback = frameTimeNanos -> {
        resizeFrameScheduled = false;
        applyPendingResize();
        if (saveLayoutAfterResizeFrame) {
            saveLayoutAfterResizeFrame = false;
            saveOverlayLayoutPrefs();
        }
    };
    private float initialX;
    private float initialY;
    private float initialTouchX;
    private float initialTouchY;
    private android.content.SharedPreferences uiPrefs;
    private final android.content.SharedPreferences.OnSharedPreferenceChangeListener prefListener =
            (sharedPreferences, key) -> {
                if (UiPrefs.KEY_SIGNAL_ENHANCED_PREVIEW.equals(key)
                        && OVERLAY_REASON_SIGNAL.equals(overlayReason)) {
                    stopPreview();
                    startPreview();
                } else if (UiPrefs.KEY_SIGNAL_COMPACT_ENABLED.equals(key)
                        || UiPrefs.KEY_SIGNAL_COMPACT_LEFT.equals(key)
                        || UiPrefs.KEY_SIGNAL_COMPACT_RIGHT.equals(key)) {
                    updateOverlayPresentation(false);
                } else if (UiPrefs.KEY_TILE_CORNER_RADIUS.equals(key)) {
                    applyOverlayCornerRadius();
                } else if (UiPrefs.KEY_OVERLAY_ROTATE_TO_DRIVING_DIRECTION.equals(key)) {
                    updateOverlayPresentation(true);
                } else if (UiPrefs.KEY_DEV_OVERLAY_TOP_INSET_PX.equals(key)) {
                    updateOverlayPresentation(true);
                } else if (UiPrefs.KEY_OVERLAY_RESIZE_LOCKED.equals(key)
                        && UiPrefs.isOverlayResizeLocked(sharedPreferences)) {
                    cancelPendingResize();
                } else if (UiPrefs.KEY_SMOOTH_ENTRY.equals(key)
                        && !UiPrefs.isSmoothEntryEnabled(sharedPreferences)
                        && overlayView != null) {
                    if (fadeOutPending) {
                        fadeOutPending = false;
                        overlayView.animate().cancel();
                        stopSelf();
                    } else {
                        overlayView.animate().cancel();
                        if (!awaitingInitialGeometry) overlayView.setAlpha(1f);
                    }
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

    public static void hideOverlayWithFade(Context context) {
        if (!sOverlayVisible) return;
        if (!UiPrefs.isSmoothEntryEnabled(UiPrefs.getPrefs(context))) {
            hideOverlay(context);
            return;
        }
        Intent i = new Intent(context, OverlayService.class);
        i.putExtra(EXTRA_HIDE_WITH_FADE, true);
        context.startForegroundService(i);
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

        if (intent != null && intent.getBooleanExtra(EXTRA_HIDE_WITH_FADE, false)) {
            fadeOutAndStop();
            return START_NOT_STICKY;
        }

        if (overlayView == null) {
            showFloatingWindow();
        } else {
            fadeOutPending = false;
            overlayView.animate().cancel();
            if (!awaitingInitialGeometry) overlayView.setAlpha(1f);
            sOverlayVisible = true;
            if (geometry != null) geometry.refresh();
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
                preferredWidthPx = w;
                preferredHeightPx = h;
            }
        } catch (Throwable e) {
            Log.w("OverlayService", "Failed to read overlay size prefs", e);
        }
        normalizeOverlaySize();

        overlayParams = new WindowManager.LayoutParams(
                getVisibleOverlayWidth(),
                getVisibleOverlayHeight(),
                layoutType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
        );
        overlayParams.gravity = Gravity.TOP | Gravity.START;

        // New positions use physical screen coordinates. Old window coordinates are
        // migrated once, after the actual origin has been measured on the attached view.
        android.content.SharedPreferences sp = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        restoreLegacyPosition = !sp.contains(KEY_SCREEN_X) && sp.contains(KEY_LAST_X);
        preferredScreenX = sp.getInt(KEY_SCREEN_X, sp.getInt(KEY_LAST_X, 32));
        preferredScreenY = sp.getInt(KEY_SCREEN_Y, sp.getInt(KEY_LAST_Y, 120));
        overlayParams.x = preferredScreenX;
        overlayParams.y = preferredScreenY;
        if (!restoreLegacyPosition) clampOverlayPositionToScreen();

        overlayView = createOverlayCard();

        scaleGestureDetector = new ScaleGestureDetector(this,
                new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    @Override
                    public boolean onScaleBegin(ScaleGestureDetector detector) {
                        if (uiPrefs != null && UiPrefs.isOverlayResizeLocked(uiPrefs)) {
                            return false;
                        }
                        if (!resizePending) {
                            resizeTargetWidthPx = overlayWidthPx;
                        }
                        saveLayoutAfterResizeFrame = false;
                        return true;
                    }

                    @Override
                    public boolean onScale(ScaleGestureDetector detector) {
                        if (uiPrefs != null && UiPrefs.isOverlayResizeLocked(uiPrefs)) {
                            return true;
                        }
                        float factor = detector.getScaleFactor();
                        int minWidth = clampOverlaySize(OVERLAY_MIN_WIDTH_PX)[0];
                        int maxWidth = clampOverlaySize(OVERLAY_MAX_WIDTH_PX)[0];
                        float nextTargetWidth = OverlayResizeMath.updateTargetWidth(
                                resizeTargetWidthPx,
                                factor,
                                minWidth,
                                maxWidth
                        );
                        if (Float.compare(nextTargetWidth, resizeTargetWidthPx) != 0) {
                            resizeTargetWidthPx = nextTargetWidth;
                            resizePending = true;
                            scheduleResizeFrame();
                        }
                        return true;
                    }

                    @Override
                    public void onScaleEnd(ScaleGestureDetector detector) {
                        finishResizeGesture();
                    }
                });

        awaitingInitialGeometry = true;
        overlayView.setAlpha(0f);
        windowManager.addView(overlayView, overlayParams);
        geometry = new FloatingWindowGeometry(windowManager, overlayView, overlayParams,
                this::onWindowGeometryChanged);
        sOverlayVisible = true;
        sOverlayReason = overlayReason == null ? "" : overlayReason;
        applyPreviewTransform();

        overlayView.setOnTouchListener((v, event) -> {
            // Two fingers: pinch to resize, with no dragging.
            scaleGestureDetector.onTouchEvent(event);

            int action = event.getActionMasked();
            int pointerCount = event.getPointerCount();

            if (action == MotionEvent.ACTION_CANCEL) {
                finishResizeGesture();
                if (geometry != null) geometry.setPaused(false);
                return true;
            }

            if (pointerCount >= 2) {
                return true;
            }

            switch (action) {
                case MotionEvent.ACTION_DOWN:
                    if (geometry != null) geometry.setPaused(true);
                    initialX = overlayParams.x + windowOriginX();
                    initialY = overlayParams.y + windowOriginY();
                    initialTouchX = event.getRawX();
                    initialTouchY = event.getRawY();
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (scaleGestureDetector.isInProgress()) {
                        return true;
                    }
                    if (uiPrefs != null && UiPrefs.isOverlayMoveLocked(uiPrefs)) {
                        return true;
                    }
                    float dx = event.getRawX() - initialTouchX;
                    float dy = event.getRawY() - initialTouchY;
                    int newX = (int) (initialX + dx);
                    int newY = (int) (initialY + dy);
                    preferredScreenX = newX;
                    preferredScreenY = newY;
                    clampOverlayPositionToScreen();
                    rememberVisiblePosition();
                    updateWindowLayout();
                    return true;
                case MotionEvent.ACTION_UP:
                    finishResizeGesture();
                    if (geometry != null) geometry.setPaused(false);
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

    private void scheduleResizeFrame() {
        if (resizeFrameScheduled) return;
        resizeFrameScheduled = true;
        Choreographer.getInstance().postFrameCallback(resizeFrameCallback);
    }

    private void finishResizeGesture() {
        if (!resizePending) {
            if (!resizeFrameScheduled) {
                saveOverlayLayoutPrefs();
            } else {
                saveLayoutAfterResizeFrame = true;
            }
            return;
        }
        saveLayoutAfterResizeFrame = true;
        scheduleResizeFrame();
    }

    private void applyPendingResize() {
        if (!resizePending || overlayParams == null || overlayView == null || windowManager == null) {
            return;
        }
        resizePending = false;

        int requestedWidth = Math.round(resizeTargetWidthPx);
        int[] wh = clampOverlaySize(requestedWidth);
        if (wh[0] != requestedWidth) {
            resizeTargetWidthPx = wh[0];
        }
        if (overlayWidthPx == wh[0] && overlayHeightPx == wh[1]) {
            preferredWidthPx = wh[0];
            preferredHeightPx = wh[1];
            return;
        }

        preferredWidthPx = overlayWidthPx = wh[0];
        preferredHeightPx = overlayHeightPx = wh[1];
        overlayParams.width = getVisibleOverlayWidth();
        overlayParams.height = getVisibleOverlayHeight();
        clampOverlayPositionToScreen();
        rememberVisiblePosition();
        updateWindowLayout();
        applyPreviewTransform();
    }

    private void cancelResizeFrame() {
        if (!resizeFrameScheduled) return;
        Choreographer.getInstance().removeFrameCallback(resizeFrameCallback);
        resizeFrameScheduled = false;
    }

    private void cancelPendingResize() {
        cancelResizeFrame();
        resizePending = false;
        saveLayoutAfterResizeFrame = false;
        resizeTargetWidthPx = overlayWidthPx;
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

    private boolean shouldFadeInOnShow() {
        return OVERLAY_REASON_SIGNAL.equals(overlayReason)
                && uiPrefs != null
                && UiPrefs.isSmoothEntryEnabled(uiPrefs);
    }

    private void startFadeInIfNeeded() {
        if (overlayView == null || !shouldFadeInOnShow()) return;
        overlayView.animate()
                .alpha(1f)
                .setDuration(SIGNAL_FADE_IN_MS)
                .setStartDelay(0L)
                .start();
    }

    private void fadeOutAndStop() {
        if (overlayView == null
                || !OVERLAY_REASON_SIGNAL.equals(overlayReason)
                || uiPrefs == null
                || !UiPrefs.isSmoothEntryEnabled(uiPrefs)) {
            stopSelf();
            return;
        }
        fadeOutPending = true;
        overlayView.animate().cancel();
        overlayView.animate()
                .alpha(0f)
                .setDuration(SIGNAL_FADE_OUT_MS)
                .setStartDelay(0L)
                .withEndAction(() -> {
                    if (!fadeOutPending) return;
                    fadeOutPending = false;
                    stopSelf();
                })
                .start();
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
        Rect bounds = getAvailableOverlayBoundsPx();
        int crop = isCompactEnabled()
                ? UiPrefs.getSignalCompactLeft(uiPrefs) + UiPrefs.getSignalCompactRight(uiPrefs) : 0;
        float visibleFraction = 1f - crop / 100f;
        boolean rotated = shouldRotatePreviewToDrivingDirection();
        return OverlayResizeMath.clampSize(w, getActiveOverlayAspect(),
                OVERLAY_MIN_WIDTH_PX, OVERLAY_MAX_WIDTH_PX,
                bounds != null ? bounds.width() : OVERLAY_MAX_WIDTH_PX,
                bounds != null ? bounds.height() : OVERLAY_MAX_WIDTH_PX,
                rotated ? 1f : visibleFraction, rotated ? visibleFraction : 1f);
    }

    private void normalizeOverlaySize() {
        boolean shouldRotate = shouldRotatePreviewToDrivingDirection();
        boolean isLandscape = preferredWidthPx >= preferredHeightPx;
        if (shouldRotate == isLandscape) {
            int swappedWidth = preferredHeightPx;
            preferredHeightPx = preferredWidthPx;
            preferredWidthPx = swappedWidth;
        }
        int[] clamped = clampOverlaySize(preferredWidthPx);
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
        normalizeOverlaySize();
        if (overlayParams != null) {
            overlayParams.width = getVisibleOverlayWidth();
            overlayParams.height = getVisibleOverlayHeight();
            clampOverlayPositionToScreen();
            if (windowManager != null && overlayView != null) {
                updateWindowLayout();
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
        if (Looper.myLooper() != Looper.getMainLooper()) {
            tv.post(() -> applyPreviewTransformTo(tv));
            return;
        }
        applyPreviewTransformTo(tv);
    }

    private boolean isCompactEnabled() {
        return uiPrefs != null && OVERLAY_REASON_SIGNAL.equals(overlayReason)
                && uiPrefs.getBoolean(UiPrefs.KEY_SIGNAL_COMPACT_ENABLED, false);
    }

    private int getVisibleOverlayWidth() {
        return getCompactGeometry().visibleWidth;
    }

    private int getVisibleOverlayHeight() {
        return getCompactGeometry().visibleHeight;
    }

    private OverlayCompactGeometry getCompactGeometry() {
        int left = isCompactEnabled() ? UiPrefs.getSignalCompactLeft(uiPrefs) : 0;
        int right = isCompactEnabled() ? UiPrefs.getSignalCompactRight(uiPrefs) : 0;
        return OverlayCompactGeometry.calculate(overlayWidthPx, overlayHeightPx,
                CameraIndex.fromVideoIndex(cameraIndex) == CameraIndex.LEFT,
                shouldRotatePreviewToDrivingDirection(), left, right);
    }

    private void applyPreviewTransformTo(TextureView tv) {
        boolean rotated = shouldRotatePreviewToDrivingDirection();
        OverlayCompactGeometry compact = getCompactGeometry();
        boolean rotateCompactContent = rotated && isCompactEnabled();
        // A rotated, oversized TextureView can be clipped by its window before
        // the turn. Keep this view inside the visible window and rotate its
        // content instead, mapping the same full image behind the same crop.
        int desiredWidth = rotateCompactContent ? compact.visibleWidth
                : rotated ? overlayHeightPx : overlayWidthPx;
        int desiredHeight = rotateCompactContent ? compact.visibleHeight
                : rotated ? overlayWidthPx : overlayHeightPx;
        float desiredRotation = rotateCompactContent ? 0f : getPreviewRotationDegrees();

        ViewGroup.LayoutParams currentParams = tv.getLayoutParams();
        if (!(currentParams instanceof FrameLayout.LayoutParams)
                || currentParams.width != desiredWidth
                || currentParams.height != desiredHeight
                || ((FrameLayout.LayoutParams) currentParams).gravity != Gravity.CENTER) {
            tv.setLayoutParams(new FrameLayout.LayoutParams(
                    desiredWidth,
                    desiredHeight,
                    Gravity.CENTER
            ));
        }
        if (tv.getScaleX() != 1f) tv.setScaleX(1f);
        if (tv.getScaleY() != 1f) tv.setScaleY(1f);
        if (tv.getRotation() != desiredRotation) tv.setRotation(desiredRotation);
        if (rotateCompactContent) {
            float[] source = {0f, 0f, desiredWidth, 0f,
                    desiredWidth, desiredHeight, 0f, desiredHeight};
            float[] destination = compact.rotatedContentCorners(overlayWidthPx, overlayHeightPx,
                    CameraIndex.fromVideoIndex(cameraIndex) == CameraIndex.LEFT);
            Matrix transform = new Matrix();
            transform.setPolyToPoly(source, 0, destination, 0, 4);
            tv.setTransform(transform);
            tv.setTranslationX(0f);
            tv.setTranslationY(0f);
        } else {
            tv.setTransform(null);
            tv.setTranslationX(compact.translationX);
            tv.setTranslationY(compact.translationY);
        }
    }

    private int windowOriginX() { return geometry == null ? 0 : geometry.originX(); }
    private int windowOriginY() { return geometry == null ? 0 : geometry.originY(); }

    private void rememberVisiblePosition() {
        preferredScreenX = overlayParams.x + windowOriginX();
        preferredScreenY = overlayParams.y + windowOriginY();
    }

    private void clampOverlayPositionToScreen() {
        if (overlayParams == null) return;
        Rect bounds = getAvailableOverlayBoundsPx();
        FloatingWindowPlacement place = FloatingWindowPlacement.calculate(
                preferredScreenX, preferredScreenY, overlayParams.width, overlayParams.height,
                windowOriginX(), windowOriginY(), bounds.left, bounds.top, bounds.right, bounds.bottom);
        overlayParams.x = place.windowX;
        overlayParams.y = place.windowY;
    }

    private Rect getAvailableOverlayBoundsPx() {
        Rect bounds;
        if (geometry != null && geometry.isMeasured()) {
            bounds = geometry.visibleBounds();
        } else {
            Point size = FloatingWindowGeometry.screenSize(this);
            bounds = new Rect(0, 0, size.x, size.y);
        }
        // Keep the user's optional reserved top area; the actual system frame is measured.
        int minimumTop = uiPrefs == null ? UiPrefs.DEFAULT_DEV_OVERLAY_TOP_INSET_PX
                : UiPrefs.getDevOverlayTopInsetPx(uiPrefs);
        bounds.top = Math.min(bounds.bottom - 1, Math.max(bounds.top, minimumTop));
        return bounds;
    }

    private void updateWindowLayout() {
        if (geometry != null) geometry.updateLayout();
        else if (windowManager != null && overlayView != null)
            windowManager.updateViewLayout(overlayView, overlayParams);
    }

    private void onWindowGeometryChanged() {
        if (overlayView == null || overlayParams == null || geometry == null) return;
        boolean migrate = restoreLegacyPosition;
        if (migrate) {
            rememberVisiblePosition();
            restoreLegacyPosition = false;
        }
        int oldX = overlayParams.x, oldY = overlayParams.y;
        int oldWidth = overlayParams.width, oldHeight = overlayParams.height;
        normalizeOverlaySize();
        overlayParams.width = getVisibleOverlayWidth();
        overlayParams.height = getVisibleOverlayHeight();
        clampOverlayPositionToScreen();
        if (oldX != overlayParams.x || oldY != overlayParams.y
                || oldWidth != overlayParams.width || oldHeight != overlayParams.height) {
            updateWindowLayout();
            applyPreviewTransform();
        }
        if (migrate) saveOverlayLayoutPrefs();
        if (awaitingInitialGeometry && !fadeOutPending) {
            awaitingInitialGeometry = false;
            overlayView.setAlpha(shouldFadeInOnShow() ? SIGNAL_FADE_IN_START_ALPHA : 1f);
            startFadeInIfNeeded();
        }
        com.drivehub.kamera.dev.DevRuntimeLog.add("FloatingGeometry", "camera origin="
                + windowOriginX() + "," + windowOriginY() + " frame=" + geometry.visibleBounds());
    }

    private void saveOverlayLayoutPrefs() {
        if (overlayParams == null) return;
        try {
            android.content.SharedPreferences sp =
                    getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
            sp.edit()
                    .putInt(KEY_SCREEN_X, preferredScreenX)
                    .putInt(KEY_SCREEN_Y, preferredScreenY)
                    .putInt(KEY_OVERLAY_W, preferredWidthPx)
                    .putInt(KEY_OVERLAY_H, preferredHeightPx)
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
        cancelResizeFrame();
        if (resizePending) {
            applyPendingResize();
            saveOverlayLayoutPrefs();
        }
        saveLayoutAfterResizeFrame = false;
        fadeOutPending = false;
        if (overlayView != null) {
            overlayView.animate().cancel();
        }
        stopPreview();
        if (textureSurface != null) {
            textureSurface.release();
            textureSurface = null;
        }
        if (geometry != null) { geometry.close(); geometry = null; }
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
        boolean enhanced = OVERLAY_REASON_SIGNAL.equals(overlayReason) && uiPrefs != null
                && uiPrefs.getBoolean(UiPrefs.KEY_SIGNAL_ENHANCED_PREVIEW, false);
        if (enhanced ? CameraProbe.attachEnhancedPreview(cameraIndex, textureSurface)
                : CameraProbe.attachPreview(cameraIndex, textureSurface)) {
            attachedPreviewCameraIndex = cameraIndex;
        }
    }

    private void stopPreview() {
        if (attachedPreviewCameraIndex == -1) return;
        CameraProbe.detachPreview(attachedPreviewCameraIndex);
        attachedPreviewCameraIndex = -1;
    }
}
