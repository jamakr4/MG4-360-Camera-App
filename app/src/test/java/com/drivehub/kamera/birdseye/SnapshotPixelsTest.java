package com.drivehub.kamera.birdseye;
import org.junit.Test;
import static org.junit.Assert.*;
public class SnapshotPixelsTest {
    private byte[] rows(int... luminance){byte[] bytes=new byte[luminance.length*4];for(int y=0;y<luminance.length;y++){int i=y*4;bytes[i]=(byte)128;bytes[i+1]=(byte)luminance[y];bytes[i+2]=(byte)128;bytes[i+3]=(byte)luminance[y];}return bytes;}
    @Test public void stackedFieldsAreSpatiallyWovenNotBlindlyResizedOrMirrored(){
        int[] tb=SnapshotPixels.argb(rows(16,60,120,235),2,4,5);
        assertEquals(0xFF000000,tb[0]);assertEquals(0xFFFFFFFF,tb[6]);
        int[] bt=SnapshotPixels.argb(rows(120,235,16,60),2,4,6);assertArrayEquals(tb,bt);
        assertArrayEquals(tb,SnapshotPixels.argb(rows(16,120,60,235),2,4,4));
        assertArrayEquals(tb,SnapshotPixels.argb(rows(16,60,120,235),2,4,1));
    }
    @Test public void uyvyPairUsesDistinctLumaAndPreservesLeftRight(){
        byte[] bytes=rows(16,16);bytes[3]=(byte)235;int[] p=SnapshotPixels.argb(bytes,2,2,4);
        assertEquals(0xFF000000,p[0]);assertEquals(0xFFFFFFFF,p[1]);
    }
    @Test public void chromaUsesUyvyOrderAndLimitedRangeBt601(){
        byte[] bytes={(byte)90,81,(byte)240,81,(byte)240,41,(byte)110,41};
        int[] pixels=SnapshotPixels.argb(bytes,2,2,4);
        assertEquals(255,(pixels[0]>>>16)&255,1);assertEquals(0,pixels[0]&0xFFFF);
        assertEquals(0xFF0000FF,pixels[2]);
    }
    @Test public void unsupportedSingleFieldsAndInvalidBoundsFailClearly(){
        for(int field:new int[]{2,3,7,99})try{SnapshotPixels.argb(rows(16,16),2,2,field);fail();}catch(IllegalArgumentException expected){}
        try{SnapshotPixels.argb(new byte[3],2,2,5);fail();}catch(IllegalArgumentException expected){}
    }
}
