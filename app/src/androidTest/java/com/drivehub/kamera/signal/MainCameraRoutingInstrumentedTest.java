package com.drivehub.kamera.signal;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;

import androidx.lifecycle.Lifecycle;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.drivehub.kamera.MainActivity;
import com.drivehub.kamera.R;
import com.drivehub.kamera.camera.CameraIndex;
import com.drivehub.kamera.camera.OverlayService;
import com.drivehub.kamera.settings.UiPrefs;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

/** Exercises the real vehicle decision, route broadcast and MainActivity receiver without cameras. */
@RunWith(AndroidJUnit4.class)
public class MainCameraRoutingInstrumentedTest {
    @Test public void signalOffRestoresGearCameraAndReturningToMainClearsAnOldSignalView() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        SharedPreferences prefs = UiPrefs.getPrefs(context);
        String[] keys = {UiPrefs.KEY_OEM_AVM_COEXIST, UiPrefs.KEY_OEM_AVM_ACTIVE,
                UiPrefs.KEY_OEM_CAMERA_ACCESS_BLOCKED, UiPrefs.KEY_LANE_CHANGES_ONLY,
                UiPrefs.KEY_DIGITAL_REARVIEW_ENABLED, UiPrefs.KEY_OVERLAY_ON_SIGNAL,
                UiPrefs.KEY_OVERLAY_HIDE_DELAY_MS};
        Map<String, ?> saved = prefs.getAll();
        assertTrue(prefs.edit().putBoolean(UiPrefs.KEY_OEM_AVM_COEXIST, false)
                .putBoolean(UiPrefs.KEY_OEM_AVM_ACTIVE, false)
                .putBoolean(UiPrefs.KEY_OEM_CAMERA_ACCESS_BLOCKED, false)
                .putBoolean(UiPrefs.KEY_LANE_CHANGES_ONLY, false)
                .putBoolean(UiPrefs.KEY_DIGITAL_REARVIEW_ENABLED, false)
                .putBoolean(UiPrefs.KEY_OVERLAY_ON_SIGNAL, false)
                .putInt(UiPrefs.KEY_OVERLAY_HIDE_DELAY_MS, 5000).commit());
        SignalService service = null;
        boolean resumePolling = false;
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            long deadline = SystemClock.elapsedRealtime()+4000;
            while (!SignalService.isRunning() && SystemClock.elapsedRealtime()<deadline) SystemClock.sleep(25);
            assertTrue(SignalService.isRunning());
            service = (SignalService) field(SignalService.class,"sInstance").get(null);
            // Run only where there is no real vehicle lamp callback to compete with injected samples.
            org.junit.Assume.assumeTrue(field(SignalService.class,"lampCallbackProxy").get(service)==null);
            deadline = SystemClock.elapsedRealtime()+4000;
            while (!field(SignalService.class,"polling").getBoolean(service)
                    && SystemClock.elapsedRealtime()<deadline) SystemClock.sleep(25);
            assertTrue("Property sampler must be initialized",field(SignalService.class,"polling").getBoolean(service));
            resumePolling = pausePolling(service);
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();

            sample(service,TurnSignal.OFF,4); assertCamera(scenario,CameraIndex.FRONT);
            sample(service,TurnSignal.LEFT,4); assertCamera(scenario,CameraIndex.LEFT);
            sample(service,TurnSignal.OFF,4); assertCamera(scenario,CameraIndex.FRONT);
            sample(service,TurnSignal.RIGHT,4); assertCamera(scenario,CameraIndex.RIGHT);
            sample(service,TurnSignal.OFF,4); assertCamera(scenario,CameraIndex.FRONT);

            sample(service,TurnSignal.LEFT,4); assertCamera(scenario,CameraIndex.LEFT);
            sample(service,TurnSignal.LEFT,2); assertCamera(scenario,CameraIndex.REAR);
            sample(service,TurnSignal.OFF,2); assertCamera(scenario,CameraIndex.REAR);
            sample(service,TurnSignal.RIGHT,2); assertCamera(scenario,CameraIndex.REAR);
            sample(service,TurnSignal.OFF,4); assertCamera(scenario,CameraIndex.FRONT);

