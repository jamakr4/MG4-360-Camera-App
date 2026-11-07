package com.drivehub.kamera.side;

/** Shared stream-manager taps for the two side cameras; no OEM renderer or assets. */
final class SideCameraNative {
    static {
        System.loadLibrary("cameraprobe");
    }

    private SideCameraNative() {}

    static native void enablePreviews();
    static native void setWheelsMode(boolean wheels);
    static native boolean attachPreview(int side, android.view.Surface surface, float[] crops);
    static native void detachPreview(int side);
    static native void detachPreviews();
    static native long previewAgeMs(int side);
}
