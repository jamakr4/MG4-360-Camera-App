package com.drivehub.kamera.birdseye;

import android.os.SystemClock;
import android.view.Gravity;
import android.widget.FrameLayout;
import androidx.lifecycle.Lifecycle;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.Assert.*;

/** The production TextureView/EGL worker, in addition to the pixel-level pbuffer tests. */
@RunWith(AndroidJUnit4.class)
public class BirdseyeSurfaceInstrumentedTest {
    private void waitFor(ActivityScenario<BirdseyeDebugActivity> scenario,
                         java.util.function.Predicate<BirdseyeDebugActivity> condition) {waitFor(scenario,condition,5000);}
    private void waitFor(ActivityScenario<BirdseyeDebugActivity> scenario,
                         java.util.function.Predicate<BirdseyeDebugActivity> condition,long timeoutMs) {
        AtomicBoolean ready=new AtomicBoolean();
        java.util.concurrent.atomic.AtomicReference<String> status=new java.util.concurrent.atomic.AtomicReference<>();
        long deadline=SystemClock.uptimeMillis()+timeoutMs;
        do {
            scenario.onActivity(a->{ready.set(condition.test(a));status.set("requested="+a.renderer.threeDRequested
                    +" active="+a.renderer.threeDActive+" depth="+a.renderer.depthAvailable+" error="+a.renderer.error
                    +" 3dError="+a.renderer.threeDError+" prepare="+a.preparationError);});
            if(ready.get())return;
            SystemClock.sleep(25);
        } while(SystemClock.uptimeMillis()<deadline);
        fail("Window renderer did not reach expected state: "+status.get());
    }
    private long workers() {
        return Thread.getAllStackTraces().keySet().stream()
                .filter(t->t.isAlive() && t.getName().equals("Birdseye-EGL")).count();
    }
    @Test public void bothModesFollowDisplayCallbacksAndPauseWithoutPendingFrames() {
        android.content.SharedPreferences prefs=com.drivehub.kamera.settings.UiPrefs.getPrefs(
                androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().getTargetContext());
        boolean saved=prefs.getBoolean(com.drivehub.kamera.settings.UiPrefs.KEY_PARKING_SURROUND_3D,false);
        try(ActivityScenario<BirdseyeDebugActivity> scenario=ActivityScenario.launch(BirdseyeDebugActivity.class)) {
            waitFor(scenario,a->a.renderer.freshMask==15);
            for(boolean threeD:new boolean[]{false,true}) {
                scenario.onActivity(a->prefs.edit().putBoolean(com.drivehub.kamera.settings.UiPrefs.KEY_PARKING_SURROUND_3D,threeD).apply());
                // Allow cold model/shader preparation in the full software-GPU suite.
                waitFor(scenario,a->{assertNull(a.preparationError);assertNull(a.renderer.threeDError);
                    assertNull(a.renderer.error);return a.renderer.threeDActive==threeD;},20000);
                long[] before=new long[3];
                scenario.onActivity(a->{before[0]=a.gl.displayCallbacks();before[1]=a.renderer.renderedFrames;before[2]=SystemClock.uptimeMillis();});
                SystemClock.sleep(2000);
                scenario.onActivity(a->{
                    long callbacks=a.gl.displayCallbacks()-before[0],draws=a.renderer.renderedFrames-before[1];
                    double seconds=(SystemClock.uptimeMillis()-before[2])/1000.0;
                    android.util.Log.i("BirdseyeCadence",(threeD?"3D":"2D")+" callbacks/s="+callbacks/seconds+" draws/s="+draws/seconds);
                    String timing=BirdseyeSurface.presentationStatus();
                    android.util.Log.i("BirdseyeCadence",timing);
                    assertTrue("Successful swaps must report output waits separately from CPU submission",timing.contains("EGL:")&&timing.contains("swap wait")&&timing.contains("busy skips"));
                    assertNull(a.renderer.error);assertTrue("Display callbacks must progress independently of 50 ms state polling",callbacks>0);
                    assertTrue("Frames must progress without a UI requestRender timer",draws>2);
                    assertTrue("No more than one job per display callback",draws<=callbacks+1);
                    a.gl.setContinuousRendering(false);
                });
                SystemClock.sleep(150); // Drain the single in-flight render.
                long[] idle=new long[2];scenario.onActivity(a->{idle[0]=a.gl.displayCallbacks();idle[1]=a.renderer.renderedFrames;});
                SystemClock.sleep(200);
                scenario.onActivity(a->{assertEquals(idle[0],a.gl.displayCallbacks());assertEquals(idle[1],a.renderer.renderedFrames);a.gl.setContinuousRendering(true);});
            }
            scenario.moveToState(Lifecycle.State.CREATED);
            SystemClock.sleep(200);
            scenario.moveToState(Lifecycle.State.RESUMED);
            waitFor(scenario,a->a.renderer.freshMask==15);
        }finally{prefs.edit().putBoolean(com.drivehub.kamera.settings.UiPrefs.KEY_PARKING_SURROUND_3D,saved).commit();}
    }
    @Test public void windowEglResumesAndOneFailedOutputLeavesAnotherWorkerHealthy() {
        long initialWorkers=workers();
        try(ActivityScenario<BirdseyeDebugActivity> scenario=ActivityScenario.launch(BirdseyeDebugActivity.class)) {
            waitFor(scenario,a->a.renderer.freshMask==15);
            for(int i=0;i<3;i++) {
                scenario.moveToState(Lifecycle.State.CREATED);
                scenario.moveToState(Lifecycle.State.RESUMED);
                waitFor(scenario,a->a.renderer.freshMask==15);
            }
            BirdseyeSurface[] other=new BirdseyeSurface[1];
            BirdseyeRenderer[] healthy=new BirdseyeRenderer[1];
            java.util.concurrent.atomic.AtomicInteger updates=new java.util.concurrent.atomic.AtomicInteger();
            AtomicBoolean updateOffUi=new AtomicBoolean();
            try {
                scenario.onActivity(a->{
                    BirdseyeSynthetic frames=new BirdseyeSynthetic();
                    healthy[0]=new BirdseyeRenderer(frames);healthy[0].setMesh(new BirdseyeMesh(frames.calibration));
                    other[0]=new BirdseyeSurface(a,healthy[0],null);
                    assertTrue(android.view.TextureView.class.isInstance(other[0]));
                    other[0].setBeforeFrame(()->{
                        updates.incrementAndGet();
                        if(android.os.Looper.myLooper()!=android.os.Looper.getMainLooper())updateOffUi.set(true);
                    });
                    ((FrameLayout)a.gl.getParent()).addView(other[0],new FrameLayout.LayoutParams(180,256,Gravity.END));
                    other[0].onResume();
                });
                // Explicit requests also work while the continuous display loop is active.
                waitFor(scenario,a->{other[0].requestRender();return healthy[0].freshMask==15;});
                long before=healthy[0].renderedFrames;
                scenario.onActivity(a->a.frames.throwOnCopy=true);
                waitFor(scenario,a->a.renderer.error!=null);
                waitFor(scenario,a->{other[0].requestRender();return healthy[0].renderedFrames>before+2;});
                assertNull(healthy[0].error);assertEquals(15,healthy[0].freshMask);
                assertTrue("Display frames must update the live pose",updates.get()>2);
                assertFalse("Steering state belongs to the UI thread",updateOffUi.get());
            } finally {
                scenario.onActivity(a->{if(other[0]!=null){other[0].onPause();((FrameLayout)other[0].getParent()).removeView(other[0]);}});
            }
            scenario.recreate();waitFor(scenario,a->a.renderer.freshMask==15);
        }
        long deadline=SystemClock.uptimeMillis()+5000;
        while(workers()>initialWorkers && SystemClock.uptimeMillis()<deadline)SystemClock.sleep(25);
        assertTrue("EGL workers must terminate after window/Activity close",workers()<=initialWorkers);
    }
}
