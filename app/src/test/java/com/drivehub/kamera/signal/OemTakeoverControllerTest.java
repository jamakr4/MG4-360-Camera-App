package com.drivehub.kamera.signal;

import org.junit.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.*;

public class OemTakeoverControllerTest {
    static final class Fake implements OemTakeoverController.Ports {
        OemTakeoverController.Journal disk = new OemTakeoverController.Journal();
        final List<String> events = new ArrayList<>();
        int receiver, held = 15, opens, closes, starts;
        long age;
        boolean data = true, deny, ignoreDisable, failSave, releaseFails, released, yielded;
        boolean available = true, ignoreEnable, externalPause;
        public OemTakeoverController.Journal load() { return disk.copy(); }
        public void save(OemTakeoverController.Journal j) {
            if (failSave) throw new IllegalStateException("disk full");
            disk = j.copy(); events.add("save");
        }
        public int receiverState() { return receiver; }
        public boolean receiverAvailable() { return available; }
        public void receiverState(int value) {
            events.add("receiver=" + value);
            if (deny) throw new SecurityException("permission");
            if (!(ignoreDisable && value == 2) && !(ignoreEnable && value == 1)) receiver = value;
        }
        public void startDataService() { starts++; events.add("data-start"); }
        public boolean dataServiceRunning() { return data; }
        public void yieldCameras(boolean value) { yielded=value; events.add("yield=" + value); }
        public void releaseCameras() {
            events.add("release");
            if (releaseFails) throw new IllegalStateException("held fd");
            released = true;
        }
        public void acquireCameras() { events.add("acquire"); released = false; }
        public int heldMask() { return held; }
        public long rearFrameAgeMs() { return age; }
        public boolean externallyPaused() { return externalPause; }
        public void closeOem() { closes++; }
        public void openOem() { opens++; events.add("open"); }
        public void log(String message) { }
    }

    @Test public void externalHandoffInReverseDoesNotTriggerOemFallbackAndResumeGetsANewDeadline() {
        Fake p = new Fake(); OemTakeoverController c = new OemTakeoverController(p);
        c.tick(false, 2, false, 0);
        assertEquals(OemTakeoverController.State.EXCLUSIVE, c.snapshot().state);
        p.externalPause = true; p.held = 0; p.age = -1;
        c.tick(false, 2, false, 100);
        c.tick(false, 2, true, 10_000);
        assertFalse(p.disk.fallback); assertEquals(0, p.opens); assertEquals(0, p.closes);
        assertEquals(2, p.receiver);
        p.externalPause = false;
        c.tick(false, 2, false, 10_001);
        assertFalse(p.disk.fallback);
        p.held = 15; p.age = 0;
        c.tick(false, 2, false, 10_500);
        assertEquals(OemTakeoverController.State.EXCLUSIVE, c.snapshot().state);
        assertFalse(p.disk.fallback);
    }

    @Test public void externalHandoffSuspendsAcquisitionTimeoutButDoesNotPreventExplicitOemRestore() {
        Fake p = new Fake(); p.held = 0; p.age = -1;
        OemTakeoverController c = new OemTakeoverController(p);
        c.tick(false, 1, false, 0);
        p.externalPause = true;
        c.tick(false, 1, false, 100);
        c.tick(false, 1, false, 20_000);
        assertFalse(p.disk.fault);
        p.externalPause = false; p.held = 15; p.age = 0;
        c.tick(false, 1, false, 20_001);
        assertEquals(OemTakeoverController.State.EXCLUSIVE, c.snapshot().state);
        p.externalPause = true;
        assertTrue(c.restoreOem(1, 21_000));
        assertEquals(0, p.receiver);
        assertFalse(p.disk.ownsReceiver);
    }

    @Test public void explicitRestoreRepairsRepeatedBlocksWithoutChangingAnAlreadyEnabledPreference() {
        Fake ports = new Fake();
        OemTakeoverController controller = new OemTakeoverController(ports);
        controller.tick(true, 1, false, 0);
        for (int attempt = 1; attempt <= 2; attempt++) {
            ports.receiver = 2;
            ports.released = false;
            ports.events.clear();
            assertTrue(controller.restoreOem(1, attempt * 100));
            assertEquals(1, ports.receiver);
            assertTrue(ports.released);
            assertFalse(ports.disk.ownsReceiver);
            assertTrue(ports.events.indexOf("release") < ports.events.indexOf("receiver=1"));
        }
    }

