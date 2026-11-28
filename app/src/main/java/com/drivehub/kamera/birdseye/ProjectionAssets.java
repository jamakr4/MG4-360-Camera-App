package com.drivehub.kamera.birdseye;

import java.io.File;
import java.io.IOException;

/** Shared CPU projection data. Call only after successfully reading/validating the current calibration. */
public final class ProjectionAssets {
    private static final SingleAssetCache<ProjectionAssets> CACHE=new SingleAssetCache<>();
    private static volatile ProjectionDiskCache disk;
    public final BirdseyeMesh mesh;
    public final RivelaScene scene;
    public final BirdseyeMesh wideMesh;
    public final String wideError;
    private ProjectionAssets(RivelaScene scene,BirdseyeMesh mesh,BirdseyeMesh wideMesh,String wideError) {
        this.scene=scene;this.mesh=mesh;this.wideMesh=wideMesh;this.wideError=wideError;
    }
    private static ProjectionAssets compute(RivelaScene scene) {
        BirdseyeMesh mesh=new BirdseyeMesh(scene.calibration);
        BirdseyeMesh wide=null;String error=null;
        try{scene.validateBowlLenses();wide=new BirdseyeMesh(scene);}catch(RuntimeException failure){error=failure.getMessage();
            if(error==null)error=failure.getClass().getSimpleName();}
        return new ProjectionAssets(scene,mesh,wide,error);
    }
    /** Enable the persistent mesh cache; idempotent, any process-wide caller may set it. */
    public static void useDiskCache(File directory) {
        if(disk==null&&directory!=null)disk=new ProjectionDiskCache(directory);
    }
    private static long ms(long startedNs) {return (System.nanoTime()-startedNs)/1_000_000;}
    static String key(BirdseyeCalibration calibration) {
        return "projection-v2-wide2x:"+calibration.sha256+":"+BirdseyeMesh.COLS+":"+BirdseyeMesh.ROWS
                +":"+BirdseyeMesh.GROUND_WIDTH+":"+BirdseyeMesh.GROUND_HEIGHT+":"+BirdseyeMesh.CAR_WIDTH
                +":"+BirdseyeMesh.CAR_LENGTH+":"+RivelaScene.BOWL_THETA+":"+RivelaScene.BOWL_RINGS
                +":"+RivelaScene.GUIDANCE_FLAT_MM+":"+com.drivehub.kamera.rivela.Bowl.CURVE_MM
                +":"+com.drivehub.kamera.rivela.Bowl.MAX_MM+":"+com.drivehub.kamera.rivela.Bowl.MAX_THETA_DEG;
    }
    public static ProjectionAssets get(BirdseyeCalibration calibration) throws IOException {
        String key=key(calibration);
        return CACHE.get(key,()->{
            long started=System.nanoTime();
            // The scene is cheap and must always come from the calibration that was just read.
            RivelaScene scene=new RivelaScene(calibration,RivelaScene.GUIDANCE_FLAT_MM);
            ProjectionDiskCache store=disk;
            ProjectionDiskCache.Entry cached=store==null?null:store.load(key);
            if(cached!=null) {
                ProjectionDiskCache.info("Projection from disk cache in "+ms(started)+" ms");
                return new ProjectionAssets(scene,cached.mesh,cached.wideMesh,cached.wideError);
            }
            ProjectionAssets computed=compute(scene);
            long elapsed=ms(started);
            if(store!=null)store.store(key,new ProjectionDiskCache.Entry(computed.mesh,computed.wideMesh,computed.wideError));
            ProjectionDiskCache.info("Projection computed in "+elapsed+" ms"
                    +(store==null?"":", persisted after "+ms(started)+" ms"));
            return computed;
        });
    }
}
