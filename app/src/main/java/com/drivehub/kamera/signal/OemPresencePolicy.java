package com.drivehub.kamera.signal;

/** A sticky Start/PauseStart property alone is not proof of a live OEM window after a reboot. */
public final class OemPresencePolicy {
    public static final long ABSENT_CONFIRM_MS = 1000;
    private long absentSince = -1;
    private String lifecycle = "";

    public boolean busy(Boolean foreground, String nextLifecycle, long now) {
        boolean propertyBusy = "Start".equals(nextLifecycle) || "PauseStart".equals(nextLifecycle);
        if (!nextLifecycle.equals(lifecycle)) { lifecycle = nextLifecycle; absentSince = -1; }
        if (Boolean.TRUE.equals(foreground)) { absentSince = -1; return true; }
        if (foreground == null) { absentSince = -1; return propertyBusy; }
        if (!propertyBusy) { absentSince = -1; return false; }
        if (absentSince < 0) absentSince = now;
        return now - absentSince < ABSENT_CONFIRM_MS;
    }
}
