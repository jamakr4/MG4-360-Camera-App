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

    public static native boolean attachEnhancedPreview(int videoIndex, android.view.Surface surface);

    /** Detaches the preview consumer from the given /dev/video index. */
    public static native void detachPreview(int videoIndex);

    /**
     * What each camera device reported the last time it was opened: pixel format, size, stride
     * and field order.
     *
     * <p>The field order is the one that matters. The capture path keeps the top half of every
     * frame; whether the other half is a duplicate or the missing scan lines decides whether the
     * recording is at half the vertical resolution it could be.
     */
    public static native String describeCameraFormats();

    /**
     * Shows the composed grid live on a Surface - the same canvas that recording writes.
     *
     * <p>If a recording is running the preview rides on its composer, so what appears is the very
     * frame going into the file. If not, a composer is started with no encoder behind it.
     */
    public static native boolean attachCombinedPreview(android.view.Surface surface,
            int cellWidth, int cellHeight, int fps, String signature, boolean showSpeed,
            int cameraMask);

    public static native boolean detachCombinedPreview();

    /** The composed canvas size, so the preview can be given its shape instead of a stretch. */
    public static native int previewCanvasWidth(int cellWidth, int cellHeight);

    public static native int previewCanvasHeight(int cellWidth, int cellHeight);

    /**
     * Detaches all preview consumers managed by the native camera stream manager.
     */
    public static native void detachAllPreviews();

    /**
     * Starts MP4 recording from a specific /dev/videoX device.
     * slot: 0..3 to allow multiple concurrent recorders.
     */
    /**
     * Forces the filesystem the given path lives on to write out what it is still holding.
     *
     * <p>For deletions, which nothing else forces: see the comment on the native side. Returns
     * false if the path cannot be opened or the sync fails, and the caller is expected to carry
     * on - a medium that will not sync has larger problems than this call can fix.
     */
    public static native boolean syncFilesystem(String path);

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
     * Stops the currently running combined grid recording.
     *
     * @param keepCamerasWarm true only when another clip is about to start immediately. The four
     *                        video devices are then left open and streaming with nothing reading
     *                        from them, so the next clip does not pay to reopen them. Any stop
     *                        that really means it - the service stopping, the screen going off,
     *                        the factory 360 view asking for the cameras - passes false, and the
     *                        devices are released before this call returns.
     */
    public static native boolean stopCombinedMp4Record(boolean keepCamerasWarm, boolean urgent);

    public static boolean stopCombinedMp4Record() {
        return stopCombinedMp4Record(false, false);
    }

    /** Successful muxer samples in this segment, or -1 on a write failure. */
    public static native long writtenSamples();

    /**
     * Releases cameras left open by {@link #stopCombinedMp4Record(boolean, boolean)} with the hold set.
     *
     * <p>Idempotent and cheap: it does nothing if no hold is outstanding. Called wherever the
     * recording loop stops meaning to start another clip, so the devices never linger for a
     * rotation that is not coming.
     */
    public static native void releaseCombinedCameras();

    /**
     * Waits for every finished clip to be fully on the medium.
     *
     * <p>Closing a clip hands the descriptor to a detached thread that fsyncs it and syncs the
     * directory, so the next clip does not wait for up to 34 MB to land. Nobody could wait for
     * those threads, which made the shutdown path claim the stick was idle while it was not.
     *
     * @return true if everything landed, false if the timeout ran out first.
     */
    public static native boolean awaitPendingFlushes(int timeoutMs);

    /** Updates the current speed shown in the active combined recording overlay. */
    public static native void updateCombinedRecordingSpeed(int speedKmh);
}
