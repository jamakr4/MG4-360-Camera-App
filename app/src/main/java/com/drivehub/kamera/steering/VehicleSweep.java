package com.drivehub.kamera.steering;

import java.util.Arrays;

/** Finite swept rectangle under constant rear-axle curvature, including both overhangs.
 * The selected clearance width describes a virtual rectangular footprint; BODY uses the real body width.
 * The outer front corner and the inside rear-axle side point share the wheel model's turning centre. */
public final class VehicleSweep {
    public static final class Envelope {
        public final double[] left,right,triangles;
        private Envelope(double[] left,double[] right,double[] triangles) {
            this.left=left;this.right=right;this.triangles=triangles;
        }
    }
    private static final Envelope EMPTY=new Envelope(new double[0],new double[0],new double[0]);
    private final VehicleGeometry car;
    public VehicleSweep(){this(VehicleGeometry.MG4);}
    public VehicleSweep(VehicleGeometry car){this.car=java.util.Objects.requireNonNull(car);}

    /** Positive travel is reverse, negative travel is forward. Stable at and near straight. */
    static void point(double k,double travel,double x,double y,double[] into,int offset) {
        double angle=k*travel,sin=Math.sin(angle),cos=Math.cos(angle),half=angle/2;
        // sin(a)/k and (1-cos(a))/k lose the straight limit when k is subnormal.
        double sinc=angle==0?1:sin/angle,sincHalf=half==0?1:Math.sin(half)/half;
        into[offset]=.5*k*travel*travel*sincHalf*sincHalf+x*cos+y*sin;
        into[offset+1]=travel*sinc-x*sin+y*cos;
    }
    public double outerFrontRadius(SteeringModel.Solution pose,double widthMm) {
        return pose.valid?Math.hypot(1/Math.abs(pose.curvaturePerMm)+widthMm/2,-car.frontY()):Double.NaN;
    }
    public Envelope forward(SteeringModel.Solution pose,double centerX,double centerY,double widthMm,double travelMm,double stepMm) {
        return forward(pose,centerX,centerY,widthMm,travelMm,stepMm,car.frontY(),car.rearY());
    }
    /** Same virtual width and motion, bounded longitudinally by the two axle planes. */
    public Envelope forwardAxles(SteeringModel.Solution pose,double centerX,double centerY,double widthMm,double travelMm,double stepMm) {
        return forward(pose,centerX,centerY,widthMm,travelMm,stepMm,-car.wheelbaseMm,0);
    }
    /** Reverse traverses the same finite sweep in the opposite order. Advancing the
     * forward envelope by the final reverse pose places it over poses 0..travel,
     * including the front corner's outward swing without approximating a wider track. */
    public Envelope reverse(SteeringModel.Solution pose,double centerX,double centerY,double widthMm,double travelMm,double stepMm) {
        Envelope envelope=forward(pose,centerX,centerY,widthMm,travelMm,stepMm);
        if(envelope.left.length==0)return envelope;
        double rearAxleY=car.rearAxleY(centerY);
        for(double[] xy:new double[][]{envelope.left,envelope.right,envelope.triangles})for(int i=0;i<xy.length;i+=2) {
            point(pose.curvaturePerMm,travelMm,xy[i]-centerX,xy[i+1]-rearAxleY,xy,i);
            xy[i]+=centerX;xy[i+1]+=rearAxleY;
        }
        // Edge order follows reverse driving: initial front bumper to final rear bumper.
        reversePoints(envelope.left);reversePoints(envelope.right);
        for(int i=0;i<envelope.triangles.length;i+=6) {
            double x=envelope.triangles[i+2],y=envelope.triangles[i+3];
            envelope.triangles[i+2]=envelope.triangles[i+4];envelope.triangles[i+3]=envelope.triangles[i+5];
            envelope.triangles[i+4]=x;envelope.triangles[i+5]=y;
        }
        return envelope;
    }
    private static void reversePoints(double[] xy) {
        for(int i=0,j=xy.length-2;i<j;i+=2,j-=2) {
            double x=xy[i],y=xy[i+1];xy[i]=xy[j];xy[i+1]=xy[j+1];xy[j]=x;xy[j+1]=y;
        }
    }
    private Envelope forward(SteeringModel.Solution pose,double centerX,double centerY,double widthMm,double travelMm,double stepMm,double frontY,double rearY) {
        if(!pose.valid||!Double.isFinite(centerX)||!Double.isFinite(centerY))return EMPTY;
        if(!Double.isFinite(widthMm)||widthMm<=0||!Double.isFinite(travelMm)||travelMm<=0||!Double.isFinite(stepMm)||stepMm<=0)
            throw new IllegalArgumentException("Invalid swept footprint");
        double k=Math.abs(pose.curvaturePerMm),halfWidth=widthMm/2;
        if(k*halfWidth>=1)throw new IllegalArgumentException("Turning centre lies within footprint");
        Path outer=new Path(stepMm),inner=new Path(stepMm);
        if(k==0) {
            outer.line(-halfWidth,rearY,-halfWidth,frontY-travelMm);
            inner.line(halfWidth,rearY,halfWidth,frontY-travelMm);
        }else {
            // The rear outer corner initially swings outside the current side. Its arc
            // rejoins that side after twice atan(rear overhang / outer rear-axle radius).
            double q=rearY*k/(1+halfWidth*k);
            double tailTravel=2*rearY/(1+halfWidth*k)*(q==0?1:Math.atan(q)/q);
            outer.arc(k,-halfWidth,rearY,Math.min(travelMm,tailTravel));
            if(travelMm<tailTravel) {
                double halfYaw=k*travelMm/2;
                outer.to(-halfWidth,-(1+halfWidth*k)*travelMm/2*(halfYaw==0?1:Math.tan(halfYaw)/halfYaw));
            }
            outer.to(-halfWidth,frontY);
            outer.arc(k,-halfWidth,frontY,travelMm);
            inner.line(halfWidth,rearY,halfWidth,0);
            inner.arc(k,halfWidth,0,travelMm);
            double[] front=new double[2];point(k,-travelMm,halfWidth,frontY,front,0);
            // At the final pose the body side joins the inside rear-axle arc to the
            // front corner. The closing edge is the final bumper, not a fictitious rear-wheel curve.
            inner.to(front[0],front[1]);
        }
        double[] outside=outer.finish(),inside=inner.finish();
        double turn=pose.curvaturePerMm<0?-1:1;
        double[] left=turn<0?inside:outside,right=turn<0?outside:inside;
        if(turn<0){mirror(left);mirror(right);}
        double[] triangles=triangulate(left,right,k,turn);
        double rearAxleY=car.rearAxleY(centerY);
        translate(left,centerX,rearAxleY);translate(right,centerX,rearAxleY);translate(triangles,centerX,rearAxleY);
        return new Envelope(left,right,triangles);
    }
    private static void mirror(double[] xy){for(int i=0;i<xy.length;i+=2)xy[i]=-xy[i];}
    private static void translate(double[] xy,double x,double y){for(int i=0;i<xy.length;i+=2){xy[i]+=x;xy[i+1]+=y;}}
    private static double phase(double[] xy,int index,double k,double turn) {
        return k==0?-xy[index+1]:Math.atan2(-k*xy[index+1],1-k*turn*xy[index]);
    }
    /** The two envelope edges are monotone around the turning centre. Zip their
     * phases into disjoint triangles in linear time; no per-frame polygon ear search. */
    private static double[] triangulate(double[] left,double[] right,double k,double turn) {
        double[] triangles=new double[(left.length/2+right.length/2-2)*6];int a=0,b=0,out=0;
        while(a+2<left.length||b+2<right.length) {
            boolean advanceLeft=b+2==right.length||(a+2<left.length&&phase(left,a+2,k,turn)<=phase(right,b+2,k,turn));
            double[] next=advanceLeft?left:right;int index=advanceLeft?a+2:b+2;
            triangles[out++]=left[a];triangles[out++]=left[a+1];
            triangles[out++]=next[index];triangles[out++]=next[index+1];
            triangles[out++]=right[b];triangles[out++]=right[b+1];
            if(advanceLeft)a+=2;else b+=2;
        }
        return triangles;
    }
    private static final class Path {
        private final double step;
        private double[] xy=new double[128];private int size;
        Path(double step){this.step=step;}
        void add(double x,double y) {
            if(size>0&&Math.hypot(x-xy[size-2],y-xy[size-1])<1e-7)return;
            if(size+2>xy.length)xy=Arrays.copyOf(xy,xy.length*2);
            xy[size++]=x;xy[size++]=y;
        }
        void line(double x,double y,double xx,double yy){add(x,y);to(xx,yy);}
        void to(double x,double y) {
            double startX=xy[size-2],startY=xy[size-1];
            int steps=Math.max(1,(int)Math.ceil(Math.hypot(x-startX,y-startY)/step));
            for(int i=1;i<=steps;i++)add(startX+(x-startX)*i/steps,startY+(y-startY)*i/steps);
        }
        void arc(double k,double x,double y,double travel) {
            int steps=Math.max(1,(int)Math.ceil(travel*Math.hypot(1-k*x,k*y)/step));
            double[] p=new double[2];
            for(int i=0;i<=steps;i++){point(k,-travel*i/steps,x,y,p,0);add(p[0],p[1]);}
        }
        double[] finish(){return Arrays.copyOf(xy,size);}
    }
}
