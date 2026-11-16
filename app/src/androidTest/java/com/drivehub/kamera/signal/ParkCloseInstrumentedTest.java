package com.drivehub.kamera.signal;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import androidx.lifecycle.Lifecycle;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.lifecycle.ActivityLifecycleCallback;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry;
import androidx.test.runner.lifecycle.Stage;
import com.drivehub.kamera.MainActivity;
import com.drivehub.kamera.R;
import com.drivehub.kamera.dashcam.DashcamSettingsController;
import com.drivehub.kamera.settings.UiPrefs;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** P-close with OEM allowed: deliberately no exclusive camera or automatic parking session. */
@RunWith(AndroidJUnit4.class)
public class ParkCloseInstrumentedTest {
    private final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    private SharedPreferences prefs;
    private Map<String, ?> saved;
    private SignalService service;
    private final String[] keys = {UiPrefs.KEY_PARKING_AUTO_CLOSE_ON_PARK,
            UiPrefs.KEY_PARKING_AUTO_CLOSE, UiPrefs.KEY_PARKING_AUTO_OPEN_DRIVE,
            UiPrefs.KEY_OEM_AVM_COEXIST, UiPrefs.KEY_OEM_AVM_ACTIVE,
            UiPrefs.KEY_OEM_CAMERA_ACCESS_BLOCKED, UiPrefs.KEY_OEM_TAKEOVER_YIELD,
            UiPrefs.KEY_EXTERNAL_CAMERA_PAUSED, UiPrefs.KEY_DIGITAL_REARVIEW_ENABLED,
            UiPrefs.KEY_OVERLAY_ON_SIGNAL, DashcamSettingsController.KEY_ENABLED};

    private static void main(Runnable run) { InstrumentationRegistry.getInstrumentation().runOnMainSync(run); }
    private static Object get(Object object, String name) {
        try { Field f = object.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(object); }
        catch (Exception error) { throw new AssertionError(error); }
    }
    private void invoke(String name, Class<?>[] types, Object... args) {
        try { Method m = SignalService.class.getDeclaredMethod(name, types); m.setAccessible(true); m.invoke(service, args); }
        catch (Exception error) { throw new AssertionError(error); }
    }
    private static void await(java.util.function.BooleanSupplier ready) {
        long end = SystemClock.elapsedRealtime() + 6000;
        do { if (ready.getAsBoolean()) return; SystemClock.sleep(25); } while (SystemClock.elapsedRealtime() < end);
        assertTrue("P-close lifecycle did not settle", ready.getAsBoolean());
    }
    private void gear(int value) { main(() -> sample(value)); }
    private void sample(int value) { invoke("onGearStateSample", new Class[]{int.class}, value); }
    private ParkClosePolicy policy() { return (ParkClosePolicy) get(service, "parkClose"); }
    private void replay(ParkClosePolicy.Request request) {
        context.sendBroadcast(new Intent(SignalService.ACTION_CLOSE_ON_PARK).setPackage(context.getPackageName())
                .putExtra(SignalService.EXTRA_PARK_CLOSE_VIEW, request.viewId)
                .putExtra(SignalService.EXTRA_PARK_CLOSE_TRANSITION, request.transition));
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    }

