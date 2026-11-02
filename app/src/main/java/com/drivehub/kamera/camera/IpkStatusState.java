package com.drivehub.kamera.camera;

/** Retains the last real session independently of polling, windows and service restarts. */
public final class IpkStatusState {
    private String lastSession;
    private String current;

    public IpkStatusState(String restoredSession) {
        lastSession = restoredSession;
        idle();
    }

    public void update(String message) {
        lastSession = message;
        current = message;
    }

    public void idle() {
        current = lastSession == null ? "bereit · noch kein Kameratest"
                : "aus · zuletzt: " + lastSession;
    }

    public String snapshot() { return "IPK2 · " + current; }
    public String lastSession() { return lastSession; }
}
