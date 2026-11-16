package com.drivehub.kamera.signal;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry;
import androidx.test.runner.lifecycle.Stage;
import com.drivehub.kamera.CameraProbe;
import com.drivehub.kamera.MainActivity;
import com.drivehub.kamera.R;
import com.drivehub.kamera.camera.CameraIndex;
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

@RunWith(AndroidJUnit4.class)
public class ParkingAutomationInstrumentedTest {
    private final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    private SharedPreferences prefs;
    private Map<String, ?> saved;
    private SignalService service;
    private OemTakeoverManager manager;
    private OemTakeoverController originalController;
    private final String[] keys = {UiPrefs.KEY_PARKING_AUTO_CLOSE, UiPrefs.KEY_PARKING_AUTO_OPEN_DRIVE,
            UiPrefs.KEY_PARKING_AUTO_CLOSE_ON_PARK,
            UiPrefs.KEY_PARKING_AUTO_CLOSE_SPEED, UiPrefs.KEY_EXTERNAL_CAMERA_PAUSED,
            DashcamSettingsController.KEY_ENABLED, UiPrefs.KEY_OEM_AVM_COEXIST, UiPrefs.KEY_OEM_AVM_ACTIVE,
            UiPrefs.KEY_OEM_CAMERA_ACCESS_BLOCKED, UiPrefs.KEY_OEM_TAKEOVER_YIELD,
            UiPrefs.KEY_DIGITAL_REARVIEW_ENABLED, UiPrefs.KEY_OVERLAY_ON_SIGNAL};

