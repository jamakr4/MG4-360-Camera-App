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
