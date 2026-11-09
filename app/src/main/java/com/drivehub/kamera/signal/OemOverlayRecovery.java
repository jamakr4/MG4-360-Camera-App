package com.drivehub.kamera.signal;

/** A stop broadcast starts recovery; confirmed OEM absence allows the display to return. */
final class OemOverlayRecovery {
    static final long SETTLE_MS = 250;
    private boolean pending;
    private long absentSince = -1;

    void start() {
        pending = true;
        absentSince = -1;
    }

    void cancel() {
        pending = false;
        absentSince = -1;
    }

    boolean isPending() { return pending; }

    boolean canRestore(Boolean foreground, boolean latched, boolean lifecycleDestroyed, long now) {
        return canRestore(foreground, latched, lifecycleDestroyed, false, now);
    }

    boolean canRestore(Boolean foreground, boolean latched, boolean lifecycleDestroyed, boolean reverse, long now) {
        if (!pending) return false;
        // Unknown foreground needs the independent OEM DestroyEnd signal.
        boolean absent = Boolean.FALSE.equals(foreground) || (foreground == null && lifecycleDestroyed);
        if (reverse || latched || Boolean.TRUE.equals(foreground) || !absent) {
            absentSince = -1;
            return false;
        }
        if (absentSince < 0) absentSince = now;
        return now - absentSince >= SETTLE_MS;
    }
}
