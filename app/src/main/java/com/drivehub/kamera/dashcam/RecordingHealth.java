package com.drivehub.kamera.dashcam;

final class RecordingHealth {
    enum State { STARTING, RECORDING, ERROR }
    static State evaluate(long samples, long idleMs) {
        if (samples < 0 || idleMs > 5000) return State.ERROR;
        return samples > 0 ? State.RECORDING : State.STARTING;
    }
    static boolean segmentOverdue(long elapsedMs, long segmentMs, boolean oemPaused) {
        return !oemPaused && elapsedMs > 3 * segmentMs;
    }
}