    @Test public void explicitRestoreReportsFailureUntilReceiverEnableIsVerified() {
        Fake ports = new Fake();
        ports.receiver = 2;
        ports.ignoreEnable = true;
        OemTakeoverController controller = new OemTakeoverController(ports);
        assertFalse(controller.restoreOem(1, 0));
        assertEquals(2, ports.receiver);
        ports.ignoreEnable = false;
        assertTrue(controller.restoreOem(1, 100));
        ports.available = false;
        assertFalse(controller.restoreOem(1, 200));
    }

    @Test public void reinstallWithDefaultOemEnabledRepairsOrphanedReceiverAfterReleasingCameras() {
        Fake p = new Fake(); p.receiver = 2; // Android retains this while uninstall removes disk.
        OemTakeoverController c = new OemTakeoverController(p);
        c.tick(true, 2, false, 0);
        assertEquals(1, p.receiver);
        assertEquals(OemTakeoverController.State.OEM, c.snapshot().state);
        assertFalse(p.disk.ownsReceiver); assertEquals(1, p.opens); assertEquals(0, p.starts);
        assertTrue(p.events.indexOf("save") < p.events.indexOf("receiver=1"));
        assertTrue(p.events.indexOf("release") < p.events.indexOf("receiver=1"));
        p.events.clear(); c.tick(true, 2, false, 100);
        assertFalse(p.events.contains("receiver=1")); assertEquals(1, p.opens);
    }

    @Test public void recoveryOfAnOrphanIsVerifiedAndCanBeRetriedWithoutTogglingThePreference() {
        Fake p = new Fake(); p.receiver = 2; p.ignoreEnable = true;
        OemTakeoverController c = new OemTakeoverController(p);
        c.tick(true, 1, false, 0);
        assertEquals(OemTakeoverController.State.ERROR, c.snapshot().state);
        assertTrue(p.disk.ownsReceiver); assertEquals(2, p.receiver);
        int calls = p.events.size(); c.tick(true, 1, false, 100);
        assertEquals(calls + 1, p.events.size()); // Only the safe outside-R yield update.
        p.ignoreEnable = false; c.retryRestore(); c.tick(true, 1, false, 200);
        assertEquals(1, p.receiver); assertFalse(p.disk.ownsReceiver);
        assertEquals(OemTakeoverController.State.OEM, c.snapshot().state);
    }

    @Test public void explicitRestoreRechecksAnAlreadyAllowedReceiverAndAbsentOemNeedsNoMutation() {
        Fake p = new Fake(); OemTakeoverController c = new OemTakeoverController(p);
        c.tick(true, 1, false, 0);
        p.receiver = 2; c.retryRestore(); c.tick(true, 1, false, 100);
        assertEquals(1, p.receiver);
        p = new Fake(); p.available = false; p.receiver = 2;
        c = new OemTakeoverController(p); c.tick(true, 1, false, 0);
        assertEquals(2, p.receiver); assertFalse(p.disk.ownsReceiver);
        assertEquals(OemTakeoverController.State.OEM, c.snapshot().state);
    }

    @Test public void takeoverPersistsOriginalWithoutReleasingHealthyCamerasDuringPreflight() {
        Fake p = new Fake();
        OemTakeoverController c = new OemTakeoverController(p);
        c.tick(false, 1, false, 0);
        assertEquals(OemTakeoverController.State.EXCLUSIVE, c.snapshot().state);
        assertTrue(p.disk.ownsReceiver); assertFalse(p.disk.pending);
        assertTrue(p.events.indexOf("save") < p.events.indexOf("receiver=2"));
        assertTrue(p.events.indexOf("data-start") < p.events.indexOf("receiver=2"));
        assertTrue(p.events.indexOf("acquire") < p.events.indexOf("receiver=2"));
        assertFalse(p.events.contains("release"));
        assertFalse(p.yielded);
        p.events.clear(); c.tick(true, 1, false, 100);
        assertEquals(0, p.receiver);
        assertTrue(p.events.indexOf("release") < p.events.indexOf("receiver=0"));
        assertFalse(p.disk.ownsReceiver);
    }

    @Test public void recordingAndMainVisibilityAreNotRequiredForOwnership() {
        Fake p = new Fake(); OemTakeoverController c = new OemTakeoverController(p);
        long now = 0;
        for (int gear : new int[]{1, 4, 2, 3, 4}) c.tick(false, gear, false, now += 100);
        assertEquals(OemTakeoverController.State.EXCLUSIVE, c.snapshot().state);
        assertEquals(2, p.receiver); assertEquals(1, p.starts);
        assertEquals(0, p.opens); assertEquals(0, p.closes);
    }

    @Test public void shortRearFrameGapsDoNotFallBack() {
        Fake p = new Fake(); OemTakeoverController c = new OemTakeoverController(p);
        c.tick(false, 2, false, 0);
        p.age = 1500; c.tick(false, 2, false, 1500);
        p.age = 0; c.tick(false, 2, false, 2000);
        p.age = 2000; c.tick(false, 2, false, 4000);
        assertEquals(OemTakeoverController.State.EXCLUSIVE, c.snapshot().state);
        assertEquals(0, p.opens);
    }

