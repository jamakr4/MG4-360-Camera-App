package com.drivehub.kamera.signal;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.drivehub.kamera.CameraProbe;
import com.drivehub.kamera.MainActivity;
import com.drivehub.kamera.R;
import com.drivehub.kamera.dashcam.DashcamSettingsController;
import com.drivehub.kamera.settings.UiPrefs;
import java.lang.reflect.Field;
import java.util.Map;
import java.util.function.BooleanSupplier;
import org.junit.Test;
import org.junit.runner.RunWith;
import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.action.ViewActions.scrollTo;
import static androidx.test.espresso.matcher.RootMatchers.isDialog;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class OemTakeoverInstrumentedTest {
    private static final Context CONTEXT = InstrumentationRegistry.getInstrumentation().getTargetContext();

    @Test public void diagnosticExportCreatesATextFileInTheSelectedRecordingStorage() throws Exception {
        SharedPreferences prefs=UiPrefs.getPrefs(CONTEXT);
        String key=com.drivehub.kamera.dashcam.DashcamStorageManager.KEY_STORAGE_TARGET;
        boolean existed=prefs.contains(key);int original=prefs.getInt(key,2);
        java.io.File file=null;
        try {
            com.drivehub.kamera.dashcam.DashcamStorageManager.setStorageTarget(prefs,
                    com.drivehub.kamera.dashcam.DashcamStorageManager.TARGET_INTERNAL_ONLY);
            file=OemTakeoverManager.exportDiagnostic(CONTEXT);
            assertTrue(file.isFile());assertEquals("oem-diagnostics",file.getParentFile().getName());
            assertTrue(file.getName().endsWith(".log"));
            String text=new String(java.nio.file.Files.readAllBytes(file.toPath()),java.nio.charset.StandardCharsets.UTF_8);
            assertTrue(text.startsWith("App "+com.drivehub.kamera.BuildConfig.VERSION_NAME));
            assertTrue(text.contains("OEM takeover diagnostics; no camera images"));
        } finally {
            if(file!=null)assertTrue(file.delete());
            SharedPreferences.Editor editor=prefs.edit();
            if(existed)editor.putInt(key,original);else editor.remove(key);
            editor.commit();
        }
    }

    @Test public void nativeHealthReportsDescriptorsAndFrameAbsenceWithoutOpeningCameras() {
        boolean blocked = CameraProbe.isOemCameraAccessBlocked();
        try {
            CameraProbe.setOemCameraAccessBlocked(true);
            long[] health = CameraProbe.cameraHealth();
            assertEquals(24, health.length);
            for (int camera = 0; camera < 4; camera++) {
                assertEquals(0, health[camera * 6]);
                assertEquals(0, health[camera * 6 + 2]);
                assertEquals(-1, health[camera * 6 + 3]);
            }
        } finally { CameraProbe.setOemCameraAccessBlocked(blocked); }
    }

    @Test public void settingsRestoreAcknowledgesPersistedFallback() throws Exception {
        SharedPreferences prefs = UiPrefs.getPrefs(CONTEXT);
        Map<String, ?> saved = prefs.getAll();
        prefs.edit().putBoolean(DashcamSettingsController.KEY_ENABLED, false)
                .putBoolean(UiPrefs.KEY_OEM_AVM_COEXIST, true).commit();
        OemTakeoverManager manager = null;
        OemTakeoverController original = null;
        Field controllerField = OemTakeoverManager.class.getDeclaredField("controller");
        controllerField.setAccessible(true);
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            await(() -> SignalService.takeoverSnapshot() != null);
            Field instanceField = SignalService.class.getDeclaredField("sInstance"); instanceField.setAccessible(true);
            SignalService service = (SignalService) instanceField.get(null);
            Field managerField = SignalService.class.getDeclaredField("takeoverManager"); managerField.setAccessible(true);
            manager = (OemTakeoverManager) managerField.get(service);
            original = (OemTakeoverController) controllerField.get(manager);
            FallbackPorts ports = new FallbackPorts();
            controllerField.set(manager, new OemTakeoverController(ports));
            scenario.onActivity(activity -> UiPrefs.setOemAvmCoexistEnabled(prefs, false));
            await(() -> SignalService.takeoverSnapshot().state == OemTakeoverController.State.FALLBACK);
            assertTrue(CameraProbe.isOemCameraAccessBlocked());
            onView(withId(R.id.btnSettings)).perform(click());
            onView(withId(R.id.tabSettings)).inRoot(isDialog()).perform(scrollTo(), click());
            onView(withId(R.id.btnSettingsRestoreOem)).inRoot(isDialog()).perform(scrollTo(), click());
            await(() -> SignalService.takeoverSnapshot().state == OemTakeoverController.State.OEM);
            assertTrue(UiPrefs.isOemAvmCoexistEnabled(prefs));
            assertFalse(prefs.getBoolean(UiPrefs.KEY_OEM_TAKEOVER_YIELD, true));
            assertEquals(0, ports.opens); // Unknown emulator gear must not relaunch anything.
            onView(withId(R.id.btnClose)).inRoot(isDialog()).perform(click());
            await(() -> OemTakeoverManager.diagnosticText(CONTEXT).contains("health[L,R,F,rear"));
        } finally {
            prefs.edit().putBoolean(UiPrefs.KEY_OEM_AVM_COEXIST, true).commit();
            if (manager != null && original != null) controllerField.set(manager, original);
            SharedPreferences.Editor editor = prefs.edit();
            for (String key : new String[]{DashcamSettingsController.KEY_ENABLED, UiPrefs.KEY_OEM_AVM_COEXIST,
                    UiPrefs.KEY_OEM_TAKEOVER_YIELD}) {
                if (saved.containsKey(key)) editor.putBoolean(key, (Boolean) saved.get(key));
                else editor.remove(key);
            }
            editor.commit();
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> SignalService.refreshCameraAccess(CONTEXT));
        }
    }

    /** Simulates the persisted reverse latch; native gates and the real Settings UI remain active. */
    private static final class FallbackPorts implements OemTakeoverController.Ports {
        OemTakeoverController.Journal disk = new OemTakeoverController.Journal();
        int opens;
        FallbackPorts() { disk.fallback = true; disk.oemLaunchAttempted = true; }
        public OemTakeoverController.Journal load() { return disk.copy(); }
        public void save(OemTakeoverController.Journal journal) { disk = journal.copy(); }
        public int receiverState() { return 0; }
        public void receiverState(int state) { throw new AssertionError("No OEM mutation in emulator fixture"); }
        public void startDataService() { }
        public boolean dataServiceRunning() { return true; }
        public void yieldCameras(boolean yield) { SignalService.setTakeoverYield(CONTEXT, yield); }
        public void releaseCameras() { CameraProbe.setOemCameraAccessBlocked(true); }
        public void acquireCameras() { throw new AssertionError("Fallback cannot reacquire cameras"); }
        public int heldMask() { return 0; }
        public long rearFrameAgeMs() { return -1; }
        public void closeOem() { throw new AssertionError("Fallback cannot close OEM"); }
        public void openOem() { opens++; }
        public void log(String message) { }
    }

    private static void await(BooleanSupplier ready) {
        long deadline = SystemClock.elapsedRealtime() + 5000;
        while (!ready.getAsBoolean() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(30);
        assertTrue("Takeover state did not settle", ready.getAsBoolean());
    }
}
