package com.drivehub.kamera.steering;

import org.junit.Test;
import static org.junit.Assert.*;

public class VehicleSweepTest {
    private final VehicleGeometry car=VehicleGeometry.MG4;
    private final SteeringModel model=new SteeringModel();
    private final VehicleSweep sweep=new VehicleSweep();
    private VehicleSweep.Envelope envelope(float raw,double width){return sweep.forward(model.solve(raw),0,0,width,3000,50);}

    @Test public void reverseEnvelopeContainsEveryIntermediateFootprintIncludingFrontSwing() {
        for(int width:new int[]{1,1836,2060,3000})for(float raw:new float[]{-516.95f,-199.3f,-.45f,0,.45f,199.3f,516.95f})
            for(int distance:new int[]{50,500,3000}) {
                SteeringModel.Solution pose=model.solve(raw);double k=pose.curvaturePerMm;
                VehicleSweep.Envelope e=sweep.reverse(pose,125,-300,width,distance,50);
                for(int side=0;side<2;side++) {
                    double[] edge=side==0?e.left:e.right;double x=(side==0?-1:1)*width/2.0;
                    assertEquals(125+x,edge[0],1e-8);assertEquals(-300+1402.5-3546,edge[1],1e-8);
                    double angle=k*distance;
                    double xx=k==0?x:1/k+(x-1/k)*Math.cos(angle)+741*Math.sin(angle);
                    double yy=k==0?741+distance:-(x-1/k)*Math.sin(angle)+741*Math.cos(angle);
                    assertEquals(125+xx,edge[edge.length-2],1e-7);assertEquals(-300+1402.5+yy,edge[edge.length-1],1e-7);
                }
                for(int t=0;t<=60;t++)for(double x:new double[]{-width/2.0,0,width/2.0})
                    for(double y:new double[]{741,0,-2705,-3546}) {
                        double travel=distance*t/60.0,angle=k*travel;
                        double xx=k==0?x:1/k+(x-1/k)*Math.cos(angle)+y*Math.sin(angle);
                        double yy=k==0?y+travel:-(x-1/k)*Math.sin(angle)+y*Math.cos(angle);
                        assertTrue("Outside R sweep: "+raw+" width="+width+" travel="+travel+" x/y="+x+"/"+y,
                                contains(e,125+xx,-300+1402.5+yy));
                    }
                assertEquals(polygonArea(e),triangleArea(e.triangles),1e-5);
                assertEquals(triangleArea(sweep.forward(pose,125,-300,width,distance,50).triangles),triangleArea(e.triangles),1e-5);
                for(int i=0;i<e.triangles.length;i+=6) {
                    double[] t=e.triangles;assertTrue(cross(t[i+2]-t[i],t[i+3]-t[i+1],t[i+4]-t[i],t[i+5]-t[i+1])<=1e-6);
                }
            }
        VehicleSweep.Envelope right=sweep.reverse(model.solve(-516.95f),0,0,1836,3000,50);
        assertTrue(java.util.stream.IntStream.range(0,right.left.length/2).mapToDouble(i->right.left[2*i]).min().getAsDouble() < -918-500);
    }

    @Test public void reverseMirrorsAndKeepsTheStraightLimitEvenAtSubnormalCurvature() {
        for(float raw:new float[]{0,.0001f,199.3f,516.95f}) {
            VehicleSweep.Envelope a=sweep.reverse(model.solve(raw),0,0,2060,3000,50),b=sweep.reverse(model.solve(-raw),0,0,2060,3000,50);
            for(int side=0;side<2;side++) {
                double[] x=side==0?a.left:a.right,y=side==0?b.right:b.left;assertEquals(x.length,y.length);
                for(int i=0;i<x.length;i+=2){assertEquals(-x[i],y[i],1e-8);assertEquals(x[i+1],y[i+1],1e-8);}
            }
        }
        for(double k:new double[]{0,Double.MIN_VALUE,-Double.MIN_VALUE,1e-100,-1e-100}) {
            VehicleSweep.Envelope e=sweep.reverse(model.fromCurvature(k),0,0,1836,3000,50);
            assertEquals(-2143.5,e.left[1],1e-8);assertEquals(5143.5,e.left[e.left.length-1],1e-8);
            assertEquals(1836*7287,triangleArea(e.triangles),1e-5);
        }
        assertEquals(0,sweep.reverse(SteeringModel.INVALID,0,0,1836,3000,50).triangles.length);
    }

