package com.drivehub.kamera.signal;
import org.junit.Test;
import static org.junit.Assert.*;
public class OemPresencePolicyTest {
    @Test public void stickyStartExpiresOnlyAfterConfirmedAbsence() {
        OemPresencePolicy p = new OemPresencePolicy();
        assertTrue(p.busy(false, "Start", 0));
        assertTrue(p.busy(false, "Start", 999));
        assertFalse(p.busy(false, "Start", 1000));
    }
    @Test public void realWindowRemainsBusyAndRestartsAbsenceConfirmation() {
        OemPresencePolicy p = new OemPresencePolicy();
        p.busy(false, "Start", 0);
        assertTrue(p.busy(true, "Start", 2000));
        assertTrue(p.busy(false, "Start", 3000));
        assertFalse(p.busy(false, "Start", 4000));
    }
    @Test public void unknownForegroundIsNeverConfirmedAbsence() {
        OemPresencePolicy p = new OemPresencePolicy();
        p.busy(false, "Start", 0);
        assertTrue(p.busy(null, "Start", 5000));
        assertTrue(p.busy(false, "Start", 6000));
    }
    @Test public void teardownLifecycleChangeStartsItsOwnGrace() {
        OemPresencePolicy p = new OemPresencePolicy();
        p.busy(false, "Start", 0);
        assertTrue(p.busy(false, "PauseStart", 1000));
        assertTrue(p.busy(false, "PauseStart", 1999));
        assertFalse(p.busy(false, "DestroyEnd", 2000));
    }
}
