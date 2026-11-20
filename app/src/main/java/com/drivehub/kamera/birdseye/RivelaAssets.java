package com.drivehub.kamera.birdseye;

import android.content.res.AssetManager;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.CompletableFuture;
import java.nio.file.Paths;

/** Process-wide immutable CPU assets; model work never occupies the camera attach/detach worker. */
public final class RivelaAssets {
    private static final ExecutorService IO=Executors.newSingleThreadExecutor(r->new Thread(()->{
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
        r.run();
    },"Rivela-assets"));
    private static final Handler MAIN=new Handler(Looper.getMainLooper());
    private static final SingleAssetCache<VehicleModel> MODELS=new SingleAssetCache<>();
    private static CompletableFuture<WarmupResult> warmup;
    public static final class WarmupResult {
        public final boolean modelReady,projectionReady;
        public final long elapsedMs;
        private WarmupResult(boolean modelReady,boolean projectionReady,long elapsedMs) {
            this.modelReady=modelReady;this.projectionReady=projectionReady;this.elapsedMs=elapsedMs;
        }
    }
    interface CalibrationLoader { BirdseyeCalibration load() throws IOException; }
    public interface Callback { void ready(VehicleModel model,Throwable error); }
    private RivelaAssets() {}
    /** Call after the existing foreground service is active, including boot/sticky process restarts. */
    public static synchronized CompletableFuture<WarmupResult> prewarm(Context context) {
        if(warmup!=null)return warmup;
        AssetManager assets=context.getApplicationContext().getAssets();
        CompletableFuture<WarmupResult> pending=new CompletableFuture<>();warmup=pending;
        try {
            IO.execute(()->{
                WarmupResult result=warm(assets,()->BirdseyeCalibration.load(Paths.get(BirdseyeCalibration.VEHICLE_PATH)));
                pending.complete(result);
            });
        }catch(RuntimeException|OutOfMemoryError error) {
            warmup=null;pending.completeExceptionally(error);
            Log.w("RivelaAssets","Cannot schedule startup warmup",error);
        }
        return pending;
    }
    static synchronized CompletableFuture<WarmupResult> scheduledWarmup() {return warmup;}
    /** Independent stages: late/unreadable vehicle calibration must not prevent model preloading. */
    static WarmupResult warm(AssetManager assets,CalibrationLoader calibration) {
        long started=SystemClock.elapsedRealtime();boolean modelReady=false,projectionReady=false;
        try {model(assets);modelReady=true;}
        catch(Exception|OutOfMemoryError error) {Log.w("RivelaAssets","Model warmup unavailable",ThreeDFailure.at(ThreeDFailure.Kind.MODEL,error));}
        try {
            ProjectionAssets projection=ProjectionAssets.get(calibration.load());
            projection.scene.prepareBowl();projectionReady=true;
        }catch(Exception|OutOfMemoryError error) {
            // The mount may not be ready at boot. The visible view reads/validates the file again.
            Log.i("RivelaAssets","Projection warmup deferred: "+error.getMessage());
        }
        WarmupResult result=new WarmupResult(modelReady,projectionReady,SystemClock.elapsedRealtime()-started);
        Log.i("RivelaAssets","Startup warmup: model="+result.modelReady+", projection="+result.projectionReady+", "+result.elapsedMs+" ms");
        return result;
    }
    /** IO-only entry point, also used by instrumentation to measure the actual cache path. */
    static VehicleModel model(AssetManager assets) throws IOException {
        String key=VehicleModel.assetKey(assets);
        return MODELS.get(key,()->{
            long started=SystemClock.elapsedRealtime();
            VehicleModel loaded=VehicleModel.load(assets);
            Log.i("RivelaAssets","Validated binary model and 8 paints in "+(SystemClock.elapsedRealtime()-started)+" ms");
            return loaded;
        });
    }
    public static void prepare(RivelaScene scene,Context context,Callback callback) {
        Context app=context.getApplicationContext();
        IO.execute(()->{
            ThreeDFailure.Kind stage=ThreeDFailure.Kind.PROJECTION;
            try {
                scene.prepareBowl();
                stage=ThreeDFailure.Kind.MODEL;VehicleModel loaded=model(app.getAssets());
                MAIN.post(()->callback.ready(loaded,null));
            } catch(Exception|OutOfMemoryError error) {
                ThreeDFailure failure=ThreeDFailure.at(stage,error);
                MAIN.post(()->callback.ready(null,failure));
            }
        });
    }
}
