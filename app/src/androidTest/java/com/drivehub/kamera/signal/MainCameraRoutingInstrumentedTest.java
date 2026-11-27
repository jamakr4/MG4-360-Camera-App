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
import com.drivehub.kamera.dev.DevRuntimeLog;
import com.drivehub.kamera.side.SideCameraView;
import com.drivehub.kamera.settings.UiPrefs;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

/** Exercises the real vehicle decision, route broadcast and MainActivity receiver without cameras. */
@RunWith(AndroidJUnit4.class)
public class MainCameraRoutingInstrumentedTest {
    @Test public void signalOffRestoresGearCameraAndReturningToMainClearsAnOldSignalView() throws Exception {
        try (Fixture f = new Fixture()) {
            sample(f.service,TurnSignal.OFF,4); assertCamera(f.scenario,CameraIndex.FRONT);
            sample(f.service,TurnSignal.LEFT,4); assertCamera(f.scenario,CameraIndex.LEFT);
            sample(f.service,TurnSignal.OFF,4); assertCamera(f.scenario,CameraIndex.FRONT);
            sample(f.service,TurnSignal.RIGHT,4); assertCamera(f.scenario,CameraIndex.RIGHT);
            sample(f.service,TurnSignal.OFF,4); assertCamera(f.scenario,CameraIndex.FRONT);

            sample(f.service,TurnSignal.LEFT,4); assertCamera(f.scenario,CameraIndex.LEFT);
            sample(f.service,TurnSignal.LEFT,2); assertCamera(f.scenario,CameraIndex.REAR);
            sample(f.service,TurnSignal.OFF,2); assertCamera(f.scenario,CameraIndex.REAR);
            sample(f.service,TurnSignal.RIGHT,2); assertCamera(f.scenario,CameraIndex.REAR);
            sample(f.service,TurnSignal.OFF,4); assertCamera(f.scenario,CameraIndex.FRONT);

            sample(f.service,TurnSignal.RIGHT,4); assertCamera(f.scenario,CameraIndex.RIGHT);
            f.scenario.moveToState(Lifecycle.State.CREATED);
            sample(f.service,TurnSignal.OFF,4);
            f.scenario.moveToState(Lifecycle.State.RESUMED);
            assertCamera(f.scenario,CameraIndex.FRONT);
        }
    }

    @Test public void slowPreviewWorkersAllowRoutingAndClosingBeforeAnyQueuedOpen() throws Exception {
        try (Fixture f = new Fixture()) {
            f.scenario.moveToState(Lifecycle.State.CREATED);
            ExecutorService main = (ExecutorService) field(MainActivity.class, "PREVIEW_IO").get(null);
            ExecutorService sides = (ExecutorService) field(SideCameraView.class, "IO").get(null);
            CountDownLatch release = new CountDownLatch(1);
            Future<Boolean> mainHeld = main.submit(() -> release.await(12, TimeUnit.SECONDS));
            Future<Boolean> sidesHeld = sides.submit(() -> release.await(12, TimeUnit.SECONDS));
            try {
                DevRuntimeLog.add("Test", "begin delayed preview opens");
                f.scenario.moveToState(Lifecycle.State.RESUMED);
                sample(f.service, TurnSignal.LEFT, 4);
                assertCamera(f.scenario, CameraIndex.LEFT);
                Field request = field(MainActivity.class, "previewRequest");
                f.scenario.onActivity(activity -> {
                    try { assertNotNull("An open should be queued", request.get(activity)); }
                    catch (IllegalAccessException error) { throw new AssertionError(error); }
                });
                f.scenario.moveToState(Lifecycle.State.CREATED);
            } finally {
                release.countDown();
            }
            assertTrue("UI waited for main preview IO", mainHeld.get());
            assertTrue("UI waited for side preview IO", sidesHeld.get());
            main.submit(() -> {}).get(3, TimeUnit.SECONDS);
            sides.submit(() -> {}).get(3, TimeUnit.SECONDS);
            String log = DevRuntimeLog.snapshot();
            int marker = log.lastIndexOf("begin delayed preview opens");
            assertTrue(marker >= 0);
            assertFalse(log.substring(marker).contains("[Main preview]"));
            assertFalse(log.substring(marker).contains("[Side preview]"));
        }
    }

