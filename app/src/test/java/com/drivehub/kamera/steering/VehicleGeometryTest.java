package com.drivehub.kamera.steering;

import com.drivehub.kamera.birdseye.BirdseyeMesh;
import com.drivehub.kamera.rivela.CarFrame;
import org.junit.Test;
import static org.junit.Assert.*;

public class VehicleGeometryTest {
    @Test public void manualDimensionsAreConsistentAndSharedBySteeringAndSurroundViews() {
        VehicleGeometry g=VehicleGeometry.MG4;
        assertEquals(4287,g.lengthMm,0);assertEquals(1836,g.widthMm,0);assertEquals(1504,g.heightMm,0);
        assertEquals(2705,g.wheelbaseMm,0);assertEquals(841,g.frontOverhangMm,0);assertEquals(741,g.rearOverhangMm,0);
        assertEquals(g.lengthMm,g.frontOverhangMm+g.wheelbaseMm+g.rearOverhangMm,0);
        assertEquals(1550,g.frontTrackMm,0);assertEquals(1551,g.rearTrackMm,0);assertEquals(117,g.groundClearanceMm,0);
        assertEquals(g.lengthMm,CarFrame.MG4_LENGTH,0);assertEquals(g.widthMm,CarFrame.MG4_WIDTH,0);
        assertEquals(g.lengthMm,BirdseyeMesh.CAR_LENGTH,0);assertEquals(g.widthMm,BirdseyeMesh.CAR_WIDTH,0);
        assertEquals(-1302.5,g.frontAxleY(0),0);assertEquals(1402.5,g.rearAxleY(0),0);
        assertEquals(-g.lengthMm/2,g.rearAxleY(0)+g.frontY(),0);
    }
}
