package com.drivehub.kamera.birdseye;

/** Shared ground-strip / four-point fisheye warp. No vehicle images or fitted coordinates. */
public final class SideGroundPatch {
    public static final int SIDE=0, FRONT=1, REAR=2;
    public static final int MISSING=0xFF30232E;
    private SideGroundPatch() {}

    /** Automatic metric ground rectangle next to the car; front/rear axle positions are approximate. */
    public static double[] defaults(BirdseyeCalibration calibration,int camera,int region) {
        if(camera<0||camera>1||region<0||region>2)throw new IllegalArgumentException("Side camera/region required");
        if(region==SIDE)return parkingStrip(calibration,camera);
        double near=1836/2.0+80,far=near+1620;
        double x0=calibration.centerX+(camera==0?-far:near);
        double x1=calibration.centerX+(camera==0?-near:far);
        double y0=calibration.centerY-4587/2.0,y1=calibration.centerY+4587/2.0;
        if(region!=SIDE){
            double cy=calibration.centerY+(region==FRONT?-1350:1350);
            y0=cy-485;y1=cy+485;
            if(camera==0)x0=x1-1100;else x1=x0+1100;
        }
        return new double[]{x0,y0,x1,y0,x1,y1,x0,y1};
    }
    /** Vehicle-parallel road strip including a small visible body margin.
     * Front is at the top; the body stays on the inner edge on both sides.
     * The forward/rear calibration positions define the longitudinal axis, not image corners.
     */
    private static double[] parkingStrip(BirdseyeCalibration calibration,int camera){
        double[] front=calibration.cameraCenter(2),rear=calibration.cameraCenter(3);
        double ax=rear[0]-front[0],ay=rear[1]-front[1],length=Math.hypot(ax,ay);
        if(!Double.isFinite(length)||length<1e-6)throw new IllegalArgumentException("Vehicle axis unavailable");
        ax/=length;ay/=length;
        double near=1836/2.0-350,far=near+1700,halfLength=4287/2.0+200;
        double left=camera==0?-far:near,right=camera==0?-near:far;
        double[] lateral={left,right,right,left},along={-halfLength,-halfLength,halfLength,halfLength},quad=new double[8];
        for(int i=0;i<4;i++){
            quad[i*2]=calibration.centerX+ay*lateral[i]+ax*along[i];
            quad[i*2+1]=calibration.centerY-ax*lateral[i]+ay*along[i];
        }
        return quad;
    }
    public static double[] imageCorners(BirdseyeCalibration calibration,int camera,double[] ground) {
        validate(ground);double[] result=new double[8];
        for(int i=0;i<4;i++){
            BirdseyeCalibration.Projection p=calibration.project(camera,ground[2*i],ground[2*i+1]);
            result[2*i]=p.weight>0?p.u:Double.NaN;result[2*i+1]=p.weight>0?p.v:Double.NaN;
        }
        return result;
    }
    public static double[] fromImageCorners(BirdseyeCalibration calibration,int camera,double[] pixels) {
        if(pixels==null||pixels.length!=8)throw new IllegalArgumentException("Four points required");
        double[] ground=new double[8];
        for(int i=0;i<4;i++){
            double u=pixels[2*i],v=pixels[2*i+1];
            if(!Double.isFinite(u)||!Double.isFinite(v)||u<0||u>719||v<0||v>479)
                throw new IllegalArgumentException("Point outside the reference image");
            double[] p=calibration.groundPoint(camera,u,v);
            if(p==null)throw new IllegalArgumentException("Point has no valid ground intersection");
            BirdseyeCalibration.Projection check=calibration.project(camera,p[0],p[1]);
            if(check.weight<=0||Math.hypot(check.u-u,check.v-v)>.01)
                throw new IllegalArgumentException("Point outside calibrated ground coverage");
            ground[2*i]=p[0];ground[2*i+1]=p[1];
        }
        validate(ground);return ground;
    }
    private static void validate(double[] p){
        if(p==null||p.length!=8)throw new IllegalArgumentException("Four ground points required");
        for(double n:p)if(!Double.isFinite(n)||Math.abs(n)>100000)
            throw new IllegalArgumentException("Invalid or too distant ground point");
        double sign=0,area=0;
        for(int i=0;i<4;i++){
            int j=(i+1)%4,k=(i+2)%4;
            double cross=(p[2*j]-p[2*i])*(p[2*k+1]-p[2*j+1])-(p[2*j+1]-p[2*i+1])*(p[2*k]-p[2*j]);
            if(Math.abs(cross)<1||sign*cross<0)throw new IllegalArgumentException("Points must form a convex, ordered quadrilateral");
            sign=cross;area+=p[2*i]*p[2*j+1]-p[2*j]*p[2*i+1];
        }
        if(Math.abs(area)<100)throw new IllegalArgumentException("Ground patch too small");
    }
    public static final class Warp {
        private final double[] h;
        public Warp(double[] ground){
            validate(ground);double[][] m=new double[8][9];
            double[][] corners={{0,0},{1,0},{1,1},{0,1}};
            for(int i=0;i<4;i++){
                double s=corners[i][0],t=corners[i][1],x=ground[2*i],y=ground[2*i+1];
                m[2*i]=new double[]{s,t,1,0,0,0,-x*s,-x*t,x};
                m[2*i+1]=new double[]{0,0,0,s,t,1,-y*s,-y*t,y};
            }
            for(int col=0;col<8;col++){
                int pivot=col;for(int r=col+1;r<8;r++)if(Math.abs(m[r][col])>Math.abs(m[pivot][col]))pivot=r;
                if(Math.abs(m[pivot][col])<1e-10)throw new IllegalArgumentException("Degenerate ground patch");
                double[] swap=m[col];m[col]=m[pivot];m[pivot]=swap;
                double divisor=m[col][col];for(int j=col;j<=8;j++)m[col][j]/=divisor;
                for(int r=0;r<8;r++)if(r!=col){double factor=m[r][col];for(int j=col;j<=8;j++)m[r][j]-=factor*m[col][j];}
            }
            h=new double[8];for(int i=0;i<8;i++)h[i]=m[i][8];
        }
        public double[] ground(double s,double t){
            double denominator=h[6]*s+h[7]*t+1;
            if(!Double.isFinite(denominator)||Math.abs(denominator)<1e-10)return null;
            return new double[]{(h[0]*s+h[1]*t+h[2])/denominator,(h[3]*s+h[4]*t+h[5])/denominator};
        }
    }
    /** Cached inverse sampling map; no fitting or image reconstruction on the UI/capture thread. */
    public static final class Map {
        public final int width,height,covered;
        private final float[] uv;
        public Map(BirdseyeCalibration calibration,int camera,double[] ground,int width,int height){
            if(camera<0||camera>1||width<2||width>1024||height<2||height>1024)throw new IllegalArgumentException("Invalid bounded map");
            this.width=width;this.height=height;uv=new float[width*height*2];int count=0;
            Warp warp=new Warp(ground);
            for(int y=0;y<height;y++)for(int x=0;x<width;x++){
                int i=(y*width+x)*2;double[] g=warp.ground(x/(double)(width-1),y/(double)(height-1));
                BirdseyeCalibration.Projection p=g==null?null:calibration.project(camera,g[0],g[1]);
                if(p!=null&&p.weight>0){uv[i]=(float)p.u;uv[i+1]=(float)p.v;count++;}
                else {uv[i]=Float.NaN;uv[i+1]=Float.NaN;}
            }
            covered=count;
        }
        public int[] render(int[] source){return sample(source,width,height,uv);}
        public float[] coordinates(){return uv.clone();}
        public double[] sourcePixel(int x,int y){int i=(y*width+x)*2;return new double[]{uv[i],uv[i+1]};}
    }
    static int[] sample(int[] source,int width,int height,float[] uv){return SideLensPatch.sample(source,width,height,uv);}
}
