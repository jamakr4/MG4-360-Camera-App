package com.drivehub.kamera.steering;

import com.drivehub.kamera.rivela.PdcBars;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class GuidelineGeometryTest {
    private final SteeringModel model=new SteeringModel();
    private final WheelGuides guides=new WheelGuides();

    @Test public void widthAndSelectedFrontReferenceAreSharedBySeparateLinesAndSimpleEnvelope() {
        for(int width:new int[]{1,1550,1836,2060,2500,3000})
            for(float raw:new float[]{-516.95f,-199.3f,-.001f,0,.001f,199.3f,516.95f})
                for(WheelGuides.Geometry geometry:WheelGuides.Geometry.values()) {
                    SteeringModel.Solution pose=model.solve(raw);
                    List<PdcBars.Bar> lines=guides.clearanceLines(pose,0,0,WheelGuides.Direction.FORWARD,width,geometry);
                    WheelGuides.Corridor area=guides.corridor(pose,0,0,WheelGuides.Direction.FORWARD,width,geometry);
                    double y=1402.5-(geometry==WheelGuides.Geometry.AXLES?2705:3546);
                    assertEquals(4,lines.size());assertEquals(2,area.boundaries.size());
                    for(int side=0;side<2;side++) {
                        double[] front=lines.get(side).xy,edge=area.boundaries.get(side).xy;
                        assertEquals((side==0?-1:1)*width/2.0,front[0],0);assertEquals(y,front[1],0);
                        boolean inside=side==0?pose.curvaturePerMm<0:pose.curvaturePerMm>0;
                        double[] arc=lines.get(side+(inside?2:0)).xy;
                        assertEquals(arc[arc.length-2],edge[edge.length-2],1e-8);
                        assertEquals(arc[arc.length-1],edge[edge.length-1],1e-8);
                    }
                    // Consistent winding and area guard against folds/double-painted transparent fill.
                    double triangles=0;
                    for(int i=0;i<area.triangles.length;i+=6) {
                        double[] t=area.triangles;
                        double cross=(t[i+2]-t[i])*(t[i+5]-t[i+1])-(t[i+3]-t[i+1])*(t[i+4]-t[i]);
                        assertTrue(cross>=-1e-6);triangles+=cross/2;
                    }
                    double[] a=area.boundaries.get(0).xy,b=area.boundaries.get(1).xy;
                    double polygon=0;
                    for(int i=0;i<a.length-2;i+=2)polygon+=a[i]*a[i+3]-a[i+1]*a[i+2];
                    polygon+=a[a.length-2]*b[b.length-1]-a[a.length-1]*b[b.length-2];
                    for(int i=b.length-2;i>0;i-=2)polygon+=b[i]*b[i-1]-b[i+1]*b[i-2];
                    polygon+=b[0]*a[1]-b[1]*a[0];
                    assertEquals(Math.abs(polygon)/2,triangles,1e-5);
                    if(raw==0)assertEquals(width*(geometry==WheelGuides.Geometry.AXLES?6646:7287),triangles,1e-5);
                }
    }
    @Test public void reverseOutsideArcContinuesWithoutAStraightSideAndAllPathsReachTheSamePreviewPlane() {
        for(float raw:new float[]{-516.95f,-199.3f,-.001f,0,.001f,199.3f,516.95f})for(int width:new int[]{1,1836,2060,2500,3000}) {
            SteeringModel.Solution pose=model.solve(raw);
            List<PdcBars.Bar> axles=guides.clearanceLines(pose,0,0,WheelGuides.Direction.REVERSE,width,WheelGuides.Geometry.AXLES);
            List<PdcBars.Bar> bumper=guides.clearanceLines(pose,0,0,WheelGuides.Direction.REVERSE,width,WheelGuides.Geometry.FRONT_BUMPER);
            WheelGuides.Corridor simple=guides.corridor(pose,0,0,WheelGuides.Direction.REVERSE,width,WheelGuides.Geometry.FRONT_BUMPER);
            double angle=pose.curvaturePerMm*3000,sin=Math.sin(angle),cos=Math.cos(angle);
            double[] origin=new double[2];VehicleSweep.point(pose.curvaturePerMm,3000,0,0,origin,0);
            for(int side=0;side<2;side++) {
                double x=(side==0?-1:1)*width/2.0;
                assertEquals(-1302.5,axles.get(side).xy[1],0);assertEquals(-2143.5,bumper.get(side).xy[1],0);
                for(int index:new int[]{side,side+2}) {
                    double[] path=bumper.get(index).xy;int end=path.length-2;
                    // In the predicted vehicle pose every endpoint lies on the rear bumper plane.
                    double xx=path[end]-origin[0],yy=path[end+1]-1402.5-origin[1];
                    assertEquals(741,xx*sin+yy*cos,1e-7);
                    assertEquals(x,path[0],0);
                }
                double[] edge=simple.boundaries.get(side).xy;
                assertEquals(x,edge[0],1e-8);assertEquals(-2143.5,edge[1],1e-8);
                boolean outside=pose.curvaturePerMm==0||(side==0?pose.curvaturePerMm>0:pose.curvaturePerMm<0);
                if(outside)assertArrayEquals("Outside edge must remain the complete front-corner curve",bumper.get(side).xy,edge,0);
                else {
                    double[] rear=bumper.get(side+2).xy;
                    for(int i=0;i<rear.length;i++)assertEquals(rear[i],edge[edge.length-rear.length+i],0);
                }
            }
            // The field must still include the actual vehicle throughout its first 3 m of reversing.
            VehicleSweep.Envelope footprint=new VehicleSweep().reverse(pose,0,0,width,3000,50);
            for(double[] edge:new double[][]{footprint.left,footprint.right})for(int i=0;i<edge.length;i+=2)
                assertTrue("Footprint outside curve corridor: raw="+raw+" width="+width,inside(simple,edge[i],edge[i+1]));
        }
        assertEquals(WheelGuides.Geometry.FRONT_BUMPER,WheelGuides.Geometry.fromId("future"));
        for(WheelGuides.Geometry geometry:WheelGuides.Geometry.values())assertEquals(geometry,WheelGuides.Geometry.fromId(geometry.id));
    }
    @Test public void reverseGuidanceRetainsItsStraightDepthAtSubnormalCurvature() {
        for(double k:new double[]{0,Double.MIN_VALUE,-Double.MIN_VALUE,1e-100,-1e-100,1e-250,-1e-250}) {
            SteeringModel.Solution pose=model.fromCurvature(k);
            List<PdcBars.Bar> lines=guides.clearanceLines(pose,0,0,WheelGuides.Direction.REVERSE,1836,WheelGuides.Geometry.FRONT_BUMPER);
            WheelGuides.Corridor simple=guides.corridor(pose,0,0,WheelGuides.Direction.REVERSE,1836,WheelGuides.Geometry.FRONT_BUMPER);
            for(int i=0;i<4;i++) {
                double[] xy=lines.get(i).xy;
                assertEquals(i%2==0?-918:918,xy[xy.length-2],1e-8);assertEquals(5143.5,xy[xy.length-1],1e-8);
            }
            for(PdcBars.Bar edge:simple.boundaries)assertEquals(5143.5,edge.xy[edge.xy.length-1],1e-8);
            for(double value:simple.triangles)assertTrue(Double.isFinite(value));
        }
    }
    private static boolean inside(WheelGuides.Corridor area,double x,double y) {
        double[] a=area.boundaries.get(0).xy,b=area.boundaries.get(1).xy;int n=(a.length+b.length)/2;boolean inside=false;
        for(int i=0;i<n;i++) {
            int j=(i+1)%n;double[] p=i<a.length/2?a:b,q=j<a.length/2?a:b;
            int u=i<a.length/2?2*i:2*(n-1-i),v=j<a.length/2?2*j:2*(n-1-j);
            double dx=q[v]-p[u],dy=q[v+1]-p[u+1],t=Math.max(0,Math.min(1,((x-p[u])*dx+(y-p[u+1])*dy)/(dx*dx+dy*dy)));
            if(Math.hypot(x-p[u]-t*dx,y-p[u+1]-t*dy)<.2)return true;
            if((p[u+1]>y)!=(q[v+1]>y)&&x<p[u]+(y-p[u+1])*dx/dy)inside=!inside;
        }
        return inside;
    }

}
