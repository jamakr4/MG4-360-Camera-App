package com.drivehub.kamera.dashcam;

import org.junit.Test;
import static org.junit.Assert.*;

public class SurveillanceSessionTest {
    private SurveillanceSession session(boolean enabled) {
        return new SurveillanceSession(120_000, 3, enabled, "recycle", "/surveillance", "start-id");
    }

    @Test public void processRecoveryKeepsOriginalDeadlineAndConfiguration() {
        SurveillanceSession session = session(true);
        assertTrue(session.canResume(3, 119_999));
        assertFalse(session.canResume(3, 120_000));
        assertEquals(120_000, session.deadline);
        assertEquals("/surveillance", session.directory);
        assertEquals("recycle", session.policy);
        assertEquals("start-id", session.requestId);
    }
    @Test public void rebootOrUnknownBootCannotResume() {
        assertFalse(session(true).canResume(4, 1));
        assertFalse(session(true).canResume(-1, 1));
    }
    @Test public void stopWinsAgainstLaterTimeoutAndFailure() {
        SurveillanceSession session = session(true);
        session.stop("requested", false, false);
        session.stop("timeout", false, false);
        session.stop("recording_failed", true, false);
        assertEquals("requested", session.stopReason());
        assertFalse(session.failed());
        assertFalse(session.canResume(3, 1));
        assertTrue(session.originalEnabled);
    }
    @Test public void failureWinsAgainstDuplicateStop() {
        SurveillanceSession session = session(false);
        session.stop("storage_unavailable", true, false);
        session.stop("requested", false, false);
        assertEquals("storage_unavailable", session.stopReason());
        assertTrue(session.failed());
        assertFalse(session.originalEnabled);
    }
    @Test public void shutdownAlwaysDefersRestartEvenAfterEarlierStop() {
        SurveillanceSession session = session(true);
        session.stop("requested", false, false);
        session.stop("shutdown", false, true);
        assertEquals("requested", session.stopReason());
        assertTrue(session.deferRestart());
    }
}
