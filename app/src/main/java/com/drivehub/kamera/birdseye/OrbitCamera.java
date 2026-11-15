package com.drivehub.kamera.birdseye;
/** UI-writable bounded orbit. Eye starts above the rear-right quarter; world Z points down. */
public final class OrbitCamera {
    private float yaw=35,pitch=55,distance=11000;
    public synchronized void rotate(float dx,float dy) { yaw=(yaw+dx*.35f)%360;pitch=Math.max(25,Math.min(80,pitch+dy*.25f)); }
    public synchronized void zoom(float factor) { if(Float.isFinite(factor)&&factor>0)distance=Math.max(5000,Math.min(16000,distance/factor)); }
    public synchronized void reset() {yaw=35;pitch=55;distance=11000;}
    public synchronized float[] values() {return new float[]{yaw,pitch,distance};}
}
