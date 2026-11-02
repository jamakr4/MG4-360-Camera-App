package com.drivehub.kamera.camera;

import org.junit.Test;
import static org.junit.Assert.*;

public class IpkRoutePolicyTest {
    @Test public void cameraOnlyUsesTheTwoAndroidPages() {
        for (int tab = 0; tab <= 7; tab++)
            assertEquals(tab == 1 || tab == 2, IpkRoutePolicy.supportsCamera(tab));
        assertFalse(IpkRoutePolicy.supportsCamera(null));
    }

    @Test public void autoRoutingYieldsToCallsWarningsAssistanceAndUnknownState() {
        IpkRoutePolicy policy = new IpkRoutePolicy();
        assertTrue(policy.shouldRequest(0, true, 0));
        assertTrue(policy.shouldRequest(5, true, 0));
        assertFalse(policy.shouldRequest(0, false, 0));
        assertFalse(policy.shouldRequest(null, true, 0));
        for (int tab : new int[]{1, 2, 3, 4, 6, 7})
            assertFalse(policy.shouldRequest(tab, true, 0));
    }

    @Test public void retriesAreSpacedAndBoundedEvenIfTabNeverChanges() {
        IpkRoutePolicy policy = new IpkRoutePolicy();
        policy.requested(0);
        assertFalse(policy.shouldRequest(0, true, 1499));
        assertTrue(policy.shouldRequest(0, true, 1500));
        policy.requested(1500);
        assertFalse(policy.shouldRequest(0, true, 10000));
    }

    @Test public void aConfirmedPageStopsRequestsIfDriverLaterChangesTheirMind() {
        IpkRoutePolicy policy = new IpkRoutePolicy();
        policy.requested(0);
        policy.observeTab(1);
        assertFalse(policy.shouldRequest(0, true, 5000));
        assertFalse(policy.shouldRequest(5, true, 5000));
    }

    @Test public void placementAccountsForConstraintMarginsAndFitsSmallDisplays() {
        assertArrayEquals(new int[]{482, 126, 260}, IpkRoutePolicy.bounds(800, 480));
        assertArrayEquals(new int[]{10, 0, 180}, IpkRoutePolicy.bounds(200, 180));
    }
}
