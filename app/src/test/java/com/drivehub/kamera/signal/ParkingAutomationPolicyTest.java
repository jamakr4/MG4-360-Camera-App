package com.drivehub.kamera.signal;

import org.junit.Test;
import static org.junit.Assert.*;

public class ParkingAutomationPolicyTest {
    private ParkingAutomationPolicy automatic(boolean close) {
        ParkingAutomationPolicy p = new ParkingAutomationPolicy();
        p.configure(close, 25); p.begin("first"); assertTrue(p.shown("first"));
        return p;
    }

    @Test public void everyDirectParkToDriveCanOpenButRepeatedDriveCannot() {
        ParkingAutomationPolicy p = new ParkingAutomationPolicy();
        assertFalse(p.onGear(true, true, 4, false)); // Service starts in D.
        assertFalse(p.onGear(true, true, 1, false));
        assertTrue(p.onGear(true, true, 4, false));
        p.begin("first"); p.shown("first"); p.cancel();
        assertFalse(p.onGear(true, true, 4, false));
        p.onGear(true, true, 1, false);
        assertTrue(p.onGear(true, true, 4, false));
    }

    @Test public void neutralReverseInvalidAndVisibleManualViewsNeverOpenOnDrive() {
        for (int previous : new int[]{0, 2, 3, -1}) {
            ParkingAutomationPolicy p = new ParkingAutomationPolicy();
            p.onGear(true, true, 1, false); p.onGear(true, true, previous, false);
            assertFalse(p.onGear(true, true, 4, false));
        }
        ParkingAutomationPolicy p = new ParkingAutomationPolicy();
        p.onGear(true, true, 1, true);
        assertFalse(p.onGear(true, true, 4, true));
        assertNull(p.session()); assertFalse(p.onSpeed(100));
    }

    @Test public void enablingTheOptionOrCameraAccessWhileAlreadyInDriveDoesNotOpen() {
        ParkingAutomationPolicy p = new ParkingAutomationPolicy();
        p.onGear(false, false, 1, false);
        assertFalse(p.onGear(false, false, 4, false));
        assertFalse(p.onGear(true, true, 4, false));
        p.onGear(true, true, 1, false);
        assertTrue(p.onGear(true, true, 4, false));
    }

    @Test public void masterCloseIsIndependentOfOpeningAndBoundaryIsInclusive() {
        ParkingAutomationPolicy p = automatic(false);
        assertFalse(p.shouldPollSpeed()); assertFalse(p.onSpeed(0)); assertFalse(p.onSpeed(80));
        p.configure(true, 25); assertTrue(p.shouldPollSpeed());
        assertFalse(p.onSpeed(0)); assertFalse(p.onSpeed(24.99f));
        assertTrue(p.onSpeed(25)); assertTrue(p.canClose("first"));
    }

    @Test public void highSpeedOpeningWaitsUntilBelowThenAbove() {
        ParkingAutomationPolicy p = automatic(true);
        assertFalse(p.onSpeed(80)); assertFalse(p.onSpeed(25));
        assertFalse(p.onSpeed(24)); assertTrue(p.onSpeed(25));
    }

    @Test public void invalidSpeedNeverArmsOrCloses() {
        ParkingAutomationPolicy p = automatic(true);
        for (float speed : new float[]{Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, -1})
            assertFalse(p.onSpeed(speed));
        assertFalse(p.onSpeed(50)); assertFalse(p.onSpeed(0));
        assertFalse(p.onSpeed(Float.NaN)); assertFalse(p.onSpeed(-1));
        assertTrue(p.onSpeed(25));
    }

    @Test public void settingsChangesRestartTheBelowAboveObservation() {
        ParkingAutomationPolicy p = automatic(true); p.onSpeed(0);
        p.configure(true, 30); assertFalse(p.onSpeed(40));
        p.onSpeed(20); assertTrue(p.onSpeed(30));
        p.configure(false, 30); assertFalse(p.canClose("first")); assertFalse(p.shouldPollSpeed());
        p.configure(true, 30); assertFalse(p.onSpeed(40));
        p.onSpeed(0); assertTrue(p.onSpeed(30));
    }

    @Test public void unchangedSettingsDoNotResetObservation() {
        ParkingAutomationPolicy p = automatic(true); p.onSpeed(0);
        p.configure(true, 25); assertTrue(p.onSpeed(25));
    }

    @Test public void gearChangesPreserveOneSessionAndRecreationPreservesObservation() {
        ParkingAutomationPolicy p = automatic(true); p.onSpeed(0);
        for (int gear : new int[]{2, 4, 1, 4}) assertFalse(p.onGear(true, true, gear, true));
        p.recreating("first"); assertFalse(p.shouldPollSpeed()); assertFalse(p.onSpeed(25));
        assertTrue(p.shown("first")); assertTrue(p.onSpeed(25));
    }

    @Test public void onlyAcknowledgedCurrentSessionCanCloseAndStaleRequestsAreRejected() {
        ParkingAutomationPolicy p = automatic(true); p.onSpeed(0); assertTrue(p.onSpeed(25));
        assertFalse(p.canClose("other")); p.cancel();
        assertFalse(p.canClose("first")); assertFalse(p.shown("first")); assertFalse(p.shouldPollSpeed());
        p.begin("second"); assertFalse(p.onSpeed(0)); assertFalse(p.onSpeed(40));
        p.shown("second"); assertFalse(p.canClose("first"));
        assertFalse(p.onSpeed(40)); p.onSpeed(0); assertTrue(p.onSpeed(40));
        assertTrue(p.canClose("second")); assertFalse(p.canClose("first"));
    }

    @Test public void lossOfCameraAccessDiscardsTheSessionAndPendingClose() {
        ParkingAutomationPolicy p = automatic(true); p.onSpeed(0); p.onSpeed(25);
        assertFalse(p.onGear(false, true, 4, false));
        assertNull(p.session()); assertFalse(p.canClose("first")); assertFalse(p.shouldPollSpeed());
    }

    @Test public void automaticCloseDismissesExistingReversePolicyUntilANewReverse() {
        ReverseLaunchPolicy reverse = new ReverseLaunchPolicy();
        reverse.update(false, 2, false, false, 0);
        assertTrue(reverse.update(false, 2, false, false, 300).showCamera);
        reverse.update(false, 2, true, false, 400);
        ParkingAutomationPolicy p = automatic(true); p.onSpeed(0); assertTrue(p.onSpeed(25));
        p.cancel(); reverse.dismiss();
        assertFalse(reverse.update(false, 2, false, false, 1000).showCamera);
        reverse.update(false, 4, false, false, 1100);
        reverse.update(false, 2, false, false, 1200);
        assertTrue(reverse.update(false, 2, false, false, 1500).showCamera);
    }
}