    @Test public void aShortIndicationKeepsTheMainCameraUntilTheMinimumOnTimeExpires() throws Exception {
        try (Fixture f = new Fixture()) {
            f.prefs.edit().putInt(UiPrefs.KEY_OVERLAY_MIN_SHOW_MS,800).commit();
            long started = SystemClock.elapsedRealtime();
            sample(f.service,TurnSignal.LEFT,4); assertCamera(f.scenario,CameraIndex.LEFT);
            sample(f.service,TurnSignal.OFF,4);
            for (int i=0;i<4;i++) {
                SystemClock.sleep(50);
                sample(f.service,TurnSignal.OFF,4);
                assertCamera(f.scenario,CameraIndex.LEFT);
            }
            assertCamera(f.scenario,CameraIndex.FRONT);
            assertTrue(SystemClock.elapsedRealtime()-started>=750);
        }
    }

    @Test public void mainCameraAlsoHonoursTheHideDelayAfterTheSignalStops() throws Exception {
        try (Fixture f = new Fixture()) {
            f.prefs.edit().putInt(UiPrefs.KEY_OVERLAY_HIDE_DELAY_MS,600).commit();
            sample(f.service,TurnSignal.RIGHT,4); assertCamera(f.scenario,CameraIndex.RIGHT);
            long stopped = SystemClock.elapsedRealtime();
            sample(f.service,TurnSignal.OFF,4);
            SystemClock.sleep(200);
            assertCamera(f.scenario,CameraIndex.RIGHT);
            assertCamera(f.scenario,CameraIndex.FRONT);
            assertTrue(SystemClock.elapsedRealtime()-stopped>=550);
        }
    }

    @Test public void aLongIndicationDoesNotStartAnotherMinimumOnTimeWhenItStops() throws Exception {
        try (Fixture f = new Fixture()) {
            f.prefs.edit().putInt(UiPrefs.KEY_OVERLAY_MIN_SHOW_MS,700).commit();
            sample(f.service,TurnSignal.LEFT,4); assertCamera(f.scenario,CameraIndex.LEFT);
            SystemClock.sleep(800);
            sample(f.service,TurnSignal.OFF,4);
            assertCamera(f.scenario,CameraIndex.FRONT,400);
        }
    }

    @Test public void changingSideAndReverseCancelThePendingReturnToFront() throws Exception {
        try (Fixture f = new Fixture()) {
            f.prefs.edit().putInt(UiPrefs.KEY_OVERLAY_MIN_SHOW_MS,600).commit();
            sample(f.service,TurnSignal.LEFT,4); assertCamera(f.scenario,CameraIndex.LEFT);
            sample(f.service,TurnSignal.OFF,4);
            sample(f.service,TurnSignal.RIGHT,4); assertCamera(f.scenario,CameraIndex.RIGHT,400);
            SystemClock.sleep(250);
            assertCamera(f.scenario,CameraIndex.RIGHT);
            sample(f.service,TurnSignal.OFF,4);
            sample(f.service,TurnSignal.OFF,2); assertCamera(f.scenario,CameraIndex.REAR,400);
            SystemClock.sleep(700);
            assertCamera(f.scenario,CameraIndex.REAR);
            sample(f.service,TurnSignal.OFF,4); assertCamera(f.scenario,CameraIndex.FRONT);
        }
    }

    @Test public void returningToMainDoesNotReviveASignalCameraFromAPendingDelay() throws Exception {
        try (Fixture f = new Fixture()) {
            f.prefs.edit().putInt(UiPrefs.KEY_OVERLAY_MIN_SHOW_MS,1200).commit();
            sample(f.service,TurnSignal.LEFT,4); assertCamera(f.scenario,CameraIndex.LEFT);
            sample(f.service,TurnSignal.OFF,4);
            f.scenario.moveToState(Lifecycle.State.CREATED);
            f.scenario.moveToState(Lifecycle.State.RESUMED);
            assertCamera(f.scenario,CameraIndex.FRONT);
            SystemClock.sleep(1300);
            assertCamera(f.scenario,CameraIndex.FRONT);
        }
    }

