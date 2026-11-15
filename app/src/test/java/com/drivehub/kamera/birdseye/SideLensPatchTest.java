package com.drivehub.kamera.birdseye;

import org.junit.Test;
import static org.junit.Assert.*;

public class SideLensPatchTest {
    private final BirdseyeCalibration calibration=BirdseyeCalibration.parse(BirdseyeSynthetic.calibrationBytes());
    @Test public void symmetricFisheyePatchBecomesRectilinearWithExactCentreAndCorners(){
        double[] points={260,170,460,170,460,310,260,310};
        SideLensPatch.Map map=new SideLensPatch.Map(calibration,0,points,201,141);
        assertArrayEquals(new double[]{360,240},map.sourcePixel(100,70),1e-4);
        assertArrayEquals(new double[]{260,170},map.sourcePixel(0,0),1e-3);
        assertArrayEquals(new double[]{460,310},map.sourcePixel(200,140),1e-3);
        assertTrue(Math.abs(map.sourcePixel(100,0)[1]-170)>5); // nonlinear, not rotation/resize
    }
    @Test public void allFourWheelSelectionsRemainBoundedAndPreserveRealBlackPixels(){
        int[] black=new int[720*480];java.util.Arrays.fill(black,0xFF000000);
        for(int camera=0;camera<2;camera++)for(boolean front:new boolean[]{true,false}){
            double[] points=SideLensPatch.defaults(calibration,camera,front);
            SideLensPatch.Map map=new SideLensPatch.Map(calibration,camera,points,254,224);
            assertTrue(map.covered>10000);assertTrue(map.covered<=254*224);
            for(int value:map.render(black))assertTrue(value==0xFF000000||value==SideGroundPatch.MISSING);
        }
    }
    @Test public void offLensFoldedAndNonFinitePointsCannotProduceAWarp(){
        for(double[] points:new double[][]{{0,0,719,0,719,479,0,479},{260,170,460,310,460,170,260,310},{Double.NaN,170,460,170,460,310,260,310}}){
            try{new SideLensPatch.Map(calibration,0,points,254,224);fail();}catch(IllegalArgumentException expected){}
        }
    }
    @Test public void cylindricalPortraitUsesUniformAnglesAndKeepsTheOpticalCentre(){
        SideLensPatch.Map map=new SideLensPatch.Map(calibration,0,new double[]{260,170,460,170,460,310,260,310},201,141,true);
        assertArrayEquals(new double[]{360,240},map.sourcePixel(100,70),1e-4);
        double theta=Math.hypot(100,70)/180.0;
        double topAngle=Math.atan2(-70/180.0*Math.sin(theta)/theta,Math.cos(theta));
        double expected=240+180*topAngle;
        assertArrayEquals(new double[]{360,expected},map.sourcePixel(100,0),1e-3);
        assertArrayEquals(new double[]{360,(240+expected)/2},map.sourcePixel(100,35),1e-3);
    }
    @Test public void bothFullSidesHaveBoundedNonEmptyPortraitCoverage(){
        for(int camera=0;camera<2;camera++){
            SideLensPatch.Map map=new SideLensPatch.Map(calibration,camera,SideLensPatch.sideDefaults(calibration,camera),254,456,true);
            assertTrue(map.covered>20000);assertEquals(254*456*2,map.coordinates().length);
            float[] uv=map.coordinates();
            for(int i=0;i<uv.length;i+=2)assertTrue(Float.isNaN(uv[i])&&Float.isNaN(uv[i+1])
                    ||uv[i]>=0&&uv[i]<=719&&uv[i+1]>=0&&uv[i+1]<=479);
        }
    }
}
