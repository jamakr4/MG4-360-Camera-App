package com.drivehub.kamera.camera;

import org.junit.Test;
import static org.junit.Assert.*;

public class IpkCameraSelectionTest {
    @Test public void signalTemporarilyReplacesRearviewAndRearviewReturns() {
        assertEquals(17, IpkCameraSelection.selectVideo(4, true, true, false));
        assertEquals(16, IpkCameraSelection.selectVideo(1, true, true, false));
        assertEquals(14, IpkCameraSelection.selectVideo(2, true, true, false));
        assertEquals(17, IpkCameraSelection.selectVideo(4, true, true, false));
    }

    @Test public void rearviewCanStayOnWhenOnlyMainScreenUsesTheSignalCamera() {
        for (int mode : new int[]{1, 2, 4})
            assertEquals(17, IpkCameraSelection.selectVideo(mode, false, true, false));
        assertEquals(-1, IpkCameraSelection.selectVideo(4, true, false, false));
        assertEquals(-1, IpkCameraSelection.selectVideo(1, false, false, false));
    }

    @Test public void reverseOemAndTileViewReleaseAllIpkSources() {
        for (int mode : new int[]{1, 2, 3, 4})
            assertEquals(-1, IpkCameraSelection.selectVideo(mode, true, true, true));
        for (int mode : new int[]{-2, -1, 0, 3})
            assertEquals(-1, IpkCameraSelection.selectVideo(mode, true, true, false));
    }

    @Test public void aNewSignalAfterContinuousRearviewGetsItsOwnRoutingBudget() {
        assertTrue(IpkCameraSelection.startsNewSession(-1, 17));
        assertTrue(IpkCameraSelection.startsNewSession(17, 16));
        assertTrue(IpkCameraSelection.startsNewSession(14, 17));
        assertFalse(IpkCameraSelection.startsNewSession(16, 14));
        assertFalse(IpkCameraSelection.startsNewSession(17, 17));
    }
}
