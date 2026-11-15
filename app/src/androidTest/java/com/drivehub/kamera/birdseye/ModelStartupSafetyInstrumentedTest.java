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
        };
        Field singleton=ModelLoadSafety.class.getDeclaredField("instance");singleton.setAccessible(true);Object previous=singleton.get(null);
        Field future=RivelaAssets.class.getDeclaredField("warmup");future.setAccessible(true);Object previousFuture=future.get(null);
        try {
            for(int attempt=1;attempt<=5;attempt++) {
                singleton.set(null,null);assertTrue(ModelLoadSafety.enter(isolated,true));
                assertTrue(ModelLoadSafety.status(isolated).contains("unconfirmed="+attempt+"/5"));
            }
            singleton.set(null,null);assertTrue(ModelLoadSafety.blocked(isolated));assertFalse(ModelLoadSafety.enter(isolated,true));
            future.set(null,null);RivelaAssets.WarmupResult skipped=RivelaAssets.prewarm(isolated).get(10,TimeUnit.SECONDS);
            assertFalse(skipped.modelReady);assertFalse(skipped.projectionReady);
            RivelaScene scene=new RivelaScene(BirdseyeCalibration.parse(BirdseyeSynthetic.calibrationBytes()));
            CountDownLatch done=new CountDownLatch(1);AtomicReference<Throwable> error=new AtomicReference<>();
            RivelaAssets.prepare(scene,isolated,(model,failure)->{error.set(failure);assertNull(model);done.countDown();});
            assertTrue(done.await(5,TimeUnit.SECONDS));assertNotNull(error.get());assertNull(scene.bowl());
            ModelLoadSafety.reset(isolated);assertFalse(ModelLoadSafety.blocked(isolated));assertTrue(ModelLoadSafety.enter(isolated,false));
            singleton.set(null,null);try(FileOutputStream out=new FileOutputStream(new File(dir,"birdseye-startup-guard.bin"))){out.write(1);out.getFD().sync();}
            assertTrue(ModelLoadSafety.blocked(isolated));assertFalse(ModelLoadSafety.enter(isolated,false));
            ModelLoadSafety.reset(isolated);assertFalse(ModelLoadSafety.blocked(isolated));assertFalse(ModelLoadSafety.automaticEnabled(isolated));
        }finally {
            singleton.set(null,previous);future.set(null,previousFuture);
            File[] files=dir.listFiles();if(files!=null)for(File file:files)file.delete();dir.delete();
        }
    }
}
