package com.drivehub.kamera.signal;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class LaneChangeModeTest {

    @Test
    public void disabledModeAlwaysAllowsSignalCamera() {
        assertTrue(LaneChangeMode.allowsSignalCamera(false, 0, 15));
    }

    @Test
    public void enabledModeBlocksSpeedBelowThreshold() {
        assertFalse(LaneChangeMode.allowsSignalCamera(true, 14, 15));
    }

    @Test
    public void enabledModeAllowsSpeedAtThreshold() {
        assertTrue(LaneChangeMode.allowsSignalCamera(true, 15, 15));
    }

    @Test
    public void enabledModeAllowsSpeedAboveThreshold() {
        assertTrue(LaneChangeMode.allowsSignalCamera(true, 80, 15));
    }
}
