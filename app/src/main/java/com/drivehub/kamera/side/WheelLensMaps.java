package com.drivehub.kamera.side;

import com.drivehub.kamera.birdseye.BirdseyeCalibration;
import com.drivehub.kamera.birdseye.WheelSillPatch;
import com.drivehub.kamera.birdseye.SideGroundPatch;

import com.drivehub.kamera.dev.DevRuntimeLog;
import java.io.IOException;
import java.util.concurrent.Executor;

/** One immutable set in memory, backed by the vehicle-specific UV grids on disk. */
final class WheelLensMaps {
    private static String cachedKey;
    private static float[][] cached;
    static String key(BirdseyeCalibration calibration){return "curbside-v4-wheel-axis:"+calibration.sha256;}
    static synchronized float[][] get(BirdseyeCalibration calibration){
        return get(calibration, null, null);
    }

    static synchronized float[][] get(BirdseyeCalibration calibration, WheelLensMapCache disk, Executor io){
        String key=key(calibration);
        if(key.equals(cachedKey))return cached;
        long started = System.nanoTime();
        if (disk != null) {
            try {
                float[][] stored = disk.read(key);
                if (stored != null) {
                    cachedKey = key;
                    cached = stored;
                    DevRuntimeLog.add("Curbside", "Loaded correction maps in " + elapsedMs(started) + " ms");
                    return stored;
                }
            } catch (IOException failure) {
                DevRuntimeLog.add("Curbside", "Rebuilding unreadable correction maps: " + failure.getMessage());
            }
        }
        float[][] maps=new float[6][];
        for(int side=0;side<2;side++)for(int axle=0;axle<2;axle++){
            maps[side*2+axle]=new WheelSillPatch.Map(calibration,side,axle==0,
                    SideCameraGeometry.TILE_WIDTH,SideCameraGeometry.WHEEL_HEIGHT).coordinates();
        }
        for(int side=0;side<2;side++)maps[4+side]=new SideGroundPatch.Map(calibration,side,
                SideGroundPatch.defaults(calibration,side,SideGroundPatch.SIDE),SideCameraGeometry.TILE_WIDTH,
                SideCameraGeometry.SIDE_HEIGHT).coordinates();
        cached=maps;cachedKey=key;
        if (disk != null) {
            DevRuntimeLog.add("Curbside", "Computed correction maps in " + elapsedMs(started) + " ms");
            io.execute(() -> {
                try { disk.write(key, maps); }
                catch (IOException failure) { DevRuntimeLog.add("Curbside", "Cannot save correction maps: " + failure.getMessage()); }
            });
        }
        return maps;
    }
    private static long elapsedMs(long started) { return (System.nanoTime() - started) / 1_000_000; }
}