    @Test public void surfaceViewKeepsTheOriginalOrientationAcrossCameraAndPreferenceChanges() throws Exception {
        try (Fixture f = new Fixture()) {
            f.prefs.edit().putBoolean(UiPrefs.KEY_OVERLAY_ROTATE_TO_DRIVING_DIRECTION,true).commit();
            sample(f.service,TurnSignal.LEFT,4); assertCamera(f.scenario,CameraIndex.LEFT);
            assertOrientation(f.scenario,0);
            f.prefs.edit().putBoolean(UiPrefs.KEY_OVERLAY_ROTATE_TO_DRIVING_DIRECTION,false).commit();
            assertOrientation(f.scenario,0);
            f.prefs.edit().putBoolean(UiPrefs.KEY_OVERLAY_ROTATE_TO_DRIVING_DIRECTION,true).commit();
            assertOrientation(f.scenario,0);
            sample(f.service,TurnSignal.RIGHT,4); assertCamera(f.scenario,CameraIndex.RIGHT);
            assertOrientation(f.scenario,0);
            sample(f.service,TurnSignal.OFF,4); assertCamera(f.scenario,CameraIndex.FRONT);
            assertOrientation(f.scenario,0);
            sample(f.service,TurnSignal.LEFT,4); assertCamera(f.scenario,CameraIndex.LEFT);
            sample(f.service,TurnSignal.LEFT,2); assertCamera(f.scenario,CameraIndex.REAR);
            assertOrientation(f.scenario,0);
        }
    }

    @Test public void surfaceViewActuallyDisplaysAllFourUnrotatedImageCorners() throws Exception {
        try (Fixture f = new Fixture()) {
            f.prefs.edit().putBoolean(UiPrefs.KEY_OVERLAY_ROTATE_TO_DRIVING_DIRECTION,true).commit();
            for (TurnSignal signal : new TurnSignal[]{TurnSignal.LEFT,TurnSignal.RIGHT}) {
                sample(f.service,signal,4);
                assertCamera(f.scenario,signal==TurnSignal.LEFT?CameraIndex.LEFT:CameraIndex.RIGHT);
                assertOrientation(f.scenario,0);
                assertRenderedCorners(f.scenario);
            }
        }
    }

    @Test public void swipesKeepTheirScreenDirectionForSideCameras() throws Exception {
        try (Fixture f = new Fixture()) {
            f.prefs.edit().putBoolean(UiPrefs.KEY_OVERLAY_ROTATE_TO_DRIVING_DIRECTION,true).commit();
            sample(f.service,TurnSignal.LEFT,4); assertCamera(f.scenario,CameraIndex.LEFT);
            androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.withId(R.id.mainCameraPanel))
                    .perform(androidx.test.espresso.action.ViewActions.swipeRight());
            assertCamera(f.scenario,CameraIndex.RIGHT);
            androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.withId(R.id.mainCameraPanel))
                    .perform(androidx.test.espresso.action.ViewActions.swipeLeft());
            assertCamera(f.scenario,CameraIndex.LEFT);
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

    private static void assertOrientation(ActivityScenario<MainActivity> scenario, int expected) {
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        scenario.onActivity(activity -> {
            android.view.SurfaceView preview = activity.findViewById(R.id.surfaceView);
            assertTrue(preview.getWidth()>0 && preview.getHeight()>0);
            assertEquals(0,preview.getRotation(),0);
            android.graphics.Matrix transform=preview.getMatrix();
            float[] corners = {0,0,preview.getWidth(),0,preview.getWidth(),preview.getHeight(),0,preview.getHeight()};
            transform.mapPoints(corners);
            assertEquals(expected,Math.toDegrees(Math.atan2(corners[3]-corners[1],corners[2]-corners[0])),.001);
            double scaleX=Math.hypot(corners[2]-corners[0],corners[3]-corners[1])/preview.getWidth();
            double scaleY=Math.hypot(corners[6]-corners[0],corners[7]-corners[1])/preview.getHeight();
            assertEquals(scaleX,scaleY,.001);
            if(expected==0)assertEquals(1,scaleX,.001);
            for (int i=0;i<corners.length;i+=2) {
                assertTrue(corners[i]>=-1 && corners[i]<=preview.getWidth()+1);
                assertTrue(corners[i+1]>=-1 && corners[i+1]<=preview.getHeight()+1);
            }
        });
    }

