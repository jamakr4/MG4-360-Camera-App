package com.drivehub.kamera.birdseye;

import org.junit.Test;
import static org.junit.Assert.*;

public class MainCameraGeometryTest {
    @Test public void referencePhysicalRectanglesAreExact() {
        MainCameraGeometry g=new MainCameraGeometry(1920,640);
        assertArrayEquals(new int[]{0,0,540,640},g.panels[0]);
        assertArrayEquals(new int[]{540,0,1470,640},g.panels[1]);
        assertArrayEquals(new int[]{1470,0,1920,640},g.panels[2]);
    }
    @Test public void commonScaleCentersLetterboxedAndNarrowWindows() {
        MainCameraGeometry g=new MainCameraGeometry(960,800);
        assertArrayEquals(new int[]{0,240,270,560},g.panels[0]);
        assertArrayEquals(new int[]{270,240,735,560},g.panels[1]);
        assertArrayEquals(new int[]{735,240,960,560},g.panels[2]);
        g=new MainCameraGeometry(2200,640);
        assertArrayEquals(new int[]{140,0,680,640},g.panels[0]);
        assertArrayEquals(new int[]{1610,0,2060,640},g.panels[2]);
    }
    @Test public void roundingNeverIntroducesGaps() {
        for(int width=100;width<2500;width+=37) {
            MainCameraGeometry g=new MainCameraGeometry(width,719);
            assertEquals(g.panels[0][2],g.panels[1][0]);
            assertEquals(g.panels[1][2],g.panels[2][0]);
            for(int[] p:g.panels) { assertTrue(p[0]>=0);assertTrue(p[2]<=width);assertTrue(p[3]<=719); }
        }
    }
    @Test public void receiptAgeAndSkewExcludeMissingStaleAndFutureFrames() {
        long now=1_000_000;
        assertEquals(15,BirdseyeFrameHealth.freshMask(new long[]{now,now-10_000,now-20_000,now},now));
        assertEquals(9,BirdseyeFrameHealth.freshMask(new long[]{now,now-200_000,0,now},now));
        assertEquals(0,BirdseyeFrameHealth.freshMask(new long[]{now-500_001,0,now+1,0},now));
        assertEquals(15,BirdseyeFrameHealth.freshMask(new long[]{now-500_000,now-500_000,now-500_000,now-500_000},now));
    }
}
