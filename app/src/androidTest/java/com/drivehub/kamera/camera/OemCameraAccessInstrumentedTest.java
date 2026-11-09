package com.drivehub.kamera.camera;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.view.Surface;
import android.view.SurfaceView;
import android.widget.TextView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.drivehub.kamera.CameraProbe;
import com.drivehub.kamera.MainActivity;
import com.drivehub.kamera.R;
import com.drivehub.kamera.dashcam.DashcamSettingsController;
import com.drivehub.kamera.dashcam.OemAvmReceiver;
import com.drivehub.kamera.settings.UiPrefs;
import com.drivehub.kamera.signal.SignalService;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.lang.reflect.Field;
import java.util.Map;
import java.util.function.BooleanSupplier;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class OemCameraAccessInstrumentedTest {
    private static final String START = "com.saicmotor.hmi.aroundview.ACTION_START";
    private static final String STOP = "com.saicmotor.hmi.aroundview.ACTION_STOP";

    @Test public void oemRequestBlocksOpenAppWithoutDashcamAndSurvivesRecreation() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        SharedPreferences prefs = UiPrefs.getPrefs(context);
        Map<String, ?> original = prefs.getAll();
        prepare(prefs);
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            await(SignalService::isRunning);
            scenario.onActivity(activity -> {
                prefs.edit().putBoolean(UiPrefs.KEY_OEM_AVM_COEXIST, true).apply();
                assertFalse(prefs.getBoolean(DashcamSettingsController.KEY_ENABLED, true));
                new OemAvmReceiver().onReceive(activity, new Intent(START));
                assertTrue(CameraProbe.isOemCameraAccessBlocked());
                Surface surface = ((SurfaceView) activity.findViewById(R.id.surfaceView)).getHolder().getSurface();
                assertFalse(CameraProbe.attachPreview(17, surface));
                assertFalse(CameraProbe.attachEnhancedPreview(16, surface));
                assertFalse(CameraProbe.attachPreviewSink(17, 12, surface));
                assertFalse(CameraProbe.attachCombinedPreview(surface, 720, 480, 25, "", false, 15));
                String output = new java.io.File(activity.getCacheDir(), "oem-blocked-test.mp4").getPath();
                assertFalse(CameraProbe.startMp4Record(0, 17, output, 720, 480, 25, 9000000));
                assertFalse(CameraProbe.startCombinedMp4Record(output, 720, 480, 25, 9000000, "", false, 15));
                assertFalse(new java.io.File(output).exists());
                activity.sendBroadcast(new Intent(SignalService.ACTION_ROUTE_CAMERA)
                        .setPackage(activity.getPackageName()).putExtra(SignalService.EXTRA_CAMERA_INDEX, 17));
            });
            scenario.recreate();
            scenario.onActivity(activity -> {
                assertTrue(CameraProbe.isOemCameraAccessBlocked());
                String paused = activity.getString(R.string.side_camera_oem_paused);
                assertEquals(paused, ((TextView) activity.findViewById(R.id.tvStatus)).getText().toString());
                assertEquals(paused, ((TextView) activity.findViewById(R.id.leftCameraFailure)).getText().toString());
                new OemAvmReceiver().onReceive(activity, new Intent(STOP));
                new OemAvmReceiver().onReceive(activity, new Intent(START));
            });
            SystemClock.sleep(600);
            assertTrue("Bouncing stop/start must keep ownership with OEM", CameraProbe.isOemCameraAccessBlocked());
            scenario.onActivity(activity -> new OemAvmReceiver().onReceive(activity, new Intent(STOP)));
            await(() -> !CameraProbe.isOemCameraAccessBlocked());
        } finally { restore(context, prefs, original); }
    }

    @Test public void reverseHoldsOwnershipUntilCoexistenceIsDisabled() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        SharedPreferences prefs = UiPrefs.getPrefs(context);
        Map<String, ?> original = prefs.getAll();
        prepare(prefs);
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            await(SignalService::isRunning);
            scenario.onActivity(activity -> {
                try {
                    Field instance = SignalService.class.getDeclaredField("sInstance");
                    instance.setAccessible(true);
                    SignalService service = (SignalService) instance.get(null);
                    Field gear = SignalService.class.getDeclaredField("currentGear");
                    gear.setAccessible(true);
                    int previous = gear.getInt(service);
                    try {
                        prefs.edit().putBoolean(UiPrefs.KEY_OEM_AVM_COEXIST, true).apply();
                        gear.setInt(service, 2);
                        SignalService.refreshCameraAccess(activity);
                        assertTrue(CameraProbe.isOemCameraAccessBlocked());
                        new OemAvmReceiver().onReceive(activity, new Intent(STOP));
                        SignalService.refreshCameraAccess(activity);
                        assertTrue("OEM stop must not release cameras in R", CameraProbe.isOemCameraAccessBlocked());
                        prefs.edit().putBoolean(UiPrefs.KEY_OEM_AVM_COEXIST, false).apply();
                        SignalService.refreshCameraAccess(activity);
                        assertFalse(CameraProbe.isOemCameraAccessBlocked());
                        new OemAvmReceiver().onReceive(activity, new Intent(START));
                        assertFalse(CameraProbe.isOemCameraAccessBlocked());
                        assertFalse(UiPrefs.isOemCameraAccessBlocked(prefs));
                    } finally { gear.setInt(service, previous); }
                } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
            });
        } finally { restore(context, prefs, original); }
    }

    @Test public void abandonedOemRequestRecoversWithoutRecorderOrVehicleEvents() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        SharedPreferences prefs = UiPrefs.getPrefs(context);
        Map<String, ?> original = prefs.getAll();
        prepare(prefs);
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            await(SignalService::isRunning);
            scenario.onActivity(activity -> {
                prefs.edit().putBoolean(UiPrefs.KEY_OEM_AVM_COEXIST, true).apply();
                new OemAvmReceiver().onReceive(activity, new Intent(START));
                assertTrue(CameraProbe.isOemCameraAccessBlocked());
            });
            // OEM never becomes foreground and emits no STOP. The monitor must recover after
            // its startup grace, independently of the recorder and of new indicator/gear events.
            await(() -> !CameraProbe.isOemCameraAccessBlocked(), 12000);
            scenario.onActivity(activity -> {
                assertFalse(UiPrefs.isOemAvmActive(prefs));
                assertFalse(UiPrefs.isOemCameraAccessBlocked(prefs));
            });
        } finally { restore(context, prefs, original); }
    }

    private static void prepare(SharedPreferences prefs) {
        assertTrue(prefs.edit().putBoolean(DashcamSettingsController.KEY_ENABLED, false)
                .putBoolean(UiPrefs.KEY_OEM_AVM_COEXIST, false)
                .putBoolean(UiPrefs.KEY_OEM_AVM_ACTIVE, false)
                .putBoolean(UiPrefs.KEY_OEM_CAMERA_ACCESS_BLOCKED, false).commit());
        CameraProbe.setOemCameraAccessBlocked(false);
    }

    private static void restore(Context context, SharedPreferences prefs, Map<String, ?> original) {
        prefs.edit().putBoolean(UiPrefs.KEY_OEM_AVM_COEXIST, false).commit();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            SignalService.setOemAvmActive(context, false);
            SignalService.refreshCameraAccess(context);
        });
        SharedPreferences.Editor editor = prefs.edit();
        for (String key : new String[]{DashcamSettingsController.KEY_ENABLED, UiPrefs.KEY_OEM_AVM_COEXIST,
                UiPrefs.KEY_OEM_AVM_ACTIVE, UiPrefs.KEY_OEM_CAMERA_ACCESS_BLOCKED}) {
            if (original.containsKey(key)) editor.putBoolean(key, (Boolean) original.get(key));
            else editor.remove(key);
        }
        assertTrue(editor.commit());
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> SignalService.refreshCameraAccess(context));
    }

    private static void await(BooleanSupplier condition) {
        await(condition, 4000);
    }

    private static void await(BooleanSupplier condition, long timeoutMs) {
        long deadline = SystemClock.elapsedRealtime() + timeoutMs;
        while (!condition.getAsBoolean() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(50);
        assertTrue("Camera ownership state did not settle", condition.getAsBoolean());
    }
}