    @Before public void oemAllowedFixture() throws Exception {
        prefs = UiPrefs.getPrefs(context); saved = prefs.getAll();
        prefs.edit().remove(UiPrefs.KEY_PARKING_AUTO_CLOSE_ON_PARK)
                .putBoolean(UiPrefs.KEY_PARKING_AUTO_CLOSE, false)
                .putBoolean(UiPrefs.KEY_PARKING_AUTO_OPEN_DRIVE, false)
                .putBoolean(UiPrefs.KEY_OEM_AVM_COEXIST, true)
                .putBoolean(UiPrefs.KEY_OEM_AVM_ACTIVE, false)
                .putBoolean(UiPrefs.KEY_OEM_CAMERA_ACCESS_BLOCKED, false)
                .putBoolean(UiPrefs.KEY_OEM_TAKEOVER_YIELD, false)
                .putBoolean(UiPrefs.KEY_EXTERNAL_CAMERA_PAUSED, false)
                .putBoolean(UiPrefs.KEY_DIGITAL_REARVIEW_ENABLED, false)
                .putBoolean(UiPrefs.KEY_OVERLAY_ON_SIGNAL, false)
                .putBoolean(DashcamSettingsController.KEY_ENABLED, false).commit();
        try (ActivityScenario<MainActivity> setup = ActivityScenario.launch(MainActivity.class)) {
            await(SignalService::isRunning);
            Field instance = SignalService.class.getDeclaredField("sInstance"); instance.setAccessible(true);
            service = (SignalService) instance.get(null);
            org.junit.Assume.assumeTrue(get(service, "lampCallbackProxy") == null);
            main(() -> {
                invoke("stopOemLifecycleMonitor", new Class[]{});
                try {
                    Field polling = SignalService.class.getDeclaredField("polling"); polling.setAccessible(true);
                    polling.setBoolean(service, false);
                    Handler handler = (Handler) get(service, "pollHandler");
                    if (handler != null) handler.removeCallbacksAndMessages(null);
                    HandlerThread thread = (HandlerThread) get(service, "pollThread");
                    if (thread != null) thread.quitSafely();
                    for (String name : new String[]{"pollThread", "pollHandler"}) {
                        Field field = SignalService.class.getDeclaredField(name); field.setAccessible(true); field.set(service, null);
                    }
                } catch (Exception error) { throw new AssertionError(error); }
                sample(0); sample(4);
            });
        }
        await(() -> !MainActivity.isMainVisible());
    }

    @After public void restore() {
        if (prefs == null) return;
        SharedPreferences.Editor edit = prefs.edit();
        for (String key : keys) {
            Object value = saved.get(key);
            if (value instanceof Boolean) edit.putBoolean(key, (Boolean) value);
            else edit.remove(key);
        }
        edit.commit();
        if (service != null) main(() -> {
            sample(0);
            invoke("startPropertyPollingFallback", new Class[]{});
            invoke("startOemLifecycleMonitor", new Class[]{});
        });
        SignalService.refreshCameraAccess(context);
    }

    @Test public void manualViewsCloseFromReverseNeutralAndDriveWithOemAllowed() {
        assertTrue(prefs.getBoolean(UiPrefs.KEY_OEM_AVM_COEXIST, false));
        assertTrue(UiPrefs.isParkingAutoCloseOnParkEnabled(prefs));
        for (int previous : new int[]{2, 3, 4}) {
            gear(previous);
            try (ActivityScenario<MainActivity> view = ActivityScenario.launch(MainActivity.class)) {
                gear(1); await(() -> view.getState() == Lifecycle.State.DESTROYED);
                assertTrue(SignalService.isRunning());
            }
            gear(1);
            try (ActivityScenario<MainActivity> reopened = ActivityScenario.launch(MainActivity.class)) {
                gear(1); assertEquals(Lifecycle.State.RESUMED, reopened.getState());
            }
        }
    }

    @Test public void initialParkAndEnablingInParkLeaveTheViewOpen() {
        gear(0); gear(1);
        try (ActivityScenario<MainActivity> view = ActivityScenario.launch(MainActivity.class)) {
            gear(1); assertEquals(Lifecycle.State.RESUMED, view.getState());
            prefs.edit().putBoolean(UiPrefs.KEY_PARKING_AUTO_CLOSE_ON_PARK, false).commit();
            gear(4); gear(1); assertEquals(Lifecycle.State.RESUMED, view.getState());
            prefs.edit().putBoolean(UiPrefs.KEY_PARKING_AUTO_CLOSE_ON_PARK, true).commit();
            gear(1); assertEquals(Lifecycle.State.RESUMED, view.getState());
            gear(4); gear(1); await(() -> view.getState() == Lifecycle.State.DESTROYED);
        }
    }

    @Test public void queuedCloseIsRejectedAfterLeavingParkOrDisablingTheToggle() {
        try (ActivityScenario<MainActivity> view = ActivityScenario.launch(MainActivity.class)) {
            AtomicReference<ParkClosePolicy.Request> old = new AtomicReference<>();
            view.onActivity(a -> {
                sample(4); sample(1); old.set(policy().pendingFor(MainActivity.foregroundCameraViewId()));
                sample(4);
            });
            replay(old.get()); assertEquals(Lifecycle.State.RESUMED, view.getState());
            view.onActivity(a -> {
                sample(1); old.set(policy().pendingFor(MainActivity.foregroundCameraViewId()));
                prefs.edit().putBoolean(UiPrefs.KEY_PARKING_AUTO_CLOSE_ON_PARK, false).commit();
                prefs.edit().putBoolean(UiPrefs.KEY_PARKING_AUTO_CLOSE_ON_PARK, true).commit();
            });
            replay(old.get()); assertEquals(Lifecycle.State.RESUMED, view.getState());
        }
    }

