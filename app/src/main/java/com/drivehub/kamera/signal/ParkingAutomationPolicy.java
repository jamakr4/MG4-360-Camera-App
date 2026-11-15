package com.drivehub.kamera.signal;

/** Main-thread parking UI ownership; independent of recording and camera ownership. */
public final class ParkingAutomationPolicy {
    private static final int PARK = 1, DRIVE = 4;
    private int previousGear;
    private String session;
    private boolean visible, autoClose, observedBelow, closePending;
    private int threshold = 25;

    /** Every sample is consumed, including samples while disabled or unavailable. */
    public boolean onGear(boolean available, boolean openOnDrive, int gear, boolean mainVisible) {
        int previous = previousGear;
        previousGear = gear;
        if (!available) cancel();
        return available && openOnDrive && previous == PARK && gear == DRIVE
                && !mainVisible && session == null;
    }

    public void configure(boolean enabled, int speedKmh) {
        if (autoClose != enabled || threshold != speedKmh) {
            autoClose = enabled;
            threshold = speedKmh;
            observedBelow = closePending = false;
        }
    }

    public void begin(String id) {
        if (id == null || id.isEmpty()) throw new IllegalArgumentException("Missing parking session");
        session = id;
        visible = observedBelow = closePending = false;
    }

    public String session() { return session; }
    public boolean matches(String id) { return session != null && session.equals(id); }
    public boolean shown(String id) {
        if (!matches(id)) return false;
        visible = true;
        return true;
    }
    public void recreating(String id) { if (matches(id)) visible = false; }
    public boolean shouldPollSpeed() { return session != null && visible && autoClose; }

    /** High-speed opening is allowed: first observe a valid sample below the threshold. */
    public boolean onSpeed(float kmh) {
        if (!shouldPollSpeed()) return false;
        if (closePending) return true;
        if (!Float.isFinite(kmh) || kmh < 0) return false;
        if (kmh < threshold) observedBelow = true;
        else if (observedBelow) closePending = true;
        return closePending;
    }

    public boolean canClose(String id) { return matches(id) && shouldPollSpeed() && closePending; }
    public void cancel() {
        session = null;
        visible = observedBelow = closePending = false;
    }
}
