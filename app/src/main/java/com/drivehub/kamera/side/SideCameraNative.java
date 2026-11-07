package com.drivehub.kamera.side;

/** Shared stream-manager taps for the two side cameras; no OEM renderer or assets. */
final class SideCameraNative {
    static {
        System.loadLibrary("cameraprobe");
    }

    private SideCameraNative() {}

    static native void enableFrames();
    static native boolean attachFrames(boolean retryStale);
    static native void detachFrames();
    static native int updateTextures(int leftY, int leftUv, int rightY, int rightUv);
}
