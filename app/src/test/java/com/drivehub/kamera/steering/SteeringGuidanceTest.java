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
        assertEquals(28.6533,reverse.wheels.leftDegrees,.001);assertEquals(38.5024,reverse.wheels.rightDegrees,.001);
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

    @Test public void fullLockOvershootKeepsGuidanceAndFinitePathsInBothGearsAndReturnsSmoothly() {
        SteeringModel model=new SteeringModel();WheelGuides guides=new WheelGuides();
        for(int gear:new int[]{2,4})for(float sign:new float[]{-1,1}) {
            SteeringGuidance guidance=new SteeringGuidance();long now=1000;
            SteeringModel.Solution limit=model.solve(sign*516.95f);
            for(float magnitude:new float[]{516.95f,Math.nextUp(516.95f),517,520,540,517,516.95f,516.9f,500}) {
                float raw=sign*magnitude;
                SteeringSnapshot sample=SteeringSnapshot.value(raw,0,now,10);
                SteeringGuidance.Frame frame=guidance.update(sample,GearSnapshot.value(gear,now),true,now);
                assertTrue("guidance at raw="+raw+" gear="+gear,frame.guidanceAvailable);
                assertTrue(frame.steeringAvailable);assertTrue(frame.wheels.valid);
                assertEquals(gear==2?WheelGuides.Direction.REVERSE:WheelGuides.Direction.FORWARD,frame.direction);
                if(magnitude>=516.95f)assertEquals(limit.curvaturePerMm,frame.wheels.curvaturePerMm,0);
                for(com.drivehub.kamera.rivela.PdcBars.Bar path:guides.lines(frame.wheels,0,0,frame.direction))
                    for(double coordinate:path.xy)assertTrue(Double.isFinite(coordinate));
                WheelGuides.Corridor corridor=guides.corridor(frame.wheels,0,0,frame.direction);
                assertTrue(corridor.triangles.length>0);
                for(double coordinate:corridor.triangles)assertTrue(Double.isFinite(coordinate));
                now+=50;
            }
            SteeringGuidance.Frame returned=guidance.update(SteeringSnapshot.value(sign*500,0,now,10),GearSnapshot.value(gear,now),true,now);
            assertTrue(returned.guidanceAvailable);
            assertTrue(Math.abs(returned.wheels.curvaturePerMm)<Math.abs(limit.curvaturePerMm));
            assertTrue(Math.abs(returned.wheels.curvaturePerMm)>Math.abs(model.solve(sign*500).curvaturePerMm));
        }
    }

    @Test public void fullLockOvershootDoesNotBypassStatusFreshnessOrPlausibilityChecks() {
        for(int gear:new int[]{2,4})for(float sign:new float[]{-1,1}) {
            SteeringGuidance guidance=new SteeringGuidance();
            SteeringSnapshot valid=SteeringSnapshot.value(sign*520,0,1000,1);
            SteeringGuidance.Frame first=guidance.update(valid,GearSnapshot.value(gear,1000),true,1000);
            assertTrue(first.guidanceAvailable);
            for(SteeringSnapshot invalid:new SteeringSnapshot[]{SteeringSnapshot.value(sign*520,1,1100,1),
                    SteeringSnapshot.value(sign*Math.nextUp(540f),0,1100,1),SteeringSnapshot.value(Float.NaN,0,1100,1),
                    SteeringSnapshot.unavailable("disconnected",1100)}) {
                SteeringGuidance.Frame frame=guidance.update(invalid,GearSnapshot.value(gear,1100),true,1100);
                assertFalse(frame.guidanceAvailable);assertFalse(frame.steeringAvailable);
                assertEquals(first.wheels.curvaturePerMm,frame.wheels.curvaturePerMm,0);
            }
            assertFalse(guidance.update(valid,GearSnapshot.value(gear,1501),true,1501).guidanceAvailable);
            SteeringSnapshot fresh=SteeringSnapshot.value(sign*520,0,1501,1);
            assertFalse(guidance.update(fresh,GearSnapshot.value(gear,1000),true,1501).guidanceAvailable);
            assertFalse(guidance.update(fresh,GearSnapshot.value(gear,1501),false,1501).guidanceAvailable);
            assertTrue(guidance.update(fresh,GearSnapshot.value(gear,1501),true,1501).guidanceAvailable);
        }
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
