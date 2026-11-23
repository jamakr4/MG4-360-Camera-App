package com.drivehub.kamera.steering;

import org.junit.Test;
import static org.junit.Assert.*;

public class GuidelineWidthTest {
    @Test public void presetsResolveIndependentlyOfTheRememberedCustomWidth() {
        assertEquals(2060,GuidelineWidth.resolveMm(GuidelineWidth.Mode.MIRRORS,2500));
        assertEquals(1836,GuidelineWidth.resolveMm(GuidelineWidth.Mode.BODY,2500));
        assertEquals(2500,GuidelineWidth.resolveMm(GuidelineWidth.Mode.CUSTOM,2500));
        assertEquals(GuidelineWidth.Mode.MIRRORS,GuidelineWidth.Mode.fromId(null));
        assertEquals(GuidelineWidth.Mode.MIRRORS,GuidelineWidth.Mode.fromId("future_mode"));
        assertEquals(GuidelineWidth.Mode.BODY,GuidelineWidth.Mode.fromId("body"));
        assertEquals(GuidelineWidth.Mode.CUSTOM,GuidelineWidth.Mode.fromId("custom"));
    }
    @Test public void customAllowsPositiveWholeMillimetresIncludingNarrowWidths() {
        for(int width:new int[]{1,1000,1836,2060,3000}) {
            assertEquals(Integer.valueOf(width),GuidelineWidth.parseCustomMm(" "+width+" "));
            assertEquals(width,GuidelineWidth.resolveMm(GuidelineWidth.Mode.CUSTOM,width));
        }
        for(String invalid:new String[]{""," ","0","-1","3001","2060.5","2060,5","abc","999999999999999999999"})
            assertNull(invalid,GuidelineWidth.parseCustomMm(invalid));
        for(int invalid:new int[]{Integer.MIN_VALUE,-1,0,3001,Integer.MAX_VALUE})
            assertEquals(2060,GuidelineWidth.resolveMm(GuidelineWidth.Mode.CUSTOM,invalid));
    }
}
