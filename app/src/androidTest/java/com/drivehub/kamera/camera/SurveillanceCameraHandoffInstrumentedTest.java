package com.drivehub.kamera.camera;

import android.content.ComponentName;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.SystemClock;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.drivehub.kamera.dashcam.DashcamSettingsController;
import com.drivehub.kamera.dashcam.RecordingService;
import com.drivehub.kamera.dashcam.SurveillanceSession;
import com.drivehub.kamera.dashcam.SurveillanceStore;
import com.drivehub.kamera.remote.RemoteRecording;
import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** A cold surveillance recovery must participate in camera handoff before its async service starts. */
@RunWith(AndroidJUnit4.class)
public class SurveillanceCameraHandoffInstrumentedTest {
    @Test public void recoveryCannotReportIdleWhileWaitingForTheRecorderToStart() throws Exception {
        assertFalse("Fixture requires an idle recording service", RecordingService.isRunning());
        Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        SharedPreferences prefs = target.getSharedPreferences("surveillance-handoff-isolated-test", Context.MODE_PRIVATE);
        Field field = CameraHandoffController.class.getDeclaredField("STATE");
        field.setAccessible(true);
        CameraHandoffState state = (CameraHandoffState) field.get(null);
        prefs.edit().clear().putBoolean(DashcamSettingsController.KEY_ENABLED, false).commit();
        AtomicBoolean started = new AtomicBoolean();
        CameraHandoffController.demand("recording", false);
        try {
            assertEquals("idle", state.evaluate(false, state.generation(), 0, 0, false, true, false));
            ContextWrapper context = new ContextWrapper(target) {
                @Override public SharedPreferences getSharedPreferences(String name, int mode) { return prefs; }
                @Override public ComponentName startForegroundService(Intent intent) {
                    started.set(true);
                    assertEquals(RemoteRecording.RECOVER, intent.getAction());
                    assertNull("Resume must wait for recorder readiness before returning idle",
                            state.evaluate(false, state.generation(), 0, 0, false, true, false));
                    assertEquals("timeout", state.evaluate(false, state.generation(), 0, 0, false, true, true));
                    return intent.getComponent();
                }
            };
            SurveillanceSession session = new SurveillanceSession(SystemClock.elapsedRealtime() + 60_000,
                    SurveillanceStore.bootCount(context), false, "stop", "/surveillance", "handoff-test");
            assertTrue(SurveillanceStore.save(context, session, false));
            assertFalse(prefs.getBoolean(DashcamSettingsController.KEY_ENABLED, true));
            RecordingService.startIfDashcamEnabled(context);
            assertTrue(started.get());
            assertNull(state.evaluate(false, state.generation(), 0, 0, false, true, false));
        } finally {
            CameraHandoffController.demand("recording", false);
            prefs.edit().clear().commit();
        }
    }
}
