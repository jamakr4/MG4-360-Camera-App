package com.drivehub.kamera.camera;

import com.drivehub.kamera.R;

/**
 * Maps the four factory cameras to their {@code /dev/videoX} indices.
 * <p>
 * The MG4 exposes the surround-view cameras through a single V4L2 device per
 * direction. Each device delivers a stacked UYVY frame whose top half is the
 * actual camera feed and whose bottom half is unused.
 */
public enum CameraIndex {

    /** Passenger side – mirrored for natural driving-direction view. */
    RIGHT(14, R.string.main_camera_label_right, false),

    /** Forward-facing camera. */
    FRONT(15, R.string.main_camera_label_front, false),

    /** Driver side – mirrored for natural driving-direction view. */
    LEFT(16, R.string.main_camera_label_left, false),

    /** Rear-facing camera – flipped horizontally so left/right match expectations. */
    REAR(17, R.string.main_camera_label_rear, true);

    private final int videoIndex;
    private final int labelResId;
    private final boolean flipHorizontal;

    CameraIndex(int videoIndex, int labelResId, boolean flipHorizontal) {
        this.videoIndex = videoIndex;
        this.labelResId = labelResId;
        this.flipHorizontal = flipHorizontal;
    }

    /** The {@code /dev/videoX} node index for this camera. */
    public int getVideoIndex() {
        return videoIndex;
    }

    /** String resource id for the human-readable camera label. */
    public int getLabelResId() {
        return labelResId;
    }

    /** Whether frames from this camera should be mirrored. */
    public boolean isFlipHorizontal() {
        return flipHorizontal;
    }

    /** Returns {@code true} for LEFT and RIGHT (side-facing) cameras. */
    public boolean isSide() {
        return this == LEFT || this == RIGHT;
    }

    /**
     * Looks up the enum constant for the given hardware video index.
     *
     * @return the matching constant, or {@code null} if {@code videoIndex} is not 14–17.
     */
    public static CameraIndex fromVideoIndex(int videoIndex) {
        for (CameraIndex ci : values()) {
            if (ci.videoIndex == videoIndex) return ci;
        }
        return null;
    }
}
