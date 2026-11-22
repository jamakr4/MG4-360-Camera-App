package com.drivehub.kamera.camera;

import com.drivehub.kamera.birdseye.BirdseyeCalibration;
import com.drivehub.kamera.birdseye.BirdseyeSynthetic;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.junit.Test;
import static org.junit.Assert.*;

public class RearviewLensMapTest {
    private BirdseyeCalibration calibration(){return BirdseyeCalibration.parse(BirdseyeSynthetic.calibrationBytes());}
    @Test public void opticalCentreAndHundredDegreeHorizontalFovArePreservedBeforeMirroring() {
        BirdseyeCalibration c=calibration();RearviewLensMap map=new RearviewLensMap(c);
        float[] uv=map.coordinates();assertEquals(260*173*2,uv.length);assertEquals(c.sha256,map.calibrationSha256);
        int middle=(86*260)*2;
        assertEquals(240,uv[middle+1],.0001);
        // Independent inverse of the equidistant synthetic lens: output pixel centres span 100 degrees.
        double focal=260/(2*Math.tan(Math.toRadians(50)));
        assertEquals(Math.atan(129.5/focal),Math.abs(uv[middle]-360)/180.0,1e-6);
        for(int y=0;y<173;y++)for(int x=0;x<260;x++) {
            int i=(y*260+x)*2,opposite=(y*260+259-x)*2;
            assertTrue(uv[i]>=0&&uv[i]<=719);assertTrue(uv[i+1]>=0&&uv[i+1]<=479);
            assertEquals(720,uv[i]+uv[opposite],.0001);
            if(x>0)assertTrue(uv[i]>uv[i-2]);
        }
        uv[0]=-1;assertTrue(map.coordinates()[0]>=0);
    }
    @Test public void mapUsesRearIntrinsicsAndFisheyePolynomial() {
        byte[] bytes=BirdseyeSynthetic.calibrationBytes();ByteBuffer b=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        b.putDouble(49*8,200);b.putDouble(50*8,190);
        b.putDouble(51*8,350);b.putDouble(52*8,238);
        b.putDouble(55*8,.1);b.putDouble(56*8,-.02);
        BirdseyeCalibration c=BirdseyeCalibration.parse(bytes);float[] uv=new RearviewLensMap(c).coordinates();
        double f=260/(2*Math.tan(Math.toRadians(50))),theta=Math.atan(129.5/f),t=theta*theta;
        double radius=theta*(1+.1*t-.02*t*t);
        int right=(86*260+259)*2;
        assertEquals(350+200*radius,uv[right],.0001);
        assertEquals(238,uv[right+1],.0001);
    }
    @Test public void calibratedViewWithoutCompleteCoverageIsRejectedForWideFallback() {
        byte[] bytes=BirdseyeSynthetic.calibrationBytes();
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putDouble(51*8,0);
        try{new RearviewLensMap(BirdseyeCalibration.parse(bytes));fail("Uncovered Normal view accepted");}
        catch(IllegalArgumentException expected){assertTrue(expected.getMessage().contains("Rearview"));}
    }
}
