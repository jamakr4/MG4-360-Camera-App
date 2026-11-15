package com.drivehub.kamera.birdseye;

/** Receipt times from one monotonic clock; these are not synchronized sensor exposures. */
public final class BirdseyeFrameHealth {
    public static final long MAX_AGE_US = 500_000, MAX_SKEW_US = 150_000;
    private BirdseyeFrameHealth() {}
    public static int freshMask(long[] capturedUs, long nowUs) {
        long newest = 0;
        for (long t : capturedUs) if (t > 0 && t <= nowUs) newest = Math.max(newest, t);
        int mask = 0;
        for (int i = 0; i < 4; i++) {
            long t = capturedUs[i];
            if (t > 0 && t <= nowUs && nowUs-t <= MAX_AGE_US && newest-t <= MAX_SKEW_US) mask |= 1 << i;
        }
        return mask;
    }
}
