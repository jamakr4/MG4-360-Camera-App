package com.drivehub.kamera.dashcam;
import org.junit.Test;
import static org.junit.Assert.*;
public class OemPausePolicyTest {
    @Test public void activeFactoryViewNeverTimesOut() {
        assertFalse(OemPausePolicy.shouldResume(true, false, 100_000, 1, 1000, 2000));
        assertFalse(OemPausePolicy.shouldResume(true, true, 100_000, 1, 1000, 2000));
    }
    @Test public void unknownForegroundHasBoundedFallback() {
        assertFalse(OemPausePolicy.shouldResume(null, false, 59_000, 1, 1000, 2000));
        assertTrue(OemPausePolicy.shouldResume(null, false, 60_001, 1, 1000, 2000));
    }
    @Test public void shortVisitsLeaveTimeForOemRetry() {
        assertFalse(OemPausePolicy.shouldResume(false, false, 8000, 1, 1000, 8000));
        assertTrue(OemPausePolicy.shouldResume(false, false, 9000, 1, 1000, 8000));
        assertTrue(OemPausePolicy.shouldResume(false, false, 3000, 1, 1000, 2000));
    }
    @Test public void broadcastStartWaitsForStopEvenWithAnotherAppInForeground() {
        assertFalse(OemPausePolicy.shouldResume(false, true, 9000, 1, 1000, 8000));
        assertFalse(OemPausePolicy.shouldResume(false, true, 3000, 1, 1000, 2000));
        assertFalse(OemPausePolicy.shouldResume(false, true, 60_000, 1, 1000, 8000));
    }
    @Test public void missingStopBroadcastStillHasSixtySecondFallback() {
        assertTrue(OemPausePolicy.shouldResume(false, true, 60_001, 1, 1000, 8000));
        assertFalse(OemPausePolicy.shouldResume(null, true, 60_000, 1, 1000, 8000));
        assertTrue(OemPausePolicy.shouldResume(null, true, 60_001, 1, 1000, 8000));
    }
    @Test public void broadcastStartWithoutTimestampCannotExpire() {
        assertFalse(OemPausePolicy.shouldResume(false, true, 100_000, 0, 1000, 8000));
        assertFalse(OemPausePolicy.shouldResume(null, true, 100_000, 0, 1000, 8000));
    }
}
