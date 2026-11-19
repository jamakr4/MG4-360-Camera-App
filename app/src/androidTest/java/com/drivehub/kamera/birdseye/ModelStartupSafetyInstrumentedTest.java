package com.drivehub.kamera.birdseye;
import android.content.Context;
import android.content.ContextWrapper;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.*;
import java.lang.reflect.Field;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;
/** Simulated process loss only: never crashes Android or writes the live protection record. */
@RunWith(AndroidJUnit4.class)
public class ModelStartupSafetyInstrumentedTest {
    @Test public void durableFiveStartLimitBlocksWarmupAndViewThenExplicitResetRecovers() throws Exception {
        CompletableFuture<RivelaAssets.WarmupResult> pending=RivelaAssets.scheduledWarmup();if(pending!=null)pending.get(10,TimeUnit.SECONDS);
        Context base=InstrumentationRegistry.getInstrumentation().getTargetContext();
        File dir=new File(base.getCacheDir(),"guard-test-"+System.nanoTime());assertTrue(dir.mkdirs());
        Context isolated=new ContextWrapper(base){
            @Override public Context getApplicationContext(){return this;}
            @Override public Context createDeviceProtectedStorageContext(){return this;}
            @Override public File getFilesDir(){return dir;}
            @Override public android.content.SharedPreferences getSharedPreferences(String name,int mode){return base.getSharedPreferences("guard-test-"+name,mode);}
        };
        Field singleton=ModelLoadSafety.class.getDeclaredField("instance");singleton.setAccessible(true);Object previous=singleton.get(null);
        Field future=RivelaAssets.class.getDeclaredField("warmup");future.setAccessible(true);Object previousFuture=future.get(null);
        try {
            for(int attempt=1;attempt<=5;attempt++) {
                singleton.set(null,null);assertTrue(ModelLoadSafety.enter(isolated,true));
                assertTrue(ModelLoadSafety.status(isolated).contains("unconfirmed="+attempt+"/5"));
            }
            singleton.set(null,null);assertTrue(ModelLoadSafety.blocked(isolated));assertFalse(ModelLoadSafety.enter(isolated,true));
            assertEquals(ThreeDFailure.Kind.STARTUP_BLOCKED,ModelLoadSafety.blockedFailure(isolated).kind);
            InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{
                BirdseyeView view=new BirdseyeView(isolated,null);
                assertTrue(view.findViewById(com.drivehub.kamera.R.id.surroundModeToggle).performClick());
                assertFalse(com.drivehub.kamera.settings.UiPrefs.getPrefs(isolated).getBoolean(com.drivehub.kamera.settings.UiPrefs.KEY_PARKING_SURROUND_3D,false));
                assertTrue(ThreeDFailure.lastReport(isolated).contains("3D-GUARD"));
                assertTrue(ModelLoadSafety.blocked(isolated));
            });
            future.set(null,null);RivelaAssets.WarmupResult skipped=RivelaAssets.prewarm(isolated).get(10,TimeUnit.SECONDS);
            assertFalse(skipped.modelReady);assertFalse(skipped.projectionReady);
            RivelaScene scene=new RivelaScene(BirdseyeCalibration.parse(BirdseyeSynthetic.calibrationBytes()));
            CountDownLatch done=new CountDownLatch(1);AtomicReference<Throwable> error=new AtomicReference<>();
            RivelaAssets.prepare(scene,isolated,(model,failure)->{error.set(failure);assertNull(model);done.countDown();});
            assertTrue(done.await(5,TimeUnit.SECONDS));assertNotNull(error.get());assertNull(scene.bowl());
            assertEquals(ThreeDFailure.Kind.STARTUP_BLOCKED,((ThreeDFailure)error.get()).kind);
            ModelLoadSafety.reset(isolated);assertFalse(ModelLoadSafety.blocked(isolated));assertTrue(ModelLoadSafety.enter(isolated,false));
            RivelaScene invalid=new RivelaScene(BirdseyeCalibration.parse(BirdseyeSynthetic.calibrationBytes()));
            invalid.camera(0).k[0]=-1;
            CompletableFuture<Throwable> projectionError=new CompletableFuture<>();
            RivelaAssets.prepare(invalid,isolated,(model,failure)->projectionError.complete(failure));
            ThreeDFailure projectionFailure=(ThreeDFailure)projectionError.get(5,TimeUnit.SECONDS);
            assertEquals(ThreeDFailure.Kind.PROJECTION,projectionFailure.kind);
            assertTrue(projectionFailure.getMessage().contains("3D lens range is invalid"));
            singleton.set(null,null);try(FileOutputStream out=new FileOutputStream(new File(dir,"birdseye-startup-guard.bin"))){out.write(1);out.getFD().sync();}
            assertTrue(ModelLoadSafety.blocked(isolated));assertFalse(ModelLoadSafety.enter(isolated,false));
            assertEquals(ThreeDFailure.Kind.STARTUP_STORAGE,ModelLoadSafety.blockedFailure(isolated).kind);
            assertTrue(ModelLoadSafety.blockedFailure(isolated).getMessage().contains("Invalid startup ledger length"));
            ModelLoadSafety.reset(isolated);assertFalse(ModelLoadSafety.blocked(isolated));assertFalse(ModelLoadSafety.automaticEnabled(isolated));
        }finally {
            singleton.set(null,previous);future.set(null,previousFuture);
            for(String name:new String[]{"birdseye_3d_diagnostics",com.drivehub.kamera.settings.UiPrefs.REC_PREFS_NAME})isolated.getSharedPreferences(name,Context.MODE_PRIVATE).edit().clear().commit();
            File[] files=dir.listFiles();if(files!=null)for(File file:files)file.delete();dir.delete();
        }
    }
}
