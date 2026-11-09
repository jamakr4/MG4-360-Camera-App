package com.drivehub.kamera.side;

import org.junit.Test;
import static org.junit.Assert.*;

public class SideCameraGeometryTest {
    @Test public void nativeCropsKeepPortraitFrontAndRearInOrder() {
        for (boolean left : new boolean[]{true, false}) {
            float[] values = SideCameraGeometry.previewCrops(left);
            SideCameraGeometry.Crop[] expected = {SideCameraGeometry.side(left),
                    SideCameraGeometry.wheel(left, true), SideCameraGeometry.wheel(left, false)};
            for (int i = 0; i < 3; i++) {
                assertArrayEquals(new float[]{expected[i].left, expected[i].top,
                        expected[i].right, expected[i].bottom},
                        java.util.Arrays.copyOfRange(values, i*4, i*4+4), .00001f);
            }
        }
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
    @Test public void everyCropStaysInsideTheOriginalCameraImage() {
        for (boolean left : new boolean[]{true, false}) {
            for (float value : SideCameraGeometry.previewCrops(left)) {
                assertTrue(value >= 0 && value <= 1);
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
