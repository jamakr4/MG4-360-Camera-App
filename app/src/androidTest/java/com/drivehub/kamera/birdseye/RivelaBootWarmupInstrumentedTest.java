package com.drivehub.kamera.birdseye;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Looper;
import android.os.SystemClock;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.drivehub.kamera.boot.BootReceiver;
import com.drivehub.kamera.dashcam.DashcamSettingsController;
import com.drivehub.kamera.signal.SignalService;
import com.drivehub.kamera.settings.UiPrefs;
import org.junit.*;
import org.junit.runner.RunWith;
import org.junit.runners.MethodSorters;
import java.io.IOException;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class) @FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class RivelaBootWarmupInstrumentedTest {
    @Test public void aBootReceiverStartsServiceAndSchedulesOneBackgroundWarmup() throws Exception {
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        SharedPreferences prefs=UiPrefs.getPrefs(context);boolean had=prefs.contains(DashcamSettingsController.KEY_ENABLED);
        boolean enabled=prefs.getBoolean(DashcamSettingsController.KEY_ENABLED,false),running=SignalService.isRunning();
        prefs.edit().putBoolean(DashcamSettingsController.KEY_ENABLED,false).commit();
        try {
            InstrumentationRegistry.getInstrumentation().runOnMainSync(()->new BootReceiver().onReceive(context,new Intent(Intent.ACTION_BOOT_COMPLETED)));
            long until=SystemClock.elapsedRealtime()+10000;
            while(RivelaAssets.scheduledWarmup()==null&&SystemClock.elapsedRealtime()<until)SystemClock.sleep(25);
            assertTrue(SignalService.isRunning());
            CompletableFuture<RivelaAssets.WarmupResult> first=RivelaAssets.scheduledWarmup();assertNotNull(first);
            RivelaAssets.WarmupResult result=first.get(10,TimeUnit.SECONDS);assertTrue(result.modelReady);
            VehicleModel model=RivelaAssets.model(context.getAssets());
            AtomicReference<CompletableFuture<RivelaAssets.WarmupResult>> second=new AtomicReference<>();
            InstrumentationRegistry.getInstrumentation().runOnMainSync(()->second.set(RivelaAssets.prewarm(context)));
            assertSame(first,second.get());assertSame(result,second.get().get());assertSame(model,RivelaAssets.model(context.getAssets()));
            assertFalse("Prewarming must not require an Activity",com.drivehub.kamera.MainActivity.isMainVisible());
        }finally {
            if(!running)context.stopService(new Intent(context,SignalService.class));
            SharedPreferences.Editor edit=prefs.edit();if(had)edit.putBoolean(DashcamSettingsController.KEY_ENABLED,enabled);else edit.remove(DashcamSettingsController.KEY_ENABLED);edit.commit();
        }
    }
    @Test public void bUnavailableOrInvalidCalibrationStillLeavesTheModelReady() throws Exception {
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        RivelaAssets.WarmupResult missing=RivelaAssets.warm(context.getAssets(),()->{throw new IOException("mount not ready");});
        assertTrue(missing.modelReady);assertFalse(missing.projectionReady);
        RivelaAssets.WarmupResult invalid=RivelaAssets.warm(context.getAssets(),()->BirdseyeCalibration.parse(new byte[520]));
        assertTrue(invalid.modelReady);assertFalse(invalid.projectionReady);
    }
    @Test public void cAvailableCalibrationSeedsTheSameProjectionUsedByTheView() throws Exception {
        assertNotEquals(Looper.getMainLooper(),Looper.myLooper());
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        BirdseyeCalibration calibration=BirdseyeCalibration.parse(BirdseyeSynthetic.calibrationBytes());
        RivelaAssets.WarmupResult result=RivelaAssets.warm(context.getAssets(),()->calibration);
        assertTrue(result.modelReady);assertTrue(result.projectionReady);
        ProjectionAssets loaded=ProjectionAssets.get(calibration);assertNotNull(loaded.scene.bowl());
        assertSame(loaded,ProjectionAssets.get(BirdseyeCalibration.parse(BirdseyeSynthetic.calibrationBytes())));
    }
}
