package com.drivehub.kamera.signal;

import org.junit.Test;
import static org.junit.Assert.*;
import static com.drivehub.kamera.signal.DigitalRearviewPolicy.*;

public class DigitalRearviewPolicyTest {
    @Test public void onlyNormalDisableOrDeselectRequestsAnIpkFadeOut() {
        assertTrue(selectOutputs(false, DISPLAY_IPK, false, false, false).fadeIpkExit);
        assertTrue(selectOutputs(true, DISPLAY_INFOTAINMENT, false, false, false).fadeIpkExit);
        for (int target : new int[]{DISPLAY_IPK, DISPLAY_BOTH}) {
            assertFalse(selectOutputs(true, target, false, false, false).fadeIpkExit);
            assertFalse(selectOutputs(true, target, true, true, false).fadeIpkExit);
        }
    }

    @Test public void vehicleBlockersAlwaysOverrideARequestedFadeOut() {
        for (int target = DISPLAY_INFOTAINMENT; target <= DISPLAY_BOTH; ++target)
            for (boolean enabled : new boolean[]{false, true})
                assertFalse(selectOutputs(enabled, target, false, true, true).fadeIpkExit);
    }

    @Test public void openingAppUiHidesOnlyTheInfotainmentMirror() {
        for (int target : new int[]{DISPLAY_BOTH, DISPLAY_IPK}) {
            Outputs outputs = selectOutputs(true, target, false, true, false);
            assertFalse(outputs.infotainment);
            assertTrue(outputs.ipk);
        }
        Outputs closed = selectOutputs(true, DISPLAY_BOTH, false, false, false);
        assertTrue(closed.infotainment);
        assertTrue(closed.ipk);
    }

    @Test public void externalOffStillStopsIpkWhileTheAppIsOpen() {
        Outputs outputs = selectOutputs(false, DISPLAY_BOTH, true, true, false);
        assertFalse(outputs.infotainment);
        assertFalse(outputs.ipk);
    }

    @Test public void vehicleBlockersStillStopBothOutputsWhileTheAppIsOpen() {
        Outputs outputs = selectOutputs(true, DISPLAY_BOTH, false, true, true);
        assertFalse(outputs.infotainment);
        assertFalse(outputs.ipk);
    }

    @Test public void outputTargetsSelectTheRequestedDisplays() {
        assertOutputs(DISPLAY_INFOTAINMENT, true, false);
        assertOutputs(DISPLAY_IPK, false, true);
        assertOutputs(DISPLAY_BOTH, true, true);
    }

    @Test public void tappingTheMainMirrorLeavesTheIpkMirrorVisible() {
        assertFalse(showOnInfotainment(true, DISPLAY_BOTH, true, false));
        assertTrue(showOnIpk(true, DISPLAY_BOTH, false));
        assertTrue(showOnInfotainment(true, DISPLAY_BOTH, false, false));
    }

    @Test public void externalOffAndCloseStopBothOutputsEvenDuringTempHide() {
        for (int target = DISPLAY_INFOTAINMENT; target <= DISPLAY_BOTH; target++) {
            assertFalse(showOnInfotainment(false, target, true, false));
            assertFalse(showOnInfotainment(false, target, false, false));
            assertFalse(showOnIpk(false, target, false));
        }
    }

    @Test public void oemReverseAndOtherBlockersReleaseBothOutputs() {
        assertFalse(showOnInfotainment(true, DISPLAY_BOTH, false, true));
        assertFalse(showOnIpk(true, DISPLAY_BOTH, true));
    }

    @Test public void unknownTargetPreservesTheExistingInfotainmentBehavior() {
        assertOutputs(-1, true, false);
        assertOutputs(99, true, false);
    }

    private static void assertOutputs(int target, boolean main, boolean ipk) {
        assertEquals(main, showOnInfotainment(true, target, false, false));
        assertEquals(ipk, showOnIpk(true, target, false));
    }
}
