package com.drivehub.kamera.steering;

import com.drivehub.kamera.rivela.PdcBars;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Ground guides: kinematic wheel traces plus D rear display curves sharing the front endpoints. */
public final class WheelGuides {
    public enum Direction { REVERSE, FORWARD }
    public enum Style { COLORED, LINES, SIMPLE }
    public static final int LEFT_COLOR=0xFF00FFFF, RIGHT_COLOR=0xFFFFFF00;
    public static final int REAR_LEFT_COLOR=0xFF00FF00, REAR_RIGHT_COLOR=0xFFFF66FF;
    public static final int CORRIDOR_EDGE_COLOR=0xFF73D9FF, CORRIDOR_FILL_COLOR=0x552A8FFF;
    public static final float WIDTH_MM=60, REAR_WIDTH_MM=40;
    public static final double OEM_START_OFFSET_MM=100, OEM_ARC_MM=3000, STEP_MM=50;
    /** Forward horizon: both endpoints share the pose after this rear-axle centre travel. */
    public static final double FORWARD_TRAVEL_MM=3000;
    // Merge axle joins closer than one micrometre to avoid numerical slivers near straight.
    private static final double JOIN_EPS_MM=.001;
    private final VehicleGeometry car=VehicleGeometry.MG4;

    public static final class Corridor {
        public final List<PdcBars.Bar> boundaries;
        /** Non-overlapping ground triangles: three XY pairs per triangle. */
        public final double[] triangles;
        private Corridor(List<PdcBars.Bar> boundaries,double[] triangles){this.boundaries=boundaries;this.triangles=triangles;}
    }
    private static final Corridor EMPTY_CORRIDOR=new Corridor(Collections.emptyList(),new double[0]);

    public Corridor corridor(SteeringModel.Solution pose,double centerX,double centerY,Direction direction) {
        return corridor(pose,centerX,centerY,direction,GuidelineWidth.DEFAULT_MM);
    }
    public Corridor corridor(SteeringModel.Solution pose,double centerX,double centerY,Direction direction,int widthMm) {
        // Apply the same vehicle motion at virtual side points. Do not widen the wheel
        // track used for Ackermann angles or change the body width used by the OEM radius model.
        double width=GuidelineWidth.validOrDefault(widthMm);
        return outerCorridor(lines(pose,centerX,centerY,direction,width,width),
                pose.curvaturePerMm,direction);
    }
    /** Select the outer pair; retain the starting-axle lead-in on the other side. */
    private static Corridor outerCorridor(List<PdcBars.Bar> traces,double curvature,Direction direction) {
        if(traces.size()<2||!Double.isFinite(curvature))return EMPTY_CORRIDOR;
        int left=traces.size()>=4&&(curvature<0||(curvature==0&&direction==Direction.FORWARD))?2:0;
        int right=traces.size()>=4&&(curvature>0||(curvature==0&&direction==Direction.FORWARD))?3:1;
        double[] a=traces.get(left).xy,b=traces.get(right).xy;
        if(direction==Direction.REVERSE) {
            if(left==2)a=leadIn(traces.get(0).xy,a,true);
            if(right==3)b=leadIn(traces.get(1).xy,b,true);
        }else if(traces.size()>=4) {
            if(left==0)a=leadIn(traces.get(2).xy,a,false);
            if(right==1)b=leadIn(traces.get(3).xy,b,false);
        }
        int na=a.length/2,nb=b.length/2;
        if(na<2||nb<2)return EMPTY_CORRIDOR;
        double[] triangles=triangulate(a,b);
        List<PdcBars.Bar> edges=new ArrayList<>(2);
        edges.add(new PdcBars.Bar(CORRIDOR_EDGE_COLOR,WIDTH_MM,a));
        edges.add(new PdcBars.Bar(CORRIDOR_EDGE_COLOR,WIDTH_MM,b));
        return new Corridor(Collections.unmodifiableList(edges),triangles);
    }

