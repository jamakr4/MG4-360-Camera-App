package com.drivehub.kamera.steering;

import com.drivehub.kamera.birdseye.WheelSteering;
import org.junit.Test;
import static org.junit.Assert.*;

public class SteeringModelTest {
    private final SteeringModel model = new SteeringModel();
    private static final float[] INPUT = {0,99.65f,199.30f,249.13f,298.96f,348.78f,398.61f,448.44f,485.81f,516.95f};
    private static final float[] OUTPUT = {0,6.89f,14.07f,17.81f,21.69f,25.72f,29.95f,34.41f,37.94f,41.04f};

    @Test public void recoveredCoefficientsMatchSupportPointsMidpointsAndEveryBoundarySide() {
        for (int i=0;i<INPUT.length;i++) {
            assertEquals(OUTPUT[i],OemSteeringCurve.magnitudeDegrees(INPUT[i]),.00001f);
            if(i>0) {
                float mid=(INPUT[i-1]+INPUT[i])/2;
                assertEquals((OUTPUT[i-1]+OUTPUT[i])/2,OemSteeringCurve.magnitudeDegrees(mid),.00001f);
                assertEquals(OUTPUT[i],OemSteeringCurve.magnitudeDegrees(Math.nextDown(INPUT[i])),.00002f);
            }
            if(i<INPUT.length-1)
                assertEquals(OUTPUT[i],OemSteeringCurve.magnitudeDegrees(Math.nextUp(INPUT[i])),.00002f);
        }
    }

    @Test public void oemRadiusIncludesInnerGuidelineReferenceInsteadOfAssumingAnEquivalentWheel() {
        SteeringModel.Solution right=model.solve(-516.95f);
        assertTrue(right.valid);
        assertEquals(4158.1313,1/right.curvaturePerMm,.002);
        assertEquals(38.08016,right.rightDegrees,.0001);
        assertEquals(28.84932,right.leftDegrees,.0001);
        assertTrue(right.rightDegrees>WheelSteering.MAX_DEGREES);
        // Independent native construction: near-side radius L/tan(a), plus half (body width + 300).
        double nativeRadius=2690/Math.tan(Math.toRadians(OemSteeringCurve.magnitudeDegrees(516.95f)))+(1836+300)/2.0;
        assertEquals(nativeRadius,1/right.curvaturePerMm,1e-8);
        assertEquals(-3534,model.geometry.frontY(),0);assertEquals(745,model.geometry.rearY(),0);
    }

    @Test public void fullSensorSweepIsMonotonicSymmetricAndBothWheelNormalsMeetAtOneCenter() {
        double previous=0;
        for(int step=1;step<=10339;step++) {
            float sensor=step/20f;
            SteeringModel.Solution right=model.solve(-sensor),left=model.solve(sensor);
            assertTrue(right.valid);assertTrue(left.valid);
            assertTrue(right.curvaturePerMm>previous);previous=right.curvaturePerMm;
            assertTrue(right.rightDegrees>right.leftDegrees);
            assertEquals(-right.rightDegrees,left.leftDegrees,0);
            assertEquals(-right.leftDegrees,left.rightDegrees,0);
            assertEquals(-right.curvaturePerMm,left.curvaturePerMm,0);
            double fromLeft=-725+2690/Math.tan(Math.toRadians(right.leftDegrees));
            double fromRight=725+2690/Math.tan(Math.toRadians(right.rightDegrees));
            double expected=1/right.curvaturePerMm;
            assertEquals(expected,fromLeft,expected*2e-7);
            assertEquals(expected,fromRight,expected*2e-7);
        }
    }

    @Test public void zeroTinyAnglesAndInvalidInputDoNotCreateInfiniteGeometryOrFakeStraightReadings() {
        SteeringModel.Solution zero=model.solve(-0f);
        assertTrue(zero.valid);assertEquals(0,zero.curvaturePerMm,0);assertEquals(0,zero.leftDegrees,0);
        assertTrue(model.solve(-.000001f).curvaturePerMm>0);
        assertTrue(model.solve(.000001f).curvaturePerMm<0);
        for(float bad:new float[]{Float.NaN,Float.POSITIVE_INFINITY,Float.NEGATIVE_INFINITY,517,-517,
                Math.nextUp(SteeringSnapshot.MAX_RAW_DEGREES),Math.nextDown(-SteeringSnapshot.MAX_RAW_DEGREES)}) {
            assertFalse(model.solve(bad).valid);assertTrue(Double.isNaN(model.solve(bad).curvaturePerMm));
        }
        assertTrue(Float.isNaN(OemSteeringCurve.magnitudeDegrees(-1)));
        assertFalse(model.fromCurvature(Double.NaN).valid);assertFalse(model.fromCurvature(1).valid);
    }

