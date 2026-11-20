package com.drivehub.kamera.birdseye;

import org.junit.Test;
import static org.junit.Assert.*;

public class BirdseyeRearMaskTest {
    @Test public void rearContourKeepsGroundAndFeathersItsSelfBodyBoundary() {
        assertEquals(1,BirdseyeRearMask.visibility(350,390),0);
        assertEquals(0,BirdseyeRearMask.visibility(350,460),0);
        assertEquals(0,BirdseyeRearMask.visibility(100,390),0);
        double transition=BirdseyeRearMask.visibility(350,414);
        assertTrue(transition>0 && transition<1);
        assertEquals(1,BirdseyeRearMask.visibility(0,300),0);
        assertEquals(1,BirdseyeRearMask.visibility(719,300),0);
    }

    @Test public void bakedCoverPreservesBodyPaintAndLeavesGroundOutsideItsRegionTransparent() {
        int width=768,height=1536,first=BirdseyeRearMask.firstShadowRow(height);
        BirdseyeCarGeometry geometry=new BirdseyeCarGeometry(width,height,
                .10512996465,.04582828283,.89487004280,.95276959240);
        int[] rows=new int[width*(height-first)];
        for(int y=1100;y<1460;y++)for(int x=120;x<648;x++)rows[(y-first)*width+x]=0xFFF08030;
        int hole=(1400-first)*width+384,opaque=(1390-first)*width+384;
        int partial=(1402-first)*width+384,ground=(height-1-first)*width+384;
        rows[hole]=0;rows[partial]=0x80F08030;
        BirdseyeRearMask.addShadow(rows,width,height,first,geometry);
        assertEquals(0xFFF08030,rows[opaque]);
        assertEquals(255,rows[hole]>>>24);
        assertEquals(255,rows[partial]>>>24);
        assertTrue(((rows[partial]>>16)&255)>((rows[hole]>>16)&255));
        assertEquals(0,rows[ground]);
        assertEquals(0,rows[(1400-first)*width]);
    }
}
