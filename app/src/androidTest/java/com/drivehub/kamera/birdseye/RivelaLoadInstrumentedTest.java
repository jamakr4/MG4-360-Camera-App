package com.drivehub.kamera.birdseye;

import android.content.SharedPreferences;
import android.os.SystemClock;
import androidx.lifecycle.Lifecycle;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.drivehub.kamera.settings.UiPrefs;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.junit.runners.MethodSorters;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.Assert.*;

/** Actual startup/cache measurements; no timing threshold tied to an emulator's speed. */
@RunWith(AndroidJUnit4.class) @FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class RivelaLoadInstrumentedTest {
    @Test public void aActivityReopenRetainsModelAndProjectionAndRebuildsGl() throws Exception {
        SharedPreferences prefs=UiPrefs.getPrefs(InstrumentationRegistry.getInstrumentation().getTargetContext());
        boolean had=prefs.contains(UiPrefs.KEY_PARKING_SURROUND_3D),old=prefs.getBoolean(UiPrefs.KEY_PARKING_SURROUND_3D,false);
        prefs.edit().putBoolean(UiPrefs.KEY_PARKING_SURROUND_3D,true).commit();JSONArray rows=new JSONArray();
        Object[] original=new Object[2];
        try {
            long start=SystemClock.elapsedRealtime();
            try(ActivityScenario<BirdseyeDebugActivity> scenario=ActivityScenario.launch(BirdseyeDebugActivity.class)) {
                ready(scenario,start);rows.put(row(scenario,"launch",start));objects(scenario,original);
                AtomicInteger generation=new AtomicInteger();scenario.onActivity(a->generation.set(a.renderer.contextGeneration));
                scenario.moveToState(Lifecycle.State.CREATED);SystemClock.sleep(100);
                start=SystemClock.elapsedRealtime();scenario.moveToState(Lifecycle.State.RESUMED);ready(scenario,start);
                Object[] resumed=new Object[2];objects(scenario,resumed);assertSame(original[0],resumed[0]);assertSame(original[1],resumed[1]);
                scenario.onActivity(a->assertTrue(a.renderer.contextGeneration>generation.get()));rows.put(row(scenario,"same_activity_resume",start));
                start=SystemClock.elapsedRealtime();scenario.recreate();ready(scenario,start);
                Object[] recreated=new Object[2];objects(scenario,recreated);assertSame(original[0],recreated[0]);assertSame(original[1],recreated[1]);
                rows.put(row(scenario,"activity_recreate_same_process",start));
            }
            start=SystemClock.elapsedRealtime();
            try(ActivityScenario<BirdseyeDebugActivity> scenario=ActivityScenario.launch(BirdseyeDebugActivity.class)) {
                ready(scenario,start);Object[] reopened=new Object[2];objects(scenario,reopened);
                assertSame(original[0],reopened[0]);assertSame(original[1],reopened[1]);rows.put(row(scenario,"new_activity_same_process",start));
            }
            save("startup",rows);
        }finally {
            SharedPreferences.Editor edit=prefs.edit();if(had)edit.putBoolean(UiPrefs.KEY_PARKING_SURROUND_3D,old);else edit.remove(UiPrefs.KEY_PARKING_SURROUND_3D);edit.commit();
        }
    }
    @Test public void bValidatedBinaryAndProcessCacheMeasurements() throws Exception {
        android.content.res.AssetManager assets=InstrumentationRegistry.getInstrumentation().getTargetContext().getAssets();
        Set<String> files=new HashSet<>(Arrays.asList(assets.list(VehicleModel.DIRECTORY.substring(0,VehicleModel.DIRECTORY.length()-1))));
        assertTrue(files.contains("car.meshbin"));assertFalse(files.contains("car.obj"));assertFalse(files.contains("car.mtl"));
        VehicleModel cached=RivelaAssets.model(assets);JSONArray rows=new JSONArray();
        for(int pass=0;pass<3;pass++) {
            long start=SystemClock.elapsedRealtimeNanos();VehicleModel loaded=VehicleModel.load(assets);
            double load=(SystemClock.elapsedRealtimeNanos()-start)/1e6;
            start=SystemClock.elapsedRealtimeNanos();assertSame(cached,RivelaAssets.model(assets));double hit=(SystemClock.elapsedRealtimeNanos()-start)/1e6;
            assertEquals(263985,loaded.vertexCount);assertEquals(cached.vertexCount,loaded.vertexCount);
            assertEquals(cached.vertices.get(0),loaded.vertices.get(0),0);
            rows.put(new JSONObject().put("pass",pass).put("binary_model_load_ms",load).put("process_cache_hit_ms",hit));
            for(BirdseyeCarColor color:BirdseyeCarColor.values())loaded.paint(color).atlas.recycle();
        }
        save("binary",rows);
    }
    @Test public void cAsyncPreparationReturnsSharedAssetsOnMainThread() throws Exception {
        android.content.res.AssetManager assets=InstrumentationRegistry.getInstrumentation().getTargetContext().getAssets();
        VehicleModel expected=RivelaAssets.model(assets);
        ProjectionAssets projection=ProjectionAssets.get(BirdseyeCalibration.parse(BirdseyeSynthetic.calibrationBytes()));
        CountDownLatch done=new CountDownLatch(2);AtomicReference<Throwable> failure=new AtomicReference<>();
        for(int i=0;i<2;i++)RivelaAssets.prepare(projection.scene,InstrumentationRegistry.getInstrumentation().getTargetContext(),(model,error)->{
            try {assertEquals(android.os.Looper.getMainLooper(),android.os.Looper.myLooper());assertNull(error);assertSame(expected,model);assertNotNull(projection.scene.bowl());}
            catch(Throwable t){failure.set(t);}finally{done.countDown();}
        });
        assertTrue(done.await(5,TimeUnit.SECONDS));if(failure.get()!=null)throw new AssertionError(failure.get());
    }
    private static void objects(ActivityScenario<BirdseyeDebugActivity> scenario,Object[] result) {
        scenario.onActivity(a->{try {
            for(int i=0;i<2;i++){java.lang.reflect.Field field=BirdseyeRenderer.class.getDeclaredField(i==0?"model":"scene");field.setAccessible(true);result[i]=field.get(a.renderer);}
        }catch(Exception e){throw new AssertionError(e);}});
    }
    private static JSONObject row(ActivityScenario<BirdseyeDebugActivity> scenario,String action,long start) throws Exception {
        JSONObject row=new JSONObject().put("action",action).put("ready_ms",SystemClock.elapsedRealtime()-start).put("pid",android.os.Process.myPid());
        Object[] assets=new Object[2];objects(scenario,assets);
        return row.put("model_identity",System.identityHashCode(assets[0])).put("scene_identity",System.identityHashCode(assets[1]));
    }
    private static void ready(ActivityScenario<BirdseyeDebugActivity> scenario,long started) {
        long deadline=SystemClock.elapsedRealtime()+20000;AtomicBoolean active=new AtomicBoolean();
        do {
            scenario.onActivity(a->{assertNull(a.preparationError);assertNull(a.renderer.error);assertNull(a.renderer.threeDError);active.set(a.renderer.threeDActive&&a.renderer.lastDrawElapsedMs>=started);});
            if(active.get())return;SystemClock.sleep(25);
        }while(SystemClock.elapsedRealtime()<deadline);fail("No fresh 3D frame");
    }
    private static void save(String name,JSONArray rows) throws Exception {
        File file=new File(InstrumentationRegistry.getInstrumentation().getTargetContext().getFilesDir(),"rivela-"+name+"-"+android.os.Process.myPid()+".json");
        try(FileOutputStream out=new FileOutputStream(file)){out.write(rows.toString(2).getBytes("UTF-8"));}
    }
}
