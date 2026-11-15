package com.drivehub.kamera.birdseye;

/** Visual road-wheel angle in degrees, positive right. Not a steering-wheel sensor conversion. */
public final class WheelSteering {
    public static final int MAX_DEGREES=35;
    private volatile float degrees;
    public float degrees(){return degrees;}
    public void setDegrees(float value){if(Float.isFinite(value))degrees=Math.max(-MAX_DEGREES,Math.min(MAX_DEGREES,value));}
}
