package com.drivehub.kamera.steering;

/** Speed and gear read together by the existing vehicle worker; speed is the Dashcam's km/h property. */
public final class VehicleMotionSnapshot {
    public static final long MAX_AGE_MS=500;
    public static final VehicleMotionSnapshot EMPTY=new VehicleMotionSnapshot(Float.NaN,0,0);
    public final float speedKmh;
    public final int gear;
    public final long readElapsedMs;
    private VehicleMotionSnapshot(float speedKmh,int gear,long now){this.speedKmh=speedKmh;this.gear=gear;readElapsedMs=now;}
    public static VehicleMotionSnapshot value(float speedKmh,int gear,long now) {
        return new VehicleMotionSnapshot(Float.isFinite(speedKmh)&&speedKmh>=0&&speedKmh<=300?speedKmh:Float.NaN,gear,now);
    }
    public boolean fresh(long now){return Float.isFinite(speedKmh)&&now>=readElapsedMs&&now-readElapsedMs<=MAX_AGE_MS;}
    public int direction(long now){return !fresh(now)?0:gear==4?1:gear==2?-1:0;}
}
