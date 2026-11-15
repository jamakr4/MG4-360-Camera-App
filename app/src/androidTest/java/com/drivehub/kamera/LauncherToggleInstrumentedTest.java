package com.drivehub.kamera;

import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import androidx.lifecycle.Lifecycle;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.drivehub.kamera.camera.CameraIndex;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class LauncherToggleInstrumentedTest {
    private final Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
    private Intent launcher() {
        return new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                .setClass(context,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }
    private void await(java.util.function.BooleanSupplier ready) {
        long deadline=SystemClock.elapsedRealtime()+5000;
        do {if(ready.getAsBoolean())return;SystemClock.sleep(25);}while(SystemClock.elapsedRealtime()<deadline);
        assertTrue("Launcher lifecycle did not settle",ready.getAsBoolean());
    }
    @Test public void repeatedForegroundLauncherClosesAndNextColdLaunchOpens() {
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(launcher())) {
            assertTrue(MainActivity.isMainVisible());
            context.startActivity(launcher());
            await(()->scenario.getState()==Lifecycle.State.DESTROYED);
            assertFalse(MainActivity.isMainVisible());
        }
        try(ActivityScenario<MainActivity> reopened=ActivityScenario.launch(launcher())) {
            assertEquals(Lifecycle.State.RESUMED,reopened.getState());assertTrue(MainActivity.isMainVisible());
        }
    }
    @Test public void backgroundLauncherOpensAndPackageScopedHardKeyCloses() {
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(launcher())) {
            scenario.moveToState(Lifecycle.State.CREATED);assertFalse(MainActivity.isMainVisible());
            context.startActivity(launcher());
            await(()->scenario.getState()==Lifecycle.State.RESUMED);
            assertTrue(MainActivity.isMainVisible());
            context.sendBroadcast(new Intent(MainActivity.ACTION_HARD_KEY_CLOSE).setPackage(context.getPackageName())
                    .putExtra(MainActivity.EXTRA_CLOSE_PACKAGE,"another.package"));
            SystemClock.sleep(150);assertEquals(Lifecycle.State.RESUMED,scenario.getState());
            context.sendBroadcast(new Intent(MainActivity.ACTION_HARD_KEY_CLOSE).setPackage(context.getPackageName())
                    .putExtra(MainActivity.EXTRA_CLOSE_PACKAGE,context.getPackageName()));
            await(()->scenario.getState()==Lifecycle.State.DESTROYED);
        }
    }
    @Test public void internalCameraShowOnlyOpensOrRoutesAnAlreadyVisibleActivity() {
        Intent show=MainActivity.showCameraIntent(context,CameraIndex.REAR.getVideoIndex());
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(show)) {
            context.startActivity(MainActivity.showCameraIntent(context,CameraIndex.FRONT.getVideoIndex()));
            await(()->scenario.getState()==Lifecycle.State.RESUMED);
            scenario.onActivity(a->assertFalse(a.isFinishing()));
        }
    }

}
