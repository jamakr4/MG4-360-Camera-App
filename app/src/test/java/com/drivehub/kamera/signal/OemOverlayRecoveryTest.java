package com.drivehub.kamera.signal;

import org.junit.Test;
import static org.junit.Assert.*;

public class OemOverlayRecoveryTest {
    @Test public void earlyStopWaitsForActualExitWithoutAnyLampOrGearEvent() {
        OemOverlayRecovery recovery = new OemOverlayRecovery();
        recovery.start();
        assertFalse(recovery.canRestore(true, false, false, 1000));
        assertFalse(recovery.canRestore(true, false, true, 2000));
        assertFalse(recovery.canRestore(false, false, true, 3000));
        assertFalse(recovery.canRestore(false, false, true, 3249));
        assertTrue(recovery.canRestore(false, false, true, 3250));
    }

    @Test public void reappearingOemViewRestartsTheAbsenceInterval() {
        OemOverlayRecovery recovery = new OemOverlayRecovery();
        recovery.start();
        assertFalse(recovery.canRestore(false, false, false, 0));
        assertFalse(recovery.canRestore(true, false, false, 200));
        assertFalse(recovery.canRestore(false, false, false, 300));
        assertFalse(recovery.canRestore(false, false, false, 500));
        assertTrue(recovery.canRestore(false, false, false, 550));
    }

    @Test public void unknownForegroundNeedsAnIndependentDestroySignal() {
        OemOverlayRecovery recovery = new OemOverlayRecovery();
        recovery.start();
        assertFalse(recovery.canRestore(null, false, false, 1000));
        assertFalse(recovery.canRestore(null, false, false, 100000));
        assertFalse(recovery.canRestore(null, false, true, 101000));
        assertTrue(recovery.canRestore(null, false, true, 101250));
    }

    @Test public void aNewOemStartOrServiceStopCancelsOldRecovery() {
        OemOverlayRecovery recovery = new OemOverlayRecovery();
        recovery.start();
        assertFalse(recovery.canRestore(false, false, true, 0));
        recovery.cancel();
        assertFalse(recovery.isPending());
        assertFalse(recovery.canRestore(false, false, true, 1000));
        recovery.start();
        assertFalse(recovery.canRestore(false, true, true, 2000));
        assertFalse(recovery.canRestore(false, false, true, 3000));
        assertTrue(recovery.canRestore(false, false, true, 3250));
    }
}
