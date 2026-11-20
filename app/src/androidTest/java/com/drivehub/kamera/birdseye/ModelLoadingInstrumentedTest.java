package com.drivehub.kamera.birdseye;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.drivehub.kamera.R;
import com.drivehub.kamera.settings.UiPrefs;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.zip.CRC32;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class ModelLoadingInstrumentedTest {
    @Test public void oldBlockedAndCorruptRecordsCannotDisableWarmupOrManualThreeD() throws Exception {
        CompletableFuture<RivelaAssets.WarmupResult> pending=RivelaAssets.scheduledWarmup();
        if(pending!=null)pending.get(30,TimeUnit.SECONDS);
        Context base=InstrumentationRegistry.getInstrumentation().getTargetContext();
        String name="legacy-model-guard-"+System.nanoTime();
        File dir=new File(base.getCacheDir(),name);assertTrue(dir.mkdirs());
        SharedPreferences prefs=base.getSharedPreferences(name,Context.MODE_PRIVATE);
        Context isolated=new ContextWrapper(base) {
            @Override public Context getApplicationContext(){return this;}
            @Override public Context createDeviceProtectedStorageContext(){return this;}
            @Override public File getFilesDir(){return dir;}
            @Override public SharedPreferences getSharedPreferences(String key,int mode){return prefs;}
        };
        Field future=RivelaAssets.class.getDeclaredField("warmup");future.setAccessible(true);
        Object previous=future.get(null);
        File record=new File(dir,"birdseye-startup-guard.bin");
        try {
            // Simulate both a previous five-start lock with preloading off and a damaged record.
            for(byte[] legacy:new byte[][]{blockedLegacyRecord(),new byte[]{1}}) {
                Files.write(record.toPath(),legacy);
                prefs.edit().clear().commit();
                InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{
                    BirdseyeView view=new BirdseyeView(isolated,null);
                    android.view.View toggle=view.findViewById(R.id.surroundModeToggle);
                    assertTrue(toggle.isEnabled());assertEquals(1f,toggle.getAlpha(),0f);
                    assertTrue(toggle.performClick());
                    assertTrue(UiPrefs.getPrefs(isolated).getBoolean(UiPrefs.KEY_PARKING_SURROUND_3D,false));
                });
                future.set(null,null);
                assertTrue(RivelaAssets.prewarm(isolated).get(30,TimeUnit.SECONDS).modelReady);
                RivelaScene scene=new RivelaScene(BirdseyeCalibration.parse(BirdseyeSynthetic.calibrationBytes()));
                CompletableFuture<VehicleModel> prepared=new CompletableFuture<>();
                RivelaAssets.prepare(scene,isolated,(model,error)->{
                    if(error!=null)prepared.completeExceptionally(error);else prepared.complete(model);
                });
                assertNotNull(prepared.get(30,TimeUnit.SECONDS));assertNotNull(scene.bowl());
                // No migration or reset is required; the obsolete record is never consulted or written.
                assertArrayEquals(legacy,Files.readAllBytes(record.toPath()));
            }
        } finally {
            future.set(null,previous);prefs.edit().clear().commit();
            record.delete();dir.delete();
        }
    }

    @Test public void invalidProjectionStillReportsItsActualCause() throws Exception {
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        RivelaScene invalid=new RivelaScene(BirdseyeCalibration.parse(BirdseyeSynthetic.calibrationBytes()));
        invalid.camera(0).k[0]=-1;
        CompletableFuture<Throwable> result=new CompletableFuture<>();
        RivelaAssets.prepare(invalid,context,(model,error)->result.complete(error));
        ThreeDFailure failure=(ThreeDFailure)result.get(10,TimeUnit.SECONDS);
        assertNotNull(failure);assertEquals(ThreeDFailure.Kind.PROJECTION,failure.kind);
        assertTrue(failure.getMessage().contains("3D lens range is invalid"));
    }

    private static byte[] blockedLegacyRecord() throws Exception {
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        DataOutputStream out=new DataOutputStream(bytes);
        out.writeInt(0x4d473447);out.writeInt(1);out.writeInt(5);out.writeLong(5);out.writeBoolean(false);
        CRC32 crc=new CRC32();crc.update(bytes.toByteArray());out.writeInt((int)crc.getValue());
        return bytes.toByteArray();
    }
}
