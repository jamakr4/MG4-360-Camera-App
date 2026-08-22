package com.drivehub.kamera;

public final class CameraProbe {
    static {
        System.loadLibrary("cameraprobe");
    }

    private CameraProbe() {
    }

    /** Probes /dev/video0..maxIndex-1 and returns a human-readable summary. */
    public static native String probeAll(int maxIndex);

    /** Attaches a preview consumer to the given /dev/video index. */
    public static native boolean attachPreview(int videoIndex, android.view.Surface surface);

    /** Detaches the preview consumer from the given /dev/video index. */
    public static native void detachPreview(int videoIndex);

    /**
     * Detaches all preview consumers managed by the native camera stream manager.
     */
    public static native void detachAllPreviews();

    /**
     * Starts MP4 recording from a specific /dev/videoX device.
     * slot: 0..3 to allow multiple concurrent recorders.
     */
    public static native boolean startMp4Record(int slot, int videoIndex, String outputPath,
            int width, int height, int fps, int bitrate);

    /** Stops MP4 recording for the given slot. */
    public static native boolean stopMp4Record(int slot);

    /** Starts a grid MP4 recording that combines the selected cameras. */
    public static native boolean startCombinedMp4Record(String outputPath,
            int cellWidth, int cellHeight,
            int fps, int bitrate,
            String signature,
            boolean showSpeed,
            int cameraMask);

    /**
     * Starts a grid MP4 recording on an already opened, seekable file descriptor. The native
     * layer duplicates the descriptor before this call returns, so the Java owner may close its
     * ParcelFileDescriptor immediately afterwards.
     */
    public static native boolean startCombinedMp4RecordFd(int outputFd,
            int cellWidth, int cellHeight,
            int fps, int bitrate,
            String signature,
            boolean showSpeed,
            int cameraMask);

    /** Stops the currently running combined grid recording. */
    public static native boolean stopCombinedMp4Record();

    /** Updates the current speed shown in the active combined recording overlay. */
    public static native void updateCombinedRecordingSpeed(int speedKmh);
}
