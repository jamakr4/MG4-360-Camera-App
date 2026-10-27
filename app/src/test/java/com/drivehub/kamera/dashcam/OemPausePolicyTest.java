package com.drivehub.kamera.dashcam;
import org.junit.Test;
import static org.junit.Assert.*;
public class OemPausePolicyTest {
    @Test public void activeFactoryViewNeverTimesOut() {
        assertFalse(OemPausePolicy.shouldResume(true, 100_000, 1, 1000, 2000));
    }
    @Test public void unknownForegroundHasBoundedFallback() {
        assertFalse(OemPausePolicy.shouldResume(null, 59_000, 1, 1000, 2000));
        assertTrue(OemPausePolicy.shouldResume(null, 60_001, 1, 1000, 2000));
    }
    @Test public void shortVisitsLeaveTimeForOemRetry() {
        assertFalse(OemPausePolicy.shouldResume(false, 8000, 1, 1000, 8000));
        assertTrue(OemPausePolicy.shouldResume(false, 9000, 1, 1000, 8000));
        assertTrue(OemPausePolicy.shouldResume(false, 3000, 1, 1000, 2000));
    }
}
