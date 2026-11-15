package com.drivehub.kamera.camera;

import org.junit.Test;
import static org.junit.Assert.*;

public class CameraHandoffStateTest {
    @Test public void releaseWaitsForRealDevicesAndInflightOpens() {
        CameraHandoffState state = new CameraHandoffState();
        long token = state.transition(true);
        assertNull(state.evaluate(true, token, 1, 0, true, true, false));
        assertNull(state.evaluate(true, token, 0, 1, true, true, false));
        assertNull(state.evaluate(true, token, 0, 0, false, true, false));
        assertEquals("released", state.evaluate(true, token, 0, 0, true, false, false));
    }
    @Test public void resumeWaitsForEveryRequestedOutputAndActualOwnership() {
        CameraHandoffState state = new CameraHandoffState();
        long token = state.transition(false);
        state.require("recording", true);
        state.require("preview", true);
        state.ready("preview", true, token);
        assertNull(state.evaluate(false, token, 4, 0, false, true, false));
        state.ready("recording", true, token);
        assertNull(state.evaluate(false, token, 0, 0, false, true, false));
        assertEquals("claimed", state.evaluate(false, token, 4, 0, false, true, false));
    }
    @Test public void noDemandIsIdleOnlyAfterPolicyHasRun() {
        CameraHandoffState state = new CameraHandoffState();
        assertNull(state.evaluate(false, 0, 0, 0, false, false, false));
        assertEquals("idle", state.evaluate(false, 0, 0, 0, false, true, false));
        state.require("disabled-output", true);
        state.require("disabled-output", false);
        assertEquals("idle", state.evaluate(false, 0, 0, 0, false, true, false));
    }
    @Test public void oemOwnershipSurvivesExternalResume() {
        CameraHandoffState state = new CameraHandoffState();
        state.transition(true);
        long token = state.transition(false);
        assertEquals("blocked", state.evaluate(false, token, 0, 0, true, true, false));
    }
    @Test public void duplicateRequestsKeepConfirmedStartsButOldStartsCannotWin() {
        CameraHandoffState state = new CameraHandoffState();
        state.require("preview", true);
        state.ready("preview", true, 0);
        assertEquals(0, state.transition(false));
        assertEquals("claimed", state.evaluate(false, 0, 1, 0, false, true, false));
        long paused = state.transition(true);
        assertEquals(paused, state.transition(true));
        long resumed = state.transition(false);
        state.ready("preview", true, 0);
        assertNull(state.evaluate(false, resumed, 1, 0, false, true, false));
        assertEquals("error", state.evaluate(true, paused, 0, 0, false, true, false));
        state.ready("preview", true, resumed);
        assertEquals("claimed", state.evaluate(false, resumed, 1, 0, false, true, false));
    }
    @Test public void timeoutDoesNotRemovePauseOrPretendDevicesAreReleased() {
        CameraHandoffState state = new CameraHandoffState();
        long token = state.transition(true);
        assertEquals("timeout", state.evaluate(true, token, 1, 0, true, true, true));
        assertEquals(token, state.transition(true));
        assertEquals("released", state.evaluate(true, token, 0, 0, true, true, false));
    }
    @Test public void failedRequiredOutputDoesNotBecomeSuccessfulIdle() {
        CameraHandoffState state = new CameraHandoffState();
        state.require("recording", true);
        state.failed("recording", 0);
        assertEquals("error", state.evaluate(false, 0, 1, 0, false, true, false));
        state.transition(true);
        long resumed = state.transition(false);
        state.failed("recording", 0);
        assertNull(state.evaluate(false, resumed, 0, 0, false, true, false));
        state.ready("recording", true, resumed);
        assertEquals("claimed", state.evaluate(false, resumed, 4, 0, false, true, false));
    }
    @Test public void pauseSurvivesProcessRestartButNotAnotherBoot() {
        assertTrue(CameraHandoffState.restorePause(true, 42, 42));
        assertFalse(CameraHandoffState.restorePause(true, 42, 43));
        assertFalse(CameraHandoffState.restorePause(false, 42, 42));
        assertTrue(CameraHandoffState.restorePause(true, 42, -1));
    }
}
