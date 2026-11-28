package com.drivehub.kamera.birdseye;

import android.content.Context;
import android.content.ContextWrapper;
import android.os.SystemClock;
import android.widget.FrameLayout;
import android.widget.TextView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.drivehub.kamera.R;
import com.drivehub.kamera.settings.UiPrefs;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import static org.junit.Assert.*;

/** Exercise the live view's loader and status overlay, without a vehicle calibration file. */
@RunWith(AndroidJUnit4.class)
public class CalibrationStartupInstrumentedTest {
    private static TextView message(BirdseyeView view) {
        try {
            java.lang.reflect.Field field=BirdseyeView.class.getDeclaredField("message");
            field.setAccessible(true);return (TextView)field.get(view);
        }catch(ReflectiveOperationException error){throw new AssertionError(error);}
    }
    private static void await(ActivityScenario<BirdseyeDebugActivity> scenario,BirdseyeView view,
                              Predicate<BirdseyeView> condition) {
        AtomicBoolean ready=new AtomicBoolean();long deadline=SystemClock.uptimeMillis()+12000;
        do {
            scenario.onActivity(a->ready.set(condition.test(view)));
            if(ready.get())return;
            SystemClock.sleep(25);
        }while(SystemClock.uptimeMillis()<deadline);
        scenario.onActivity(a->fail("Calibration did not recover: "+message(view).getText()));
    }
    private static void withView(BirdseyeView.CalibrationLoader loader,
                                  java.util.function.BiConsumer<ActivityScenario<BirdseyeDebugActivity>,BirdseyeView> test) {
        withView(loader, null, test);
    }
    private static void withView(BirdseyeView.CalibrationLoader loader, BirdseyeRenderer.Frames frames,
                                  java.util.function.BiConsumer<ActivityScenario<BirdseyeDebugActivity>,BirdseyeView> test) {
        try(ActivityScenario<BirdseyeDebugActivity> scenario=ActivityScenario.launch(BirdseyeDebugActivity.class)) {
            BirdseyeView[] view=new BirdseyeView[1];
            scenario.onActivity(a->{
                Context isolated=new ContextWrapper(a){
                    @Override public android.content.SharedPreferences getSharedPreferences(String name,int mode){
                        return a.getSharedPreferences("calibration-startup-test-"+name,mode);
                    }
                };
                UiPrefs.getPrefs(isolated).edit().clear().commit();
                view[0]=new BirdseyeView(isolated,loader,frames);
                ((FrameLayout)a.gl.getParent()).addView(view[0],new FrameLayout.LayoutParams(180,256));
                view[0].resumeDisplay();
            });
            try {test.accept(scenario,view[0]);}
            finally {scenario.onActivity(a->{view[0].pauseDisplay();((FrameLayout)view[0].getParent()).removeView(view[0]);});}
        }
    }
    @Test public void firstPartialCameraImageDismissesWaitingWithoutStatusPoll() {
        BirdseyeSynthetic frames = new BirdseyeSynthetic();
        frames.missingMask = 15;
        withView(() -> frames.calibration, frames, (scenario, view) -> {
            await(scenario, view, v -> v.scene() != null);
            scenario.onActivity(a -> {
                assertEquals(android.view.View.VISIBLE, message(view).getVisibility());
                try {
                    java.lang.reflect.Field tick = BirdseyeView.class.getDeclaredField("tick");
                    java.lang.reflect.Field main = BirdseyeView.class.getDeclaredField("main");
                    tick.setAccessible(true);
                    main.setAccessible(true);
                    ((android.os.Handler) main.get(view)).removeCallbacks((Runnable) tick.get(view));
                } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
                frames.missingMask = 14; // Only the left camera delivers frames.
            });
            await(scenario, view, v -> message(v).getVisibility() == android.view.View.GONE);
            scenario.onActivity(a -> {
                BirdseyeSurface surface = (BirdseyeSurface) view.getChildAt(0);
                assertEquals(1, surface.presentedMask());
                assertEquals(android.view.View.VISIBLE, view.getChildAt(1).getVisibility());
            });
            frames.missingMask = 15;
            await(scenario, view, v -> message(v).getVisibility() == android.view.View.VISIBLE);
        });
    }

    @Test public void missingThenPartiallyWrittenCalibrationLoadsWithoutReopening() throws Exception {
        Path directory=Files.createTempDirectory(InstrumentationRegistry.getInstrumentation()
                .getTargetContext().getCacheDir().toPath(),"calibration-startup-");
        Path file=directory.resolve("calibration.bin");AtomicInteger invalidReads=new AtomicInteger();
        try {
            withView(()->{
                try{return BirdseyeCalibration.load(file);}
                catch(IllegalArgumentException invalid){invalidReads.incrementAndGet();throw invalid;}
            },(scenario,view)->{
                await(scenario,view,v->message(v).getText().equals(v.getContext().getString(R.string.birdseye_calibration_missing)));
                assertNull(view.scene());
                try{Files.write(file,new byte[100]);}catch(java.io.IOException error){throw new AssertionError(error);}
                await(scenario,view,v->invalidReads.get()>0);
                assertNull(view.scene());
                try{Files.write(file,BirdseyeSynthetic.calibrationBytes());}catch(java.io.IOException error){throw new AssertionError(error);}
                await(scenario,view,v->v.scene()!=null);
                scenario.onActivity(a->assertEquals(a.getString(R.string.birdseye_waiting),message(view).getText().toString()));
            });
        }finally{Files.deleteIfExists(file);Files.delete(directory);}
    }
    @Test public void failedPeriodicLoadRestartsWithoutReopening() {
        AtomicInteger reads=new AtomicInteger();
        BirdseyeCalibration calibration=BirdseyeCalibration.parse(BirdseyeSynthetic.calibrationBytes());
        withView(()->{
            if(reads.incrementAndGet()==1)throw new OutOfMemoryError("Injected cold-start allocation failure");
            return calibration;
        },(scenario,view)->{
            await(scenario,view,v->v.scene()!=null);
            assertTrue("The failed periodic job must run again",reads.get()>=2);
            scenario.onActivity(a->assertEquals(a.getString(R.string.birdseye_waiting),message(view).getText().toString()));
        });
    }
}
