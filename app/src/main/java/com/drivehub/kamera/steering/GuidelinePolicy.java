package com.drivehub.kamera.steering;

/** Visibility only: automatic speed filtering never changes the user's main on/off choice. */
public final class GuidelinePolicy {
    public static final int DEFAULT_SPEED_KMH=25;
    private GuidelinePolicy() {}
    public static int clampSpeedKmh(int speed){return Math.max(0,Math.min(130,speed));}
    public static boolean visible(boolean enabled,boolean limitEnabled,int limitKmh,
                                  VehicleMotionSnapshot motion,long nowMs) {
        return enabled&&(!limitEnabled||(motion!=null&&motion.fresh(nowMs)
                &&motion.speedKmh<=clampSpeedKmh(limitKmh)));
    }
}