    private static void assertRenderedCorners(ActivityScenario<MainActivity> scenario) {
        java.util.concurrent.atomic.AtomicReference<float[]> points = new java.util.concurrent.atomic.AtomicReference<>();
        int[] colors = {android.graphics.Color.RED,android.graphics.Color.GREEN,
                android.graphics.Color.BLUE,android.graphics.Color.YELLOW};
        scenario.onActivity(activity -> {
            try {
                org.junit.Assume.assumeFalse("Synthetic frame test requires unavailable vehicle cameras",
                        field(MainActivity.class,"previewRunning").getBoolean(activity));
            } catch (Exception error) { throw new AssertionError(error); }
            android.view.SurfaceView preview = activity.findViewById(R.id.surfaceView);
            android.graphics.Canvas canvas = preview.getHolder().lockCanvas();
            assertNotNull(canvas);
            try {
                android.graphics.Paint paint = new android.graphics.Paint();
                for (int i=0;i<4;i++) {
                    paint.setColor(colors[i]);
                    int x=i%2,y=i/2;
                    canvas.drawRect(x*canvas.getWidth()/2f,y*canvas.getHeight()/2f,
                            (x+1)*canvas.getWidth()/2f,(y+1)*canvas.getHeight()/2f,paint);
                }
            } finally { preview.getHolder().unlockCanvasAndPost(canvas); }
            float[] centers = {preview.getWidth()*.25f,preview.getHeight()*.25f,
                    preview.getWidth()*.75f,preview.getHeight()*.25f,
                    preview.getWidth()*.25f,preview.getHeight()*.75f,
                    preview.getWidth()*.75f,preview.getHeight()*.75f};
            preview.getMatrix().mapPoints(centers);
            int[] parent = new int[2];
            ((android.view.View)preview.getParent()).getLocationOnScreen(parent);
            for (int i=0;i<centers.length;i+=2) {
                centers[i]+=parent[0]+preview.getLeft();
                centers[i+1]+=parent[1]+preview.getTop();
            }
            points.set(centers);
        });
        long deadline=SystemClock.elapsedRealtime()+3000;
        int[] actual=new int[4];
        do {
            android.graphics.Bitmap screenshot=InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();
            assertNotNull(screenshot);
            try {
                float[] centers=points.get();
                for(int i=0;i<4;i++)actual[i]=screenshot.getPixel(Math.round(centers[2*i]),Math.round(centers[2*i+1]));
            } finally { screenshot.recycle(); }
            if(java.util.Arrays.equals(colors,actual))return;
            SystemClock.sleep(100);
        } while(SystemClock.elapsedRealtime()<deadline);
        assertArrayEquals("SurfaceView must display all four synthetic image corners",colors,actual);
    }

    private static final class Fixture implements AutoCloseable {
        final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        final SharedPreferences prefs = UiPrefs.getPrefs(context);
        final Map<String, ?> saved = prefs.getAll();
        final String[] keys = {UiPrefs.KEY_OEM_AVM_COEXIST,UiPrefs.KEY_OEM_AVM_ACTIVE,
                UiPrefs.KEY_OEM_CAMERA_ACCESS_BLOCKED,UiPrefs.KEY_LANE_CHANGES_ONLY,
                UiPrefs.KEY_DIGITAL_REARVIEW_ENABLED,UiPrefs.KEY_OVERLAY_ON_SIGNAL,
                UiPrefs.KEY_OVERLAY_HIDE_DELAY_MS,UiPrefs.KEY_OVERLAY_MIN_SHOW_MS,
                UiPrefs.KEY_OVERLAY_ROTATE_TO_DRIVING_DIRECTION};
        ActivityScenario<MainActivity> scenario;
        SignalService service;
        boolean resumePolling;
        volatile boolean closed;
        final Handler samples = new Handler(android.os.Looper.getMainLooper());
        final Runnable sampler = new Runnable() {
            @Override public void run() {
                if (closed) return;
                try {
                    deliverSample(service,field(SignalService.class,"currentLamp").getInt(service),
                            field(SignalService.class,"currentGear").getInt(service));
                } catch (Exception error) { throw new AssertionError(error); }
                if (!closed) samples.postDelayed(this,100);
            }
        };

