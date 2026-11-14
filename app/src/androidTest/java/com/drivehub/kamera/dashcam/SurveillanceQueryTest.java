package com.drivehub.kamera.dashcam;

import android.content.ComponentName;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.SystemClock;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.drivehub.kamera.remote.QueryStateReceiver;
import com.drivehub.kamera.remote.RemoteRecording;
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

/** Exercise real query replies with isolated preferences, without starting vehicle cameras. */
@RunWith(AndroidJUnit4.class)
public class SurveillanceQueryTest {
    private SharedPreferences prefs;
    private Capture context;
    private RecordingService live;

    @Before public void setup() throws Exception {
        // A constructor-only fixture supplies the service's observed state, not camera hardware.
        assumeTrue("Query fixture requires an idle instrumentation app", field("instance").get(null) == null);
        Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        prefs = target.getSharedPreferences("surveillance-query-isolated-test", Context.MODE_PRIVATE);
        prefs.edit().clear().commit();
        context = new Capture(target);
    }

    @After public void cleanup() throws Exception {
        if (live != null) {
            field("instance").set(null, null);
            ((ExecutorService) field("supervisor").get(live)).shutdownNow();
            ((ExecutorService) field("eventCopyExecutor").get(live)).shutdownNow();
        }
        if (prefs != null) prefs.edit().clear().commit();
    }

    @Test public void idleQueryRepliesWithoutStartingAnythingOrWritingPreferences() {
        new QueryStateReceiver().onReceive(context, new Intent(RemoteRecording.QUERY).putExtra("request_id", "idle-1"));
        assertReply("stopped", "idle", "", -1, "idle-1");
        assertTrue(prefs.getAll().isEmpty());
    }

    @Test public void liveStatesAndStorageAreReturnedWithQueryCorrelationWithoutChangingTheSession() throws Exception {
        SurveillanceSession session = activeFixture();
        long deadline = session.deadline;
        Map<String, ?> originalPrefs = new HashMap<>(prefs.getAll());
        for (String state : new String[]{"starting", "started", "paused", "error"}) {
            field("remoteState").set(live, state);
            String reason = state.equals("paused") ? "oem_camera" : state.equals("error") ? "recording_failed" : "";
            field("remoteReason").set(live, reason);
            field("remoteFreeBytes").set(live, 3 * SurveillancePolicy.GIB);
            new QueryStateReceiver().onReceive(context, new Intent(RemoteRecording.QUERY)
                    .putExtra("request_id", "query-" + state)
                    .putExtra("max_minutes", "120").putExtra("directory", "/ignored")
                    .putExtra("storage_policy", "warn"));
            assertReply(state, reason, "/surveillance", 3 * SurveillancePolicy.GIB, "query-" + state);
            assertEquals("limited", context.reply.getStringExtra("storage_level"));
            assertEquals(deadline, session.deadline);
            assertEquals("stop", session.policy);
            assertEquals("start-1", session.requestId);
            assertNull(session.stopReason());
            assertEquals(originalPrefs, prefs.getAll());
        }
    }

    @Test public void normalDashcamIsReportedAsSurveillanceIdleEvenWithAnOldStartedStatus() throws Exception {
        activeFixture();
        field("surveillanceSession").set(live, null);
        field("remoteState").set(live, "started");
        SurveillanceStore.restore(context);
        assertTrue(prefs.getBoolean(DashcamSettingsController.KEY_ENABLED, false));
        new QueryStateReceiver().onReceive(context, new Intent(RemoteRecording.QUERY).putExtra("request_id", "normal-1"));
        assertReply("stopped", "idle", "", -1, "normal-1");
        assertTrue(prefs.getBoolean(DashcamSettingsController.KEY_ENABLED, false));
    }

    @Test public void liveFailureDoesNotReportOldStartedStateWhileFinalizationIsPending() throws Exception {
        SurveillanceSession session = activeFixture();
        field("remoteState").set(live, "started");
        session.stop("storage_unavailable", true, false);
        Map<String, ?> before = new HashMap<>(prefs.getAll());
        new QueryStateReceiver().onReceive(context, new Intent(RemoteRecording.QUERY).putExtra("request_id", "failed-1"));
        assertReply("error", "storage_unavailable", "/surveillance", -1, "failed-1");
        assertEquals(before, prefs.getAll());
    }

