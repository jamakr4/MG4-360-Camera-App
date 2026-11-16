package com.drivehub.kamera.signal;

import org.junit.Test;
import static org.junit.Assert.*;

public class ParkClosePolicyTest {
    @Test public void eachDrivingGearToParkClosesExactlyOnce() {
        for (int gear : new int[]{2, 3, 4}) {
            ParkClosePolicy p = new ParkClosePolicy();
            assertNull(p.onGear(gear, "view"));
            ParkClosePolicy.Request request = p.onGear(1, "view");
            assertNotNull(request);
            assertNull(p.onGear(1, "view"));
            assertTrue(p.consume("view", request.transition));
            assertFalse(p.consume("view", request.transition));
            assertNull(p.onGear(1, "view"));
        }
    }

    @Test public void initialParkAndInvalidSamplesNeverInventAnEdge() {
        ParkClosePolicy p = new ParkClosePolicy();
        assertNull(p.onGear(1, "view"));
        for (int invalid : new int[]{0, -1, 5, Integer.MAX_VALUE}) {
            p.onGear(4, "view");
            assertNull(p.onGear(invalid, "view"));
            assertNull(p.onGear(1, "view"));
        }
        p.onGear(4, "view");
        assertNotNull(p.onGear(1, "view"));
        p.onGear(0, "view");
        assertNull(p.pendingFor("view"));
    }

    @Test public void disabledSamplesAreConsumedAndEnablingInParkDoesNotClose() {
        ParkClosePolicy p = new ParkClosePolicy();
        p.configure(false);
        p.onGear(4, "view");
        assertNull(p.onGear(1, "view"));
        p.configure(true);
        assertNull(p.onGear(1, "view"));
        p.onGear(4, "view");
        ParkClosePolicy.Request request = p.onGear(1, "view");
        p.configure(false);
        p.configure(true);
        assertFalse(p.consume("view", request.transition));
        assertNull(p.onGear(1, "view"));
    }

    @Test public void backgroundTransitionsAreNeverReplayedOnOpening() {
        ParkClosePolicy p = new ParkClosePolicy();
        p.onGear(4, null);
        assertNull(p.onGear(1, null));
        p.viewStarted("manual");
        assertNull(p.pendingFor("manual"));
        assertNull(p.onGear(1, "manual"));
    }

    @Test public void onlyCurrentViewAndCurrentTransitionCanConsumeTheRequest() {
        ParkClosePolicy p = new ParkClosePolicy();
        p.onGear(4, "first");
        ParkClosePolicy.Request old = p.onGear(1, "first");
        assertFalse(p.consume("other", old.transition));
        p.onGear(4, "first");
        assertFalse(p.consume("first", old.transition));
        ParkClosePolicy.Request next = p.onGear(1, "first");
        assertNotEquals(old.transition, next.transition);
        assertFalse(p.consume("first", old.transition));
        assertTrue(p.consume("first", next.transition));
    }

    @Test public void recreationKeepsPendingButClosingOrBackgroundingInvalidatesIt() {
        ParkClosePolicy p = new ParkClosePolicy();
        p.onGear(4, "first");
        ParkClosePolicy.Request request = p.onGear(1, "first");
        p.viewStarted("first"); // Same foreground identity after configuration recreation.
        assertSame(request, p.pendingFor("first"));
        p.viewStopped("first");
        p.viewStarted("second");
        assertFalse(p.consume("second", request.transition));
        assertFalse(p.consume("first", request.transition));
        assertNull(p.onGear(1, "second"));
    }

    @Test public void newViewAndServiceRestartDiscardOutstandingRequests() {
        ParkClosePolicy p = new ParkClosePolicy();
        p.onGear(4, "first");
        ParkClosePolicy.Request old = p.onGear(1, "first");
        p.viewStarted("second");
        assertFalse(p.consume("first", old.transition));
        p.onGear(4, "second");
        ParkClosePolicy.Request next = p.onGear(1, "second");
        p.reset();
        assertFalse(p.consume("second", next.transition));
        assertNull(p.onGear(1, "second"));
    }

    @Test public void oldServiceRequestsCannotConsumeANewServicesTransition() {
        ParkClosePolicy oldService = new ParkClosePolicy();
        oldService.onGear(4, "same-view");
        ParkClosePolicy.Request old = oldService.onGear(1, "same-view");
        ParkClosePolicy newService = new ParkClosePolicy();
        newService.onGear(4, "same-view");
        ParkClosePolicy.Request next = newService.onGear(1, "same-view");
        assertFalse(newService.consume("same-view", old.transition));
        assertTrue(newService.consume("same-view", next.transition));
    }
}
