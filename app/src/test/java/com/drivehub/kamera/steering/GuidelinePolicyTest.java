package com.drivehub.kamera.steering;

import org.junit.Test;
import static org.junit.Assert.*;

public class GuidelinePolicyTest {
    private boolean visible(boolean main,boolean limit,int speedLimit,float speed,long sampled,long now) {
        return GuidelinePolicy.visible(main,limit,speedLimit,VehicleMotionSnapshot.value(speed,4,sampled),now);
    }
    @Test public void boundaryIsInclusiveAndVisibilityRecoversWithoutChangingTheMainSwitch() {
        assertTrue(visible(true,true,25,24.9f,1000,1000));
        assertTrue(visible(true,true,25,25,1000,1000));
        assertFalse(visible(true,true,25,25.01f,1000,1000));
        assertTrue(visible(true,true,25,20,1100,1100));
        assertFalse(visible(false,true,25,20,1100,1100));
    }
    @Test public void disabledFilterNeedsNoSpeedButStillRespectsTheMainSwitch() {
        assertTrue(GuidelinePolicy.visible(true,false,25,VehicleMotionSnapshot.EMPTY,1000));
        assertTrue(visible(true,false,25,200,1000,1000));
        assertFalse(visible(false,false,25,0,1000,1000));
    }
    @Test public void enabledFilterRequiresCurrentFiniteSpeed() {
        assertFalse(GuidelinePolicy.visible(true,true,25,VehicleMotionSnapshot.EMPTY,1000));
        assertFalse(visible(true,true,25,Float.NaN,1000,1000));
        assertFalse(visible(true,true,25,Float.POSITIVE_INFINITY,1000,1000));
        assertFalse(visible(true,true,25,-1,1000,1000));
        assertTrue(visible(true,true,25,5,1000,1500));
        assertFalse(visible(true,true,25,5,1000,1501));
        assertFalse(visible(true,true,25,5,1001,1000));
    }
    @Test public void zeroLimitAndClampedLimitsHaveDefinedBehaviour() {
        assertEquals(25,GuidelinePolicy.DEFAULT_SPEED_KMH);
        assertEquals(0,GuidelinePolicy.clampSpeedKmh(-1));
        assertEquals(130,GuidelinePolicy.clampSpeedKmh(500));
        assertTrue(visible(true,true,0,0,1000,1000));
        assertFalse(visible(true,true,0,.1f,1000,1000));
        assertTrue(visible(true,true,500,130,1000,1000));
        assertFalse(visible(true,true,500,131,1000,1000));
    }
}