    @Test public void motionRetainsFractionalTravelEvenAfterTheFilterDecaysToSubnormalCurvature() {
        double[] point=new double[2];
        for(double k:new double[]{0,Double.MIN_VALUE,-Double.MIN_VALUE,1e-100,-1e-100})
            for(double travel:new double[]{-.25,-123.25,123.25}) {
                VehicleSweep.point(k,travel,918,-3546,point,0);
                assertEquals(918,point[0],1e-9);assertEquals(-3546+travel,point[1],1e-9);
                VehicleSweep.Envelope e=sweep.forward(model.fromCurvature(k),0,0,1836,3000,50);
                assertEquals(1836*7287,triangleArea(e.triangles),1e-5);
            }
    }

    @Test public void straightSweepIsTheSelectedFootprintExtendedByTheTravelDistance() {
        for(int width:new int[]{1,1836,2060,3000}) {
            VehicleSweep.Envelope e=envelope(0,width);
            assertEquals(2143.5,e.left[1],0);assertEquals(-5143.5,e.left[e.left.length-1],0);
            for(int i=0;i<e.left.length;i+=2)assertEquals(-width/2.0,e.left[i],0);
            for(int i=0;i<e.right.length;i+=2)assertEquals(width/2.0,e.right[i],0);
            assertEquals(width*(4287+3000),triangleArea(e.triangles),1e-5);
        }
    }
    @Test public void outsideFrontCornerHasTheWallToWallRadiusAndBothSidesShareOneFinalBumper() {
        for(float raw:new float[]{-516.95f,-199.3f,199.3f,516.95f}) {
            SteeringModel.Solution pose=model.solve(raw);VehicleSweep.Envelope e=envelope(raw,1836);
            double r=1/Math.abs(pose.curvaturePerMm),expected=Math.hypot(r+918,2705+841);
            double[] outside=raw<0?e.left:e.right;
            int end=outside.length-2;
            assertEquals(expected,sweep.outerFrontRadius(pose,1836),1e-9);
            assertEquals(expected,Math.hypot(outside[end]-1/pose.curvaturePerMm,outside[end+1]-1402.5),1e-8);
            assertTrue(expected>Math.hypot(r+1550/2.0,2705));
            int l=e.left.length-2,q=e.right.length-2;
            assertEquals(1836,Math.hypot(e.left[l]-e.right[q],e.left[l+1]-e.right[q+1]),1e-8);
        }
    }
    @Test public void leftAndRightTurnsMirrorAndTrianglesExactlyFillTheBoundaryWithoutOverlaps() {
        for(int width:new int[]{1,1836,2060,3000})for(float raw:new float[]{.0001f,.45f,199.3f,516.95f}) {
            VehicleSweep.Envelope a=envelope(raw,width),b=envelope(-raw,width);
            assertEquals(a.left.length,b.right.length);assertEquals(a.right.length,b.left.length);
            for(int side=0;side<2;side++) {
                double[] x=side==0?a.left:a.right,y=side==0?b.right:b.left;
                for(int i=0;i<x.length;i+=2){assertEquals(-x[i],y[i],1e-8);assertEquals(x[i+1],y[i+1],1e-8);}
            }
            assertEquals(polygonArea(a),triangleArea(a.triangles),1e-5);
            assertEquals(polygonArea(b),triangleArea(b.triangles),1e-5);
            // Every strip triangle has the same winding. A fold would double-paint the translucent fill.
            for(double[] triangles:new double[][]{a.triangles,b.triangles})for(int i=0;i<triangles.length;i+=6)
                assertTrue(cross(triangles[i+2]-triangles[i],triangles[i+3]-triangles[i+1],
                        triangles[i+4]-triangles[i],triangles[i+5]-triangles[i+1])>=-1e-6);
        }
    }
    @Test public void everyIntermediateVehicleFootprintIsInsideTheEnvelopeIncludingTheRearTailSwing() {
        for(int width:new int[]{1836,2060,3000})for(float raw:new float[]{-516.95f,-199.3f,0,199.3f,516.95f}) {
            VehicleSweep.Envelope e=envelope(raw,width);double k=model.solve(raw).curvaturePerMm;
            for(int t=0;t<=60;t++)for(double x:new double[]{-width/2.0,0,width/2.0})
                for(double y:new double[]{741,0,-2705,-3546}) {
                    double travel=-3000*t/60.0,angle=k*travel;
                    // Independent rotation about the ICR; straight is its continuous limit.
                    double xx=k==0?x:1/k+(x-1/k)*Math.cos(angle)+y*Math.sin(angle);
                    double yy=1402.5+(k==0?y+travel:-(x-1/k)*Math.sin(angle)+y*Math.cos(angle));
                    assertTrue("Outside sweep at raw="+raw+" width="+width+" t="+t+" x/y="+x+"/"+y,contains(e,xx,yy));
                }
        }
    }
    @Test public void nearStraightFilteringAndShortTravelRemainFiniteAndNonOverlapping() {
        for(int sign:new int[]{-1,1})for(int exponent=0;exponent<=60;exponent++) {
            SteeringModel.Solution p=model.fromCurvature(sign*Math.scalb(model.solve(516.95f).curvaturePerMm,-exponent));
            for(int travel:new int[]{50,500,3000}) {
                VehicleSweep.Envelope e=sweep.forward(p,125,-300,2060,travel,50);
                for(double v:e.triangles)assertTrue(Double.isFinite(v));
                assertEquals(polygonArea(e),triangleArea(e.triangles),1e-5);
            }
        }
        assertEquals(0,sweep.forward(SteeringModel.INVALID,0,0,1836,3000,50).triangles.length);
    }
    private static double cross(double x,double y,double xx,double yy){return x*yy-y*xx;}
    private static double triangleArea(double[] t) {
        double area=0;for(int i=0;i<t.length;i+=6)area+=Math.abs(cross(t[i+2]-t[i],t[i+3]-t[i+1],t[i+4]-t[i],t[i+5]-t[i+1]))/2;
        return area;
    }
    private static double polygonArea(VehicleSweep.Envelope e) {
        double area=0;int n=(e.left.length+e.right.length)/2;
        for(int i=0;i<n;i++) {
            int j=(i+1)%n;double[] a=i<e.left.length/2?e.left:e.right,b=j<e.left.length/2?e.left:e.right;
            int ia=i<e.left.length/2?2*i:2*(n-1-i),ib=j<e.left.length/2?2*j:2*(n-1-j);
            area+=cross(a[ia],a[ia+1],b[ib],b[ib+1]);
        }
        return Math.abs(area)/2;
    }
    private static boolean contains(VehicleSweep.Envelope e,double x,double y) {
        boolean inside=false;int n=(e.left.length+e.right.length)/2;
        for(int i=0;i<n;i++) {
            int j=(i+1)%n;double[] a=i<e.left.length/2?e.left:e.right,b=j<e.left.length/2?e.left:e.right;
            int ia=i<e.left.length/2?2*i:2*(n-1-i),ib=j<e.left.length/2?2*j:2*(n-1-j);
            double ax=a[ia],ay=a[ia+1],bx=b[ib],by=b[ib+1],dx=bx-ax,dy=by-ay;
            double t=Math.max(0,Math.min(1,((x-ax)*dx+(y-ay)*dy)/(dx*dx+dy*dy)));
            if(Math.hypot(x-ax-t*dx,y-ay-t*dy)<.2)return true; // Sub-millimetre chord sagitta, below the 60 mm band.
            if((ay>y)!=(by>y)&&x<ax+(y-ay)*dx/dy)inside=!inside;
        }
        return inside;
    }
}