    @Test public void staleAndUnavailableSamplesHoldLastValidPoseAndRecoverWithoutIndependentWheelFilters() {
        SteeringModelPose pose=new SteeringModelPose(model);
        assertFalse(pose.update(SteeringSnapshot.EMPTY,1000).hasValue);
        SteeringSnapshot sample=SteeringSnapshot.value(-199.3f,0,1000,1);
        SteeringModelPose.Frame first=pose.update(sample,1000);
        assertTrue(first.available);assertTrue(pose.update(sample,1500).available);
        SteeringModelPose.Frame stale=pose.update(sample,1501);
        assertFalse(stale.available);assertTrue(stale.hasValue);
        assertTrue(stale.displayed.valid);
        assertEquals(first.displayed.curvaturePerMm,stale.displayed.curvaturePerMm,0);
        assertEquals(stale.displayed.leftDegrees,pose.update(SteeringSnapshot.EMPTY,1510).displayed.leftDegrees,0);
        SteeringModelPose.Frame recovered=pose.update(SteeringSnapshot.value(199.3f,0,1520,1),1520);
        assertTrue(recovered.available);
        assertTrue(recovered.displayed.curvaturePerMm<stale.displayed.curvaturePerMm);
        assertTrue(recovered.displayed.curvaturePerMm>model.solve(199.3f).curvaturePerMm);
        SteeringModel.Solution sameCenter=model.fromCurvature(recovered.displayed.curvaturePerMm);
        assertEquals(sameCenter.leftDegrees,recovered.displayed.leftDegrees,0);
        assertEquals(sameCenter.rightDegrees,recovered.displayed.rightDegrees,0);
        SteeringModelPose restored=new SteeringModelPose(model);restored.restore(stale.displayed.curvaturePerMm);
        assertEquals(stale.displayed.curvaturePerMm,restored.update(SteeringSnapshot.EMPTY,2000).displayed.curvaturePerMm,0);
    }

    @Test public void displayFilterHasTheSameResponseAtDifferentFrameRatesAndPausesItsClock() {
        SteeringModelPose fast=new SteeringModelPose(model),slow=new SteeringModelPose(model);
        fast.update(SteeringSnapshot.value(0,0,1000,0),1000);slow.update(SteeringSnapshot.value(0,0,1000,0),1000);
        SteeringSnapshot target=SteeringSnapshot.value(398.61f,0,1000,0);
        SteeringModelPose.Frame a=null,b=null;
        for(int time=1010;time<=1200;time+=10)a=fast.update(target,time);
        for(int time=1050;time<=1200;time+=50)b=slow.update(target,time);
        assertEquals(a.displayed.curvaturePerMm,b.displayed.curvaturePerMm,1e-18);
        double before=a.displayed.curvaturePerMm;fast.pause();
        assertEquals(before,fast.update(SteeringSnapshot.value(0,0,10000,0),10000).displayed.curvaturePerMm,0);
    }

    @Test public void rendererPairIsAtomicAndLegacyVisualLimitDoesNotClampModelAngles() {
        WheelSteering wheels=new WheelSteering();wheels.setAngles(28.85f,38.08f);
        WheelSteering.Angles pair=wheels.angles();
        assertEquals(28.85f,pair.forPart("front_left"),0);assertEquals(38.08f,pair.forPart("front_right"),0);
        assertEquals(0,pair.forPart("rear_right"),0);
        wheels.setAngles(Float.NaN,0);assertSame(pair,wheels.angles());
        wheels.setDegrees(100);assertEquals(35,wheels.angles().left,0);assertEquals(35,wheels.angles().right,0);
        assertEquals(38.08f,pair.right,0);
    }
}
