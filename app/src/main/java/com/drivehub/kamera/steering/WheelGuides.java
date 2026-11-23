package com.drivehub.kamera.steering;

import com.drivehub.kamera.rivela.PdcBars;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Kinematic wheel traces and vehicle-clearance guides from the same rear-axle motion. */
public final class WheelGuides {
    public enum Direction { REVERSE, FORWARD }
    public enum Style {
        COLORED("colored"), LINES("lines"), SIMPLE("simple"), OUTER_LINES("outer_lines");
        public static final Style DEFAULT=OUTER_LINES;
        public final String id;
        Style(String id){this.id=id;}
        public static Style fromId(String id){for(Style style:values())if(style.id.equals(id))return style;return DEFAULT;}
    }
    public enum Geometry {
        AXLES("axles"), FRONT_BUMPER("front_bumper");
        public final String id;
        Geometry(String id){this.id=id;}
        public static Geometry fromId(String id){return "axles".equals(id)?AXLES:FRONT_BUMPER;}
    }
    public static final int LEFT_COLOR=0xFF00FFFF, RIGHT_COLOR=0xFFFFFF00;
    public static final int REAR_LEFT_COLOR=0xFF00FF00, REAR_RIGHT_COLOR=0xFFFF66FF;
    public static final int CORRIDOR_EDGE_COLOR=0xFF73D9FF, CORRIDOR_FILL_COLOR=0x552A8FFF;
    public static final float WIDTH_MM=60, REAR_WIDTH_MM=40;
    public static final double OEM_START_OFFSET_MM=100, OEM_ARC_MM=3000, STEP_MM=50;
    /** Preview pose at the rear axle; clearance curves continue to its reachable driving-end plane. */
    public static final double VEHICLE_TRAVEL_MM=3000;
    // Merge axle joins closer than one micrometre to avoid numerical slivers near straight.
    private static final double JOIN_EPS_MM=.001;
    private final VehicleGeometry car=VehicleGeometry.MG4;
    private final VehicleSweep sweep=new VehicleSweep(car);

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
        return corridor(pose,centerX,centerY,direction,widthMm,Geometry.FRONT_BUMPER);
    }
    public Corridor corridor(SteeringModel.Solution pose,double centerX,double centerY,Direction direction,int widthMm,Geometry geometry) {
        // Apply the same vehicle motion at virtual side points. Do not widen the wheel
        // track used for Ackermann angles or change the body width used by the OEM radius model.
        double width=GuidelineWidth.validOrDefault(widthMm);
        if(direction==Direction.REVERSE&&geometry==Geometry.FRONT_BUMPER)
            return reverseBumperCorridor(lines(pose,centerX,centerY,direction,width,width,true),pose.curvaturePerMm);
        if(direction==Direction.FORWARD) {
            VehicleSweep.Envelope envelope=geometry==Geometry.FRONT_BUMPER
                    ?sweep.forward(pose,centerX,centerY,width,VEHICLE_TRAVEL_MM,STEP_MM)
                    :sweep.forwardAxles(pose,centerX,centerY,width,VEHICLE_TRAVEL_MM,STEP_MM);
            if(envelope.left.length==0)return EMPTY_CORRIDOR;
            List<PdcBars.Bar> traces=clearanceLines(pose,centerX,centerY,direction,widthMm,geometry);
            List<PdcBars.Bar> edges=new ArrayList<>(2);
            for(int side=0;side<2;side++) {
                boolean inside=side==0?pose.curvaturePerMm<0:pose.curvaturePerMm>0;
                double[] arc=traces.get(side+(inside?2:0)).xy;
                double[] footprint=side==0?envelope.left:envelope.right;
                // Keep the initial tail swing/body side, then continue the selected curve
                // to the shared preview cap. A final-pose body side would kink too early.
                double[] edge=pose.curvaturePerMm==0?sideLeadIn(arc,footprint[1]):continueArc(footprint,arc);
                edges.add(new PdcBars.Bar(CORRIDOR_EDGE_COLOR,WIDTH_MM,edge));
            }
            return new Corridor(Collections.unmodifiableList(edges),triangulate(edges.get(0).xy,edges.get(1).xy));
        }
        return outerCorridor(lines(pose,centerX,centerY,direction,width,width,false),
                pose.curvaturePerMm,direction);
    }
    /** The finite sweep supplies the initial footprint; its moving arc is replaced
     * from the same fixed vehicle point so the preview has no final straight side. */
    private static double[] continueArc(double[] footprint,double[] arc) {
        for(int join=0;join<footprint.length;join+=2) {
            if(Math.hypot(footprint[join]-arc[0],footprint[join+1]-arc[1])>=JOIN_EPS_MM)continue;
            double[] edge=new double[join+arc.length];
            System.arraycopy(footprint,0,edge,0,join);
            System.arraycopy(arc,0,edge,join,arc.length);
            return edge;
        }
        throw new IllegalArgumentException("Clearance arc does not join the initial footprint");
    }
    /** Keep the outside front-corner arc through to the preview cap. Connecting the
     * last vehicle pose's front and rear corners would replace this arc with a long straight side. */
    private static Corridor reverseBumperCorridor(List<PdcBars.Bar> traces,double curvature) {
        if(traces.size()!=4)return EMPTY_CORRIDOR;
        double[] left=traces.get(0).xy,right=traces.get(1).xy;
        if(curvature<0)left=initialSide(left,traces.get(2).xy);
        if(curvature>0)right=initialSide(right,traces.get(3).xy);
        List<PdcBars.Bar> edges=new ArrayList<>(2);
        edges.add(new PdcBars.Bar(CORRIDOR_EDGE_COLOR,WIDTH_MM,left));
        edges.add(new PdcBars.Bar(CORRIDOR_EDGE_COLOR,WIDTH_MM,right));
        return new Corridor(Collections.unmodifiableList(edges),triangulate(left,right));
    }
    /** Inside clearance follows the initial body side to the rear axle, then its arc. */
    private static double[] initialSide(double[] front,double[] rear) {
        int steps=Math.max(1,(int)Math.ceil(Math.hypot(front[0]-rear[0],front[1]-rear[1])/STEP_MM));
        double[] xy=new double[steps*2+rear.length];
        for(int i=0;i<steps;i++) {
            xy[2*i]=front[0]+(rear[0]-front[0])*i/steps;
            xy[2*i+1]=front[1]+(rear[1]-front[1])*i/steps;
        }
        System.arraycopy(rear,0,xy,steps*2,rear.length);
        return xy;
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

    public List<PdcBars.Bar> lines(SteeringModel.Solution pose,double centerX,double centerY) {
        return lines(pose,centerX,centerY,Direction.REVERSE);
    }
    public List<PdcBars.Bar> lines(SteeringModel.Solution pose,double centerX,double centerY,Direction direction) {
        return lines(pose,centerX,centerY,direction,car.frontTrackMm,car.rearTrackMm,false);
    }
    /** Four blue curves, using either front corners or axle-side points in both gears. */
    public List<PdcBars.Bar> clearanceLines(SteeringModel.Solution pose,double centerX,double centerY,Direction direction) {
        return clearanceLines(pose,centerX,centerY,direction,GuidelineWidth.DEFAULT_MM);
    }
    public List<PdcBars.Bar> clearanceLines(SteeringModel.Solution pose,double centerX,double centerY,Direction direction,int widthMm) {
        return clearanceLines(pose,centerX,centerY,direction,widthMm,Geometry.FRONT_BUMPER);
    }
    public List<PdcBars.Bar> clearanceLines(SteeringModel.Solution pose,double centerX,double centerY,Direction direction,int widthMm,Geometry geometry) {
        List<PdcBars.Bar> result=new ArrayList<>(4);
        double width=GuidelineWidth.validOrDefault(widthMm);
        for(PdcBars.Bar line:lines(pose,centerX,centerY,direction,width,width,geometry==Geometry.FRONT_BUMPER,true))
            result.add(new PdcBars.Bar(CORRIDOR_EDGE_COLOR,line.widthMm,line.xy));
        return Collections.unmodifiableList(result);
    }
    /** Two outer curves with a shared start at the trailing end of the guide footprint.
     * Side lead-ins keep both paths long when the front/rear curve selection changes at straight. */
    public List<PdcBars.Bar> outerLines(SteeringModel.Solution pose,double centerX,double centerY,Direction direction,int widthMm,Geometry geometry) {
        List<PdcBars.Bar> all=clearanceLines(pose,centerX,centerY,direction,widthMm,geometry);
        if(all.size()!=4)return Collections.emptyList();
        int left=pose.curvaturePerMm<0?2:0,right=pose.curvaturePerMm>0?3:1;
        double startY=direction==Direction.FORWARD?car.rearAxleY(centerY):all.get(0).xy[1];
        List<PdcBars.Bar> result=new ArrayList<>(2);
        for(int index:new int[]{left,right})result.add(new PdcBars.Bar(CORRIDOR_EDGE_COLOR,WIDTH_MM,sideLeadIn(all.get(index).xy,startY)));
        return Collections.unmodifiableList(result);
    }
    private static double[] sideLeadIn(double[] curve,double startY) {
        double distance=Math.abs(curve[1]-startY);
        if(distance<JOIN_EPS_MM)return curve;
        int steps=Math.max(1,(int)Math.ceil(distance/STEP_MM));
        double[] xy=new double[steps*2+curve.length];
        for(int i=0;i<steps;i++){xy[2*i]=curve[0];xy[2*i+1]=startY+(curve[1]-startY)*i/steps;}
        System.arraycopy(curve,0,xy,steps*2,curve.length);
        return xy;
    }
    private List<PdcBars.Bar> lines(SteeringModel.Solution pose,double centerX,double centerY,Direction direction,
                                   double frontTrackMm,double rearTrackMm,boolean frontCorners) {
        return lines(pose,centerX,centerY,direction,frontTrackMm,rearTrackMm,frontCorners,false);
    }
    private List<PdcBars.Bar> lines(SteeringModel.Solution pose,double centerX,double centerY,Direction direction,
                                   double frontTrackMm,double rearTrackMm,boolean frontCorners,boolean clearancePreview) {
        if(!pose.valid||!Double.isFinite(centerX)||!Double.isFinite(centerY))return Collections.emptyList();
        List<PdcBars.Bar> lines=new ArrayList<>(4);
        double k=pose.curvaturePerMm;
        double rearAxleY=car.rearAxleY(centerY);
        double forwardEndY=car.frontY()-(frontCorners?0:OEM_START_OFFSET_MM);
        if(k!=0) {
            // Every curve must reach the common cap. At tight locks the inside rear
            // circle can be shallower than the bumper offset; use its reachable depth.
            double minimumRadius=(1-Math.abs(k)*rearTrackMm/2)/Math.abs(k);
            forwardEndY=Math.max(forwardEndY,-minimumRadius);
        }
        // Front L/R first, then rear L/R. Every trace follows a fixed point on the moving vehicle.
        for(int axle=0;axle<2;axle++)for(int side:new int[]{-1,1}) {
            boolean rear=axle==1;
            double x=side*(rear?rearTrackMm:frontTrackMm)/2;
            double y=rear?0:frontCorners?car.frontY():-car.wheelbaseMm;
            double travel;
            if(direction==Direction.FORWARD)travel=clearancePreview
                    ?previewTravel(k,x,y,forwardEndY,-VEHICLE_TRAVEL_MM):-VEHICLE_TRAVEL_MM;
            else if(frontCorners)travel=reversePreviewTravel(k,x,y);
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
                VehicleSweep.point(k,travel*i/steps,x,y,xy,2*i);
                xy[2*i]+=centerX;xy[2*i+1]+=rearAxleY;
            }
            int color=rear?(side<0?REAR_LEFT_COLOR:REAR_RIGHT_COLOR):(side<0?LEFT_COLOR:RIGHT_COLOR);
            // Narrower rear bands leave the front trace's edges visible when the paths coincide at straight.
            lines.add(new PdcBars.Bar(color,rear?REAR_WIDTH_MM:WIDTH_MM,xy));
        }
        return Collections.unmodifiableList(lines);
    }

    /** All R clearance curves meet the rear-bumper plane after 3 m of rear-axle
     * travel. Front corners need more arc travel to reach that spatial horizon.
     * Ratio forms of asin/atan retain the straight limit even for subnormal curvature. */
    private double reversePreviewTravel(double k,double x,double y) {
        return previewTravel(k,x,y,car.rearY(),VEHICLE_TRAVEL_MM);
    }
    private static double previewTravel(double k,double x,double y,double endY,double previewTravel) {
        double q=Math.abs(k),a=1-k*x,radiusScale=Math.hypot(a,q*y);
        double z=q*endY/radiusScale,w=q*y/a;
        double extra=endY/radiusScale*(z==0?1:Math.asin(Math.max(-1,Math.min(1,z)))/z)
                -y/a*(w==0?1:Math.atan(w)/w);
        return previewTravel+extra;
    }

    /** Longitudinal depth of the corresponding OEM body-side far endpoint, rear-axle reference. */
    double oemEndY(double curvature,int side) {
        if(curvature==0)return car.rearOverhangMm+OEM_START_OFFSET_MM+OEM_ARC_MM;
        double transverse=1/Math.abs(curvature)-Math.signum(curvature)*side*car.oemRadiusOffsetMm();
        double radius=Math.hypot(transverse,car.rearOverhangMm),angle=OEM_ARC_MM/radius;
        return OEM_START_OFFSET_MM+car.rearOverhangMm*Math.cos(angle)+transverse*Math.sin(angle);
    }
}
