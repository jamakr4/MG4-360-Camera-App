package com.drivehub.kamera.camera;

import com.drivehub.kamera.birdseye.BirdseyeCalibration;

/** Rectilinear rearview at the original optical centre and direction, before mirroring. */
public final class RearviewLensMap {
    public static final int WIDTH=260, HEIGHT=173;
    public static final double HORIZONTAL_FOV_DEGREES=100;
    public final String calibrationSha256;
    private final float[] uv=new float[WIDTH*HEIGHT*2];

    public RearviewLensMap(BirdseyeCalibration calibration) {
        calibrationSha256=calibration.sha256;
        BirdseyeCalibration.CameraParameters c=calibration.cameraParameters(3);
        double[] k=c.distortion();
        double focal=WIDTH/(2*Math.tan(Math.toRadians(HORIZONTAL_FOV_DEGREES/2)));
        for(int y=0;y<HEIGHT;y++)for(int x=0;x<WIDTH;x++) {
            double qx=(x+.5-WIDTH/2.0)/focal,qy=(y+.5-HEIGHT/2.0)/focal;
            double radial=Math.hypot(qx,qy),theta=Math.atan(radial),t=theta*theta;
            double radius=theta*(1+t*(k[0]+t*(k[1]+t*(k[2]+t*k[3]))));
            double scale=radial<1e-12?0:radius/radial;
            double u=c.cx+c.fx*qx*scale,v=c.cy+c.fy*qy*scale;
            if(theta>=BirdseyeCalibration.MAX_THETA||!Double.isFinite(u)||!Double.isFinite(v)
                    ||u<0||u>719||v<0||v>479)
                throw new IllegalArgumentException("Rearview outside calibrated image");
            int i=(y*WIDTH+x)*2;uv[i]=(float)u;uv[i+1]=(float)v;
        }
    }
    /** Original unmirrored 720x480 calibration pixels; native code handles half/full fields. */
    public float[] coordinates(){return uv.clone();}
}
