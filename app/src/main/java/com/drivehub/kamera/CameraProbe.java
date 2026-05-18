package com.drivehub.kamera;

public final class CameraProbe {
    static {
        System.loadLibrary("cameraprobe");
    }

    private CameraProbe() {}

    /** Probes /dev/video0..maxIndex-1 and returns a human-readable summary. */
    public static native String probeAll(int maxIndex);

    /** Starts preview on the given /dev/video index onto the provided Surface. */
    public static native boolean startPreview(int videoIndex, android.view.Surface surface);

    /** Stops any running preview. */
    public static native void stopPreview();

    /** Updates the live undistort tuning parameters used by the native preview path. */
    public static native void setUndistortParams(float balance, float fovScale);

    /**
     * Starts MP4 recording from a specific /dev/videoX device.
     * slot: 0..3 to allow multiple concurrent recorders.
     */
    public static native boolean startMp4Record(int slot, int videoIndex, String outputPath,
                                                  int width, int height, int fps, int bitrate);

    /** Stops MP4 recording for the given slot. */
    public static native void stopMp4Record(int slot);
}
