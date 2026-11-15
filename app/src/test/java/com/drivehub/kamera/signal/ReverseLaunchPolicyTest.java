package com.drivehub.kamera.signal;
import org.junit.Test;
import static org.junit.Assert.*;

public class ReverseLaunchPolicyTest {
    @Test public void reverseOpensOnceAndDoesNotReopenAfterManualCloseOrAutoCloseOnDrive() {
        ReverseLaunchPolicy p=new ReverseLaunchPolicy();
        assertFalse(p.update(false,4,false,false,0).showCamera);
        assertFalse(p.update(false,2,false,false,1000).showCamera);
        assertFalse(p.update(false,2,false,false,1100).showCamera);
        assertTrue(p.update(false,2,false,false,1300).showCamera);
        assertFalse(p.update(false,2,true,false,1400).showCamera);
        assertFalse(p.update(false,2,false,false,5000).showCamera);
        assertFalse(p.update(false,4,true,false,6000).showCamera);
        assertFalse(p.update(false,2,false,false,7000).showCamera);
        assertTrue(p.update(false,2,false,false,7300).showCamera);
    }
    @Test public void coexistNeverOpensOrClosesOemAndSwitchOffArmsCurrentReverse() {
        ReverseLaunchPolicy p=new ReverseLaunchPolicy();
        for(long now=0;now<10000;now+=250) {
            ReverseLaunchPolicy.Decision d=p.update(true,2,false,true,now);
            assertFalse(d.showCamera);assertFalse(d.closeOem);
        }
        assertTrue(p.update(false,2,false,true,10000).closeOem);
        assertFalse(p.update(false,2,false,false,10250).showCamera);
        assertTrue(p.update(false,2,false,false,10550).showCamera);
        assertFalse(p.update(true,2,false,true,10600).closeOem);
    }
    @Test public void oemCloseRetriesAreBoundedAndNeverForceAnEndlessRelaunch() {
        ReverseLaunchPolicy p=new ReverseLaunchPolicy();int closes=0,shows=0;
        for(long now=1000;now<20000;now+=50) {
            ReverseLaunchPolicy.Decision d=p.update(false,2,false,true,now);
            if(d.closeOem)closes++;if(d.showCamera)shows++;
        }
        assertEquals(ReverseLaunchPolicy.MAX_CLOSE_REQUESTS,closes);assertEquals(0,shows);
        assertFalse(p.update(false,4,false,true,21000).closeOem);
        assertTrue(p.update(false,2,false,true,22000).closeOem);
    }
    @Test public void rapidRtoDOrManualOpenCancelsPendingAutomaticLaunch() {
        ReverseLaunchPolicy p=new ReverseLaunchPolicy();
        p.update(false,2,false,false,0);
        assertFalse(p.update(false,4,false,false,50).showCamera);
        assertFalse(p.update(false,4,false,false,500).showCamera);
        p.update(false,2,false,false,1000);
        assertFalse(p.update(false,2,true,false,1050).showCamera);
        assertFalse(p.update(false,2,false,false,2000).showCamera);
    }
    @Test public void aLateOemTakeoverOfAnAlreadyVisibleMainGetsClosedAndMainIsReclaimed() {
        ReverseLaunchPolicy p=new ReverseLaunchPolicy();p.update(false,2,true,false,0);
        assertTrue(p.update(false,2,true,true,10000).closeOem);
        assertFalse(p.update(false,2,false,true,10500).showCamera);
        assertFalse(p.update(false,2,false,false,11000).showCamera);
        assertTrue(p.update(false,2,false,false,11300).showCamera);
        assertFalse(p.update(false,2,true,false,11400).showCamera);
        assertFalse(p.update(false,2,false,false,12000).showCamera);
    }
    @Test public void explicitDismissalWinsOverDelayedOemEventsAndNewReverseRearms() {
        ReverseLaunchPolicy p=new ReverseLaunchPolicy();p.update(false,2,true,false,0);p.dismiss();
        assertFalse(p.update(false,2,false,true,1000).showCamera);
        assertFalse(p.update(false,2,false,false,1500).showCamera);
        p.update(false,4,false,false,2000);p.update(false,2,false,false,3000);
        assertTrue(p.update(false,2,false,false,3300).showCamera);
    }
    @Test public void unacquiredCamerasWaitForStableOemTeardown() {
        ReverseLaunchPolicy p=new ReverseLaunchPolicy();
        assertTrue(p.update(false,2,false,true,0).closeOem);
        assertTrue(ReverseLaunchPolicy.oemBusy(false,false,"PauseStart"));
        assertFalse(p.update(false,2,false,ReverseLaunchPolicy.oemBusy(false,false,"PauseStart"),9000).showCamera);
        assertFalse(p.update(false,2,false,false,10000).showCamera);
        assertFalse(p.update(false,2,false,true,10200).showCamera);
        assertFalse(p.update(false,2,false,false,10400).showCamera);
        assertFalse(p.update(false,2,false,false,10699).showCamera);
        assertTrue(p.update(false,2,false,false,10700).showCamera);
        assertFalse(p.update(false,2,false,false,11000).showCamera);
        p.update(false,2,true,false,11500);p.update(false,2,false,true,12000);
        p.update(false,4,false,false,13000);
        assertFalse(ReverseLaunchPolicy.oemBusy(false,false,"DestroyEnd"));
        assertTrue(ReverseLaunchPolicy.oemBusy(true,false,"DestroyEnd"));
        assertTrue(ReverseLaunchPolicy.oemBusy(false,true,"DestroyEnd"));
    }
    @Test public void heldCamerasAllowBackgroundReverseLaunchDespiteRepeatedOemStartHints(){
        ReverseLaunchPolicy p=new ReverseLaunchPolicy();
        assertFalse(p.update(false,4,false,true,true,0).showCamera);
        assertTrue(p.update(false,2,false,true,true,1000).closeOem);
        assertFalse(p.update(false,2,false,true,true,1299).showCamera);
        assertTrue(p.update(false,2,false,true,true,1300).showCamera);
        assertFalse(p.update(false,2,false,true,true,1400).showCamera);
        p.update(false,2,true,true,true,1500);p.dismiss();
        assertFalse(p.update(false,2,false,true,true,2000).showCamera);
        assertFalse(p.update(true,2,false,true,true,2500).showCamera);
    }
    @Test public void repeatedOemStartsCannotResetCloseOrActivityLaunchBudgets() {
        ReverseLaunchPolicy p=new ReverseLaunchPolicy();int closes=0,shows=0;
        for(int cycle=0;cycle<100;cycle++) {
            long t=cycle*2000L;
            ReverseLaunchPolicy.Decision d=p.update(false,2,false,true,t);if(d.closeOem)closes++;
            p.update(false,2,false,false,t+500);
            d=p.update(false,2,false,false,t+1000);if(d.showCamera)shows++;
            p.update(false,2,true,false,t+1500);
        }
        assertEquals(ReverseLaunchPolicy.MAX_CLOSE_REQUESTS,closes);
        assertEquals(ReverseLaunchPolicy.MAX_SHOW_REQUESTS,shows);
    }
    @Test public void oemStartWhileOurActivityIsLaunchingStillArmsOneReclaimAfterAcknowledgement() {
        ReverseLaunchPolicy p=new ReverseLaunchPolicy();p.update(false,2,false,false,0);
        assertTrue(p.update(false,2,false,false,300).showCamera);
        p.update(false,2,false,true,350);
        assertFalse(p.update(false,2,false,false,400).showCamera);
        assertFalse(p.update(false,2,false,false,750).showCamera);
        p.update(false,2,true,true,800);
        p.update(false,2,false,true,900);
        p.update(false,2,false,false,1000);
        assertTrue(p.update(false,2,false,false,1300).showCamera);
    }
}
