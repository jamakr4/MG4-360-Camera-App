package com.drivehub.kamera.dashcam;

final class OemPausePolicy {
    static boolean shouldResume(Boolean foreground, boolean awaitingStop,
                                long now, long askedAt, long goneAt, long grace) {
        if (Boolean.TRUE.equals(foreground)) return false;
        // ACTION_START confirms camera ownership even when a third-party 360 app is on top.
        if (!awaitingStop && Boolean.FALSE.equals(foreground) && now - goneAt >= grace) return true;
        return askedAt > 0 && now - askedAt >= 60_000;
    }
}
