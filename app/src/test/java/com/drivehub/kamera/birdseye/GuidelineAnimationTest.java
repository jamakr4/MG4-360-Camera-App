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
        a.invalidateOutgoing(Direction.REVERSE);assertEquals(0,a.progress(Direction.REVERSE),0);
        assertEquals(1,a.update(true,true,Direction.FORWARD,2200),0);
        assertEquals(0,a.progress(Direction.REVERSE),0);
    }
    @Test public void briefInputOrCameraGapHidesImmediatelyAndRestoresTheFullPath() {
        GuidelineAnimation a=new GuidelineAnimation(600);
        a.update(true,true,Direction.REVERSE,1000);a.update(true,true,Direction.REVERSE,1600);
        assertEquals(0,a.update(true,false,Direction.REVERSE,1650),0);
        a.invalidateOutgoing(Direction.REVERSE);
        assertEquals(0,a.progress(Direction.REVERSE),0);
        assertEquals(1,a.update(true,true,Direction.REVERSE,2000),0);
        assertEquals(0,a.update(true,true,null,2300),0);
        assertEquals(0,a.progress(Direction.REVERSE),0);
        assertEquals(1,a.update(true,true,Direction.REVERSE,2350),0);
    }
    @Test public void partialRevealPausesDuringAGapInsteadOfRestartingOrSkippingAhead() {
        GuidelineAnimation a=new GuidelineAnimation(600);
        a.update(true,true,Direction.FORWARD,1000);
        assertEquals(.5,a.update(true,true,Direction.FORWARD,1300),1e-6);
        a.update(true,false,Direction.FORWARD,1350);
        assertEquals(0,a.update(true,false,Direction.FORWARD,1500),0);
        assertEquals(.5,a.update(true,true,Direction.FORWARD,1600),1e-6);
        assertEquals(.84375,a.update(true,true,Direction.FORWARD,1750),1e-6);
        assertEquals(1,a.update(true,true,Direction.FORWARD,1900),0);
    }
    @Test public void longGapsAndDirectionChangesDoNotRestoreOldPaths() {
        for(boolean changeDirection:new boolean[]{false,true}) {
            GuidelineAnimation a=new GuidelineAnimation(600);
            a.update(true,true,Direction.REVERSE,1000);a.update(true,true,Direction.REVERSE,1600);
            a.update(true,false,Direction.REVERSE,1650);
            Direction next=changeDirection?Direction.FORWARD:Direction.REVERSE;
            long recovery=1650+(changeDirection?50:GuidelineAnimation.RESUME_WINDOW_MS+1);
            assertEquals(0,a.update(true,true,next,recovery),0);
            assertEquals(0,a.progress(Direction.REVERSE),0);
            assertEquals(.5,a.update(true,true,next,recovery+300),1e-6);
        }
    }
    @Test public void resumeWindowIncludesItsBoundaryAndRepeatedMissingReadsDoNotExtendIt() {
        for(long gap:new long[]{GuidelineAnimation.RESUME_WINDOW_MS,GuidelineAnimation.RESUME_WINDOW_MS+1}) {
            GuidelineAnimation a=new GuidelineAnimation(600);
            a.update(true,true,Direction.REVERSE,1000);a.update(true,true,Direction.REVERSE,1600);
            a.update(true,false,Direction.REVERSE,1650);
            a.update(true,false,Direction.REVERSE,1650+gap-1);
            assertEquals(gap==GuidelineAnimation.RESUME_WINDOW_MS?1:0,
                    a.update(true,true,Direction.REVERSE,1650+gap),0);
        }
    }
    @Test public void switchingOffDuringAGapAndClockRollbackClearTheSuspendedReveal() {
        for(boolean rollback:new boolean[]{false,true}) {
            GuidelineAnimation a=new GuidelineAnimation(600);
            a.update(true,true,Direction.REVERSE,1000);a.update(true,true,Direction.REVERSE,1600);
            a.update(true,false,Direction.REVERSE,1650);
            if(!rollback)a.update(false,false,Direction.REVERSE,1700);
            long recovery=rollback?1500:1750;
            assertEquals(0,a.update(true,true,Direction.REVERSE,recovery),0);
            assertEquals(.5,a.update(true,true,Direction.REVERSE,recovery+300),1e-6);
        }
    }
    @Test public void expiredOutgoingCameraCannotReappearAfterASuspendedGearTransition() {
        GuidelineAnimation a=new GuidelineAnimation(600);
        a.update(true,true,Direction.REVERSE,1000);a.update(true,true,Direction.REVERSE,1600);
        a.update(true,true,Direction.FORWARD,1600);a.update(true,true,Direction.FORWARD,1900);
        a.update(true,false,Direction.FORWARD,1950);
        a.invalidateOutgoing(Direction.REVERSE);a.invalidateOutgoing(Direction.FORWARD);
        assertEquals(0,a.progress(Direction.FORWARD),0);
        assertEquals(.5,a.update(true,true,Direction.FORWARD,2000),1e-6);
        assertEquals(0,a.progress(Direction.REVERSE),0);
        assertEquals(1,a.update(true,true,Direction.FORWARD,2300),0);
        assertEquals(0,a.progress(Direction.REVERSE),0);
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
