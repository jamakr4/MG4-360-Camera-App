package com.drivehub.kamera.dashcam;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.os.SystemClock;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class SurveillanceStoreTest {
    private Context context;
    private SharedPreferences prefs;

    @Before public void setup() {
        Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        prefs = target.getSharedPreferences("surveillance-isolated-test", Context.MODE_PRIVATE);
        prefs.edit().clear().commit();
        context = new ContextWrapper(target) {
            @Override public SharedPreferences getSharedPreferences(String name, int mode) { return prefs; }
        };
    }
    @After public void cleanup() { prefs.edit().clear().commit(); }

    private SurveillanceSession create(boolean original) {
        return new SurveillanceSession(SystemClock.elapsedRealtime() + 60_000, SurveillanceStore.bootCount(context),
                original, "stop", "/surveillance", "round-trip");
    }

    @Test public void persistsDeadlineAndOriginalToggleBeforeDisablingNormalRecording() {
        prefs.edit().putBoolean(DashcamSettingsController.KEY_ENABLED, true).commit();
        SurveillanceSession original = create(true);
        assertTrue(SurveillanceStore.save(context, original, false));
        assertTrue(prefs.getBoolean(DashcamSettingsController.KEY_ENABLED, false));
        original.prepared = true;
        original.device = 123;
        original.usingUsb = true;
        assertTrue(SurveillanceStore.save(context, original, true));
        assertFalse(prefs.getBoolean(DashcamSettingsController.KEY_ENABLED, true));
        SurveillanceSession recovered = SurveillanceStore.read(context);
        assertNotNull(recovered);
        assertEquals(original.deadline, recovered.deadline);
        assertEquals(original.bootCount, recovered.bootCount);
        assertEquals("round-trip", recovered.requestId);
        assertEquals(123, recovered.device);
        assertTrue(recovered.prepared);
        assertTrue(recovered.usingUsb);
        assertTrue(recovered.canResume(original.bootCount, SystemClock.elapsedRealtime()));
        assertTrue(SurveillanceStore.restore(context));
        assertTrue(prefs.getBoolean(DashcamSettingsController.KEY_ENABLED, false));
        assertFalse(SurveillanceStore.isActive(prefs));
    }

    @Test public void restoresAnOriginallyDisabledDashcamAndIdleRestoreIsHarmless() {
        assertTrue(SurveillanceStore.save(context, create(false), true));
        assertTrue(SurveillanceStore.restore(context));
        assertFalse(prefs.getBoolean(DashcamSettingsController.KEY_ENABLED, true));
        prefs.edit().putBoolean(DashcamSettingsController.KEY_ENABLED, true).commit();
        assertTrue(SurveillanceStore.restore(context));
        assertTrue(prefs.getBoolean(DashcamSettingsController.KEY_ENABLED, false));
    }

    @Test public void stopThenProcessDeathCannotRestartSurveillance() {
        SurveillanceSession session = create(true);
        SurveillanceStore.save(context, session, true);
        session.stop("requested", false, false);
        SurveillanceStore.save(context, session, false);
        SurveillanceSession recovered = SurveillanceStore.read(context);
        assertFalse(recovered.canResume(session.bootCount, SystemClock.elapsedRealtime()));
        assertEquals("requested", recovered.stopReason());
        SurveillanceStore.restore(context);
        assertTrue(prefs.getBoolean(DashcamSettingsController.KEY_ENABLED, false));
    }

    @Test public void rebootAndExpiredSessionRestorePreviousState() {
        SurveillanceSession session = create(true);
        SurveillanceStore.save(context, session, true);
        SurveillanceSession recovered = SurveillanceStore.read(context);
        assertFalse(recovered.canResume(session.bootCount + 1, 1));
        assertFalse(recovered.canResume(session.bootCount, session.deadline));
        SurveillanceStore.restore(context);
        assertTrue(prefs.getBoolean(DashcamSettingsController.KEY_ENABLED, false));
    }

    @Test public void malformedSessionRestoresTheSeparatelyPersistedOriginalToggle() {
        SurveillanceStore.save(context, create(true), true);
        prefs.edit().putString(SurveillanceStore.KEY_SESSION, "broken json").commit();
        assertNull(SurveillanceStore.read(context));
        assertTrue(prefs.getBoolean(DashcamSettingsController.KEY_ENABLED, false));
        assertFalse(SurveillanceStore.isActive(prefs));
    }
}
