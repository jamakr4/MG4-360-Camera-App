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
import com.drivehub.kamera.MainActivity;
import com.drivehub.kamera.CameraProbe;
import com.drivehub.kamera.camera.CameraIndex;
import com.drivehub.kamera.dashcam.DashcamSettingsController;
import com.drivehub.kamera.settings.UiPrefs;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.lang.reflect.*;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class ReverseLaunchInstrumentedTest {
    private final Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
    private static void main(Runnable job){InstrumentationRegistry.getInstrumentation().runOnMainSync(job);}
    private static Field field(Class<?> type,String name) throws Exception {Field f=type.getDeclaredField(name);f.setAccessible(true);return f;}
    private static void invoke(SignalService service,String name,Class<?>[] types,Object... args) {
        try{Method m=SignalService.class.getDeclaredMethod(name,types);m.setAccessible(true);m.invoke(service,args);}
        catch(Exception error){throw new AssertionError(error);}
    }
    private static void gear(SignalService service,int gear) {main(()->invoke(service,"onGearStateSample",new Class[]{int.class},gear));}
    private static void await(java.util.function.BooleanSupplier condition) {
        long end=SystemClock.elapsedRealtime()+5000;
        do{if(condition.getAsBoolean())return;SystemClock.sleep(25);}while(SystemClock.elapsedRealtime()<end);
        assertTrue("Reverse launch did not settle",condition.getAsBoolean());
    }
    private static MainActivity resumedMain() {
        AtomicReference<MainActivity> result=new AtomicReference<>();
        main(()->{for(android.app.Activity a:ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED))
            if(a instanceof MainActivity)result.set((MainActivity)a);});return result.get();
    }
    private static void stopSamplers(SignalService service) {
        main(()->{
            invoke(service,"stopOemLifecycleMonitor",new Class[]{});
            try{
                field(SignalService.class,"polling").setBoolean(service,false);
                Handler handler=(Handler)field(SignalService.class,"pollHandler").get(service);
                if(handler!=null)handler.removeCallbacksAndMessages(null);
                HandlerThread thread=(HandlerThread)field(SignalService.class,"pollThread").get(service);
                if(thread!=null)thread.quitSafely();
                field(SignalService.class,"pollThread").set(service,null);
                field(SignalService.class,"pollHandler").set(service,null);
            }catch(Exception error){throw new AssertionError(error);}
        });
    }
    @Test public void exclusiveReverseOpensRearWithoutRecorderAndDoesNotReopenAfterDismissal() throws Exception {
        SharedPreferences prefs=UiPrefs.getPrefs(context);Map<String,?> saved=prefs.getAll();
        String[] keys={DashcamSettingsController.KEY_ENABLED,UiPrefs.KEY_OEM_AVM_COEXIST,UiPrefs.KEY_OEM_AVM_ACTIVE,
                UiPrefs.KEY_OEM_CAMERA_ACCESS_BLOCKED,UiPrefs.KEY_OEM_TAKEOVER_YIELD,UiPrefs.KEY_DIGITAL_REARVIEW_ENABLED,UiPrefs.KEY_OVERLAY_ON_SIGNAL};
        prefs.edit().putBoolean(DashcamSettingsController.KEY_ENABLED,false).putBoolean(UiPrefs.KEY_OEM_AVM_COEXIST,true)
                .putBoolean(UiPrefs.KEY_OEM_AVM_ACTIVE,false).putBoolean(UiPrefs.KEY_OEM_CAMERA_ACCESS_BLOCKED,false)
                .putBoolean(UiPrefs.KEY_DIGITAL_REARVIEW_ENABLED,false).putBoolean(UiPrefs.KEY_OVERLAY_ON_SIGNAL,false).commit();
        SignalService service=null;
        OemTakeoverManager manager=null;
        OemTakeoverController savedController=null;
        Field controllerField=field(OemTakeoverManager.class,"controller");
        try {
            try(ActivityScenario<MainActivity> setup=ActivityScenario.launch(MainActivity.class)) {
                await(SignalService::isRunning);service=(SignalService)field(SignalService.class,"sInstance").get(null);
                org.junit.Assume.assumeTrue(field(SignalService.class,"lampCallbackProxy").get(service)==null);
                InstrumentationRegistry.getInstrumentation().waitForIdleSync();
                assertNotNull("Independent gear monitor must run with coexist and recorder disabled",
                        field(SignalService.class,"oemLifecycleThread").get(service));
                await(() -> SignalService.takeoverSnapshot()!=null);
                manager=(OemTakeoverManager)field(SignalService.class,"takeoverManager").get(service);
                savedController=(OemTakeoverController)controllerField.get(manager);
                // The emulator has no SAIC package or V4L2 cameras. Model a verified takeover;
                // missing-component refusal is exercised separately in OemCameraAccessInstrumentedTest.
                controllerField.set(manager,new OemTakeoverController(new VerifiedPorts()));
                UiPrefs.setOemAvmCoexistEnabled(prefs,false);
                await(() -> SignalService.takeoverSnapshot().state==OemTakeoverController.State.EXCLUSIVE);
                InstrumentationRegistry.getInstrumentation().waitForIdleSync();
                stopSamplers(service);gear(service,4);
            }
            await(()->!MainActivity.isMainVisible());
            gear(service,2);SystemClock.sleep(ReverseLaunchPolicy.SETTLE_MS+25);gear(service,2);
            await(()->resumedMain()!=null);MainActivity launched=resumedMain();
            assertEquals(CameraIndex.REAR.getVideoIndex(),field(MainActivity.class,"currentVideoIndex").getInt(launched));
            assertFalse(CameraProbe.isOemCameraAccessBlocked());
            gear(service,2);SystemClock.sleep(150);assertSame(launched,resumedMain());
            // Leaving R changes the selected camera, but does not auto-close the UI.
            gear(service,4);assertSame(launched,resumedMain());
            gear(service,2);assertSame(launched,resumedMain());
            main(()->launched.findViewById(com.drivehub.kamera.R.id.btnClose).performClick());await(()->!MainActivity.isMainVisible());
            SystemClock.sleep(150);gear(service,2);assertFalse(MainActivity.isMainVisible());
            SignalService original=service;main(()->invoke(original,"updateReverseLaunch",new Class[]{boolean.class},true));
            SystemClock.sleep(150);gear(service,2);assertFalse("An explicit close must beat a delayed OEM event",MainActivity.isMainVisible());
            // A fresh R edge rearms the one-shot open.
            gear(service,4);gear(service,2);SystemClock.sleep(ReverseLaunchPolicy.SETTLE_MS+25);gear(service,2);
            await(()->resumedMain()!=null);MainActivity again=resumedMain();main(again::finishAndRemoveTask);
            await(()->!MainActivity.isMainVisible());gear(service,4);
            prefs.edit().putBoolean(UiPrefs.KEY_OEM_AVM_COEXIST,true).commit();
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();stopSamplers(service);
            gear(service,2);SystemClock.sleep(150);gear(service,2);
            assertFalse("Coexist keeps the OEM in control",MainActivity.isMainVisible());
            assertTrue(CameraProbe.isOemCameraAccessBlocked());
        } finally {
            MainActivity visible=resumedMain();if(visible!=null)main(visible::finishAndRemoveTask);
            prefs.edit().putBoolean(UiPrefs.KEY_OEM_AVM_COEXIST,true).commit();
            if(service!=null)gear(service,0);
            if(manager!=null&&savedController!=null) {
                await(() -> SignalService.takeoverSnapshot().state==OemTakeoverController.State.OEM);
                controllerField.set(manager,savedController);
            }
            SharedPreferences.Editor edit=prefs.edit();
            for(String key:keys){if(saved.containsKey(key))edit.putBoolean(key,(Boolean)saved.get(key));else edit.remove(key);}edit.commit();
            if(service!=null){SignalService original=service;main(()->{
                invoke(original,"startPropertyPollingFallback",new Class[]{});
                invoke(original,"startOemLifecycleMonitor",new Class[]{});
            });}
            SignalService.refreshCameraAccess(context);
        }
    }
    private final class VerifiedPorts implements OemTakeoverController.Ports {
        private OemTakeoverController.Journal journal=new OemTakeoverController.Journal();
        private int receiver;
        public OemTakeoverController.Journal load(){return journal.copy();}
        public void save(OemTakeoverController.Journal j){journal=j.copy();}
        public int receiverState(){return receiver;}
        public void receiverState(int state){receiver=state;}
        public void startDataService(){}
        public boolean dataServiceRunning(){return true;}
        public void yieldCameras(boolean yield){SignalService.setTakeoverYield(context,yield);}
        public void releaseCameras(){CameraProbe.setOemCameraAccessBlocked(true);}
        public void acquireCameras(){}
        public int heldMask(){return 15;}
        public long rearFrameAgeMs(){return 0;}
        public void closeOem(){}
        public void openOem(){}
        public void log(String message){}
    }

    /** Drives real lifecycle/task APIs with fresh synthetic gear on the emulator. */
    private final class ForegroundHarness implements AutoCloseable {
        final SharedPreferences prefs=UiPrefs.getPrefs(context);
        final Map<String,?> saved=prefs.getAll();
        final String[] keys={UiPrefs.KEY_PARKING_REVERSE_FOREGROUND,UiPrefs.KEY_OEM_AVM_COEXIST,UiPrefs.KEY_OEM_AVM_ACTIVE,
                UiPrefs.KEY_OEM_CAMERA_ACCESS_BLOCKED,UiPrefs.KEY_OEM_TAKEOVER_YIELD,DashcamSettingsController.KEY_ENABLED,
                UiPrefs.KEY_DIGITAL_REARVIEW_ENABLED,UiPrefs.KEY_OVERLAY_ON_SIGNAL};
        final Handler handler=new Handler(android.os.Looper.getMainLooper());
        final HandlerThread worker=new HandlerThread("Reverse-foreground-test");
        final java.util.List<MainActivity> cameras=new java.util.ArrayList<>();
        MainActivity setupCamera;
        SignalService service;OemTakeoverManager manager;OemTakeoverController previous;
        volatile int value=4;
        final Runnable pump=new Runnable(){public void run(){
            try {
                field(SignalService.class,"guidanceGear").set(service,com.drivehub.kamera.steering.GearSnapshot.value(value,SystemClock.elapsedRealtime()));
                invoke(service,"onGearStateSample",new Class[]{int.class},value);
                ReverseForegroundController c=(ReverseForegroundController)field(SignalService.class,"reverseForeground").get(service);
                Handler io=(Handler)field(SignalService.class,"oemLifecycleHandler").get(service);
                if(c!=null)io.post(c::sampleOnWorker);
                handler.postDelayed(this,75);
            }catch(Exception e){throw new AssertionError(e);}
        }};
        ForegroundHarness() throws Exception {
            // API 33 emulator uses an ordinary app UID; the actual MG4 runs our platform-signed system UID.
            InstrumentationRegistry.getInstrumentation().getUiAutomation().adoptShellPermissionIdentity();
            prefs.edit().putBoolean(UiPrefs.KEY_PARKING_REVERSE_FOREGROUND,true).putBoolean(UiPrefs.KEY_OEM_AVM_COEXIST,true)
                    .putBoolean(UiPrefs.KEY_OEM_AVM_ACTIVE,false).putBoolean(UiPrefs.KEY_OEM_CAMERA_ACCESS_BLOCKED,false)
                    .putBoolean(DashcamSettingsController.KEY_ENABLED,false).putBoolean(UiPrefs.KEY_DIGITAL_REARVIEW_ENABLED,false)
                    .putBoolean(UiPrefs.KEY_OVERLAY_ON_SIGNAL,false).commit();
            context.startActivity(MainActivity.showCameraIntent(context,CameraIndex.FRONT.getVideoIndex()));
            await(()->resumedMain()!=null);setupCamera=resumedMain();await(SignalService::isRunning);
            service=(SignalService)field(SignalService.class,"sInstance").get(null);
            await(()->SignalService.takeoverSnapshot()!=null);
            manager=(OemTakeoverManager)field(SignalService.class,"takeoverManager").get(service);
            previous=(OemTakeoverController)field(OemTakeoverManager.class,"controller").get(manager);
            field(OemTakeoverManager.class,"controller").set(manager,new OemTakeoverController(new VerifiedPorts()));
            UiPrefs.setOemAvmCoexistEnabled(prefs,false);
            await(()->SignalService.takeoverSnapshot().state==OemTakeoverController.State.EXCLUSIVE);
            stopSamplers(service);worker.start();
            field(SignalService.class,"oemLifecycleHandler").set(service,new Handler(worker.getLooper()));
            main(pump);remember();
        }
        MainActivity remember(){MainActivity a=resumedMain();assertNotNull(a);if(!cameras.contains(a))cameras.add(a);return a;}
        void reverse(){value=2;await(()->SignalService.reverseForegroundDiagnostic().contains("armed"));}
        void interrupt(Class<?> type) {
            Context fixtures=InstrumentationRegistry.getInstrumentation().getContext();
            fixtures.startActivity(new Intent(fixtures,type).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_MULTIPLE_TASK));
            await(()->resumedMain()==null);
        }
        void returned(MainActivity original) {
            await(()->resumedMain()==original);
            assertEquals(original.getTaskId(),remember().getTaskId());
            SystemClock.sleep(2200); // Confirmation resets the failure budget, not each onResume.
        }
        @Override public void close() throws Exception {
            main(()->handler.removeCallbacks(pump));
            prefs.edit().putBoolean(UiPrefs.KEY_PARKING_REVERSE_FOREGROUND,false).commit();
            for(MainActivity a:cameras)main(()->{if(!a.isDestroyed())a.finishAndRemoveTask();});
            await(()->resumedMain()==null);
            main(()->invoke(service,"stopOemLifecycleMonitor",new Class[]{}));worker.quitSafely();worker.join(3000);
            prefs.edit().putBoolean(UiPrefs.KEY_OEM_AVM_COEXIST,true).commit();
            await(()->SignalService.takeoverSnapshot().state==OemTakeoverController.State.OEM);
            field(OemTakeoverManager.class,"controller").set(manager,previous);
            SharedPreferences.Editor edit=prefs.edit();
            for(String key:keys){Object v=saved.get(key);if(v instanceof Boolean)edit.putBoolean(key,(Boolean)v);else edit.remove(key);}edit.commit();
            main(()->{invoke(service,"startPropertyPollingFallback",new Class[]{});invoke(service,"startOemLifecycleMonitor",new Class[]{});});
            SignalService.refreshCameraAccess(context);
            InstrumentationRegistry.getInstrumentation().getUiAutomation().dropShellPermissionIdentity();
        }
    }

    @Test public void reverseForegroundRestoresSameManualTaskForAaCarPlayAndOtherApps() throws Exception {
        try(ForegroundHarness h=new ForegroundHarness()) {
            h.reverse();MainActivity original=h.remember();
            assertNull(field(MainActivity.class,"parkingSession").get(original));
            for(Class<?> type:new Class[]{AndroidAutoTakeoverActivity.class,CarPlayTakeoverActivity.class,OtherAppTakeoverActivity.class}) {
                h.interrupt(type);h.returned(original);
                assertNull("Manual views stay manual",field(MainActivity.class,"parkingSession").get(original));
            }
        }
    }
    @Test public void automaticReverseSessionSurvivesProjectionAndGearChangeCancelsReturn() throws Exception {
        try(ForegroundHarness h=new ForegroundHarness()) {
            main(h.setupCamera::finishAndRemoveTask);await(()->!MainActivity.isMainVisible());
            h.value=2;await(()->resumedMain()!=null);MainActivity original=h.remember();
            Object session=field(MainActivity.class,"parkingSession").get(original);assertNotNull(session);
            h.interrupt(CarPlayTakeoverActivity.class);h.returned(original);
            assertEquals(session,field(MainActivity.class,"parkingSession").get(original));
            h.interrupt(AndroidAutoTakeoverActivity.class);h.value=4;SystemClock.sleep(1500);
            assertNull("A stale R request must not reclaim the view in D",resumedMain());
        }
    }
    @Test public void reverseForegroundHonoursHomeAndCanBeDisabled() throws Exception {
        try(ForegroundHarness h=new ForegroundHarness()) {
            h.reverse();MainActivity original=h.remember();
            try(android.os.ParcelFileDescriptor result=InstrumentationRegistry.getInstrumentation().getUiAutomation().executeShellCommand("input keyevent 3")) {
                try(java.io.InputStream in=new android.os.ParcelFileDescriptor.AutoCloseInputStream(result)){while(in.read()!=-1){}}
            }
            await(()->resumedMain()==null);SystemClock.sleep(1500);assertNull(resumedMain());
            assertTrue(SignalService.reverseForegroundDiagnostic(),SignalService.reverseForegroundDiagnostic().contains("Home"));
            context.startActivity(MainActivity.showCameraIntent(context,CameraIndex.REAR.getVideoIndex()));
            await(()->resumedMain()==original);
            h.prefs.edit().putBoolean(UiPrefs.KEY_PARKING_REVERSE_FOREGROUND,false).commit();
            h.interrupt(AndroidAutoTakeoverActivity.class);SystemClock.sleep(1500);assertNull(resumedMain());
        }
    }

    @Test public void lifecycleMonitorCanStopAndRestartWhileItsWorkerIsSampling() throws Exception {
        try(ActivityScenario<MainActivity> setup=ActivityScenario.launch(MainActivity.class)) {
            await(SignalService::isRunning);SignalService service=(SignalService)field(SignalService.class,"sInstance").get(null);
            for(int cycle=0;cycle<30;cycle++) {
                main(()->{invoke(service,"stopOemLifecycleMonitor",new Class[]{});invoke(service,"startOemLifecycleMonitor",new Class[]{});});
                SystemClock.sleep(3);
            }
            assertTrue(field(SignalService.class,"oemLifecycleMonitoring").getBoolean(service));
            assertTrue(((HandlerThread)field(SignalService.class,"oemLifecycleThread").get(service)).isAlive());
        }
    }
    @Test public void oemCloseUsesOnlyTheVerifiedPackageScopedUiContract() {
        Intent intent=OemAvmControl.closeCameraIntent();
        assertEquals("com.android.systemui.ACTION_HARD_KEY_APP_CLOSE",intent.getAction());
        assertEquals("com.saicmotor.hmi.aroundview",intent.getPackage());
        assertEquals(intent.getPackage(),intent.getStringExtra("PACKAGE_NAME"));
        assertNull(intent.getComponent());assertEquals(0,intent.getFlags());
    }
}
