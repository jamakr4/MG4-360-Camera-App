package com.drivehub.kamera.dashcam;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class StatusIconPositionTest {
    @Test
    public void clampsWholeIconToAllFourScreenEdges() {
        assertEquals(0, StatusIconPosition.clamp(-100, 1920, 34));
        assertEquals(1886, StatusIconPosition.clamp(1920, 1920, 34));
        assertEquals(0, StatusIconPosition.clamp(-100, 720, 56));
        assertEquals(664, StatusIconPosition.clamp(720, 720, 56));
        assertEquals(0, StatusIconPosition.clamp(500, 720, 800));
    }

    @Test
    public void usesFullScreenAndKeepsPhysicalPositionWhenProjectionChanges() {
        int width = 34;
        for (int left : new int[]{0, 100, 900, 1886}) {
            int factoryX = StatusIconPosition.windowX(left, width, 142);
            int projectionX = StatusIconPosition.windowX(left, width, 0);
            assertEquals(71, projectionX - factoryX);
            // Factory frame: x=142, width=1778. Projection frame: x=0, width=1920.
            int factoryLeft = 142 + (1778 - width) / 2 + factoryX;
            int projectionLeft = (1920 - width) / 2 + projectionX;
            assertEquals(left, factoryLeft);
            assertEquals(left, projectionLeft);
            assertTrue(factoryLeft >= 0 && factoryLeft + width <= 1920);
        }
    }

    @Test
    public void preservesExistingDefaultHorizontalOffset() {
        int height = 56;
        int width = 34;
        int oldOffset = Math.round(height * 1.8f);
        int defaultLeft = (1920 + 142 - width) / 2 + oldOffset;
        assertEquals(oldOffset, StatusIconPosition.windowX(defaultLeft, width, 142));
        assertEquals(oldOffset + 71, StatusIconPosition.windowX(defaultLeft, width, 0));
    }
}
