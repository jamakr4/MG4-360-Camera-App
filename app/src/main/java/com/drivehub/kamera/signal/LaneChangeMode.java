package com.drivehub.kamera.signal;

/** Pure threshold logic for the optional lane-change-only signal camera mode. */
public final class LaneChangeMode {

    private LaneChangeMode() {
    }

    public static boolean allowsSignalCamera(boolean enabled, int speedKmh, int minSpeedKmh) {
        return !enabled || speedKmh >= minSpeedKmh;
    }
}
