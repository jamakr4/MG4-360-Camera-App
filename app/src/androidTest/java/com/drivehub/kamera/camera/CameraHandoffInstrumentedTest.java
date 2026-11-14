package com.drivehub.kamera.camera;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.SystemClock;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.drivehub.kamera.CameraProbe;
import com.drivehub.kamera.dashcam.DashcamSettingsController;
import com.drivehub.kamera.settings.UiPrefs;
import com.drivehub.kamera.signal.SignalService;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class CameraHandoffInstrumentedTest {
    private Context context;
    private SharedPreferences prefs;
    private Map<String, ?> original;
    @Before public void prepare() {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        prefs = UiPrefs.getPrefs(context);
        original = new HashMap<>(prefs.getAll());
        assertTrue(prefs.edit().putBoolean(DashcamSettingsController.KEY_ENABLED, false)
                .putBoolean(UiPrefs.KEY_DIGITAL_REARVIEW_ENABLED, false)
                .putBoolean(UiPrefs.KEY_OEM_AVM_COEXIST, false)
                .putBoolean(UiPrefs.KEY_OEM_AVM_ACTIVE, false)
                .putBoolean(UiPrefs.KEY_OEM_CAMERA_ACCESS_BLOCKED, false).commit());
    }
    @After public void restore() throws Exception {
        request(CameraHandoffController.ACTION_RESUME);
        SharedPreferences.Editor edit = prefs.edit();
        for (String key : new String[]{DashcamSettingsController.KEY_ENABLED,
                UiPrefs.KEY_DIGITAL_REARVIEW_ENABLED, UiPrefs.KEY_OEM_AVM_COEXIST,
                UiPrefs.KEY_OEM_AVM_ACTIVE, UiPrefs.KEY_OEM_CAMERA_ACCESS_BLOCKED}) {
            if (original.containsKey(key)) edit.putBoolean(key, (Boolean) original.get(key));
            else edit.remove(key);
        }
        assertTrue(edit.commit());
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> SignalService.refreshCameraAccess(context));
    }
    @Test public void manifestReleaseAndResumeReturnActualOwnershipWithCoexistenceOff() throws Exception {
        Bundle release = request(CameraHandoffController.ACTION_RELEASE);
        assertEquals("released", release.getString("status"));
        assertTrue(release.getBoolean("success"));
        assertTrue(release.getBoolean("blocked"));
        assertFalse(release.getBoolean("claimed"));
        assertTrue(UiPrefs.isOemCameraAccessBlocked(prefs));
        assertTrue(CameraProbe.probeAll(20).contains("blocked"));
        CameraProbe.setOemCameraAccessBlocked(false);
        assertTrue("OEM release cannot override external pause", CameraProbe.isOemCameraAccessBlocked());
        Bundle resume = request(CameraHandoffController.ACTION_RESUME);
        assertEquals("idle", resume.getString("status"));
        assertFalse(resume.getBoolean("blocked"));
        assertFalse(resume.getBoolean("claimed"));
        assertFalse(prefs.getBoolean(DashcamSettingsController.KEY_ENABLED, true));
    }
    @Test public void duplicateBroadcastsAreIdempotentAndNormalBroadcastAlsoWorks() throws Exception {
        context.sendBroadcast(new Intent(CameraHandoffController.ACTION_RELEASE).setPackage(context.getPackageName()));
        long end = SystemClock.elapsedRealtime() + 5000;
        while (!prefs.getBoolean(UiPrefs.KEY_EXTERNAL_CAMERA_PAUSED, false) && SystemClock.elapsedRealtime() < end)
            SystemClock.sleep(25);
        assertTrue(prefs.getBoolean(UiPrefs.KEY_EXTERNAL_CAMERA_PAUSED, false));
        assertEquals("released", request(CameraHandoffController.ACTION_RELEASE).getString("status"));
        assertEquals("idle", request(CameraHandoffController.ACTION_RESUME).getString("status"));
        assertEquals("idle", request(CameraHandoffController.ACTION_RESUME).getString("status"));
    }
    @Test public void openMainAndSideViewsCannotReportIdleBeforeTheirStartsComplete() throws Exception {
        try (androidx.test.core.app.ActivityScenario<com.drivehub.kamera.MainActivity> activity =
                     androidx.test.core.app.ActivityScenario.launch(com.drivehub.kamera.MainActivity.class)) {
            assertEquals("released", request(CameraHandoffController.ACTION_RELEASE).getString("status"));
            Bundle result = requestAny(CameraHandoffController.ACTION_RESUME);
            assertNotEquals("Active views must not become idle", "idle", result.getString("status"));
            if (result.getBoolean("success")) {
                assertEquals("claimed", result.getString("status"));
                assertTrue(result.getBoolean("claimed"));
            }
        }
    }
    @Test public void recordingStartRejectedByHandoffIsNotReportedAsCameraError() throws Exception {
        String storageKey = com.drivehub.kamera.dashcam.DashcamStorageManager.KEY_STORAGE_TARGET;
        Object storage = prefs.getAll().get(storageKey);
        Object path = prefs.getAll().get("recordsPath");
        try {
            prefs.edit().putInt(storageKey, com.drivehub.kamera.dashcam.DashcamStorageManager.TARGET_INTERNAL_ONLY)
                    .putString("recordsPath", new java.io.File(context.getCacheDir(), "handoff-record-test").getPath())
                    .putString("recordingStatus", "before-blocked-start").commit();
            request(CameraHandoffController.ACTION_RELEASE);
            com.drivehub.kamera.dashcam.RecordingService.startTestClip(context, 0);
            long end = SystemClock.elapsedRealtime() + 4000;
            while (!com.drivehub.kamera.dashcam.RecordingService.STATUS_OFF.equals(
                    com.drivehub.kamera.dashcam.RecordingService.readPersistedStatus(prefs).status)
                    && SystemClock.elapsedRealtime() < end) SystemClock.sleep(25);
            com.drivehub.kamera.dashcam.RecordingService.PersistedStatus status =
                    com.drivehub.kamera.dashcam.RecordingService.readPersistedStatus(prefs);
            assertEquals(com.drivehub.kamera.dashcam.RecordingService.STATUS_OFF, status.status);
            assertEquals("", status.lastError);
            assertTrue(CameraProbe.isOemCameraAccessBlocked());
        } finally {
            SharedPreferences.Editor edit = prefs.edit();
            if (storage == null) edit.remove(storageKey); else edit.putInt(storageKey, (Integer) storage);
            if (path == null) edit.remove("recordsPath"); else edit.putString("recordsPath", (String) path);
            edit.commit();
        }
    }
    @Test public void timeoutReturnsExactlyOneMeasuredFailureWithinFiveSeconds() throws Exception {
        CameraHandoffController.demand("test-unavailable-output", true);
        try {
            long started = SystemClock.elapsedRealtime();
            Bundle result = request(CameraHandoffController.ACTION_RESUME, false);
            assertTrue(SystemClock.elapsedRealtime() - started < 5500);
            assertEquals("timeout", result.getString("status"));
            assertFalse(result.getBoolean("claimed"));
            assertFalse(result.getBoolean("blocked"));
        } finally { CameraHandoffController.demand("test-unavailable-output", false); }
    }
    @Test public void oemStopCannotUndoExternalPauseAndExternalResumeCannotUndoOem() throws Exception {
        prefs.edit().putBoolean(UiPrefs.KEY_OEM_AVM_COEXIST, true).commit();
        request(CameraHandoffController.ACTION_RELEASE);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> SignalService.setOemAvmActive(context, false));
        assertTrue(CameraProbe.isOemCameraAccessBlocked());
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> SignalService.setOemAvmActive(context, true));
        Bundle result = request(CameraHandoffController.ACTION_RESUME, false);
        assertEquals("blocked", result.getString("status"));
        assertTrue(result.getBoolean("blocked"));
        assertFalse(result.getBoolean("claimed"));
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> SignalService.setOemAvmActive(context, false));
        prefs.edit().putBoolean(UiPrefs.KEY_OEM_AVM_COEXIST, false).commit();
    }
    private Bundle request(String action) throws Exception { return request(action, true); }
    private Bundle request(String action, boolean success) throws Exception {
        Bundle result = requestAny(action);
        assertEquals(success ? Activity.RESULT_OK : Activity.RESULT_CANCELED, result.getInt("result_code"));
        assertEquals(success, result.getBoolean("success"));
        return result;
    }
    private Bundle requestAny(String action) throws Exception {
        CountDownLatch completed = new CountDownLatch(1);
        AtomicReference<Bundle> result = new AtomicReference<>();
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        context.sendOrderedBroadcast(new Intent(action).setPackage(context.getPackageName()), null,
                new BroadcastReceiver() {
                    @Override public void onReceive(Context c, Intent i) {
                        calls.incrementAndGet();
                        Bundle extras = getResultExtras(false);
                        if (extras != null) extras.putInt("result_code", getResultCode());
                        result.set(extras);
                        completed.countDown();
                    }
                }, null, Activity.RESULT_CANCELED, null, null);
        assertTrue("Manifest receiver did not reply", completed.await(6, TimeUnit.SECONDS));
        assertNotNull(result.get());
        SystemClock.sleep(50);
        assertEquals(1, calls.get());
        return result.get();
    }
}