    /** Axle joins and wide R caps can be concave; clip ears without folding the fill over itself. */
    private static double[] triangulate(double[] left,double[] right) {
        double[] polygon=new double[left.length+right.length];
        System.arraycopy(left,0,polygon,0,left.length);
        for(int i=0;i<right.length;i+=2) {
            polygon[left.length+i]=right[right.length-2-i];polygon[left.length+i+1]=right[right.length-1-i];
        }
        int count=polygon.length/2;int[] previous=new int[count],next=new int[count];
        double signedArea=0;
        for(int i=0;i<count;i++) {
            previous[i]=(i+count-1)%count;next[i]=(i+1)%count;
            signedArea+=polygon[2*i]*polygon[2*next[i]+1]-polygon[2*i+1]*polygon[2*next[i]];
        }
        double winding=Math.signum(signedArea);double[] triangles=new double[(count-2)*6];
        int remaining=count,current=0,out=0,attempts=0;
        while(remaining>3) {
            int a=previous[current],b=current,c=next[current];boolean ear=area(polygon,a,b,c)*winding>0;
            if(ear)for(int p=next[c];p!=a;p=next[p]) {
                if(area(polygon,a,b,p)*winding>=0&&area(polygon,b,c,p)*winding>=0
                        &&area(polygon,c,a,p)*winding>=0){ear=false;break;}
            }
            if(ear) {
                for(int p:new int[]{a,b,c}){triangles[out++]=polygon[2*p];triangles[out++]=polygon[2*p+1];}
                next[a]=c;previous[c]=a;remaining--;attempts=0;
            }else if(++attempts>=remaining)throw new IllegalArgumentException("Invalid wheel-guide boundary polygon");
            current=next[current];
        }
        for(int p:new int[]{current,next[current],next[next[current]]}) {
            triangles[out++]=polygon[2*p];triangles[out++]=polygon[2*p+1];
        }
        return triangles;
    }
    private static double area(double[] p,int a,int b,int c) {
        return (p[2*b]-p[2*a])*(p[2*c+1]-p[2*a+1])-(p[2*b+1]-p[2*a+1])*(p[2*c]-p[2*a]);
    }

    /** Retain the starting axle's trace up to the depth where the selected outer trace starts. */
    private static double[] leadIn(double[] lead,double[] path,boolean increasingY) {
        double joinY=path[1];int end=0;
        while(end<lead.length&&(increasingY?lead[end+1]<joinY:lead[end+1]>joinY))end+=2;
        if(end==0||end==lead.length)return path;
        double fraction=(joinY-lead[end-1])/(lead[end+1]-lead[end-1]);
        double joinX=lead[end-2]+fraction*(lead[end]-lead[end-2]);
        int join=Math.hypot(joinX-path[0],joinY-path[1])<JOIN_EPS_MM?0:2;
        double[] edge=new double[end+join+path.length];
        System.arraycopy(lead,0,edge,0,end);
        if(join!=0){edge[end]=joinX;edge[end+1]=joinY;}
        System.arraycopy(path,0,edge,end+join,path.length);
        return edge;
    }

    /** Visual D guide only: from a rear wheel to the matching front guide's exact far endpoint. */
    private static double[] forwardRearPreview(double x0,double y0,double[] front) {
        int end=front.length-2;double x3=front[end],y3=front[end+1];
        double vx=x3-front[end-2],vy=y3-front[end-1],length=Math.hypot(vx,vy);vx/=length;vy/=length;
        double dx=x3-x0,dy=y3-y0,span=Math.hypot(dx,dy)/3;
        // Leave the unsteered rear wheel forwards and arrive along the front guide's final tangent.
        if(Math.abs(dx)>JOIN_EPS_MM)span=Math.min(span,.8*Math.abs(dx)/Math.abs(vx));
        span=Math.min(span,.8*Math.abs(dy)/(1+Math.abs(vy)));
        double x1=x0,y1=y0-span,x2=x3-vx*span,y2=y3-vy*span;
        int steps=Math.max(1,(int)Math.ceil(3*Math.max(span,Math.hypot(x2-x1,y2-y1))/STEP_MM));
        double[] xy=new double[(steps+1)*2];
        for(int i=0;i<=steps;i++) {
            double t=i/(double)steps,s=1-t;
            xy[2*i]=s*s*s*x0+3*s*s*t*x1+3*s*t*t*x2+t*t*t*x3;
            xy[2*i+1]=s*s*s*y0+3*s*s*t*y1+3*s*t*t*y2+t*t*t*y3;
        }
        xy[0]=x0;xy[1]=y0;xy[xy.length-2]=x3;xy[xy.length-1]=y3;
        return xy;
    }

