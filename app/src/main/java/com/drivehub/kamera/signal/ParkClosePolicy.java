package com.drivehub.kamera.signal;

/** Main-thread P-edge detection, independent of OEM ownership and automatic parking sessions. */
public final class ParkClosePolicy {
    private static final int PARK = 1;
    private int gear;
    private boolean enabled = true;
    private Request pending;

    public static final class Request {
        public final String viewId;
        public final String transition;

        private Request(String viewId) {
            this.viewId = viewId;
            this.transition = java.util.UUID.randomUUID().toString();
        }
    }

    public void configure(boolean enabled) {
        this.enabled = enabled;
        if (!enabled) pending = null;
    }

    /** Consume every sample, including while disabled or with no foreground camera view. */
    public Request onGear(int value, String foregroundViewId) {
        int previous = gear;
        if (value < 1 || value > 4) {
            gear = 0;
            pending = null;
            return null;
        }
        gear = value;
        if (gear != PARK) pending = null;
        if (enabled && previous >= 2 && previous <= 4 && gear == PARK
                && foregroundViewId != null) {
            pending = new Request(foregroundViewId);
            return pending;
        }
        return null;
    }

    public Request pendingFor(String viewId) {
        return enabled && gear == PARK && pending != null && pending.viewId.equals(viewId)
                ? pending : null;
    }

    public boolean consume(String viewId, String transition) {
        Request request = pendingFor(viewId);
        if (request == null || !request.transition.equals(transition)) return false;
        pending = null;
        return true;
    }

    public void viewStarted(String viewId) {
        if (pending != null && !pending.viewId.equals(viewId)) pending = null;
    }

    public void viewStopped(String viewId) {
        if (pending != null && pending.viewId.equals(viewId)) pending = null;
    }

    public void reset() {
        gear = 0;
        pending = null;
    }
}
