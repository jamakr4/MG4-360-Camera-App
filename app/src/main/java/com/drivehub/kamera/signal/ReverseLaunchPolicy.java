package com.drivehub.kamera.signal;

/** Bounded R screen routing, independent of the service's persistent camera ownership. */
public final class ReverseLaunchPolicy {
    public static final long SETTLE_MS = 300, CLOSE_INTERVAL_MS = 500;
    public static final int MAX_CLOSE_REQUESTS = 16, MAX_SHOW_REQUESTS = 3;
    private boolean active, showPending, reclaimPending, oemSeen, dismissed, launchOutstanding;
    private int closeRequests, showRequests;
    private long absentSince = -1, lastCloseAt;

    public static final class Decision {
        public final boolean showCamera, closeOem;
        Decision(boolean showCamera, boolean closeOem) { this.showCamera = showCamera; this.closeOem = closeOem; }
    }
    private static final Decision NONE = new Decision(false, false);

    /** PauseStart still owns/tears down native cameras; only DestroyEnd finishes that transition. */
    public static boolean oemBusy(boolean startHint, Boolean foreground, String lifecycle) {
        return startHint || Boolean.TRUE.equals(foreground)
                || "Start".equals(lifecycle) || "PauseStart".equals(lifecycle);
    }
    public void dismiss() {
        if (active) { dismissed = true; showPending = false; reclaimPending = false; }
    }
    public Decision update(boolean coexist, int gear, boolean mainVisible, boolean oemPresent, long now) {
        return update(coexist,gear,mainVisible,oemPresent,false,now);
    }
    public Decision update(boolean coexist,int gear,boolean mainVisible,boolean oemPresent,boolean camerasHeld,long now){
        if (coexist || gear != 2) {
            active = showPending = reclaimPending = oemSeen = dismissed = launchOutstanding = false;
            closeRequests = showRequests = 0; absentSince = -1;
            return NONE;
        }
        if (!active) { active = true; showPending = !mainVisible; }
        if (mainVisible) { showPending = false; launchOutstanding = false; }
        if (oemPresent) {
            if (!oemSeen) reclaimPending = !dismissed;
            oemSeen = true;
        } else {
            oemSeen = false;
            if (mainVisible) reclaimPending = false;
        }
        // Once all cameras are already ours, a late OEM window/lifecycle hint must not
        // deadlock launching our screen. Ownership itself does not depend on this policy.
        boolean canShow=!oemPresent||camerasHeld;
        if(!canShow)absentSince=-1;else if(absentSince<0)absentSince=now;
        // Brief false/true lifecycle samples must not rearm this budget or repeatedly start Activities.
        boolean close = oemPresent && closeRequests < MAX_CLOSE_REQUESTS
                && (closeRequests == 0 || now - lastCloseAt >= CLOSE_INTERVAL_MS);
        if (close) { closeRequests++; lastCloseAt = now; }
        boolean show = canShow && absentSince >= 0 && now - absentSince >= SETTLE_MS
                && !dismissed && !mainVisible && !launchOutstanding && (showPending || reclaimPending)
                && showRequests < MAX_SHOW_REQUESTS;
        if (show) {
            showPending = reclaimPending = false; launchOutstanding = true;
            showRequests++;
        }
        return new Decision(show, close);
    }
}
