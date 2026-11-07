package com.drivehub.kamera.side;

import android.app.Dialog;
import android.graphics.Rect;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.EditText;

import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.drivehub.kamera.MainActivity;
import com.drivehub.kamera.R;
import com.drivehub.kamera.ui.AppSystemBars;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class SideCameraWindowInstrumentedTest {
    @Test public void transientSideBarDoesNotResizeOrRecreateCameraSurfaces() {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            Rect[] original = new Rect[2];
            AtomicInteger destroyed = new AtomicInteger();
            scenario.onActivity(activity -> {
                SideCameraView cameras = activity.findViewById(R.id.sideCameraView);
                for (int side = 0; side < 2; side++) {
                    SurfaceHolder holder = ((SurfaceView) cameras.getChildAt(side)).getHolder();
                    assertTrue(holder.getSurface().isValid());
                    original[side] = new Rect(holder.getSurfaceFrame());
                    assertEquals(new Rect(0, 0, 254, 456), original[side]);
                    holder.addCallback(new SurfaceHolder.Callback() {
                        @Override public void surfaceCreated(SurfaceHolder h) {}
                        @Override public void surfaceChanged(SurfaceHolder h, int f, int w, int height) {}
                        @Override public void surfaceDestroyed(SurfaceHolder h) { destroyed.incrementAndGet(); }
                    });
                }
                View root = activity.findViewById(R.id.main);
                WindowInsetsCompat base = ViewCompat.getRootWindowInsets(root);
                assertNotNull(base);
                int left = root.getPaddingLeft(), right = root.getPaddingRight();
                for (Insets nav : new Insets[]{Insets.of(96, 0, 0, 0), Insets.of(0, 0, 96, 0)}) {
                    WindowInsetsCompat transientBar = new WindowInsetsCompat.Builder(base)
                            .setInsets(WindowInsetsCompat.Type.navigationBars(), nav)
                            .setVisible(WindowInsetsCompat.Type.navigationBars(), true).build();
                    ViewCompat.dispatchApplyWindowInsets(root, transientBar);
                    assertEquals(left, root.getPaddingLeft());
                    assertEquals(right, root.getPaddingRight());
                    assertEquals(AppSystemBars.contentInsets(base, true),
                            AppSystemBars.contentInsets(transientBar, true));
                    assertEquals(AppSystemBars.contentInsets(base, false),
                            AppSystemBars.contentInsets(transientBar, false));
                }
                activity.findViewById(R.id.modeWheels).performClick();
                activity.findViewById(R.id.modeSides).performClick();
            });
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            scenario.onActivity(activity -> {
                SideCameraView cameras = activity.findViewById(R.id.sideCameraView);
                for (int side = 0; side < 2; side++) {
                    SurfaceHolder holder = ((SurfaceView) cameras.getChildAt(side)).getHolder();
                    assertEquals(original[side], holder.getSurfaceFrame());
                    assertTrue(holder.getSurface().isValid());
                }
                assertEquals(0, destroyed.get());
            });
        }
    }

    @Test public void dialogRemainsFocusableAndNavigationIsHiddenAgainWithoutFocusChange() {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            Dialog[] dialog = new Dialog[1];
            try {
                scenario.onActivity(activity -> {
                    dialog[0] = new Dialog(activity);
                    dialog[0].setContentView(new EditText(activity));
                    AppSystemBars.showDialog(dialog[0]);
                    assertEquals(0, dialog[0].getWindow().getAttributes().flags
                            & WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
                });
                InstrumentationRegistry.getInstrumentation().waitForIdleSync();
                AtomicBoolean focused = new AtomicBoolean();
                long focusDeadline = android.os.SystemClock.uptimeMillis() + 2500;
                do {
                    android.os.SystemClock.sleep(50);
                    scenario.onActivity(activity -> focused.set(dialog[0].getWindow().getDecorView().hasWindowFocus()));
                } while (!focused.get() && android.os.SystemClock.uptimeMillis() < focusDeadline);
                assertTrue("Dialog must regain keyboard/input focus after immersive setup", focused.get());
                scenario.onActivity(activity -> {
                    Window window = dialog[0].getWindow();
                    WindowCompat.getInsetsController(window, window.getDecorView())
                            .show(WindowInsetsCompat.Type.navigationBars());
                });
                AtomicBoolean hidden = new AtomicBoolean();
                long deadline = android.os.SystemClock.uptimeMillis() + 2500;
                do {
                    android.os.SystemClock.sleep(50);
                    scenario.onActivity(activity -> {
                        WindowInsetsCompat insets = ViewCompat.getRootWindowInsets(dialog[0].getWindow().getDecorView());
                        hidden.set(insets != null && !insets.isVisible(WindowInsetsCompat.Type.navigationBars()));
                    });
                } while (!hidden.get() && android.os.SystemClock.uptimeMillis() < deadline);
                assertTrue("Navigation must hide again even without another focus change", hidden.get());
            } finally {
                scenario.onActivity(activity -> { if (dialog[0] != null) dialog[0].dismiss(); });
            }
        }
    }
}
