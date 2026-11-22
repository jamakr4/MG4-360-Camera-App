package com.drivehub.kamera.birdseye;
import com.drivehub.kamera.rivela.Bowl;
import com.drivehub.kamera.rivela.CarFrame;
import com.drivehub.kamera.rivela.FisheyeCamera;
/** Adapter for PR #102; the independently validated calibration parser stays unchanged. */
public final class RivelaScene {
    public static final int BOWL_THETA=144,BOWL_RINGS=56;
    public static final double GUIDANCE_FLAT_MM=4000;
    public final BirdseyeCalibration calibration;
    public final CarFrame car;
    private final FisheyeCamera[] cameras=new FisheyeCamera[4];
    private volatile Bowl bowl;
    private float[] rearGround;
    private final double flatMm;
    public RivelaScene(BirdseyeCalibration calibration) {
        this(calibration,Bowl.FLAT_MM);
    }
    /** Extend flat calibrated ground for distance guides without moving an existing scene's mesh. */
    public RivelaScene(BirdseyeCalibration calibration,double flatMm) {
        if(!Double.isFinite(flatMm)||flatMm<0||flatMm>Bowl.MAX_MM)throw new IllegalArgumentException("Invalid flat ground extent");
        this.flatMm=flatMm;
        this.calibration=calibration;
        for(int i=0;i<4;i++) {
            BirdseyeCalibration.CameraParameters c=calibration.cameraParameters(i);
            cameras[i]=new FisheyeCamera(720,480,c.fx,c.fy,c.cx,c.cy,c.distortion(),c.rotation(),c.translation());
        }
        car=CarFrame.fromCameras(cameras[0],cameras[1],cameras[2],cameras[3],BirdseyeMesh.CAR_LENGTH,BirdseyeMesh.CAR_WIDTH);
    }
    public FisheyeCamera camera(int index) { return cameras[index]; }
    public Bowl bowl() { return bowl; }
    float[] rearGround() { return rearGround; }
    /** Scene IO worker only, never capture/UI/GL. Validate the extended lens range independently. */
    public synchronized void prepareBowl() {
        if(bowl!=null)return;
        validateBowlLenses();
        Bowl built=Bowl.build(cameras,car,BOWL_THETA,BOWL_RINGS,flatMm,Bowl.CURVE_MM);
        for(float f:built.vertices)if(!Float.isFinite(f))throw new IllegalArgumentException("Non-finite bowl vertex");
        rearGround=RearGroundPatch.build(this,built);
        bowl=built;
    }
    public void validateBowlLenses() {
        for(FisheyeCamera c:cameras) {
            double previous=0;
            for(int j=1;j<=190;j++) {
                double theta=Math.toRadians(j*.5),t2=theta*theta;
                double radius=theta*(1+t2*(c.k[0]+t2*(c.k[1]+t2*(c.k[2]+t2*c.k[3]))));
                if(!Double.isFinite(radius)||radius<=previous)throw new IllegalArgumentException("3D lens range is invalid");
                previous=radius;
            }
        }
    }
}
