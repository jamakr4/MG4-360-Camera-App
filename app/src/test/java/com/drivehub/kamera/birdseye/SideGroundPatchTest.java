package com.drivehub.kamera.birdseye;

import org.junit.Test;
import static org.junit.Assert.*;

public class SideGroundPatchTest {
    private final BirdseyeCalibration calibration=BirdseyeCalibration.parse(BirdseyeSynthetic.calibrationBytes());
    @Test public void orderedFourPointWarpHitsEveryCornerAndKeepsGroundLinesStraight(){
        double[] quad={-2400,-1800,-900,-1700,-1000,1800,-2500,1600};
        SideGroundPatch.Warp warp=new SideGroundPatch.Warp(quad);
        double[][] unit={{0,0},{1,0},{1,1},{0,1}};
        for(int i=0;i<4;i++)assertArrayEquals(new double[]{quad[2*i],quad[2*i+1]},warp.ground(unit[i][0],unit[i][1]),1e-7);
        double[] a=warp.ground(0,.4),b=warp.ground(1,.4),m=warp.ground(.3,.4);
        assertEquals(0,(b[0]-a[0])*(m[1]-a[1])-(b[1]-a[1])*(m[0]-a[0]),1e-6);
    }
    @Test public void selectedReferencePixelsRecoverTheGroundWithoutMirroringSides(){
        for(int camera=0;camera<2;camera++)for(int region=0;region<3;region++){
            double[] ground=SideGroundPatch.defaults(calibration,camera,region);
            assertArrayEquals(ground,SideGroundPatch.fromImageCorners(calibration,camera,SideGroundPatch.imageCorners(calibration,camera,ground)),1e-5);
            assertTrue(ground[0]<ground[2]);assertTrue(ground[1]<ground[5]);
            assertTrue(camera==0?ground[2]<calibration.centerX:ground[0]>calibration.centerX);
        }
    }
    @Test public void foldsCollinearPointsNonFiniteAndOffLensSelectionsAreRejected(){
        for(double[] bad:new double[][]{{0,0,1,1,2,2,3,3},{0,0,1000,1000,0,1000,1000,0},{0,0,1000,0,1000,Double.NaN,0,1000}}){
            try{new SideGroundPatch.Warp(bad);fail();}catch(IllegalArgumentException expected){}
        }
        try{SideGroundPatch.fromImageCorners(calibration,0,new double[8]);fail();}catch(IllegalArgumentException expected){}
        try{new SideGroundPatch.Map(calibration,0,SideGroundPatch.defaults(calibration,0,0),1025,100);fail();}catch(IllegalArgumentException expected){}
    }
    @Test public void inverseSamplingRecoversKnownGroundGradientInsteadOfJustRotatingPixels(){
        int[] image=new int[720*480];
        for(int y=0;y<480;y++)for(int x=0;x<720;x++){
            double[] p=calibration.groundPoint(0,x,y);image[y*720+x]=0xFF000000|(p==null?0:BirdseyeSynthetic.groundColor(p[0],p[1]));
        }
        double[] ground=SideGroundPatch.defaults(calibration,0,SideGroundPatch.FRONT);
        SideGroundPatch.Map map=new SideGroundPatch.Map(calibration,0,ground,80,64);
        int[] actual=map.render(image);SideGroundPatch.Warp warp=new SideGroundPatch.Warp(ground);int tested=0;
        for(int y=0;y<64;y+=3)for(int x=0;x<80;x+=3){
            int color=actual[y*80+x];if(color==SideGroundPatch.MISSING)continue;
            double[] p=warp.ground(x/79.0,y/63.0);int expected=BirdseyeSynthetic.groundColor(p[0],p[1]);
            for(int shift=0;shift<24;shift+=8)assertEquals((expected>>>shift)&255,(color>>>shift)&255,3);
            tested++;
        }
        assertTrue(tested>400);
        java.util.Arrays.fill(image,0xFF000000);int[] black=map.render(image);
        for(int value:black)assertTrue(value==0xFF000000||value==SideGroundPatch.MISSING);
    }
    @Test public void parkingStripColumnsFollowTheVehicleSideRatherThanTheImageCentre(){
        for(int camera=0;camera<2;camera++){
            double[] quad=SideGroundPatch.defaults(calibration,camera,SideGroundPatch.SIDE);
            SideGroundPatch.Map map=new SideGroundPatch.Map(calibration,camera,quad,254,456);
            SideGroundPatch.Warp plane=new SideGroundPatch.Warp(quad);int checked=0;
            for(int x:new int[]{32,127,220})for(int y=8;y<448;y+=17){
                double[] uv=map.sourcePixel(x,y);if(!Double.isFinite(uv[0]))continue;
                double[] actual=calibration.groundPoint(camera,uv[0],uv[1]),expected=plane.ground(x/253.0,y/455.0);
                assertNotNull(actual);assertArrayEquals(expected,actual,.05);checked++;
            }
            assertTrue(checked>60);
            // A horizontal 3D sill parallel to the vehicle remains a straight line when
            // its camera rays intersect the road plane, even though it is above that plane.
            BirdseyeCalibration.CameraParameters c=calibration.cameraParameters(camera);
            double[] rotation=c.rotation(),translation=c.translation();Double firstX=null;
            for(double y=-1500;y<=1500;y+=150){
                double[] point={camera==0?-918:918,y,200},q=translation.clone();
                for(int row=0;row<3;row++)for(int col=0;col<3;col++)q[row]+=rotation[row*3+col]*point[col];
                double r=Math.hypot(q[0],q[1]),theta=Math.atan2(r,q[2]);
                double[] ground=calibration.groundPoint(camera,c.cx+c.fx*q[0]*theta/r,c.cy+c.fy*q[1]*theta/r);
                assertNotNull(ground);double x=(ground[0]-quad[0])/(quad[2]-quad[0])*253;
                if(firstX==null)firstX=x;else assertEquals(firstX,x,1e-6);
            }
        }
    }
    @Test public void aRotatedCalibrationRigKeepsTheParkingStripAlignedWithTheCar(){
        byte[] data=BirdseyeSynthetic.calibrationBytes();
        java.nio.ByteBuffer bytes=java.nio.ByteBuffer.wrap(data).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        double yaw=.32;
        for(int camera=0;camera<4;camera++){
            int at=(1+camera*16+10)*8;
            bytes.putDouble(at,Math.PI*Math.cos(yaw/2));bytes.putDouble(at+8,Math.PI*Math.sin(yaw/2));
        }
        BirdseyeCalibration rotated=BirdseyeCalibration.parse(data);
        for(int camera=0;camera<2;camera++){
            float[] original=new SideGroundPatch.Map(calibration,camera,SideGroundPatch.defaults(calibration,camera,0),254,456).coordinates();
            float[] transformed=new SideGroundPatch.Map(rotated,camera,SideGroundPatch.defaults(rotated,camera,0),254,456).coordinates();
            assertArrayEquals(original,transformed,1e-4f);
        }
    }
}
