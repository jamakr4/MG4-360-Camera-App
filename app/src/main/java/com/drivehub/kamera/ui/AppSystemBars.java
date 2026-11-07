package com.drivehub.kamera.ui;

import android.app.Dialog;
import android.os.Build;
import android.os.SystemClock;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;

import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import com.drivehub.kamera.R;
import com.drivehub.kamera.settings.UiPrefs;

/** Keep the SAIC navigation bar hidden even when vehicle UI resets window visibility. */
public final class AppSystemBars {
    private AppSystemBars() {}

    public static void apply(Window window) {
        if (window == null) return;
        View decor = window.getDecorView();
        Guard guard = (Guard) decor.getTag(R.id.tag_system_bars_guard);
        if (guard == null) {
            guard = new Guard(window);
            decor.setTag(R.id.tag_system_bars_guard, guard);
        }
        guard.applyNow();
    }

    /** Transient navigation bars must overlay the camera area, never resize it. */
    public static Insets contentInsets(WindowInsetsCompat insets, boolean showStatusBar) {
        Insets cutout = insets.getInsets(WindowInsetsCompat.Type.displayCutout());
        Insets status = showStatusBar ? insets.getInsets(WindowInsetsCompat.Type.statusBars()) : Insets.NONE;
        return Insets.max(cutout, status);
    }

    /** Inherit immersive visibility before the new dialog is allowed to take focus. */
    public static void showDialog(Dialog dialog) {
        Window window = dialog.getWindow();
        boolean restoreFocus = window != null
                && (window.getAttributes().flags & WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) == 0;
        if (restoreFocus) window.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
        apply(window);
        try {
            dialog.show();
            apply(window);
        } finally {
            if (restoreFocus) window.clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
        }
        if (window != null) window.getDecorView().post(() -> apply(window));
    }

    private static final class Guard implements View.OnAttachStateChangeListener {
        private final Window window;
        private final View decor;
        private long lastApply;
        private boolean requestedStatusVisible;
        private boolean initialized;
        private boolean reapplyPending;
        private final Runnable reapply = this::reapplyIfFocused;

        private void reapplyIfFocused() {
            reapplyPending = false;
            if (!decor.isAttachedToWindow() || !decor.hasWindowFocus()) return;
            applyNow();
            WindowInsetsCompat insets = ViewCompat.getRootWindowInsets(decor);
            if (insets != null && insets.isVisible(WindowInsetsCompat.Type.navigationBars())) {
                schedule(500);
            }
        }

        @SuppressWarnings("deprecation")
        Guard(Window window) {
            this.window = window;
            decor = window.getDecorView();
            decor.addOnAttachStateChangeListener(this);
            decor.getViewTreeObserver().addOnWindowFocusChangeListener(hasFocus -> {
                if (hasFocus) schedule(0);
                else cancelReapply();
            });
            // Android 9 OEM components can reveal the bar without a focus change.
            decor.setOnSystemUiVisibilityChangeListener(visibility -> {
                if (Build.VERSION.SDK_INT < 30
                        && (visibility & View.SYSTEM_UI_FLAG_HIDE_NAVIGATION) == 0) schedule(0);
            });
            ViewCompat.setOnApplyWindowInsetsListener(decor, (view, insets) -> {
                if (insets.isVisible(WindowInsetsCompat.Type.navigationBars())) schedule(100);
                return insets;
            });
        }

        @SuppressWarnings("deprecation")
        void applyNow() {
            lastApply = SystemClock.uptimeMillis();
            boolean showStatus = UiPrefs.isStatusBarVisible(UiPrefs.getPrefs(window.getContext()));
            WindowCompat.setDecorFitsSystemWindows(window, false);
            if (Build.VERSION.SDK_INT < 30) {
                int flags = decor.getSystemUiVisibility()
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION;
                if (showStatus) flags &= ~View.SYSTEM_UI_FLAG_FULLSCREEN;
                else flags |= View.SYSTEM_UI_FLAG_FULLSCREEN;
                if (decor.getSystemUiVisibility() != flags) decor.setSystemUiVisibility(flags);
            }
            WindowInsetsControllerCompat controller = WindowCompat.getInsetsController(window, decor);
            controller.setSystemBarsBehavior(
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            if (showStatus) controller.show(WindowInsetsCompat.Type.statusBars());
            else controller.hide(WindowInsetsCompat.Type.statusBars());
            controller.hide(WindowInsetsCompat.Type.navigationBars());
            if (!initialized || requestedStatusVisible != showStatus) {
                initialized = true;
                requestedStatusVisible = showStatus;
                ViewCompat.requestApplyInsets(decor);
            }
        }

        private void schedule(long delay) {
            if (!decor.hasWindowFocus() || reapplyPending) return;
            reapplyPending = true;
            decor.postDelayed(reapply, Math.max(delay, 100 - (SystemClock.uptimeMillis() - lastApply)));
        }

        private void cancelReapply() {
            decor.removeCallbacks(reapply);
            reapplyPending = false;
        }

        @Override public void onViewAttachedToWindow(View view) {
            schedule(0);
        }

        @Override public void onViewDetachedFromWindow(View view) {
            cancelReapply();
        }
    }
}
