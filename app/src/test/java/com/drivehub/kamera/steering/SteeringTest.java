package com.drivehub.kamera.steering;

import org.junit.Test;
import static org.junit.Assert.*;

public class SteeringTest {
    public static final class Property {
        private final Object raw;
        private final int status;
        Property(Object raw,int status) { this.raw=raw;this.status=status; }
        public Object getValue() { return raw; }
        public int getStatus() { return status; }
        public long getTimestamp() { return 123; }
    }
    public static final class LegacyProperty {
        public Float getValue() { return -112.75f; }
        public int getStatus() { return 0; }
    }
    @Test public void bothCarApiOverloadsPreserveFractionalFloatAndTimestamp() throws Exception {
        SteeringSnapshot modern=SteeringSnapshot.decode(new Property(112.75f,0),false,1000);
        assertTrue(modern.available); assertEquals(112.75f,modern.rawDegrees,0);
        assertEquals(123,modern.sensorTimestampNs);
        assertEquals(112.75f,SteeringSnapshot.decode(new Property(112.75f,0),true,1000).rawDegrees,0);
        assertEquals(-112.75f,SteeringSnapshot.decode(-112.75f,true,1000).rawDegrees,0);
        assertEquals(-112.75f,SteeringSnapshot.decode(new LegacyProperty(),false,1000).rawDegrees,0);
    }
    @Test public void unavailableStatusWrongTypesAndSentinelsNeverTurnTheModel() throws Exception {
        assertEquals("property status=1",SteeringSnapshot.decode(new Property(null,1),false,1000).state);
        for(Object bad:new Object[]{null,123,123.0,"123"}) {
            assertFalse(SteeringSnapshot.decode(bad,true,1000).available);
            assertFalse(SteeringSnapshot.decode(new Property(bad,0),false,1000).available);
        }
        for(float bad:new float[]{Float.NaN,Float.POSITIVE_INFINITY,-Float.MAX_VALUE,517,-517}) {
            assertFalse(SteeringSnapshot.value(bad,0,1000,0).available);
            assertEquals(0,SteeringMapping.degrees(bad,1,false),0);
        }
    }
    @Test public void freshnessUsesReadTimeIncludingHeldAnglesAndRejectsClockReversal() {
        SteeringSnapshot held=SteeringSnapshot.value(100,0,1000,1);
        assertTrue(held.fresh(1500));assertFalse(held.fresh(1501));assertFalse(held.fresh(999));
        assertTrue(SteeringSnapshot.value(100,0,1501,1).fresh(1501));
        assertFalse(SteeringSnapshot.EMPTY.fresh(0));
    }
    @Test public void saicPositiveLeftMapsToNegativeModelAngleWithOptionalInversionAndLimits() {
        assertEquals(14.77f,SteeringMapping.DEFAULT_SCALE,.0001f);
        assertEquals(-10,SteeringMapping.degrees(147.7f,SteeringMapping.DEFAULT_SCALE,false),.001f);
        assertEquals(10,SteeringMapping.degrees(147.7f,SteeringMapping.DEFAULT_SCALE,true),.001f);
        assertEquals(20,SteeringMapping.degrees(-200,10,false),0);
        assertEquals(-35,SteeringMapping.degrees(516.95f,1,false),0);
        assertEquals(35,SteeringMapping.degrees(-516.95f,1,false),0);
        assertEquals(-35,SteeringMapping.degrees(516.95f,SteeringMapping.DEFAULT_SCALE,false),.001f);
        assertEquals(0,SteeringMapping.degrees(0,1,true),0);
    }
    @Test public void corruptPreferencesCannotDivideByZeroOrReverseDirection() {
        for(float bad:new float[]{0,-1,.99f,30.1f,Float.NaN,Float.POSITIVE_INFINITY})
            assertEquals(SteeringMapping.DEFAULT_SCALE,SteeringMapping.scale(bad),0);
        assertEquals(1,SteeringMapping.scale(1),0);assertEquals(30,SteeringMapping.scale(30),0);
    }
    @Test public void poseSmoothsChangingValuesButClearsImmediatelyOnMissingOrExpiredInput() {
        SteeringPose pose=new SteeringPose();
        assertEquals(-10,pose.update(SteeringSnapshot.value(100,0,1000,0),1000,10,false),0);
        float moving=pose.update(SteeringSnapshot.value(-100,0,1050,0),1050,10,false);
        assertTrue(moving<10&&moving>-10);
        assertEquals(0,pose.update(SteeringSnapshot.EMPTY,1100,10,false),0);
        assertEquals(10,pose.update(SteeringSnapshot.value(-100,0,1100,0),1100,10,false),0);
        assertEquals(0,pose.update(SteeringSnapshot.value(-100,0,1100,0),1601,10,false),0);
        pose.reset();assertEquals(-10,pose.update(SteeringSnapshot.value(100,0,1700,0),1700,10,false),0);
    }
}
