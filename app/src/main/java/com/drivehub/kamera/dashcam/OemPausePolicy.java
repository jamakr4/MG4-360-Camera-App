package com.drivehub.kamera.dashcam;

final class OemPausePolicy {
    static boolean shouldResume(Boolean foreground, long now, long askedAt, long goneAt, long grace) {
        if (Boolean.TRUE.equals(foreground)) return false;
        if (Boolean.FALSE.equals(foreground) && now - goneAt >= grace) return true;
        return askedAt > 0 && now - askedAt >= 60_000;
    }
}
