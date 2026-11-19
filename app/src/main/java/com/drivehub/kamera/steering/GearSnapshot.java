package com.drivehub.kamera.steering;

/** Successful SAIC gear-property read; 1=P, 2=R, 3=N, 4=D. Missing reads never reuse a gear. */
public final class GearSnapshot {
    public static final long MAX_AGE_MS=500;
    public static final GearSnapshot EMPTY=new GearSnapshot(0,0);
    public final int gear;
    public final long readElapsedMs;
    private GearSnapshot(int gear,long readElapsedMs){this.gear=gear;this.readElapsedMs=readElapsedMs;}
    public static GearSnapshot value(int gear,long readElapsedMs) {
        return new GearSnapshot(gear>=1&&gear<=4?gear:0,readElapsedMs);
    }
    public WheelGuides.Direction direction(long nowMs) {
        if(nowMs<readElapsedMs||nowMs-readElapsedMs>MAX_AGE_MS)return null;
        return gear==2?WheelGuides.Direction.REVERSE:gear==4?WheelGuides.Direction.FORWARD:null;
    }
}
