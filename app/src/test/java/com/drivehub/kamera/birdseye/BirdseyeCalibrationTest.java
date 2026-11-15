package com.drivehub.kamera.birdseye;

import org.junit.Test;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import static org.junit.Assert.*;

public class BirdseyeCalibrationTest {
    private byte[] changed(int index, double value) {
        byte[] bytes = BirdseyeSynthetic.calibrationBytes();
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putDouble(index*8,value);
        return bytes;
    }
    private void invalid(byte[] bytes) {
        try { BirdseyeCalibration.parse(bytes); fail("Accepted invalid calibration"); }
        catch (IllegalArgumentException expected) { assertNotNull(expected.getMessage()); }
    }
    @Test public void lengthAndAllNonFiniteFieldsAreRejected() {
        invalid(new byte[0]); invalid(new byte[519]); invalid(new byte[521]);
        for (int i=0;i<65;i++) {
            invalid(changed(i,Double.NaN)); invalid(changed(i,Double.POSITIVE_INFINITY));
        }
        invalid(changed(1,0)); invalid(changed(2,-1)); invalid(changed(7,-100));
        byte[] overflow = changed(11,Double.MAX_VALUE);
        ByteBuffer.wrap(overflow).order(ByteOrder.LITTLE_ENDIAN).putDouble(12*8,Double.MAX_VALUE);
        invalid(overflow);
    }
    @Test public void unknownHeaderAndReservedValuesHaveNoInventedRules() {
        BirdseyeCalibration.parse(changed(0,-371));
        BirdseyeCalibration.parse(changed(5,-1e25));
        BirdseyeCalibration.parse(changed(6,1e25));
    }
    @Test public void knownCameraPositionsAreRecoveredWithCorrectOrder() {
        BirdseyeCalibration c = BirdseyeCalibration.parse(BirdseyeSynthetic.calibrationBytes());
        double[][] expected={{-900,0,900},{900,0,900},{0,-2100,900},{0,2100,900}};
        for (int i=0;i<4;i++) assertArrayEquals(expected[i],c.cameraCenter(i),1e-9);
        assertEquals(0,c.centerX,1e-9); assertEquals(0,c.centerY,1e-9);
        assertEquals(64,c.sha256.length());
    }
    @Test public void analyticEquidistantFortyFiveDegreeRay() {
        byte[] bytes = BirdseyeSynthetic.calibrationBytes();
        ByteBuffer b=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        double[] a={200,200,360,240,0,0,0,0,0,0,0,0,0,0,0,1000};
        for(int i=0;i<16;i++) b.putDouble((i+1)*8,a[i]);
        BirdseyeCalibration c=BirdseyeCalibration.parse(bytes);
        BirdseyeCalibration.Projection center=c.project(0,0,0), p=c.project(0,1000,0);
        assertEquals(360,center.u,1e-9); assertEquals(240,center.v,1e-9);
        assertEquals(360+50*Math.PI,p.u,1e-9); assertEquals(240,p.v,1e-9);
        assertArrayEquals(new double[]{1000,0},c.groundPoint(0,p.u,p.v),1e-8);
    }
    @Test public void inverseGroundRaysAgreeForRotatedCameras() {
        BirdseyeCalibration c=BirdseyeCalibration.parse(BirdseyeSynthetic.calibrationBytes());
        int rays=0;
        for(int i=0;i<4;i++) for(int y=20;y<460;y+=31) for(int x=20;x<700;x+=29) {
            double[] ground=c.groundPoint(i,x,y);
            if(ground==null)continue;
            BirdseyeCalibration.Projection p=c.project(i,ground[0],ground[1]);
            assertEquals(x,p.u,1e-7);assertEquals(y,p.v,1e-7);rays++;
        }
        assertTrue(rays>500);
    }
    @Test public void meshUsesMetricMaskAndHasFiniteUvsAndValidIndices() {
        BirdseyeMesh mesh=new BirdseyeMesh(BirdseyeCalibration.parse(BirdseyeSynthetic.calibrationBytes()));
        assertTrue(mesh.coveredVertices>1000);
        assertEquals(450.0/640,BirdseyeMesh.GROUND_WIDTH/BirdseyeMesh.GROUND_HEIGHT,1e-7);
        while(mesh.vertices.hasRemaining()) assertTrue(Float.isFinite(mesh.vertices.get()));
        int vertices=mesh.vertices.capacity()/BirdseyeMesh.FLOATS;
        while(mesh.indices.hasRemaining()) assertTrue((mesh.indices.get()&65535)<vertices);
    }
}
