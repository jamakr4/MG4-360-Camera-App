package com.drivehub.kamera.signal;

import org.junit.Test;
import static org.junit.Assert.*;
import static com.drivehub.kamera.signal.ReverseForegroundPolicy.Foreground.*;

public class ReverseForegroundPolicyTest {
    private ReverseForegroundPolicy armed() {
        ReverseForegroundPolicy p=new ReverseForegroundPolicy();p.configure(true,2,true);p.cameraVisible(42,true,0);return p;
    }
    private boolean other(ReverseForegroundPolicy p,long now){return p.update(FOREIGN,"AA",now);}
    @Test public void activeCameraReturnsOnlyAfterStableInterruption() {
        ReverseForegroundPolicy p=armed();assertFalse(p.monitor());p.cameraPaused(42);assertTrue(p.monitor());
        assertFalse(other(p,100));assertFalse(other(p,399));assertTrue(other(p,400));
        long token=p.generation();assertTrue(p.dispatchAllowed(token));assertFalse(other(p,500));
        p.cameraVisible(42,false,600);assertFalse(p.monitor());assertFalse(p.dispatchAllowed(token));
        p.update(OWN,"camera",2600);assertTrue(p.status().contains("attempts=0/3"));
    }
    @Test public void allForeignAppsIncludingBothProjectionStacksUseTheSamePolicy() {
        for(String app:new String[]{"Android Auto","CarPlay","Music","Launcher"}) {
            ReverseForegroundPolicy p=armed();p.cameraPaused(42);
            assertFalse(p.update(FOREIGN,app,100));assertTrue(p.update(FOREIGN,app,400));
        }
    }
    @Test public void temporaryForeignComponentsMustSettleAgain() {
        ReverseForegroundPolicy p=armed();p.cameraPaused(42);other(p,100);
        assertFalse(p.update(FOREIGN,"splash",350));assertFalse(p.update(FOREIGN,"splash",649));
        assertTrue(p.update(FOREIGN,"splash",650));
    }
    @Test public void homeAndCloseSuppressUntilReopeningOrNewReverseCycle() {
        for(String reason:new String[]{"Home","user closed camera"}) {
            ReverseForegroundPolicy p=armed();p.cameraPaused(42);other(p,100);assertTrue(other(p,400));long token=p.generation();
            p.dismiss(reason);assertFalse(p.dispatchAllowed(token));assertFalse(other(p,9000));
            p.cameraVisible(42,false,10000);assertFalse(p.anchored());
            p.cameraVisible(42,true,10000);assertTrue(p.anchored());p.dismiss(reason);
            p.configure(true,4,true);p.configure(true,2,true);p.cameraVisible(42,true,11000);assertTrue(p.anchored());
        }
    }
    @Test public void unavailableGearAndCameraAccessInvalidateQueuedReturns() {
        for(int condition=0;condition<2;condition++) {
            ReverseForegroundPolicy p=armed();p.cameraPaused(42);other(p,0);assertTrue(other(p,300));long token=p.generation();
            p.configure(true,condition==0?0:2,condition==0);assertFalse(p.monitor());assertFalse(p.dispatchAllowed(token));
            assertTrue(p.anchored());p.configure(true,2,true);assertTrue(p.monitor());
        }
    }
    @Test public void leavingReverseAndDisablingCannotReplayPendingWork() {
        for(int gear:new int[]{1,3,4}) {
            ReverseForegroundPolicy p=armed();p.cameraPaused(42);other(p,0);assertTrue(other(p,300));long token=p.generation();
            p.configure(true,gear,true);assertFalse(p.anchored());assertFalse(p.dispatchAllowed(token));assertFalse(other(p,10000));
        }
        ReverseForegroundPolicy p=armed();p.configure(false,2,true);assertFalse(p.anchored());
        p.configure(true,2,true);assertFalse(p.anchored()); // Enabling does not open a hidden camera.
        p.cameraVisible(42,true,10);assertTrue(p.anchored());
    }
    @Test public void protectedOwnAndUnknownForegroundsNeverAuthorizeReturns() {
        for(ReverseForegroundPolicy.Foreground f:new ReverseForegroundPolicy.Foreground[]{PROTECTED,OWN,UNKNOWN}) {
            ReverseForegroundPolicy p=armed();p.cameraPaused(42);other(p,0);assertTrue(other(p,300));long token=p.generation();
            assertFalse(p.update(f,"protected",400));assertFalse(p.dispatchAllowed(token));
            assertFalse(p.update(f,"protected",5000));assertFalse(other(p,6000));assertTrue(other(p,6300));
        }
    }
    @Test public void threeUnansweredReturnsStopWithoutAnUnboundedLoop() {
        ReverseForegroundPolicy p=armed();p.cameraPaused(42);other(p,0);
        assertTrue(other(p,300));assertFalse(other(p,3299));assertTrue(other(p,3300));assertTrue(other(p,6300));
        assertFalse(other(p,9300));assertTrue(p.exhausted());assertFalse(p.monitor());assertFalse(other(p,20000));
        p.cameraVisible(42,false,20500);assertTrue(p.exhausted());assertFalse(p.anchored());
        p.cameraVisible(42,true,21000);assertTrue(p.anchored());assertFalse(p.exhausted());
    }
    @Test public void briefResumesDoNotResetBudgetAndStableResumesDo() {
        ReverseForegroundPolicy p=armed();p.cameraPaused(42);other(p,0);assertTrue(other(p,300));
        for(int i=1;i<=2;i++) {
            p.cameraVisible(42,false,i*1500);p.update(OWN,"camera",i*1500+100);
            p.cameraPaused(42);other(p,i*1500+200);assertTrue(other(p,i*1500+500));
        }
        p.cameraVisible(42,false,4000);p.cameraPaused(42);assertFalse(other(p,4100));assertTrue(p.exhausted());
        p.cameraVisible(42,true,5000);p.cameraPaused(42);other(p,5100);assertTrue(other(p,5400));
        p.cameraVisible(42,false,5500);p.update(OWN,"camera",7500);assertTrue(p.status().contains("attempts=0/3"));
    }
    @Test public void rejectedTaskMovesRespectSpacingAndTheSameBudget() {
        ReverseForegroundPolicy p=armed();p.cameraPaused(42);other(p,0);assertTrue(other(p,300));p.requestFailed();
        assertFalse(other(p,1299));assertTrue(other(p,1300));p.requestFailed();assertTrue(other(p,2300));
        p.requestFailed();assertFalse(other(p,3300));assertTrue(p.exhausted());
    }
    @Test public void verifiedOemExceptionsDoNotProtectEveryNormalPhoneScreen() {
        assertTrue(ReverseForegroundPolicy.protectedComponent("com.saicmotor.hmi.btcall","com.saicmotor.hmi.btcall.ECallActivity"));
        for(String p:new String[]{"com.abupdate.ota","com.saicmotor.hmi.engmode","com.saicmotor.hmi.setupwizard","com.saicvehicleservice"})
            assertTrue(ReverseForegroundPolicy.protectedComponent(p,"SomeActivity"));
        assertFalse(ReverseForegroundPolicy.protectedComponent("com.saicmotor.hmi.btcall","NormalCallActivity"));
        assertFalse(ReverseForegroundPolicy.protectedComponent("com.allgo.carplay.service","com.allgo.carplay.service.CarPlayActivity"));
    }
}
