package com.drivehub.kamera.birdseye;

import org.junit.Test;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import static org.junit.Assert.*;

public class WheelSillPatchTest {
    /** Generic tilted side cameras, independent of all private captures/calibration. */
    private static BirdseyeCalibration rig(double yaw, double heightSign) {
        ByteBuffer data=ByteBuffer.wrap(BirdseyeSynthetic.calibrationBytes()).order(ByteOrder.LITTLE_ENDIAN);
        double[][] centers={{-1000,-500,1000*heightSign},{1000,-500,1000*heightSign},
                {0,-2100,1000*heightSign},{0,2100,1000*heightSign}};
        for(int camera=0;camera<4;camera++){
            double[][] r;
            if(camera<2){
                double side=camera==0?-1:1;
                double[] forward={side*.5,0,-heightSign*Math.sqrt(.75)},right={0,side,0};
                double[] down={-forward[2]*right[1],0,forward[0]*right[1]};
                r=new double[][]{right,down,forward};
            }else r=new double[][]{{1,0,0},{0,-1,0},{0,0,-1}};
            double cos=Math.cos(yaw),sin=Math.sin(yaw);double[] center=centers[camera];
            double[] world={cos*center[0]-sin*center[1],sin*center[0]+cos*center[1],center[2]};
            for(int row=0;row<3;row++){
                double x=r[row][0],y=r[row][1];r[row][0]=x*cos-y*sin;r[row][1]=x*sin+y*cos;
            }
            double theta=Math.acos(Math.max(-1,Math.min(1,(r[0][0]+r[1][1]+r[2][2]-1)/2)));
            double[] rotation;
            if(Math.abs(theta-Math.PI)<1e-6)rotation=new double[]{Math.PI*Math.cos(yaw/2),Math.PI*Math.sin(yaw/2),0};
            else{double scale=theta/(2*Math.sin(theta));rotation=new double[]{(r[2][1]-r[1][2])*scale,(r[0][2]-r[2][0])*scale,(r[1][0]-r[0][1])*scale};}
            int at=(1+16*camera+10)*8;
            for(int i=0;i<3;i++)data.putDouble(at+8*i,rotation[i]);
            for(int i=0;i<3;i++)data.putDouble(at+24+8*i,-r[i][0]*world[0]-r[i][1]*world[1]-r[i][2]*world[2]);
        }
        return BirdseyeCalibration.parse(data.array());
    }

    private static double[] project(BirdseyeCalibration c,int side,double x,double y,double z){
        BirdseyeCalibration.CameraParameters p=c.cameraParameters(side);
        double[] r=p.rotation(),q=p.translation(),k=p.distortion();
        for(int row=0;row<3;row++)q[row]+=r[row*3]*x+r[row*3+1]*y+r[row*3+2]*z;
        double radial=Math.hypot(q[0],q[1]),theta=Math.atan2(radial,q[2]),a=theta*theta;
        if(theta>WheelSillPatch.MAX_THETA)return null;
        double factor=radial<1e-12?0:theta*(1+a*(k[0]+a*(k[1]+a*(k[2]+a*k[3]))))/radial;
        double u=p.cx+p.fx*q[0]*factor,v=p.cy+p.fy*q[1]*factor;
        return u<0||u>=719||v<0||v>=479?null:new double[]{u,v};
    }

    @Test public void elevatedSillsFollowOneOutputColumnWithoutAGroundIntersection(){
        for(double sign:new double[]{-1,1}){
            BirdseyeCalibration c=rig(0,sign);
            for(int side=0;side<2;side++)for(boolean front:new boolean[]{true,false}){
                WheelSillPatch.Map map=new WheelSillPatch.Map(c,side,front,254,224);
                for(double height:new double[]{150,350,650}){
                    Double column=null,previousY=null;int checked=0;
                    for(double along=(front?-1900:600);along<=(front?-600:2400);along+=100){
                        double[] uv=project(c,side,side==0?-918:918,along,sign*height);
                        if(uv==null)continue;
                        double[] point=map.outputPixel(uv[0],uv[1]);assertNotNull(point);
                        if(column==null)column=point[0];else assertEquals(column,point[0],1e-6);
                        if(previousY!=null)assertTrue("Front/rear must keep upright order",point[1]>previousY);
                        previousY=point[1];checked++;
                    }
                    assertTrue(checked>=4);
                }
            }
        }
    }

    @Test public void boundedMapsRoundTripRaysAndKeepLeftRightBodyEdgesInside(){
        BirdseyeCalibration c=rig(0,-1);
        for(int side=0;side<2;side++)for(boolean front:new boolean[]{true,false}){
            WheelSillPatch.Map map=new WheelSillPatch.Map(c,side,front,254,224);
            assertTrue(map.covered>254*224/2);
            for(int y=4;y<220;y+=13)for(int x=4;x<250;x+=13){
                double[] uv=map.sourcePixel(x,y);if(!Double.isFinite(uv[0]))continue;
                assertTrue(uv[0]>=0&&uv[0]<719&&uv[1]>=0&&uv[1]<479);
                assertArrayEquals(new double[]{x,y},map.outputPixel(uv[0],uv[1]),.001);
            }
            float[] defensive=map.coordinates();defensive[0]=Float.NaN;
            assertNotSame(defensive,map.coordinates());
            assertTrue(map.innerAngle<map.outerAngle);assertTrue(map.frontAngle<map.rearAngle);
        }
    }

    @Test public void rotatingTheWorldRigDoesNotRotateTheWheelFraming(){
        BirdseyeCalibration a=rig(0,-1),b=rig(.37,-1);
        for(int side=0;side<2;side++)for(boolean front:new boolean[]{true,false}){
            float[] original=new WheelSillPatch.Map(a,side,front,64,56).coordinates();
            float[] rotated=new WheelSillPatch.Map(b,side,front,64,56).coordinates();
            assertArrayEquals(original,rotated,.001f);
        }
    }

    @Test public void nearFieldFringeCanSurviveTheGroundViewsEightyEightDegreeCutoff(){
        BirdseyeCalibration c=rig(0,-1);int fringe=0;
        for(int side=0;side<2;side++)for(boolean front:new boolean[]{true,false}){
            WheelSillPatch.Map map=new WheelSillPatch.Map(c,side,front,254,224);
            BirdseyeCalibration.CameraParameters p=c.cameraParameters(side);
            for(int y=0;y<224;y++)for(int x=0;x<254;x++){
                double[] uv=map.sourcePixel(x,y);if(!Double.isFinite(uv[0]))continue;
                double radius=Math.hypot((uv[0]-p.cx)/p.fx,(uv[1]-p.cy)/p.fy);
                // Fixture is equidistant, so normalized radius is the analytic lens angle.
                if(radius>BirdseyeCalibration.MAX_THETA){assertTrue(radius<=WheelSillPatch.MAX_THETA+.0001);fringe++;}
            }
        }
        assertTrue("Do not crop the visible near-field fringe with the ground-view limit",fringe>100);
    }

    @Test public void invalidBoundsAndCamerasAreRejected(){
        BirdseyeCalibration c=rig(0,-1);
        for(int[] bad:new int[][]{{-1,254,224},{2,254,224},{0,1,224},{0,254,1025}}){
            try{new WheelSillPatch.Map(c,bad[0],true,bad[1],bad[2]);fail();}catch(IllegalArgumentException expected){}
        }
    }
}