    @Test public void persistedSessionWithoutLiveServiceCannotClaimStartedOrTriggerRecovery() {
        SurveillanceSession session = session();
        session.prepared = true;
        assertTrue(SurveillanceStore.save(context, session, true));
        Map<String, ?> before = new HashMap<>(prefs.getAll());
        new QueryStateReceiver().onReceive(context, new Intent(RemoteRecording.QUERY).putExtra("request_id", "cold-1"));
        assertReply("stopped", "service_not_running", "/surveillance", -1, "cold-1");
        assertEquals(before, prefs.getAll());
    }

    @Test public void corruptDescriptorQueryDoesNotRestoreOrRewriteSettings() {
        assertTrue(SurveillanceStore.save(context, session(), true));
        prefs.edit().putString(SurveillanceStore.KEY_SESSION, "invalid JSON").commit();
        Map<String, ?> before = new HashMap<>(prefs.getAll());
        new QueryStateReceiver().onReceive(context, new Intent(RemoteRecording.QUERY).putExtra("request_id", "corrupt-1"));
        assertReply("stopped", "service_not_running", "", -1, "corrupt-1");
        assertEquals(before, prefs.getAll());
        assertFalse(prefs.getBoolean(DashcamSettingsController.KEY_ENABLED, true));
    }

    @Test public void destroyedServiceCannotReturnItsOldStartedObservation() throws Exception {
        activeFixture();
        field("remoteState").set(live, "started");
        field("destroyed").set(live, true);
        new QueryStateReceiver().onReceive(context, new Intent(RemoteRecording.QUERY).putExtra("request_id", "destroyed-1"));
        assertReply("stopped", "service_not_running", "/surveillance", -1, "destroyed-1");
    }

    @Test public void omittedQueryIdDoesNotReuseTheStartCommandId() throws Exception {
        activeFixture();
        new QueryStateReceiver().onReceive(context, new Intent(RemoteRecording.QUERY));
        assertEquals(RemoteRecording.STATE, context.reply.getAction());
        assertFalse(context.reply.hasExtra("request_id"));
    }

    private SurveillanceSession session() {
        return new SurveillanceSession(SystemClock.elapsedRealtime() + 60_000, SurveillanceStore.bootCount(context),
                true, "stop", "/surveillance", "start-1");
    }

    private SurveillanceSession activeFixture() throws Exception {
        live = new RecordingService();
        SurveillanceSession session = session();
        assertTrue(SurveillanceStore.save(context, session, true));
        field("surveillanceSession").set(live, session);
        field("instance").set(null, live);
        return session;
    }

    private static Field field(String name) throws Exception {
        Field field = RecordingService.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private void assertReply(String state, String reason, String directory, long freeBytes, String requestId) {
        assertNotNull(context.reply);
        assertEquals(RemoteRecording.STATE, context.reply.getAction());
        assertEquals(state, context.reply.getStringExtra("state"));
        assertEquals(reason, context.reply.getStringExtra("reason"));
        assertEquals(directory, context.reply.getStringExtra("directory"));
        assertEquals(freeBytes, context.reply.getLongExtra("free_bytes", Long.MIN_VALUE));
        assertEquals(requestId, context.reply.getStringExtra("request_id"));
    }

    private final class Capture extends ContextWrapper {
        Intent reply;
        Capture(Context target) { super(target); }
        @Override public SharedPreferences getSharedPreferences(String name, int mode) { return prefs; }
        @Override public void sendBroadcast(Intent intent) { reply = intent; }
        @Override public ComponentName startForegroundService(Intent intent) {
            throw new AssertionError("Query must not start a foreground service");
        }
        @Override public ComponentName startService(Intent intent) {
            throw new AssertionError("Query must not start a service");
        }
        @Override public void startActivity(Intent intent) {
            throw new AssertionError("Query must not open an activity");
        }
    }
}
