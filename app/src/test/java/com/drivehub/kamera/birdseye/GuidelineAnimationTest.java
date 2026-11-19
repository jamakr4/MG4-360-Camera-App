package com.drivehub.kamera.birdseye;

import com.drivehub.kamera.steering.WheelGuides.Direction;
import org.junit.Test;
import static org.junit.Assert.*;

public class GuidelineAnimationTest {
    @Test public void revealsThenRetractsOverTheSameDuration() {
        GuidelineAnimation a=new GuidelineAnimation(600);
        assertEquals(0,a.update(true,true,Direction.FORWARD,1000),0);
        assertEquals(.5,a.update(true,true,Direction.FORWARD,1300),1e-6);
        assertEquals(1,a.update(true,true,Direction.FORWARD,1600),0);
        assertEquals(1,a.update(false,true,Direction.FORWARD,1600),0);
        assertEquals(.5,a.update(false,true,Direction.FORWARD,1900),1e-6);
        assertEquals(0,a.update(false,true,Direction.FORWARD,2200),0);
    }
    @Test public void quickTogglesReverseWithoutJumpingOrRestarting() {
        GuidelineAnimation a=new GuidelineAnimation(600);
        a.update(true,true,Direction.REVERSE,1000);
        assertEquals(.5,a.update(true,true,Direction.REVERSE,1300),1e-6);
        assertEquals(.5,a.update(false,true,Direction.REVERSE,1300),1e-6);
        assertEquals(.15625,a.update(false,true,Direction.REVERSE,1450),1e-6);
        assertEquals(.15625,a.update(true,true,Direction.REVERSE,1450),1e-6);
        assertEquals(.5,a.update(true,true,Direction.REVERSE,1600),1e-6);
    }
    @Test public void repeatedTargetUpdatesDoNotRestartTheAnimation() {
        GuidelineAnimation a=new GuidelineAnimation(600);
        a.update(false,true,Direction.REVERSE,1000);
        for(long t=1000;t<=1600;t+=10)a.update(true,true,Direction.REVERSE,t);
        assertEquals(1,a.update(true,true,Direction.REVERSE,1600),0);
        assertEquals(1,a.update(true,true,Direction.REVERSE,5000),0);
    }
    @Test public void directionChangeRetractsAndBuildsTogetherOnOneClock() {
        GuidelineAnimation a=new GuidelineAnimation(600);
        a.update(true,true,Direction.REVERSE,1000);a.update(true,true,Direction.REVERSE,1600);
        assertEquals(0,a.update(true,true,Direction.FORWARD,1600),0);
        assertEquals(1,a.progress(Direction.REVERSE),0);
        assertEquals(.5,a.update(true,true,Direction.FORWARD,1900),1e-6);
        assertEquals(.5,a.progress(Direction.REVERSE),1e-6);
        assertEquals(1,a.update(true,true,Direction.FORWARD,2200),0);
        assertEquals(0,a.progress(Direction.REVERSE),0);
        assertFalse(a.crossfading());
    }
    @Test public void rapidGearReversalContinuesBothVisiblePathsWithoutRestarting() {
        GuidelineAnimation a=new GuidelineAnimation(600);
        a.update(true,true,Direction.REVERSE,1000);a.update(true,true,Direction.REVERSE,1600);
        a.update(true,true,Direction.FORWARD,1600);
        assertEquals(.5,a.update(true,true,Direction.FORWARD,1900),1e-6);
        assertEquals(.5,a.update(true,true,Direction.REVERSE,1900),1e-6);
        assertEquals(.5,a.progress(Direction.FORWARD),1e-6);
        assertEquals(1,a.update(true,true,Direction.REVERSE,2200),0);
        assertEquals(0,a.progress(Direction.FORWARD),0);
    }
    @Test public void switchingOffMidTransitionFadesBothLayersWithoutAnOpacityJump() {
        GuidelineAnimation a=new GuidelineAnimation(600);
        a.update(true,true,Direction.REVERSE,1000);a.update(true,true,Direction.REVERSE,1600);
        a.update(true,true,Direction.FORWARD,1600);a.update(true,true,Direction.FORWARD,1750);
        assertTrue(a.crossfading());
        assertEquals(0,a.update(false,true,Direction.FORWARD,1900),0);
        assertEquals(.5,a.progress(Direction.REVERSE),1e-6);
        assertTrue(a.crossfading());
        a.update(false,true,Direction.FORWARD,2200);
        assertEquals(0,a.progress(Direction.REVERSE),0);assertFalse(a.crossfading());
    }
    @Test public void invalidatingTheOutgoingCameraCannotMakeItsPathReturnLater() {
        GuidelineAnimation a=new GuidelineAnimation(600);
        a.update(true,true,Direction.REVERSE,1000);a.update(true,true,Direction.REVERSE,1600);
        a.update(true,true,Direction.FORWARD,1600);a.update(true,true,Direction.FORWARD,1900);
        a.invalidate(Direction.REVERSE);assertEquals(0,a.progress(Direction.REVERSE),0);
        assertEquals(1,a.update(true,true,Direction.FORWARD,2200),0);
        assertEquals(0,a.progress(Direction.REVERSE),0);
    }
    @Test public void invalidGeometryOrCameraHidesImmediatelyAndRecoveryRebuilds() {
        GuidelineAnimation a=new GuidelineAnimation(600);
        a.update(true,true,Direction.REVERSE,1000);a.update(true,true,Direction.REVERSE,1600);
        assertEquals(0,a.update(true,false,Direction.REVERSE,1650),0);
        assertEquals(0,a.update(true,true,Direction.REVERSE,2000),0);
        assertEquals(.5,a.update(true,true,Direction.REVERSE,2300),1e-6);
        assertEquals(0,a.update(true,true,null,2300),0);
    }
    @Test public void resetAndClockRollbackCannotLeaveAPartialStalePath() {
        GuidelineAnimation a=new GuidelineAnimation(600);
        a.update(true,true,Direction.REVERSE,1000);a.update(true,true,Direction.REVERSE,1600);
        assertEquals(0,a.update(true,true,Direction.REVERSE,900),0);
        assertEquals(.5,a.update(true,true,Direction.REVERSE,1200),1e-6);
        a.reset();assertEquals(0,a.update(true,true,Direction.REVERSE,1300),0);
    }
    @Test public void zeroDurationSupportsDeterministicStaticRendering() {
        GuidelineAnimation a=new GuidelineAnimation(0);
        assertEquals(1,a.update(true,true,Direction.REVERSE,1000),0);
        assertEquals(0,a.update(false,true,Direction.REVERSE,1000),0);
        assertEquals(0,a.update(true,false,Direction.REVERSE,1000),0);
    }
}
