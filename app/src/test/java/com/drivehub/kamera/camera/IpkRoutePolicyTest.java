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
        policy.completed(IpkRoutePolicy.RequestResult.SENT, 0);
        assertFalse(policy.shouldRequest(0, true, 1499));
        assertTrue(policy.shouldRequest(0, true, 1500));
        policy.completed(IpkRoutePolicy.RequestResult.FAILED, 1500);
        assertFalse(policy.shouldRequest(0, true, 10000));
    }

    @Test public void aConfirmedPageStopsRequestsIfDriverLaterChangesTheirMind() {
        IpkRoutePolicy policy = new IpkRoutePolicy();
        policy.completed(IpkRoutePolicy.RequestResult.SENT, 0);
        policy.observeTab(1);
        assertFalse(policy.shouldRequest(0, true, 5000));
        assertFalse(policy.shouldRequest(5, true, 5000));
    }

    @Test public void placementAccountsForConstraintMarginsAndFitsSmallDisplays() {
        assertArrayEquals(new int[]{482, 126, 260}, IpkRoutePolicy.bounds(800, 480));
        assertArrayEquals(new int[]{10, 0, 180}, IpkRoutePolicy.bounds(200, 180));
    }

    @Test public void connectingDoesNotSpendTheTwoRealAttempts() {
        IpkRoutePolicy policy = new IpkRoutePolicy();
        for (int i = 0; i < 20; i++) {
            policy.completed(IpkRoutePolicy.RequestResult.WAITING, i * 100);
            assertTrue(policy.shouldRequest(0, true, i * 100));
        }
        assertEquals(0, policy.requests());
        policy.completed(IpkRoutePolicy.RequestResult.SENT, 2000);
        assertFalse(policy.shouldRequest(0, true, 3499));
        assertTrue(policy.shouldRequest(0, true, 3500));
        policy.completed(IpkRoutePolicy.RequestResult.SENT, 3500);
        assertFalse(policy.shouldRequest(0, true, 10000));
    }

    @Test public void cancelledWorkFromAnOldSignalDoesNotSpendANewSessionAttempt() {
        IpkRoutePolicy policy = new IpkRoutePolicy();
        policy.completed(IpkRoutePolicy.RequestResult.CANCELLED, 1000);
        assertEquals(0, policy.requests());
        assertTrue(policy.shouldRequest(0, true, 1000));
    }
}
