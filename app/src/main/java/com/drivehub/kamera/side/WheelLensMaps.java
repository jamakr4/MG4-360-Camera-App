package com.drivehub.kamera.side;

import com.drivehub.kamera.birdseye.BirdseyeCalibration;
import com.drivehub.kamera.birdseye.WheelSillPatch;
import com.drivehub.kamera.birdseye.SideGroundPatch;

/** One process-local CPU cache; no pictures/calibration/map files are bundled or written. */
final class WheelLensMaps {
    private static String cachedKey;
    private static float[][] cached;
    static String key(BirdseyeCalibration calibration){return "curbside-v4-wheel-axis:"+calibration.sha256;}
    static synchronized float[][] get(BirdseyeCalibration calibration){
        String key=key(calibration);
        if(key.equals(cachedKey))return cached;
        float[][] maps=new float[6][];
        for(int side=0;side<2;side++)for(int axle=0;axle<2;axle++){
            maps[side*2+axle]=new WheelSillPatch.Map(calibration,side,axle==0,
                    SideCameraGeometry.TILE_WIDTH,SideCameraGeometry.WHEEL_HEIGHT).coordinates();
        }
        for(int side=0;side<2;side++)maps[4+side]=new SideGroundPatch.Map(calibration,side,
                SideGroundPatch.defaults(calibration,side,SideGroundPatch.SIDE),SideCameraGeometry.TILE_WIDTH,
                SideCameraGeometry.SIDE_HEIGHT).coordinates();
        cached=maps;cachedKey=key;return maps;
    }
}
