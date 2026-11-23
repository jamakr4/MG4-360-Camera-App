package com.drivehub.kamera.steering;

import com.drivehub.kamera.rivela.PdcBars;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class GuidelineHorizonTest {
    private final SteeringModel model=new SteeringModel();
    private final WheelGuides guides=new WheelGuides();

    @Test public void straightDAndRReachTheSameDistanceBeyondTheVehicleInBothLineStyles() {
        for(WheelGuides.Geometry geometry:WheelGuides.Geometry.values())for(int width:new int[]{1,1836,2060,3000}) {
            double end=geometry==WheelGuides.Geometry.FRONT_BUMPER?5143.5:5243.5;
            for(WheelGuides.Direction direction:WheelGuides.Direction.values()) {
                List<PdcBars.Bar> all=guides.clearanceLines(model.solve(0),0,0,direction,width,geometry);
                List<PdcBars.Bar> outer=guides.outerLines(model.solve(0),0,0,direction,width,geometry);
                for(List<PdcBars.Bar> paths:java.util.Arrays.asList(all,outer))for(PdcBars.Bar path:paths) {
                    assertEquals(direction==WheelGuides.Direction.FORWARD?-end:end,path.xy[path.xy.length-1],1e-8);
                    assertEquals(path.xy[0],path.xy[path.xy.length-2],0);
                }
            }
        }
    }
    @Test public void allForwardCurvesReachTheSharedCapAndRearCurvesExtendBeyondTheirOldEnd() {
        for(WheelGuides.Geometry geometry:WheelGuides.Geometry.values())for(int width:new int[]{1,1836,2060,2500,3000})
            for(float raw:new float[]{-516.95f,-199.3f,-.001f,0,.001f,199.3f,516.95f}) {
                SteeringModel.Solution pose=model.solve(raw);double k=pose.curvaturePerMm;
                List<PdcBars.Bar> paths=guides.clearanceLines(pose,125,-300,WheelGuides.Direction.FORWARD,width,geometry);
                double cap=geometry==WheelGuides.Geometry.FRONT_BUMPER?-3546:-3646;
                if(k!=0)cap=Math.max(cap,-(1/Math.abs(k)-width/2.0));
                double[] origin=new double[2];VehicleSweep.point(k,-3000,0,0,origin,0);
                double angle=-3000*k,sin=Math.sin(angle),cos=Math.cos(angle);
                for(int i=0;i<paths.size();i++) {
                    double[] xy=paths.get(i).xy;int end=xy.length-2;
                    double x=xy[end]-125-origin[0],y=xy[end+1]-(-300+1402.5)-origin[1];
                    assertEquals(cap,x*sin+y*cos,1e-7);
                    if(i>=2) {
                        double length=0;for(int n=2;n<xy.length;n+=2)length+=Math.hypot(xy[n]-xy[n-2],xy[n+1]-xy[n-1]);
                        double oldLength=3000*Math.abs(1-k*(xy[0]-125));
                        assertTrue("Rear path still ends near the rear axle",length>oldLength+1000);
                    }
                    for(double v:xy)assertTrue(Double.isFinite(v));
                }
            }
    }
    @Test public void forwardHorizonKeepsItsStraightLimitAtSubnormalCurvature() {
        for(double k:new double[]{0,Double.MIN_VALUE,-Double.MIN_VALUE,1e-100,-1e-100})
            for(PdcBars.Bar path:guides.clearanceLines(model.fromCurvature(k),0,0,WheelGuides.Direction.FORWARD,1836,WheelGuides.Geometry.FRONT_BUMPER))
                assertEquals(-5143.5,path.xy[path.xy.length-1],1e-8);
    }
    @Test public void simpleForwardContinuesBothClearanceArcsToTheSharedCapWithoutAFinalSideConnector() {
        for(WheelGuides.Geometry geometry:WheelGuides.Geometry.values())for(int width:new int[]{1,1836,2060,2500,3000})
            for(float raw:new float[]{-516.95f,-199.3f,-25.2f,-.001f,0,.001f,25.2f,199.3f,516.95f}) {
                SteeringModel.Solution pose=model.solve(raw);
                List<PdcBars.Bar> paths=guides.clearanceLines(pose,125,-300,WheelGuides.Direction.FORWARD,width,geometry);
                WheelGuides.Corridor area=guides.corridor(pose,125,-300,WheelGuides.Direction.FORWARD,width,geometry);
                for(int side=0;side<2;side++) {
                    boolean inner=side==0?pose.curvaturePerMm<0:pose.curvaturePerMm>0;
                    double[] arc=paths.get(side+(inner?2:0)).xy,edge=area.boundaries.get(side).xy;
                    assertTrue("Simple ends before its clearance arc: "+geometry+" width="+width+" raw="+raw,edge.length>=arc.length);
                    for(int i=0;i<arc.length;i++)assertEquals("Simple must retain the complete arc through the cap",
                            arc[i],edge[edge.length-arc.length+i],1e-8);
                    for(int i=2;i<edge.length;i+=2)assertTrue("Guide segment exceeds sampling distance",
                            Math.hypot(edge[i]-edge[i-2],edge[i+1]-edge[i-1])<=50.0001);
                }
            }
    }
}