    @Test public void metadataFailureNeverPermanentlyBlanksHealthyCamerasOutsideReverse() {
        for(int gear:new int[]{0,1,3,4}) {
            Fake p=new Fake();p.data=false;
            OemTakeoverController c=new OemTakeoverController(p);
            c.tick(false,gear,false,0);
            assertEquals(OemTakeoverController.State.PREPARING,c.snapshot().state);
            assertFalse(p.yielded);assertFalse(p.released);
            c.tick(false,gear,false,2100);
            assertEquals(OemTakeoverController.State.ERROR,c.snapshot().state);
            assertFalse(p.yielded);assertFalse(p.released);assertEquals(0,p.opens);
            assertEquals("data_service_unavailable",c.snapshot().detail);
            c.tick(false,gear,false,10000);
            assertFalse(p.yielded);assertEquals(0,p.opens);
        }
    }

    @Test public void transientDataProbeLossAfterLongRunningSessionHasItsOwnGracePeriod() {
        Fake p=new Fake();OemTakeoverController c=new OemTakeoverController(p);
        c.tick(false,2,false,0);
        p.data=false;c.tick(false,2,false,600000);c.tick(false,2,false,601000);
        assertEquals(OemTakeoverController.State.EXCLUSIVE,c.snapshot().state);
        assertFalse(p.disk.fault);assertFalse(p.released);assertFalse(p.yielded);
        p.data=true;c.tick(false,2,false,601500);
        assertEquals(OemTakeoverController.State.EXCLUSIVE,c.snapshot().state);
    }

    @Test public void onlyCompletedLegacyPreflightFaultCanBeRetriedOnUpgrade() {
        OemTakeoverController.Journal j=new OemTakeoverController.Journal();j.fault=true;
        j.pending=true;assertFalse(j.retryCompletedLegacyFault(1));j.pending=false;
        j.ownsReceiver=true;assertFalse(j.retryCompletedLegacyFault(1));j.ownsReceiver=false;
        j.fallback=true;assertFalse(j.retryCompletedLegacyFault(1));j.fallback=false;
        assertFalse(j.retryCompletedLegacyFault(2));
        assertTrue(j.retryCompletedLegacyFault(1));assertFalse(j.fault);
        assertFalse(j.retryCompletedLegacyFault(1));
    }

    @Test public void switchingFromOemInReverseGetsANewImageDeadline() {
        Fake p = new Fake(); p.age = -1;
        OemTakeoverController c = new OemTakeoverController(p);
        c.tick(true, 2, false, 0); c.tick(true, 2, false, 100000);
        c.tick(false, 2, false, 100100);
        assertEquals(OemTakeoverController.State.PREPARING, c.snapshot().state);
        assertEquals(0, p.opens);
        c.tick(false, 2, false, 102600);
        assertEquals(1, p.opens);
    }

    @Test public void rearFailureHandsBackOnceAndSurvivesProcessRestartUntilConfirmedNonReverse() {
        Fake p = new Fake(); OemTakeoverController c = new OemTakeoverController(p);
        c.tick(false, 2, false, 0);
        p.age = 2500; c.tick(false, 2, false, 2500);
        assertEquals(OemTakeoverController.State.FALLBACK, c.snapshot().state);
        assertEquals(0, p.receiver); assertTrue(p.released); assertEquals(1, p.opens);
        assertTrue(p.disk.fallback); assertTrue(p.disk.oemLaunchAttempted);
        long saves = p.events.stream().filter("save"::equals).count();
        for (int time = 2600; time <= 10000; time += 100) c.tick(false, 2, true, time);
        assertEquals(saves, p.events.stream().filter("save"::equals).count());
        assertEquals(1, p.opens);
        c = new OemTakeoverController(p);
        c.tick(false, 2, true, 3000); c.tick(false, 0, false, 4000);
        c.tick(false, 4, false, 4100); c.tick(false, 2, false, 4300);
        assertTrue(p.disk.fallback); assertEquals(1, p.opens);
        c.tick(false, 4, false, 4500); p.age = 0; c.tick(false, 4, false, 5000);
        assertFalse(p.disk.fallback);
        assertEquals(OemTakeoverController.State.EXCLUSIVE, c.snapshot().state);
    }

    @Test public void neverReceivedFrameUsesReverseEntryDeadline() {
        Fake p = new Fake(); p.age = -1;
        OemTakeoverController c = new OemTakeoverController(p);
        c.tick(false, 2, false, 0); c.tick(false, 2, false, 2499);
        assertEquals(0, p.opens);
        c.tick(false, 2, false, 2500);
        assertTrue(p.disk.fallback); assertEquals(1, p.opens);
    }