    private static void main(Runnable run) { InstrumentationRegistry.getInstrumentation().runOnMainSync(run); }
    private static Field field(Class<?> owner, String name) {
        try { Field value = owner.getDeclaredField(name); value.setAccessible(true); return value; }
        catch (Exception error) { throw new AssertionError(error); }
    }
    private static Object get(Object object, String name) {
        try { return field(object.getClass(), name).get(object); }
        catch (Exception error) { throw new AssertionError(error); }
    }
    private void invoke(String name, Class<?>[] types, Object... args) {
        try { Method value = SignalService.class.getDeclaredMethod(name, types); value.setAccessible(true); value.invoke(service, args); }
        catch (Exception error) { throw new AssertionError(error); }
    }
    private void gear(int value) { main(() -> invoke("onGearStateSample", new Class[]{int.class}, value)); }
    private void speed(float value) { main(() -> invoke("onParkingSpeedSample", new Class[]{float.class}, value)); }
    private ParkingAutomationPolicy policy() { return (ParkingAutomationPolicy) get(service, "parkingAutomation"); }
    private static void await(java.util.function.BooleanSupplier ready) {
        long end = SystemClock.elapsedRealtime() + 6000;
        do { if (ready.getAsBoolean()) return; SystemClock.sleep(25); } while (SystemClock.elapsedRealtime() < end);
        assertTrue("Parking activity did not settle", ready.getAsBoolean());
    }
    private static MainActivity visible() {
        AtomicReference<MainActivity> value = new AtomicReference<>();
        main(() -> { for (android.app.Activity activity : ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED))
            if (activity instanceof MainActivity) value.set((MainActivity) activity); });
        return value.get();
    }
    private void stopSamplers() {
        main(() -> {
            invoke("stopOemLifecycleMonitor", new Class[]{});
            try {
                field(SignalService.class, "polling").setBoolean(service, false);
                Handler handler = (Handler) get(service, "pollHandler");
                if (handler != null) handler.removeCallbacksAndMessages(null);
                HandlerThread thread = (HandlerThread) get(service, "pollThread");
                if (thread != null) thread.quitSafely();
                field(SignalService.class, "pollThread").set(service, null);
                field(SignalService.class, "pollHandler").set(service, null);
            } catch (Exception error) { throw new AssertionError(error); }
        });
    }

    @Before public void exclusiveCameraFixture() throws Exception {
        prefs = UiPrefs.getPrefs(context); saved = prefs.getAll();
        prefs.edit().putBoolean(DashcamSettingsController.KEY_ENABLED, false)
                .putBoolean(UiPrefs.KEY_PARKING_AUTO_CLOSE_ON_PARK, false)
                .putBoolean(UiPrefs.KEY_OEM_AVM_COEXIST, true).putBoolean(UiPrefs.KEY_OEM_AVM_ACTIVE, false)
                .putBoolean(UiPrefs.KEY_OEM_CAMERA_ACCESS_BLOCKED, false).putBoolean(UiPrefs.KEY_OEM_TAKEOVER_YIELD, false)
                .putBoolean(UiPrefs.KEY_EXTERNAL_CAMERA_PAUSED, false).putBoolean(UiPrefs.KEY_PARKING_AUTO_CLOSE, true)
                .putBoolean(UiPrefs.KEY_PARKING_AUTO_OPEN_DRIVE, true).putInt(UiPrefs.KEY_PARKING_AUTO_CLOSE_SPEED, 25)
                .putBoolean(UiPrefs.KEY_DIGITAL_REARVIEW_ENABLED, false).putBoolean(UiPrefs.KEY_OVERLAY_ON_SIGNAL, false).commit();
        try (ActivityScenario<MainActivity> setup = ActivityScenario.launch(MainActivity.class)) {
            await(SignalService::isRunning);
            service = (SignalService) field(SignalService.class, "sInstance").get(null);
            org.junit.Assume.assumeTrue(get(service, "lampCallbackProxy") == null);
            await(() -> SignalService.takeoverSnapshot() != null);
            manager = (OemTakeoverManager) get(service, "takeoverManager");
            originalController = (OemTakeoverController) get(manager, "controller");
            main(() -> {
                try { field(OemTakeoverManager.class, "controller").set(manager, new OemTakeoverController(new VerifiedPorts())); }
                catch (Exception error) { throw new AssertionError(error); }
            });
            UiPrefs.setOemAvmCoexistEnabled(prefs, false);
            await(() -> SignalService.takeoverSnapshot().state == OemTakeoverController.State.EXCLUSIVE);
            stopSamplers(); gear(4);
        }
        await(() -> !MainActivity.isMainVisible());
    }

    @After public void restore() throws Exception {
        MainActivity activity = visible();
        if (activity != null) main(activity::finishAndRemoveTask);
        if (prefs == null) return;
        prefs.edit().putBoolean(UiPrefs.KEY_OEM_AVM_COEXIST, true).putBoolean(UiPrefs.KEY_EXTERNAL_CAMERA_PAUSED, false).commit();
        if (service != null) gear(0);
        if (manager != null && originalController != null) {
            await(() -> SignalService.takeoverSnapshot().state == OemTakeoverController.State.OEM);
            main(() -> {
                try { field(OemTakeoverManager.class, "controller").set(manager, originalController); }
                catch (Exception error) { throw new AssertionError(error); }
            });
        }
        SharedPreferences.Editor edit = prefs.edit();
        for (String key : keys) {
            Object value = saved.get(key);
            if (value instanceof Boolean) edit.putBoolean(key, (Boolean) value);
            else if (value instanceof Integer) edit.putInt(key, (Integer) value);
            else edit.remove(key);
        }
        edit.commit();
        if (service != null) main(() -> {
            invoke("startPropertyPollingFallback", new Class[]{});
            invoke("startOemLifecycleMonitor", new Class[]{});
        });
        SignalService.refreshCameraAccess(context);
    }

    private MainActivity driveOpen() {
        gear(1); gear(4); await(() -> visible() != null);
        MainActivity activity = visible();
        assertEquals(CameraIndex.FRONT.getVideoIndex(), ((Integer) get(activity, "currentVideoIndex")).intValue());
        assertNotNull(policy().session());
        return activity;
    }
    private void closeUser(MainActivity activity) {
        main(() -> activity.findViewById(R.id.btnClose).performClick());
        await(() -> !MainActivity.isMainVisible());
    }
    private void staleClose(String session) {
        context.sendBroadcast(new Intent(SignalService.ACTION_CLOSE_PARKING).setPackage(context.getPackageName())
                .putExtra(SignalService.EXTRA_PARKING_SESSION, session));
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    }

    @Test public void driveClosesOnceAndManualReopenRejectsOldCloseSignals() {
        driveOpen(); String old = policy().session();
        speed(24.99f); assertTrue(MainActivity.isMainVisible()); speed(25);
        await(() -> !MainActivity.isMainVisible()); assertTrue(SignalService.isRunning());
        gear(4); assertFalse(MainActivity.isMainVisible());
        try (ActivityScenario<MainActivity> manual = ActivityScenario.launch(MainActivity.class)) {
            speed(0); speed(90); staleClose(old);
            assertTrue(MainActivity.isMainVisible()); assertNull(policy().session());
        }
        MainActivity again = driveOpen(); assertNotEquals(old, policy().session()); closeUser(again);
    }

    @Test public void highSpeedOpenWaitsForBelowThenAboveAndSurvivesRecreation() {
        MainActivity activity = driveOpen(); String session = policy().session();
        speed(60); speed(25); assertTrue(MainActivity.isMainVisible()); speed(0);
        main(activity::recreate); await(() -> visible() != null && visible() != activity);
        assertEquals(session, get(visible(), "parkingSession"));
        speed(25); await(() -> !MainActivity.isMainVisible());
    }

    @Test public void reverseUsesTheSameCloseSessionWithoutReopeningInUnchangedReverse() {
        gear(2); SystemClock.sleep(ReverseLaunchPolicy.SETTLE_MS + 30); gear(2);
        await(() -> visible() != null);
        assertEquals(CameraIndex.REAR.getVideoIndex(), ((Integer) get(visible(), "currentVideoIndex")).intValue());
        speed(0); speed(25); await(() -> !MainActivity.isMainVisible());
        gear(2); SystemClock.sleep(ReverseLaunchPolicy.SETTLE_MS + 30); gear(2);
        assertFalse(MainActivity.isMainVisible());
    }

    @Test public void manualViewDoesNotBecomeAutomaticWhenReverseIsSelected() {
        try (ActivityScenario<MainActivity> manual = ActivityScenario.launch(MainActivity.class)) {
            gear(1); gear(4); gear(2); speed(0); speed(80);
            assertTrue(MainActivity.isMainVisible()); assertNull(policy().session());
        }
    }

    @Test public void openDriveWorksWithMasterCloseOffAndEnablingItStartsFresh() {
        prefs.edit().putBoolean(UiPrefs.KEY_PARKING_AUTO_CLOSE, false).commit();
        driveOpen(); speed(0); speed(70); assertTrue(MainActivity.isMainVisible());
        prefs.edit().putBoolean(UiPrefs.KEY_PARKING_AUTO_CLOSE, true).commit();
        speed(70); assertTrue(MainActivity.isMainVisible());
        speed(0); speed(25); await(() -> !MainActivity.isMainVisible());
    }

    @Test public void leavingTheTaskCancelsOwnershipAndExternalPauseRejectsOldClose() {
        MainActivity activity = driveOpen(); String session = policy().session();
        main(() -> assertTrue(activity.moveTaskToBack(true)));
        await(() -> !MainActivity.isMainVisible()); assertNull(policy().session());
        try (ActivityScenario<MainActivity> manual = ActivityScenario.launch(MainActivity.class)) {
            staleClose(session); speed(0); speed(90); assertTrue(MainActivity.isMainVisible());
        }
        driveOpen(); session = policy().session();
        prefs.edit().putBoolean(UiPrefs.KEY_EXTERNAL_CAMERA_PAUSED, true).commit();
        await(() -> policy().session() == null);
        staleClose(session); assertTrue(MainActivity.isMainVisible());
    }

    @Test public void parkClosesAutomaticDriveAndReverseAndDiscardsTheirSpeedSessions() {
        prefs.edit().putBoolean(UiPrefs.KEY_PARKING_AUTO_CLOSE_ON_PARK, true).commit();
        driveOpen(); String driveSession = policy().session();
        gear(1); await(() -> !MainActivity.isMainVisible());
        assertNull(policy().session()); assertTrue(SignalService.isRunning());
        gear(1); assertFalse(MainActivity.isMainVisible());
        try (ActivityScenario<MainActivity> manual = ActivityScenario.launch(MainActivity.class)) {
            speed(0); speed(90); staleClose(driveSession);
            assertTrue(MainActivity.isMainVisible());
        }
        gear(3); gear(2); SystemClock.sleep(ReverseLaunchPolicy.SETTLE_MS + 30); gear(2);
        await(() -> visible() != null);
        assertNotNull(policy().session());
        gear(1); await(() -> !MainActivity.isMainVisible());
        assertNull(policy().session()); assertTrue(SignalService.isRunning());
    }

    @Test public void parkAlsoClosesManualViewsWithOemDisabledAndBothOtherTogglesOff() {
        prefs.edit().putBoolean(UiPrefs.KEY_PARKING_AUTO_CLOSE_ON_PARK, true)
                .putBoolean(UiPrefs.KEY_PARKING_AUTO_CLOSE, false)
                .putBoolean(UiPrefs.KEY_PARKING_AUTO_OPEN_DRIVE, false).commit();
        try (ActivityScenario<MainActivity> manual = ActivityScenario.launch(MainActivity.class)) {
            assertNull(policy().session()); gear(1);
            await(() -> !MainActivity.isMainVisible());
            assertTrue(SignalService.isRunning());
        }
    }

    private final class VerifiedPorts implements OemTakeoverController.Ports {
        private OemTakeoverController.Journal journal = new OemTakeoverController.Journal();
        private int receiver;
        public OemTakeoverController.Journal load() { return journal.copy(); }
        public void save(OemTakeoverController.Journal value) { journal = value.copy(); }
        public int receiverState() { return receiver; }
        public void receiverState(int state) { receiver = state; }
        public void startDataService() {}
        public boolean dataServiceRunning() { return true; }
        public void yieldCameras(boolean yield) { SignalService.setTakeoverYield(context, yield); }
        public void releaseCameras() { CameraProbe.setOemCameraAccessBlocked(true); }
        public void acquireCameras() {}
        public int heldMask() { return 15; }
        public long rearFrameAgeMs() { return 0; }
        public void closeOem() {}
        public void openOem() {}
        public void log(String message) {}
    }
}
