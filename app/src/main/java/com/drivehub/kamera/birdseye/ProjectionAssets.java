package com.drivehub.kamera.birdseye;

import java.io.IOException;

/** Shared CPU projection data. Call only after successfully reading/validating the current calibration. */
public final class ProjectionAssets {
    private static final SingleAssetCache<ProjectionAssets> CACHE=new SingleAssetCache<>();
    public final BirdseyeMesh mesh;
    public final RivelaScene scene;
    public final BirdseyeMesh wideMesh;
    public final String wideError;
    private ProjectionAssets(BirdseyeCalibration calibration) {
        mesh=new BirdseyeMesh(calibration);scene=new RivelaScene(calibration,RivelaScene.GUIDANCE_FLAT_MM);
        BirdseyeMesh wide=null;String error=null;
        try{scene.validateBowlLenses();wide=new BirdseyeMesh(scene);}catch(RuntimeException failure){error=failure.getMessage();}
        wideMesh=wide;wideError=error;
    }
    public static ProjectionAssets get(BirdseyeCalibration calibration) throws IOException {
        String key="projection-v2-wide2x:"+calibration.sha256+":"+BirdseyeMesh.COLS+":"+BirdseyeMesh.ROWS
                +":"+BirdseyeMesh.GROUND_WIDTH+":"+BirdseyeMesh.GROUND_HEIGHT+":"+BirdseyeMesh.CAR_WIDTH
                +":"+BirdseyeMesh.CAR_LENGTH+":"+RivelaScene.BOWL_THETA+":"+RivelaScene.BOWL_RINGS
                +":"+RivelaScene.GUIDANCE_FLAT_MM+":"+com.drivehub.kamera.rivela.Bowl.CURVE_MM
                +":"+com.drivehub.kamera.rivela.Bowl.MAX_MM+":"+com.drivehub.kamera.rivela.Bowl.MAX_THETA_DEG;
        return CACHE.get(key,()->new ProjectionAssets(calibration));
    }
}
