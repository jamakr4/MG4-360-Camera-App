package com.drivehub.kamera.ui;

import android.app.Dialog;
import android.view.View;
import android.view.Window;

import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import com.drivehub.kamera.settings.UiPrefs;

/** App windows always use the full width; only the top SAIC bar is configurable. */
public final class AppSystemBars {
    private AppSystemBars() {}

    public static void apply(Window window) {
        if (window == null) return;
        View decor = window.getDecorView();
        WindowInsetsControllerCompat controller = WindowCompat.getInsetsController(window, decor);
        // On Android 9, AndroidX sets IMMERSIVE_STICKY and HIDE_NAVIGATION while
        // preserving the window's existing edge-to-edge layout flags.
        controller.setSystemBarsBehavior(
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        if (UiPrefs.isStatusBarVisible(UiPrefs.getPrefs(window.getContext()))) {
            controller.show(WindowInsetsCompat.Type.statusBars());
        } else {
            controller.hide(WindowInsetsCompat.Type.statusBars());
        }
        controller.hide(WindowInsetsCompat.Type.navigationBars());
        ViewCompat.requestApplyInsets(decor);
    }

    /** Configure the dialog before it takes focus, and again after any child dialog closes. */
    public static void showDialog(Dialog dialog) {
        Window window = dialog.getWindow();
        if (window != null) {
            apply(window);
            window.getDecorView().getViewTreeObserver().addOnWindowFocusChangeListener(hasFocus -> {
                if (hasFocus) apply(window);
            });
        }
        dialog.show();
        apply(window);
    }
}