            sample(service,TurnSignal.RIGHT,4); assertCamera(scenario,CameraIndex.RIGHT);
            scenario.moveToState(Lifecycle.State.CREATED);
            sample(service,TurnSignal.OFF,4); // No MainActivity receiver while it is stopped.
            scenario.moveToState(Lifecycle.State.RESUMED);
            assertCamera(scenario,CameraIndex.FRONT);
        } finally {
            SharedPreferences.Editor editor = prefs.edit();
            for (String key : keys) {
                Object value = saved.get(key);
                if (value instanceof Boolean) editor.putBoolean(key,(Boolean)value);
                else if (value instanceof Integer) editor.putInt(key,(Integer)value);
                else if (value instanceof Long) editor.putLong(key,(Long)value);
                else editor.remove(key);
            }
            assertTrue(editor.commit());
            if (service != null && resumePolling) {
                SignalService original = service;
                InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> invoke(original,"startPropertyPollingFallback"));
            }
            if(service!=null){SignalService original=service;InstrumentationRegistry.getInstrumentation().runOnMainSync(()->invoke(original,"startOemLifecycleMonitor"));}
            SignalService.requestRecheck();
        }
    }

    @Test public void settingsKeepTheSignalTileSuppressedAndRejectLateOverlayStarts() {
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)) {
            assertTrue(MainActivity.shouldBlockOverlay());
            for(int pass=0;pass<2;pass++) {
                scenario.onActivity(activity->{
                    activity.findViewById(R.id.btnSettings).performClick();
                    assertTrue(MainActivity.isSettingsDialogOpen());assertTrue(MainActivity.shouldBlockOverlay());
                    OverlayService.showOverlay(activity,CameraIndex.LEFT.getVideoIndex());
                });
                InstrumentationRegistry.getInstrumentation().waitForIdleSync();SystemClock.sleep(150);
                assertFalse("Settings must reject new signal tiles",OverlayService.isVisible());
                // Deliver a stale show intent directly while foregrounded, without an
                // unrelated startForegroundService deadline racing a concurrent hide.
                context.startService(new android.content.Intent(context,OverlayService.class)
                        .putExtra(OverlayService.EXTRA_CAMERA_INDEX,CameraIndex.RIGHT.getVideoIndex())
                        .putExtra(OverlayService.EXTRA_OVERLAY_REASON,OverlayService.OVERLAY_REASON_SIGNAL));
                InstrumentationRegistry.getInstrumentation().waitForIdleSync();SystemClock.sleep(150);
                assertFalse("Settings must reject queued signal tiles",OverlayService.isVisible());
                androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.withId(R.id.btnClose))
                        .inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog())
                        .perform(androidx.test.espresso.action.ViewActions.click());
                assertFalse(MainActivity.isSettingsDialogOpen());assertTrue(MainActivity.shouldBlockOverlay());
            }
            scenario.moveToState(Lifecycle.State.CREATED);assertFalse(MainActivity.shouldBlockOverlay());
            scenario.moveToState(Lifecycle.State.RESUMED);assertTrue(MainActivity.shouldBlockOverlay());
        }
    }

    private static boolean pausePolling(SignalService service) throws Exception {
        boolean wasPolling = field(SignalService.class,"polling").getBoolean(service);
        HandlerThread thread = (HandlerThread)field(SignalService.class,"pollThread").get(service);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            try {
                invoke(service,"stopOemLifecycleMonitor");
                field(SignalService.class,"polling").setBoolean(service,false);
                Handler handler = (Handler)field(SignalService.class,"pollHandler").get(service);
                if (handler != null) handler.removeCallbacksAndMessages(null);
            } catch (Exception error) { throw new AssertionError(error); }
        });
        if (thread != null) { thread.quitSafely(); thread.join(2000); assertFalse(thread.isAlive()); }
        field(SignalService.class,"pollThread").set(service,null);
        field(SignalService.class,"pollHandler").set(service,null);
        return wasPolling;
    }

    private static void sample(SignalService service, TurnSignal signal, int gear) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            try {
                Method sample = SignalService.class.getDeclaredMethod("onVehicleStateSample",int.class,int.class,long.class);
                sample.setAccessible(true);
                sample.invoke(service,signal.getValue(),gear,SystemClock.elapsedRealtime());
            } catch (Exception error) { throw new AssertionError(error); }
        });
    }

    private static void assertCamera(ActivityScenario<MainActivity> scenario, CameraIndex expected) throws Exception {
        Field selected = field(MainActivity.class,"currentVideoIndex");
        AtomicInteger actual = new AtomicInteger(-1);
        long deadline = SystemClock.elapsedRealtime()+2000;
        do {
            scenario.onActivity(activity -> {
                try { actual.set(selected.getInt(activity)); }
                catch (Exception error) { throw new AssertionError(error); }
            });
            if (actual.get()==expected.getVideoIndex()) return;
            SystemClock.sleep(25);
        } while (SystemClock.elapsedRealtime()<deadline);
        assertEquals("Main camera must follow the current signal/gear",expected.getVideoIndex(),actual.get());
    }

    private static Field field(Class<?> type, String name) throws Exception {
        Field field = type.getDeclaredField(name); field.setAccessible(true); return field;
    }

    private static void invoke(SignalService service, String name) {
        try {
            Method method = SignalService.class.getDeclaredMethod(name); method.setAccessible(true); method.invoke(service);
        } catch (Exception error) { throw new AssertionError(error); }
    }
}
