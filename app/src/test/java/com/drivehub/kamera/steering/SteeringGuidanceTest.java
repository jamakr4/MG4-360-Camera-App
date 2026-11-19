package com.drivehub.kamera.steering;

import com.drivehub.kamera.birdseye.WheelSteering;
import org.junit.Test;
import static org.junit.Assert.*;

public class SteeringGuidanceTest {
    @Test public void gearSelectionRequiresRecentSuccessfulReadsOfReverseOrDrive() {
        assertEquals(WheelGuides.Direction.REVERSE,GearSnapshot.value(2,1000).direction(1000));
        assertEquals(WheelGuides.Direction.FORWARD,GearSnapshot.value(4,1000).direction(1500));
        assertNull(GearSnapshot.value(4,1000).direction(1501));
        assertNull(GearSnapshot.value(2,1000).direction(999));
        for(int gear:new int[]{Integer.MIN_VALUE,-1,0,1,3,5,8})assertNull(GearSnapshot.value(gear,1000).direction(1000));
        assertNull(GearSnapshot.EMPTY.direction(0));
    }

    @Test public void liveWheelsUseTheSharedModelAndGearChangesDoNotChangeTheirCurvature() {
        SteeringGuidance guidance=new SteeringGuidance();
        SteeringSnapshot raw=SteeringSnapshot.value(-516.95f,0,1000,10);
        SteeringGuidance.Frame reverse=guidance.update(raw,GearSnapshot.value(2,1000),true,1000);
        assertTrue(reverse.guidanceAvailable);assertTrue(reverse.steeringAvailable);
        assertEquals(WheelGuides.Direction.REVERSE,reverse.direction);
        assertEquals(28.8493,reverse.wheels.leftDegrees,.001);assertEquals(38.0802,reverse.wheels.rightDegrees,.001);
        SteeringGuidance.Frame forward=guidance.update(raw,GearSnapshot.value(4,1010),true,1010);
        assertEquals(WheelGuides.Direction.FORWARD,forward.direction);assertTrue(forward.guidanceAvailable);
        assertEquals(reverse.wheels.curvaturePerMm,forward.wheels.curvaturePerMm,0);
        for(int gear:new int[]{1,3,0}) {
            SteeringGuidance.Frame hidden=guidance.update(raw,GearSnapshot.value(gear,1020),true,1020);
            assertFalse(hidden.guidanceAvailable);assertTrue(hidden.steeringAvailable);
            assertEquals(reverse.wheels.rightDegrees,hidden.wheels.rightDegrees,0);
        }
    }

    @Test public void staleSteeringHoldsWheelsButHidesGuidanceAndFreshGearAloneCannotRestoreIt() {
        SteeringGuidance guidance=new SteeringGuidance();
        SteeringSnapshot raw=SteeringSnapshot.value(199.3f,0,1000,10);
        SteeringGuidance.Frame first=guidance.update(raw,GearSnapshot.value(4,1000),true,1000);
        SteeringGuidance.Frame stale=guidance.update(raw,GearSnapshot.value(4,1501),true,1501);
        assertFalse(stale.steeringAvailable);assertFalse(stale.guidanceAvailable);
        assertEquals(first.wheels.curvaturePerMm,stale.wheels.curvaturePerMm,0);
        SteeringGuidance.Frame unavailable=guidance.update(SteeringSnapshot.EMPTY,GearSnapshot.value(2,1600),true,1600);
        assertFalse(unavailable.guidanceAvailable);assertEquals(first.wheels.leftDegrees,unavailable.wheels.leftDegrees,0);
        assertTrue(guidance.update(SteeringSnapshot.value(199.3f,0,1610,10),GearSnapshot.value(2,1610),true,1610).guidanceAvailable);
    }

    @Test public void staleGearAndInactiveViewHideGuidesAndResetRequiresNewSteering() {
        SteeringGuidance guidance=new SteeringGuidance();
        SteeringSnapshot raw=SteeringSnapshot.value(-199.3f,0,1600,10);
        assertFalse(guidance.update(raw,GearSnapshot.value(4,1000),true,1600).guidanceAvailable);
        assertFalse(guidance.update(raw,GearSnapshot.value(4,1600),false,1600).guidanceAvailable);
        assertTrue(guidance.update(raw,GearSnapshot.value(4,1600),true,1600).guidanceAvailable);
        guidance.reset();
        SteeringGuidance.Frame reset=guidance.update(SteeringSnapshot.EMPTY,GearSnapshot.value(4,1610),true,1610);
        assertFalse(reset.guidanceAvailable);assertEquals(0,reset.wheels.curvaturePerMm,0);
    }

    @Test public void gearDirectionAvailabilityAndWheelPairAreOneImmutablePublication() {
        SteeringModel model=new SteeringModel();WheelSteering wheels=new WheelSteering();
        SteeringModel.Solution right=model.solve(-516.95f);
        wheels.setSolution(right,true,WheelGuides.Direction.REVERSE);WheelSteering.Angles original=wheels.angles();
        wheels.setSolution(right,true,WheelGuides.Direction.FORWARD);WheelSteering.Angles forward=wheels.angles();
        assertEquals(WheelGuides.Direction.REVERSE,original.guidanceDirection);
        assertEquals(WheelGuides.Direction.FORWARD,forward.guidanceDirection);
        assertTrue(forward.guidanceAvailable);assertEquals(right.curvaturePerMm,forward.curvaturePerMm,0);
        assertEquals(right.rightDegrees,forward.right,0);
        wheels.setSolution(right,false,null);assertFalse(wheels.angles().guidanceAvailable);
        assertTrue(original.guidanceAvailable);assertEquals(WheelGuides.Direction.REVERSE,original.guidanceDirection);
        wheels.setSolution(right,true);assertNull(wheels.angles().guidanceDirection);
    }
}