    @Test public void interruptedTakeoverRestoresOriginalWithoutAutomaticRetry() {
        Fake p = new Fake(); p.receiver = 2;
        p.disk.originalReceiver = 1; p.disk.ownsReceiver = p.disk.pending = true;
        OemTakeoverController c = new OemTakeoverController(p);
        c.tick(false, 2, false, 0);
        assertEquals(1, p.receiver); assertTrue(p.disk.fault); assertFalse(p.disk.pending);
        assertEquals(OemTakeoverController.State.ERROR, c.snapshot().state);
        c.tick(false, 4, false, 100); c.tick(false, 4, false, 700);
        assertEquals(0, p.starts);
        c.tick(true, 4, false, 800); c.tick(false, 4, false, 900);
        assertEquals(OemTakeoverController.State.EXCLUSIVE, c.snapshot().state);
    }

    @Test public void missingDataServiceNeverDisablesReceiver() {
        Fake p = new Fake(); p.data = false;
        OemTakeoverController c = new OemTakeoverController(p);
        c.tick(false, 2, false, 0); c.tick(false, 2, false, 2000);
        assertEquals(0, p.receiver); assertTrue(p.disk.fault); assertEquals(1, p.opens);
        assertTrue(p.events.contains("acquire"));
        assertTrue(p.released); // Only the real reverse fallback releases them.
        p.age = -1;
        c.tick(false, 2, true, 10000);
        assertEquals(OemTakeoverController.State.ERROR, c.snapshot().state);
        assertFalse(p.disk.fallback);
    }

    @Test public void permissionFailureRollsBackAndDoesNotClaimSuppression() {
        for (boolean ignored : new boolean[]{false, true}) {
            Fake p = new Fake(); p.ignoreDisable = ignored; p.deny = !ignored;
            OemTakeoverController c = new OemTakeoverController(p);
            c.tick(false, 2, false, 0);
            assertEquals(OemTakeoverController.State.ERROR, c.snapshot().state);
            assertEquals(0, p.receiver); assertTrue(p.disk.fault);
            assertFalse(c.snapshot().receiverBlocked);
            assertTrue(p.released);
        }
    }

    @Test public void persistenceFailureNeverDisablesReceiver() {
        Fake p = new Fake(); p.failSave = true;
        OemTakeoverController c = new OemTakeoverController(p);
        c.tick(false, 2, false, 0);
        assertFalse(p.events.contains("receiver=2"));
        assertFalse(p.events.contains("acquire"));
    }

    @Test public void rapidSettingChangesAlwaysRestoreBeforeAnotherTakeover() {
        Fake p = new Fake(); OemTakeoverController c = new OemTakeoverController(p);
        c.tick(false, 1, false, 0); c.tick(true, 1, false, 10);
        c.tick(false, 1, false, 20); c.tick(true, 1, false, 30);
        assertEquals(0, p.receiver); assertFalse(p.disk.ownsReceiver);
        assertEquals(OemTakeoverController.State.OEM, c.snapshot().state);
    }

    @Test public void stuckOemWindowHasBoundedCloseRequestsAndNoReopenLoop() {
        Fake p = new Fake(); OemTakeoverController c = new OemTakeoverController(p);
        for (int time = 0; time <= 10000; time += 100) c.tick(false, 2, true, time);
        assertEquals(3, p.closes); assertEquals(1, p.opens);
        assertEquals(OemTakeoverController.State.ERROR, c.snapshot().state);
    }

    @Test public void failedReleaseNeverEnablesReceiverOrLaunchesOemAndDoesNotRetryForever() {
        Fake p = new Fake(); OemTakeoverController c = new OemTakeoverController(p);
        c.tick(false, 2, false, 0); p.releaseFails = true;
        p.age = 3000; c.tick(false, 2, false, 3000);
        int operations = p.events.size();
        for (int time = 3100; time <= 10000; time += 100) c.tick(false, 2, false, time);
        assertEquals(operations, p.events.size());
        assertEquals(2, p.receiver); assertEquals(0, p.opens);
        assertEquals("restore_failed", c.snapshot().detail);
        c.tick(false,4,false,10500);assertFalse(p.yielded);
        assertEquals(2,p.receiver);assertEquals(0,p.opens);
        p.releaseFails = false;
        c.retryRestore(); c.tick(true, 2, false, 11000);
        assertEquals(0, p.receiver); assertEquals(1, p.opens);
        assertEquals(OemTakeoverController.State.OEM, c.snapshot().state);
    }
}
