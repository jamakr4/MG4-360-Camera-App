package com.drivehub.kamera.camera;

import org.junit.Test;
import static org.junit.Assert.*;

public class FloatingWindowPlacementTest {
    @Test public void launcherToFullscreenAndBackKeepsPhysicalPosition() {
        for (int origin : new int[]{142, 0, 142, 0, 142}) {
            FloatingWindowPlacement p = place(600, 200, origin, 0, origin, 80, 1920, 720);
            assertEquals(600, p.windowX + origin);
            assertEquals(200, p.windowY);
        }
    }

    @Test public void measuresBothAxesWithoutAssumingRailOrStatusBarSize() {
        for (int[] origin : new int[][]{{86, 48}, {0, 0}, {174, 96}, {23, 12}}) {
            FloatingWindowPlacement p = place(601, 250, origin[0], origin[1],
                    origin[0], origin[1], 1440, 900);
            assertEquals(601, p.windowX + origin[0]);
            assertEquals(250, p.windowY + origin[1]);
        }
    }

    @Test public void leftEdgeClampDoesNotOverwriteThePreferredPosition() {
        FloatingWindowPlacement narrow = place(32, 120, 142, 0, 142, 80, 1920, 720);
        assertEquals(142, narrow.screenX);
        assertEquals(0, narrow.windowX);
        FloatingWindowPlacement wide = place(32, 120, 0, 0, 0, 80, 1920, 720);
        assertEquals(32, wide.screenX);
        assertEquals(32, wide.windowX);
    }

    @Test public void temporarySmallerFrameRestoresTheRequestedPosition() {
        FloatingWindowPlacement small = place(1600, 600, 0, 0, 0, 48, 1280, 600);
        assertEquals(1080, small.screenX);
        assertEquals(480, small.screenY);
        FloatingWindowPlacement restored = place(1600, 600, 0, 0, 0, 48, 1920, 720);
        assertEquals(1600, restored.screenX);
        assertEquals(600, restored.screenY);
    }

    @Test public void fullScreenDotCanCompensateAnOriginInsideTheLauncherRail() {
        FloatingWindowPlacement p = FloatingWindowPlacement.calculate(
                0, 0, 34, 56, 142, 0, 0, 0, 1920, 720);
        assertEquals(-142, p.windowX);
        assertEquals(0, p.windowX + 142);
        assertEquals(0, p.windowY);
    }

    @Test public void statusDotKeepsExistingPhysicalDefaultWithoutCentreGravity() {
        for (int origin : new int[]{142, 0, 88}) {
            FloatingWindowPlacement p = FloatingWindowPlacement.calculate(
                    1166, 0, 34, 56, origin, 0, 0, 0, 1920, 720);
            assertEquals(1166, p.windowX + origin);
        }
    }

    @Test public void allEdgesAndOversizedWindowsRemainBounded() {
        FloatingWindowPlacement p = place(-100, 5000, 80, 40, 80, 40, 1080, 600);
        assertEquals(80, p.screenX);
        assertEquals(480, p.screenY);
        FloatingWindowPlacement huge = FloatingWindowPlacement.calculate(
                500, 500, 2000, 1000, 80, 40, 80, 40, 1080, 600);
        assertEquals(80, huge.screenX);
        assertEquals(40, huge.screenY);
    }

    private static FloatingWindowPlacement place(int x, int y, int originX, int originY,
            int left, int top, int right, int bottom) {
        return FloatingWindowPlacement.calculate(x, y, 200, 120, originX, originY,
                left, top, right, bottom);
    }
}