    @Test public void oldCloseCannotAffectAManuallyReopenedView() {
        AtomicReference<ParkClosePolicy.Request> old = new AtomicReference<>();
        try (ActivityScenario<MainActivity> view = ActivityScenario.launch(MainActivity.class)) {
            view.onActivity(a -> {
                sample(4); sample(1); old.set(policy().pendingFor(MainActivity.foregroundCameraViewId()));
                a.findViewById(R.id.btnClose).performClick();
            });
            await(() -> view.getState() == Lifecycle.State.DESTROYED);
        }
        try (ActivityScenario<MainActivity> reopened = ActivityScenario.launch(MainActivity.class)) {
            assertNotEquals(old.get().viewId, MainActivity.foregroundCameraViewId());
            replay(old.get()); gear(1); assertEquals(Lifecycle.State.RESUMED, reopened.getState());
            gear(4); gear(1); await(() -> reopened.getState() == Lifecycle.State.DESTROYED);
        }
    }

    @Test public void backgroundChangesAndPendingRequestsAreNotReplayedOnReturn() {
        try (ActivityScenario<MainActivity> view = ActivityScenario.launch(MainActivity.class)) {
            String before = MainActivity.foregroundCameraViewId();
            view.moveToState(Lifecycle.State.CREATED);
            assertNull(MainActivity.foregroundCameraViewId());
            gear(1);
            view.moveToState(Lifecycle.State.RESUMED);
            assertNotEquals(before, MainActivity.foregroundCameraViewId());
            assertEquals(Lifecycle.State.RESUMED, view.getState());
            view.moveToState(Lifecycle.State.STARTED);
            AtomicReference<ParkClosePolicy.Request> old = new AtomicReference<>();
            main(() -> {
                sample(4); sample(1); old.set(policy().pendingFor(MainActivity.foregroundCameraViewId()));
            });
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            view.moveToState(Lifecycle.State.CREATED);
            view.moveToState(Lifecycle.State.RESUMED);
            replay(old.get()); assertEquals(Lifecycle.State.RESUMED, view.getState());
        }
    }

    @Test public void configurationRecreationKeepsTheViewAndItsPendingClose() {
        try (ActivityScenario<MainActivity> view = ActivityScenario.launch(MainActivity.class)) {
            String before = MainActivity.foregroundCameraViewId();
            view.moveToState(Lifecycle.State.STARTED);
            gear(4); gear(1);
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            assertEquals(Lifecycle.State.STARTED, view.getState());
            AtomicReference<String> recreated = new AtomicReference<>();
            ActivityLifecycleCallback started = (activity, stage) -> {
                if (activity instanceof MainActivity && stage == Stage.STARTED)
                    recreated.set(MainActivity.foregroundCameraViewId());
            };
            main(() -> ActivityLifecycleMonitorRegistry.getInstance().addLifecycleCallback(started));
            try {
                // ActivityScenario.recreate() insists on a resumed replacement, but our
                // replacement correctly closes immediately when it resumes with a pending P edge.
                view.onActivity(MainActivity::recreate);
                await(() -> view.getState() == Lifecycle.State.DESTROYED);
                assertEquals(before, recreated.get());
            } finally {
                main(() -> ActivityLifecycleMonitorRegistry.getInstance().removeLifecycleCallback(started));
            }
        }
    }

    @Test public void parkClosesTheMainViewAndItsSettingsDialog() {
        try (ActivityScenario<MainActivity> view = ActivityScenario.launch(MainActivity.class)) {
            view.onActivity(a -> a.findViewById(R.id.btnSettings).performClick());
            assertTrue(MainActivity.isSettingsDialogOpen());
            gear(1); await(() -> view.getState() == Lifecycle.State.DESTROYED);
            assertFalse(MainActivity.isSettingsDialogOpen());
            assertTrue(SignalService.isRunning());
        }
    }
}
