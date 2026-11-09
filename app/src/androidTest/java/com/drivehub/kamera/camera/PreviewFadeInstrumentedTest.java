package com.drivehub.kamera.camera;

import android.animation.ValueAnimator;
import android.graphics.Color;
import android.graphics.Canvas;
import android.graphics.SurfaceTexture;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.view.TextureView;
import android.view.Surface;
import android.widget.FrameLayout;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.drivehub.kamera.MainActivity;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class PreviewFadeInstrumentedTest {
    @Test public void aTextureFrameCanRevealAnInitiallyTransparentPanel() {
        try (Fixture f = new Fixture(true)) {
            f.run(() -> {
                f.panel.removeAllViews();
                TextureView texture = new TextureView(f.activity);
                texture.setOpaque(false);
                texture.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
                    @Override public void onSurfaceTextureAvailable(SurfaceTexture st, int width, int height) {
                        Surface surface = new Surface(st);
                        try {
                            Canvas canvas = surface.lockCanvas(null);
                            canvas.drawColor(Color.BLUE);
                            surface.unlockCanvasAndPost(canvas);
                            assertFalse(f.fade.onFrameHealth(true));
                        } finally { surface.release(); }
                    }
                    @Override public void onSurfaceTextureSizeChanged(SurfaceTexture st, int w, int h) { }
                    @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture st) { return true; }
                    @Override public void onSurfaceTextureUpdated(SurfaceTexture st) { f.fade.onTextureFrame(); }
                });
                f.panel.addView(texture, new FrameLayout.LayoutParams(160, 106));
                assertEquals(0f, f.panel.getAlpha(), 0);
            });
            f.await(() -> f.fade.isRevealed() && f.panel.getAlpha() == 1f);
        }
    }

    @Test public void firstFrameNeedsFreshCameraAndRenderedTextureInEitherOrder() {
        for (boolean cameraFirst : new boolean[]{true, false}) {
            try (Fixture f = new Fixture(true)) {
                f.run(() -> {
                    assertEquals(0f, f.panel.getAlpha(), 0);
                    assertFalse(cameraFirst ? f.fade.onFrameHealth(true) : f.fade.onTextureFrame());
                    assertFalse(f.fade.isRevealed());
                    assertEquals(0f, f.panel.getAlpha(), 0);
                    assertTrue(cameraFirst ? f.fade.onTextureFrame() : f.fade.onFrameHealth(true));
                    assertTrue(f.fade.isRevealed());
                    if (ValueAnimator.areAnimatorsEnabled())
                        assertEquals(PreviewFadeController.FADE_IN_START_ALPHA, f.panel.getAlpha(), .001f);
                    assertEquals(PreviewFadeController.FADE_IN_MS, f.panel.animate().getDuration());
                    assertEquals(1f, f.image.getAlpha(), 0); // The complete panel is animated.
                });
                f.await(() -> f.panel.getAlpha() == 1f);
                f.run(() -> {
                    assertFalse(f.fade.onFrameHealth(true));
                    assertFalse(f.fade.onTextureFrame());
                    assertEquals(1f, f.panel.getAlpha(), 0);
                });
            }
        }
    }

    @Test public void disabledSmoothEntryStillWaitsForAHealthyRenderedFrame() {
        try (Fixture f = new Fixture(false)) {
            f.run(() -> {
                f.fade.setEnabled(true);
                f.fade.setEnabled(false);
                assertFalse(f.fade.onTextureFrame());
                assertFalse(f.fade.onFrameHealth(false));
                assertEquals(0f, f.panel.getAlpha(), 0);
                assertTrue(f.fade.onFrameHealth(true));
                assertEquals(1f, f.panel.getAlpha(), 0);
                f.fade.setEnabled(true);
                assertEquals(1f, f.panel.getAlpha(), 0); // No replay for an already live window.
            });
        }
    }

    @Test public void normalExitCompletesOnceWithoutLateFrameOrRepeatedHideRestart() {
        try (Fixture f = new Fixture(false)) {
            AtomicInteger removed = new AtomicInteger();
            f.run(() -> {
                f.reveal();
                f.fade.setEnabled(true);
                f.fade.hide(removed::incrementAndGet);
                if (ValueAnimator.areAnimatorsEnabled()) assertEquals(0, removed.get());
                assertEquals(PreviewFadeController.FADE_OUT_MS, f.panel.animate().getDuration());
                f.fade.hide(() -> fail("Repeated hide must not replace the original completion"));
                assertFalse(f.fade.onTextureFrame());
                assertFalse(f.fade.onFrameHealth(true));
            });
            f.await(() -> removed.get() == 1);
            f.run(() -> assertEquals(0f, f.panel.getAlpha(), 0));
            SystemClock.sleep(160);
            assertEquals(1, removed.get());
        }
    }

    @Test public void disablingSmoothEntryFinishesTheActiveTransitionImmediately() {
        try (Fixture f = new Fixture(true)) {
            f.run(() -> {
                f.reveal();
                f.fade.setEnabled(false);
                assertEquals(1f, f.panel.getAlpha(), 0);
            });
            SystemClock.sleep(160);
            f.run(() -> assertEquals(1f, f.panel.getAlpha(), 0));
        }
        try (Fixture f = new Fixture(false)) {
            AtomicInteger removed = new AtomicInteger();
            f.run(() -> {
                f.reveal();
                f.fade.setEnabled(true);
                f.fade.hide(removed::incrementAndGet);
                f.fade.setEnabled(false);
                assertEquals(1, removed.get());
                assertEquals(0f, f.panel.getAlpha(), 0);
            });
            SystemClock.sleep(160);
            assertEquals(1, removed.get());
        }
    }

    @Test public void immediateDisposalCancelsOldCallbacksAndProtectsAReplacementPanel() {
        try (Fixture f = new Fixture(false)) {
            AtomicInteger oldCompletion = new AtomicInteger();
            AtomicInteger completedBeforeDisposal = new AtomicInteger();
            f.run(() -> {
                f.reveal();
                f.fade.setEnabled(true);
                f.fade.hide(oldCompletion::incrementAndGet);
                completedBeforeDisposal.set(oldCompletion.get());
                PreviewFadeController old = f.fade;
                f.removePanel();
                assertFalse(old.onTextureFrame());
                assertFalse(old.onFrameHealth(true));
                f.addPanel(true);
                assertEquals(0f, f.panel.getAlpha(), 0);
                f.reveal();
            });
            f.await(() -> f.panel.getAlpha() == 1f);
            SystemClock.sleep(160);
            assertEquals(completedBeforeDisposal.get(), oldCompletion.get());
            f.run(() -> assertNotNull(f.panel.getParent()));
        }
    }

    @Test public void hideBeforeFirstFrameDoesNotWaitOrRevealLater() {
        try (Fixture f = new Fixture(true)) {
            AtomicInteger removed = new AtomicInteger();
            f.run(() -> {
                f.fade.hide(removed::incrementAndGet);
                assertEquals(1, removed.get());
                assertFalse(f.fade.onFrameHealth(true));
                assertFalse(f.fade.onTextureFrame());
                assertEquals(0f, f.panel.getAlpha(), 0);
            });
        }
    }

    private static final class Fixture implements AutoCloseable {
        final ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class);
        MainActivity activity;
        FrameLayout panel;
        View image;
        PreviewFadeController fade;

        Fixture(boolean enabled) {
            scenario.onActivity(value -> { activity = value; addPanel(enabled); });
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        }

        void addPanel(boolean enabled) {
            panel = new FrameLayout(activity);
            panel.setBackgroundColor(0xFF0C0C0C);
            image = new View(activity);
            image.setBackgroundColor(Color.BLUE);
            panel.addView(image, new FrameLayout.LayoutParams(160, 106));
            activity.addContentView(panel, new ViewGroup.LayoutParams(160, 160));
            fade = new PreviewFadeController(panel, enabled);
        }

        void reveal() {
            assertFalse(fade.onTextureFrame());
            assertTrue(fade.onFrameHealth(true));
        }

        void run(Runnable task) { scenario.onActivity(ignored -> task.run()); }

        void await(BooleanSupplier condition) {
            AtomicBoolean done = new AtomicBoolean();
            long deadline = SystemClock.uptimeMillis()+2000;
            do {
                run(() -> done.set(condition.getAsBoolean()));
                if (!done.get()) SystemClock.sleep(10);
            } while (!done.get() && SystemClock.uptimeMillis() < deadline);
            assertTrue("Preview transition did not finish", done.get());
        }

        void removePanel() {
            fade.close();
            ((ViewGroup) panel.getParent()).removeView(panel);
        }

        @Override public void close() {
            try { run(this::removePanel); }
            finally { scenario.close(); }
        }
    }
}
