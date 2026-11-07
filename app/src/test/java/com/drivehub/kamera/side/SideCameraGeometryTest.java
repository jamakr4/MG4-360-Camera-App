package com.drivehub.kamera.side;

import org.junit.Test;
import static org.junit.Assert.*;

public class SideCameraGeometryTest {
    @Test public void sideRotationKeepsFrontAboveRearWithoutMirroring() {
        assertArrayEquals(new float[]{1,0},SideCameraGeometry.rawUv(true,0,0),.00001f);
        assertArrayEquals(new float[]{0,1},SideCameraGeometry.rawUv(true,1,1),.00001f);
        assertArrayEquals(new float[]{0,1},SideCameraGeometry.rawUv(false,0,0),.00001f);
        assertArrayEquals(new float[]{1,0},SideCameraGeometry.rawUv(false,1,1),.00001f);
    }
    @Test public void allWheelRegionsContainTheMeasuredTireExtentsAndAdjacentGround() {
        for(boolean left:new boolean[]{true,false}) {
            SideCameraGeometry.Crop front=SideCameraGeometry.wheel(left,true);
            SideCameraGeometry.Crop rear=SideCameraGeometry.wheel(left,false);
            assertTrue(front.top<=.04f);assertEquals(1,rear.bottom,0);
            assertTrue(front.bottom<rear.top);
            // Body-side endpoints and rear wheel landmarks in the supplied upright montage.
            assertContains(front,left?.90f:.10f,.04f);
            assertContains(front,left?.78f:.22f,.32f);
            assertContains(rear,left?.65f:.30f,.95f);
            assertAspect(front,254f/224);assertAspect(rear,254f/224);
        }
    }
    @Test public void tallSideViewsContainBothWheelLandmarksWithoutStretching() {
        for(boolean left:new boolean[]{true,false}) {
            SideCameraGeometry.Crop side=SideCameraGeometry.side(left);
            assertEquals(0,side.top,0);assertEquals(1,side.bottom,0);
            assertContains(side,left?.90f:.10f,.04f);
            assertContains(side,left?.65f:.30f,.95f);
            assertAspect(side,254f/456);
        }
    }
    @Test public void everyUvStaysInsideTheOriginalCameraImage() {
        for(boolean left:new boolean[]{true,false}) {
            for(SideCameraGeometry.Crop crop:new SideCameraGeometry.Crop[]{SideCameraGeometry.side(left),
                    SideCameraGeometry.wheel(left,true),SideCameraGeometry.wheel(left,false)}) {
                float[] vertices=SideCameraGeometry.vertices(left,crop);
                for(int i=0;i<4;i++)for(int coordinate=2;coordinate<4;coordinate++) {
                    float value=vertices[i*4+coordinate];assertTrue(value>=0&&value<=1);
                }
            }
        }
    }
    private static void assertContains(SideCameraGeometry.Crop crop,float x,float y) {
        assertTrue(x>=crop.left&&x<=crop.right&&y>=crop.top&&y<=crop.bottom);
    }
    private static void assertAspect(SideCameraGeometry.Crop crop,float aspect) {
        // A 720x480 raw image becomes 480x720 after a quarter turn.
        assertEquals(aspect,(crop.right-crop.left)*480/((crop.bottom-crop.top)*720),.00001f);
    }
}
