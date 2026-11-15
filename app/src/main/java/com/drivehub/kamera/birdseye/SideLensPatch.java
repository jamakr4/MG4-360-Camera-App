package com.drivehub.kamera.birdseye;

import com.drivehub.kamera.side.SideCameraGeometry;

/** Shared live/debug rectilinear or cylindrical camera at the real optical centre; no ground-plane assumption. */
public final class SideLensPatch {
    public static final int MISSING=0xFF30232E;
    private SideLensPatch(){}
    private static double radius(double[] k,double theta){double a=theta*theta;return theta*(1+a*(k[0]+a*(k[1]+a*(k[2]+a*k[3]))));}
    private static double[] ray(BirdseyeCalibration.CameraParameters c,double u,double v){
        if(!Double.isFinite(u)||!Double.isFinite(v)||u<0||u>719||v<0||v>479)throw new IllegalArgumentException("Point outside image");
        double x=(u-c.cx)/c.fx,y=(v-c.cy)/c.fy,r=Math.hypot(x,y);
        double[] k=c.distortion();
        if(r>=radius(k,BirdseyeCalibration.MAX_THETA))throw new IllegalArgumentException("Point outside calibrated lens");
        double low=0,high=BirdseyeCalibration.MAX_THETA;
        for(int i=0;i<45;i++){double mid=(low+high)/2;if(radius(k,mid)<r)low=mid;else high=mid;}
        double theta=(low+high)/2,scale=r<1e-12?0:Math.sin(theta)/r;
        return new double[]{x*scale,y*scale,Math.cos(theta)};
    }
    private static double dot(double[] a,double[] b){return a[0]*b[0]+a[1]*b[1]+a[2]*b[2];}
    private static double[] unit(double[] v){double n=Math.sqrt(dot(v,v));if(n<1e-9)throw new IllegalArgumentException("Degenerate lens selection");return new double[]{v[0]/n,v[1]/n,v[2]/n};}
    private static double[] cross(double[] a,double[] b){return new double[]{a[1]*b[2]-a[2]*b[1],a[2]*b[0]-a[0]*b[2],a[0]*b[1]-a[1]*b[0]};}
    /** Wheel-crop corners in sensor pixels, clipped to the validated image/lens boundary. */
    public static double[] defaults(BirdseyeCalibration calibration,int camera,boolean front){
        return defaults(calibration,camera,SideCameraGeometry.wheel(camera==0,front),false);
    }
    /** Full portrait side view, with the same upright orientation as the legacy Sides crop. */
    public static double[] sideDefaults(BirdseyeCalibration calibration,int camera){
        return defaults(calibration,camera,SideCameraGeometry.side(camera==0),true);
    }
    private static double[] defaults(BirdseyeCalibration calibration,int camera,SideCameraGeometry.Crop crop,boolean cylindrical){
        if(camera<0||camera>1)throw new IllegalArgumentException("Side camera required");
        double cx=(crop.left+crop.right)*480/2.0,cy=(crop.top+crop.bottom)*720/2.0;
        double halfW=(crop.right-crop.left)*480/2.0,halfH=(crop.bottom-crop.top)*720/2.0;
        BirdseyeCalibration.CameraParameters c=calibration.cameraParameters(camera);
        double[] clipped=new double[8];double limit=radius(c.distortion(),BirdseyeCalibration.MAX_THETA)-1e-6;
        for(int i=0;i<4;i++){
            double x=cx+(i==0||i==3?-halfW:halfW),y=cy+(i<2?-halfH:halfH);
            double u=Math.max(0,Math.min(719,camera==0?719-y:y)),v=Math.max(0,Math.min(479,camera==0?x:479-x));
            double nx=(u-c.cx)/c.fx,ny=(v-c.cy)/c.fy,r=Math.hypot(nx,ny),scale=r>limit?limit/r:1;
            clipped[i*2]=c.cx+c.fx*nx*scale;clipped[i*2+1]=c.cy+c.fy*ny*scale;
        }
        try{new Map(calibration,camera,clipped,2,2,cylindrical);return clipped;}catch(IllegalArgumentException invalid){}
        for(double inset=1;inset>.001;inset*=.9){
            double[] points=new double[8];boolean valid=true;
            for(int i=0;i<4;i++){
                double x=cx+(i==0||i==3?-halfW:halfW)*inset,y=cy+(i<2?-halfH:halfH)*inset;
                points[2*i]=camera==0?719-y:y;points[2*i+1]=camera==0?x:479-x;
                try{ray(c,points[2*i],points[2*i+1]);}catch(IllegalArgumentException outside){valid=false;}
            }
            if(valid)try{new Map(calibration,camera,points,2,2,cylindrical);return points;}catch(IllegalArgumentException invalid){}
        }
        throw new IllegalArgumentException("Side crop outside calibrated lens; choose four valid points");
    }
    public static final class Map {
        public final int width,height,covered;
        private final float[] uv;
        public Map(BirdseyeCalibration calibration,int camera,double[] points,int width,int height){
            this(calibration,camera,points,width,height,false);
        }
        /** Cylindrical along the portrait axis for a full side's very wide front/rear field. */
        public Map(BirdseyeCalibration calibration,int camera,double[] points,int width,int height,boolean cylindrical){
            if(camera<0||camera>1||points==null||points.length!=8||width<2||width>1024||height<2||height>1024)throw new IllegalArgumentException("Invalid bounded lens map");
            this.width=width;this.height=height;uv=new float[width*height*2];
            BirdseyeCalibration.CameraParameters c=calibration.cameraParameters(camera);
            double[][] rays=new double[4][];double[] sum=new double[3];double sign=0;
            for(int i=0;i<4;i++){
                int j=(i+1)%4,k=(i+2)%4;double turn=(points[j*2]-points[i*2])*(points[k*2+1]-points[j*2+1])-(points[j*2+1]-points[i*2+1])*(points[k*2]-points[j*2]);
                if(Math.abs(turn)<1||sign*turn<0)throw new IllegalArgumentException("Use four ordered, convex lens points");sign=turn;
                rays[i]=ray(c,points[2*i],points[2*i+1]);for(int a=0;a<3;a++)sum[a]+=rays[i][a];
            }
            double[] forward=cylindrical?ray(c,(points[0]+points[2]+points[4]+points[6])/4,
                    (points[1]+points[3]+points[5]+points[7])/4):unit(sum),right=new double[3];
            for(int a=0;a<3;a++)right[a]=rays[1][a]+rays[2][a]-rays[0][a]-rays[3][a];
            double parallel=dot(right,forward);for(int a=0;a<3;a++)right[a]-=parallel*forward[a];right=unit(right);double[] down=unit(cross(forward,right));
            double minX=Double.POSITIVE_INFINITY,maxX=-minX,minY=minX,maxY=-minX;
            for(double[] r:rays){
                double z=dot(r,forward),d=dot(r,down);
                if(!cylindrical&&z<=.01)throw new IllegalArgumentException("Selection wider than one rectilinear view");
                double denominator=cylindrical?Math.hypot(d,z):z;
                if(denominator<1e-6)throw new IllegalArgumentException("Selection touches cylindrical pole");
                double x=dot(r,right)/denominator,y=cylindrical?Math.atan2(d,z):d/z;
                minX=Math.min(minX,x);maxX=Math.max(maxX,x);minY=Math.min(minY,y);maxY=Math.max(maxY,y);
            }
            if(cylindrical&&maxY-minY>=Math.PI)throw new IllegalArgumentException("Side selection wraps around camera");
            double scale=Math.min((width-1)/(maxX-minX),(height-1)/(maxY-minY));
            double cx=(minX+maxX)/2,cy=(minY+maxY)/2;int count=0;double[] distortion=c.distortion();
            for(int y=0;y<height;y++)for(int x=0;x<width;x++){
                double dx=cx+(x-(width-1)/2.0)/scale,dy=cy+(y-(height-1)/2.0)/scale;
                double f=cylindrical?Math.cos(dy):1,d=cylindrical?Math.sin(dy):dy;
                double qx=f*forward[0]+dx*right[0]+d*down[0],qy=f*forward[1]+dx*right[1]+d*down[1],qz=f*forward[2]+dx*right[2]+d*down[2];
                double r=Math.hypot(qx,qy),theta=Math.atan2(r,qz),a=theta*theta;
                double rd=theta*(1+a*(distortion[0]+a*(distortion[1]+a*(distortion[2]+a*distortion[3])))),factor=r<1e-12?0:rd/r;
                double u=c.cx+c.fx*qx*factor,v=c.cy+c.fy*qy*factor;int i=(y*width+x)*2;
                if(theta<BirdseyeCalibration.MAX_THETA&&u>=0&&u<719&&v>=0&&v<479){uv[i]=(float)u;uv[i+1]=(float)v;count++;}
                else {uv[i]=Float.NaN;uv[i+1]=Float.NaN;}
            }
            covered=count;
        }
        /** Defensive copy in unmirrored 720x480 reference pixels; NaN pairs mean missing coverage. */
        public float[] coordinates(){return uv.clone();}
        public double[] sourcePixel(int x,int y){int i=(y*width+x)*2;return new double[]{uv[i],uv[i+1]};}
        public int[] render(int[] source){return sample(source,width,height,uv);}
    }
    static int[] sample(int[] source,int width,int height,float[] uv){
            if(source==null||source.length!=720*480)throw new IllegalArgumentException("720x480 reference pixels required");
            int[] output=new int[width*height];
            for(int i=0;i<output.length;i++){
                float u=uv[i*2],v=uv[i*2+1];
                if(!Float.isFinite(u)||!Float.isFinite(v)){output[i]=MISSING;continue;}
                int x=Math.min(718,(int)u),y=Math.min(478,(int)v);double dx=u-x,dy=v-y;int rgb=0xFF000000;
                for(int shift=0;shift<24;shift+=8){
                    double a=((source[y*720+x]>>>shift)&255)*(1-dx)+((source[y*720+x+1]>>>shift)&255)*dx;
                    double b=((source[(y+1)*720+x]>>>shift)&255)*(1-dx)+((source[(y+1)*720+x+1]>>>shift)&255)*dx;
                    rgb|=(int)Math.round(a*(1-dy)+b*dy)<<shift;
                }
                output[i]=rgb;
            }
            return output;
    }
}
