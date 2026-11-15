package com.drivehub.kamera.signal;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.SharedPreferences;
import android.os.SystemClock;
import androidx.test.core.app.ActivityScenario;
import com.drivehub.kamera.MainActivity;
import com.drivehub.kamera.R;
import com.drivehub.kamera.dashcam.DashcamSettingsController;
import com.drivehub.kamera.settings.UiPrefs;
import java.lang.reflect.Field;
import java.util.Map;
import java.util.function.BooleanSupplier;
import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.action.ViewActions.scrollTo;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.RootMatchers.isDialog;
import static androidx.test.espresso.matcher.ViewMatchers.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Android component persistence is exercised against our own fixture, never the OEM package. */
@RunWith(AndroidJUnit4.class)
public class OemReceiverRecoveryInstrumentedTest {
    @Test public void lostInstallationJournalDoesNotLeaveTheReceiverDisabled() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        ComponentName receiver = new ComponentName(context, OemDataProbeDebugServices.StartReceiver.class);
        PackageManager packages = context.getPackageManager();
        int original = packages.getComponentEnabledSetting(receiver);
        int pid = android.os.Process.myPid();
        try {
            packages.setComponentEnabledSetting(receiver, PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP);
            RecoveryPorts ports = new RecoveryPorts(packages, receiver);
            // A reinstall has a new empty journal, but the OTHER package's setting remains disabled.
            OemTakeoverController controller = new OemTakeoverController(ports);
            controller.tick(true, 1, false, 0);
            assertEquals(PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                    packages.getComponentEnabledSetting(receiver));
            assertTrue(packages.getReceiverInfo(receiver, 0).enabled);
            assertEquals(OemTakeoverController.State.OEM, controller.snapshot().state);
            assertTrue(ports.released);
            assertFalse(ports.disk.ownsReceiver);
            assertEquals(pid, android.os.Process.myPid());

            // Also repair an explicit restore while the preference was already true.
            packages.setComponentEnabledSetting(receiver, PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP);
            ports.released = false;
            controller.retryRestore(); controller.tick(true, 1, false, 100);
            assertEquals(PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                    packages.getComponentEnabledSetting(receiver));
            assertTrue(ports.released);
        } catch (PackageManager.NameNotFoundException missingFixture) {
            throw new AssertionError(missingFixture);
        } finally {
            packages.setComponentEnabledSetting(receiver, original, PackageManager.DONT_KILL_APP);
        }
    }

    @Test public void restoreButtonRepairsAnOrphanWithDefaultOnAndRemainsEnabledForRepeatedClicks() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        SharedPreferences prefs = UiPrefs.getPrefs(context);
        Map<String, ?> saved = prefs.getAll();
        assertTrue(prefs.edit().remove(UiPrefs.KEY_OEM_AVM_COEXIST)
                .putBoolean(DashcamSettingsController.KEY_ENABLED, false).commit());
        ComponentName receiver = new ComponentName(context, OemDataProbeDebugServices.StartReceiver.class);
        PackageManager packages = context.getPackageManager();
        int originalReceiver = packages.getComponentEnabledSetting(receiver);
        OemTakeoverManager manager = null;
        OemTakeoverController originalController = null;
        Field controllerField = OemTakeoverManager.class.getDeclaredField("controller");
        controllerField.setAccessible(true);
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            await(() -> SignalService.takeoverSnapshot() != null);
            Field instanceField = SignalService.class.getDeclaredField("sInstance");
            instanceField.setAccessible(true);
            Field managerField = SignalService.class.getDeclaredField("takeoverManager");
            managerField.setAccessible(true);
            manager = (OemTakeoverManager) managerField.get(instanceField.get(null));
            originalController = (OemTakeoverController) controllerField.get(manager);
            packages.setComponentEnabledSetting(receiver, PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                    PackageManager.DONT_KILL_APP);
            RecoveryPorts ports = new RecoveryPorts(packages, receiver);
            OemTakeoverController controller = new OemTakeoverController(ports);
            controller.tick(true, 1, false, 0); // The normal OEM check has already completed.
            controllerField.set(manager, controller);

            onView(withId(R.id.btnSettings)).perform(click());
            onView(withId(R.id.tabSettings)).inRoot(isDialog()).perform(click());
            for (int attempt = 0; attempt < 2; attempt++) {
                packages.setComponentEnabledSetting(receiver, PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                        PackageManager.DONT_KILL_APP);
                ports.released = false;
                assertTrue(UiPrefs.isOemAvmCoexistEnabled(prefs));
                onView(withId(R.id.switchOemAvmCoexist)).inRoot(isDialog()).check(matches(isChecked()));
                onView(withId(R.id.btnSettingsRestoreOem)).inRoot(isDialog())
                        .check(matches(isEnabled())).perform(scrollTo(), click());
                await(() -> packages.getComponentEnabledSetting(receiver) == PackageManager.COMPONENT_ENABLED_STATE_ENABLED);
                assertTrue(ports.released);
                onView(withId(R.id.tvSettingsRestoreOemStatus)).inRoot(isDialog())
                        .check(matches(withText(R.string.settings_oem_restore_success)));
                onView(withId(R.id.btnSettingsRestoreOem)).inRoot(isDialog()).check(matches(isEnabled()));
            }
            onView(withId(R.id.btnClose)).inRoot(isDialog()).perform(click());
        } finally {
            if (manager != null && originalController != null) controllerField.set(manager, originalController);
            packages.setComponentEnabledSetting(receiver, originalReceiver, PackageManager.DONT_KILL_APP);
            SharedPreferences.Editor editor = prefs.edit();
            for (String key : new String[]{UiPrefs.KEY_OEM_AVM_COEXIST, DashcamSettingsController.KEY_ENABLED}) {
                if (saved.containsKey(key)) editor.putBoolean(key, (Boolean) saved.get(key));
                else editor.remove(key);
            }
            assertTrue(editor.commit());
        }
    }

    private static void await(BooleanSupplier ready) {
        long deadline = SystemClock.elapsedRealtime() + 5000;
        while (!ready.getAsBoolean() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(30);
        assertTrue("OEM receiver recovery did not finish", ready.getAsBoolean());
    }

    private static final class RecoveryPorts implements OemTakeoverController.Ports {
        final PackageManager packages;
        final ComponentName receiver;
        OemTakeoverController.Journal disk = new OemTakeoverController.Journal();
        boolean released;
        RecoveryPorts(PackageManager packages, ComponentName receiver) {
            this.packages = packages; this.receiver = receiver;
        }
        public OemTakeoverController.Journal load() { return disk.copy(); }
        public void save(OemTakeoverController.Journal journal) { disk = journal.copy(); }
        public int receiverState() { return packages.getComponentEnabledSetting(receiver); }
        public void receiverState(int value) {
            assertTrue("Release cameras before enabling OEM", released);
            assertTrue("Persist repair before changing another package", disk.ownsReceiver);
            packages.setComponentEnabledSetting(receiver, value, PackageManager.DONT_KILL_APP);
        }
        public void yieldCameras(boolean value) { }
        public void releaseCameras() { released = true; }
        public void acquireCameras() { throw new AssertionError("OEM recovery must not take cameras"); }
        public void startDataService() { throw new AssertionError("No vehicle data-service interaction"); }
        public boolean dataServiceRunning() { return false; }
        public int heldMask() { return 0; }
        public long rearFrameAgeMs() { return -1; }
        public void closeOem() { throw new AssertionError("Never close OEM during recovery"); }
        public void openOem() { throw new AssertionError("P must not launch a reverse view"); }
        public void log(String message) { }
    }
}