    public List<PdcBars.Bar> lines(SteeringModel.Solution pose,double centerX,double centerY) {
        return lines(pose,centerX,centerY,Direction.REVERSE);
    }
    public List<PdcBars.Bar> lines(SteeringModel.Solution pose,double centerX,double centerY,Direction direction) {
        return lines(pose,centerX,centerY,direction,car.effectiveFrontTrackMm,car.effectiveRearTrackMm);
    }
    /** Four blue clearance curves, using the same virtual side points as the filled corridor. */
    public List<PdcBars.Bar> clearanceLines(SteeringModel.Solution pose,double centerX,double centerY,Direction direction) {
        return clearanceLines(pose,centerX,centerY,direction,GuidelineWidth.DEFAULT_MM);
    }
    public List<PdcBars.Bar> clearanceLines(SteeringModel.Solution pose,double centerX,double centerY,Direction direction,int widthMm) {
        List<PdcBars.Bar> result=new ArrayList<>(4);
        double width=GuidelineWidth.validOrDefault(widthMm);
        for(PdcBars.Bar line:lines(pose,centerX,centerY,direction,width,width))
            result.add(new PdcBars.Bar(CORRIDOR_EDGE_COLOR,line.widthMm,line.xy));
        return Collections.unmodifiableList(result);
    }
    private List<PdcBars.Bar> lines(SteeringModel.Solution pose,double centerX,double centerY,Direction direction,
                                   double frontTrackMm,double rearTrackMm) {
        if(!pose.valid||!Double.isFinite(centerX)||!Double.isFinite(centerY))return Collections.emptyList();
        List<PdcBars.Bar> lines=new ArrayList<>(4);
        double k=pose.curvaturePerMm;
        double rearAxleY=centerY+car.lengthMm/2-car.rearOverhangMm;
        // Front L/R first, then rear L/R. D rear curves are visual links, R remains kinematic.
        for(int axle=0;axle<2;axle++)for(int side:new int[]{-1,1}) {
            boolean rear=axle==1;
            double x=side*(rear?rearTrackMm:frontTrackMm)/2;
            double y=rear?0:-car.wheelbaseMm;
            if(rear&&direction==Direction.FORWARD) {
                lines.add(new PdcBars.Bar(side<0?REAR_LEFT_COLOR:REAR_RIGHT_COLOR,REAR_WIDTH_MM,
                        forwardRearPreview(centerX+x,rearAxleY,lines.get(side<0?0:1).xy)));
                continue;
            }
            double travel;
            if(direction==Direction.FORWARD)travel=-FORWARD_TRAVEL_MM;
            else if(k==0)travel=oemEndY(k,side)-y;
            else {
                double endY=oemEndY(k,side);
                double transverse=1/Math.abs(k)-Math.signum(k)*x;
                double wheelRadius=Math.hypot(transverse,y);
                // Wide virtual side points can turn too tightly to reach the OEM end depth.
                // End that trace at its maximum reachable depth instead of producing NaN.
                double angle=Math.asin(Math.max(-1,Math.min(1,endY/wheelRadius)))-Math.atan2(y,transverse);
                travel=angle/Math.abs(k);
            }
            // Bound the true wheel arc segment length, not only rear-axle travel.
            double wheelScale=Math.hypot(1-k*x,k*y);
            int steps=Math.max(1,(int)Math.ceil(Math.abs(travel)*wheelScale/STEP_MM));
            double[] xy=new double[(steps+1)*2];
            for(int i=0;i<=steps;i++) {
                double s=travel*i/steps, angle=k*s;
                double sin=Math.sin(angle),cos=Math.cos(angle),half=Math.sin(angle/2);
                double tx=k==0?0:2*half*half/k,ty=k==0?s:sin/k;
                xy[2*i]=centerX+tx+x*cos+y*sin;
                xy[2*i+1]=rearAxleY+ty-x*sin+y*cos;
            }
            int color=rear?(side<0?REAR_LEFT_COLOR:REAR_RIGHT_COLOR):(side<0?LEFT_COLOR:RIGHT_COLOR);
            // Narrower rear bands leave the front trace's edges visible when the paths coincide at straight.
            lines.add(new PdcBars.Bar(color,rear?REAR_WIDTH_MM:WIDTH_MM,xy));
        }
        return Collections.unmodifiableList(lines);
    }

    /** Longitudinal depth of the corresponding OEM body-side far endpoint, rear-axle reference. */
    double oemEndY(double curvature,int side) {
        if(curvature==0)return car.rearOverhangMm+OEM_START_OFFSET_MM+OEM_ARC_MM;
        double transverse=1/Math.abs(curvature)-Math.signum(curvature)*side*car.oemRadiusOffsetMm();
        double radius=Math.hypot(transverse,car.rearOverhangMm),angle=OEM_ARC_MM/radius;
        return OEM_START_OFFSET_MM+car.rearOverhangMm*Math.cos(angle)+transverse*Math.sin(angle);
    }
}
