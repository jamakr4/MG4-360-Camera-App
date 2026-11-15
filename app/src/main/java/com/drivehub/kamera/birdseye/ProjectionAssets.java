package com.drivehub.kamera.birdseye;

import java.io.IOException;

/** Shared CPU projection data. Call only after successfully reading/validating the current calibration. */
public final class ProjectionAssets {
    private static final SingleAssetCache<ProjectionAssets> CACHE=new SingleAssetCache<>();
    public final BirdseyeMesh mesh;
    public final RivelaScene scene;
    private ProjectionAssets(BirdseyeCalibration calibration) {
        mesh=new BirdseyeMesh(calibration);scene=new RivelaScene(calibration);
    }
    public static ProjectionAssets get(BirdseyeCalibration calibration) throws IOException {
        String key="projection-v1:"+calibration.sha256+":"+BirdseyeMesh.COLS+":"+BirdseyeMesh.ROWS
                +":"+BirdseyeMesh.GROUND_WIDTH+":"+BirdseyeMesh.GROUND_HEIGHT+":"+BirdseyeMesh.CAR_WIDTH
                +":"+BirdseyeMesh.CAR_LENGTH+":"+RivelaScene.BOWL_THETA+":"+RivelaScene.BOWL_RINGS
                +":"+com.drivehub.kamera.rivela.Bowl.FLAT_MM+":"+com.drivehub.kamera.rivela.Bowl.CURVE_MM
                +":"+com.drivehub.kamera.rivela.Bowl.MAX_MM+":"+com.drivehub.kamera.rivela.Bowl.MAX_THETA_DEG;
        return CACHE.get(key,()->new ProjectionAssets(calibration));
    }
}