        Fixture() throws Exception {
            try {
                assertTrue(prefs.edit().putBoolean(UiPrefs.KEY_OEM_AVM_COEXIST,false)
                        .putBoolean(UiPrefs.KEY_OEM_AVM_ACTIVE,false)
                        .putBoolean(UiPrefs.KEY_OEM_CAMERA_ACCESS_BLOCKED,false)
                        .putBoolean(UiPrefs.KEY_LANE_CHANGES_ONLY,false)
                        .putBoolean(UiPrefs.KEY_DIGITAL_REARVIEW_ENABLED,false)
                        .putBoolean(UiPrefs.KEY_OVERLAY_ON_SIGNAL,false)
                        .putBoolean(UiPrefs.KEY_OVERLAY_ROTATE_TO_DRIVING_DIRECTION,false)
                        .putInt(UiPrefs.KEY_OVERLAY_HIDE_DELAY_MS,0)
                        .putInt(UiPrefs.KEY_OVERLAY_MIN_SHOW_MS,0).commit());
                scenario=ActivityScenario.launch(MainActivity.class);
                long deadline=SystemClock.elapsedRealtime()+4000;
                while(!SignalService.isRunning() && SystemClock.elapsedRealtime()<deadline)SystemClock.sleep(25);
                assertTrue(SignalService.isRunning());
                service=(SignalService)field(SignalService.class,"sInstance").get(null);
                org.junit.Assume.assumeTrue(field(SignalService.class,"lampCallbackProxy").get(service)==null);
                deadline=SystemClock.elapsedRealtime()+4000;
                while(!field(SignalService.class,"polling").getBoolean(service)
                        && SystemClock.elapsedRealtime()<deadline)SystemClock.sleep(25);
                assertTrue("Property sampler must be initialized",field(SignalService.class,"polling").getBoolean(service));
                resumePolling=pausePolling(service);
                InstrumentationRegistry.getInstrumentation().waitForIdleSync();
                sample(service,TurnSignal.OFF,4);
                // Keep sampling the injected state as the real property reader does. OEM ownership
                // can finish changing between samples, including while leaving reverse.
                samples.post(sampler);
                assertCamera(scenario,CameraIndex.FRONT);
            } catch(Exception | AssertionError error) {
                close();throw error;
            }
        }

        @Override public void close() {
            closed=true;
            samples.removeCallbacksAndMessages(null);
            // Clear injected lamp state before restoring settings and leaving the foreground.
            if (service!=null) {
                prefs.edit().putInt(UiPrefs.KEY_OVERLAY_MIN_SHOW_MS,0)
                        .putInt(UiPrefs.KEY_OVERLAY_HIDE_DELAY_MS,0).commit();
                sample(service,TurnSignal.OFF,4);
                SignalService.requestRecheck();
                InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            }
            if(scenario!=null)scenario.close();
            SharedPreferences.Editor editor=prefs.edit();
            for(String key:keys) {
                Object value=saved.get(key);
                if(value instanceof Boolean)editor.putBoolean(key,(Boolean)value);
                else if(value instanceof Integer)editor.putInt(key,(Integer)value);
                else if(value instanceof Long)editor.putLong(key,(Long)value);
                else editor.remove(key);
            }
            assertTrue(editor.commit());
            if(service!=null && resumePolling) {
                InstrumentationRegistry.getInstrumentation().runOnMainSync(()->invoke(service,"startPropertyPollingFallback"));
            }
            if(service!=null)InstrumentationRegistry.getInstrumentation().runOnMainSync(()->invoke(service,"startOemLifecycleMonitor"));
            SignalService.requestRecheck();
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
            deliverSample(service,signal.getValue(),gear);
        });
    }

    private static void deliverSample(SignalService service, int lamp, int gear) {
        try {
            Method sample = SignalService.class.getDeclaredMethod("onVehicleStateSample",int.class,int.class,long.class);
            sample.setAccessible(true);
            sample.invoke(service,lamp,gear,SystemClock.elapsedRealtime());
        } catch (Exception error) { throw new AssertionError(error); }
    }

    private static void assertCamera(ActivityScenario<MainActivity> scenario, CameraIndex expected) throws Exception {
        assertCamera(scenario,expected,2000);
    }

    private static void assertCamera(ActivityScenario<MainActivity> scenario, CameraIndex expected, long timeoutMs) throws Exception {
        Field selected = field(MainActivity.class,"currentVideoIndex");
        AtomicInteger actual = new AtomicInteger(-1);
        long deadline = SystemClock.elapsedRealtime()+timeoutMs;
        do {
            scenario.onActivity(activity -> {
                try { actual.set(selected.getInt(activity)); }
                catch (Exception error) { throw new AssertionError(error); }
            });
            if (actual.get()==expected.getVideoIndex()) return;
            SystemClock.sleep(25);
        } while (SystemClock.elapsedRealtime()<deadline);
        assertEquals("Main camera must follow the current signal/gear; visible="+MainActivity.isMainVisible()
                +"\n"+com.drivehub.kamera.dev.DevRuntimeLog.snapshot(),expected.getVideoIndex(),actual.get());
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
