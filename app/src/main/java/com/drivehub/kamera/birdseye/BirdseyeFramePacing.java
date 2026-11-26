package com.drivehub.kamera.birdseye;

/**
 * 2D draw decision for four unsynchronized cameras, evaluated once per display frame.
 *
 * <p>Draw as late as possible so that new images from several cameras share one composite,
 * but never so late that a camera delivers a second image before its first was shown:
 * that would skip a camera frame, which is exactly the judder this tries to remove.
 * Waiting for "all four new" is not enough: with phases spread over most of a 25 fps
 * period plus vsync quantization, the round outlasts the camera period.
 *
 * <p>Per camera, the receipt interval is learned from the timestamps themselves; the
 * display interval from the calls. Not thread-safe: GL thread only.
 */
final class BirdseyeFramePacing {
    /** Without new images, still redraw this often for overlays and frame health. */
    static final long IDLE_REDRAW_US = 100_000;
    /** Receipt timestamps are taken after select() wakes: allow for that jitter. */
    static final long MARGIN_US = 3_000;
    static final long DEFAULT_PERIOD_US = 33_333, DEFAULT_VSYNC_US = 16_667;

    private final long[] seenUs = new long[4], periodUs = new long[4];
    private long lastCallUs, vsyncUs = DEFAULT_VSYNC_US;

    BirdseyeFramePacing() { reset(); }

    void reset() {
        java.util.Arrays.fill(seenUs, 0);
        java.util.Arrays.fill(periodUs, DEFAULT_PERIOD_US);
        lastCallUs = 0; vsyncUs = DEFAULT_VSYNC_US;
    }

    /**
     * @param latestUs newest receipt per camera (0 = none)
     * @param shownUs receipt of the image currently uploaded per camera
     */
    boolean shouldDraw(long[] latestUs, long[] shownUs, long nowUs, long lastDrawUs) {
        learn(latestUs, nowUs);
        boolean pending = false;
        for (int i = 0; i < 4; i++) {
            long t = latestUs[i];
            if (t <= 0 || t <= shownUs[i]) continue;
            pending = true;
            // Would this camera's next image arrive before the next display frame?
            if (nowUs - t + vsyncUs + MARGIN_US >= periodUs[i]) return true;
        }
        if (!pending) return nowUs - lastDrawUs >= IDLE_REDRAW_US;
        // Only fresh images pending, and none at risk yet: if every streaming camera is
        // already in, waiting gains nothing.
        for (int i = 0; i < 4; i++) {
            long t = latestUs[i];
            boolean streaming = t > 0 && nowUs - t <= 4 * periodUs[i];
            if (streaming && t <= shownUs[i]) return false;
        }
        return true;
    }

    private void learn(long[] latestUs, long nowUs) {
        if (lastCallUs > 0) {
            long interval = nowUs - lastCallUs;
            if (interval >= 5_000 && interval <= 40_000) vsyncUs += (interval - vsyncUs) / 8;
        }
        lastCallUs = nowUs;
        for (int i = 0; i < 4; i++) {
            long t = latestUs[i], previous = seenUs[i];
            if (t <= previous) { if (t < previous) seenUs[i] = t; continue; }
            seenUs[i] = t;
            long interval = t - previous;
            if (previous > 0 && interval >= 10_000 && interval <= 100_000)
                periodUs[i] += (interval - periodUs[i]) / 8;
        }
    }
}
