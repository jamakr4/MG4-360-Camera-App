package com.drivehub.kamera.signal;

/** Serialized on OemTakeoverManager's worker. No Android dependencies; all side effects are ports. */
public final class OemTakeoverController {
    public static final long FRAME_TIMEOUT_MS = 2500, NON_REVERSE_CONFIRM_MS = 500;
    private static final long DATA_TIMEOUT_MS = 2000, ACQUIRE_TIMEOUT_MS = 5000;
    public enum State { OEM, PREPARING, EXCLUSIVE, FALLBACK, ERROR }

    public static final class Journal {
        public int originalReceiver = -1;
        public boolean ownsReceiver, pending, fallback, fault, oemLaunchAttempted;
        public String failure = "";
        public boolean retryCompletedLegacyFault(int protocol) {
            if (protocol >= 2 || !fault || pending || ownsReceiver || fallback) return false;
            fault = false; failure = "";
            return true;
        }
        public Journal copy() {
            Journal j = new Journal();
            j.originalReceiver = originalReceiver; j.ownsReceiver = ownsReceiver;
            j.pending = pending; j.fallback = fallback; j.fault = fault; j.oemLaunchAttempted = oemLaunchAttempted; j.failure = failure;
            return j;
        }
    }

    public interface Ports {
        Journal load();
        void save(Journal journal); // Must durably complete or throw before changing components.
        int receiverState(); // Android enabled-state values: 0 default, 1 enabled, 2 disabled.
        default boolean receiverAvailable() { return true; }
        void receiverState(int state);
        void startDataService();
        boolean dataServiceRunning();
        void yieldCameras(boolean yield);
        void releaseCameras(); // Synchronous: all consumers, recording and leases have released.
        void acquireCameras();
        int heldMask();
        long rearFrameAgeMs(); // -1 means no valid frame on the currently open capture.
        default boolean externallyPaused() { return false; }
        void closeOem();
        void openOem();
        void log(String message);
    }

    public static final class Snapshot {
        public final State state;
        public final String detail;
        public final boolean receiverBlocked;
        Snapshot(State state, String detail, boolean blocked) {
            this.state = state; this.detail = detail; receiverBlocked = blocked;
        }
    }

    private final Ports ports;
    private final Journal journal;
    private volatile Snapshot snapshot = new Snapshot(State.OEM, "idle", false);
    private boolean first = true, previousCoexist = true, dataRequested, acquiring, released;
    private long beganAt, reverseAt = -1, nonReverseAt = -1, lastCloseAt = -1;
    private long lastDataCheck = -1, dataMissingAt = -1;
    private String operation = "preparing";
    private int closes;
    private boolean restoreFailed, oemReceiverChecked;

    public OemTakeoverController(Ports ports) {
        this.ports = ports;
        journal = ports.load();
    }

    public Snapshot snapshot() { return snapshot; }

    /** Explicit user recovery, serialized with ticks; never called by the periodic retry path. */
    public void retryRestore() { restoreFailed = false; oemReceiverChecked = false; }

    /** Run a fresh, verified restore even when OEM coexistence was already enabled. */
    public boolean restoreOem(int gear, long now) {
        retryRestore();
        tick(true, gear, false, now);
        try {
            if (snapshot.state != State.OEM || journal.ownsReceiver || !ports.receiverAvailable()) return false;
            int state = ports.receiverState();
            return state == 0 || state == 1;
        } catch (RuntimeException | LinkageError error) {
            ports.log("restore verification failed: " + error.getClass().getSimpleName());
            return false;
        }
    }

    public void tick(boolean coexist, int gear, boolean oemBusy, long now) {
        try {
            update(coexist, gear, oemBusy, now);
        } catch (RuntimeException | LinkageError error) {
            ports.log("operation failed: " + error.getClass().getSimpleName() + ": " + error.getMessage());
            journal.fault = true;
            journal.failure = operation + "/" + error.getClass().getSimpleName();
            try { persist(); } catch (RuntimeException ignored) { /* Never take over without a journal. */ }
            try { handBack(State.ERROR, journal.failure, gear); }
            catch (RuntimeException | LinkageError restoreError) {
                ports.log("restoration failed: " + restoreError.getClass().getSimpleName());
                restoreFailed = true;
                previousCoexist = coexist;
                publish(State.ERROR, "restore_failed", false);
            }
        }
    }

    private void update(boolean coexist, int gear, boolean oemBusy, long now) {
        if (restoreFailed && coexist == previousCoexist) {
            if (gear != 2) ports.yieldCameras(false);
            return;
        }
        restoreFailed = false;
        if (first) {
            first = false;
            if (journal.pending) {
                journal.fault = true;
                journal.failure = "interrupted_takeover";
                persist();
                ports.log("interrupted takeover; restoring OEM without automatic retry");
            }
        }
        if (gear == 2) {
            if (reverseAt < 0) reverseAt = now;
            nonReverseAt = -1;
        } else if (gear == 1 || gear == 3 || gear == 4) {
            if (nonReverseAt < 0) nonReverseAt = now;
            if (now - nonReverseAt >= NON_REVERSE_CONFIRM_MS) {
                reverseAt = -1;
                if (journal.fallback || journal.oemLaunchAttempted) {
                    journal.fallback = false;
                    journal.oemLaunchAttempted = false;
                    persist();
                }
            }
        } else nonReverseAt = -1; // Unknown gear must never clear the reverse fallback.

        // An explicit selection of OEM acknowledges a fault. Returning to exclusive is a new attempt.
        if (coexist && journal.fault) { journal.fault = false; persist(); }
        previousCoexist = coexist;
        if (coexist) {
            reverseAt = -1; // The OEM may have been in R for minutes before takeover was requested.
            recoverReceiverAfterReinstall();
            handBack(State.OEM, "oem_allowed", gear);
            return;
        }
        oemReceiverChecked = false;

        if (ports.externallyPaused()) {
            // An intentional handoff is not a failed camera. Start fresh timeouts on resume.
            reverseAt = -1;
            beganAt = now;
            dataMissingAt = -1;
            return;
        }

        if (journal.fault || journal.fallback) {
            handBack(journal.fault ? State.ERROR : State.FALLBACK,
                    journal.fault ? journal.failure : "rear_frame_timeout", gear);
            return;
        }

        long age = ports.rearFrameAgeMs();
        if (gear == 2 && reverseAt >= 0 && now - reverseAt >= FRAME_TIMEOUT_MS
                && (age < 0 || age >= FRAME_TIMEOUT_MS)) {
            journal.fallback = true;
            persist(); // Latch before releasing/launching; the deadline also covers preparation.
            handBack(State.FALLBACK, "rear_frame_timeout", gear);
            return;
        }

        if (snapshot.state != State.PREPARING && snapshot.state != State.EXCLUSIVE) {
            released = false; dataRequested = false; acquiring = false;
            closes = 0; lastCloseAt = -1; beganAt = now;
            journal.pending = true; journal.failure = ""; dataMissingAt = -1;
            persist();
            // Suppressing an autostart is not a camera handoff. Keep existing previews and
            // recording alive while checking metadata, and reserve the same shared sessions.
            ports.yieldCameras(false);
            ports.acquireCameras(); acquiring = true;
            publish(State.PREPARING, "starting_data_service", false);
        }

        operation = "starting_data_service";
        if (!dataRequested) { ports.startDataService(); dataRequested = true; }
        operation = "checking_data_service";
        if (!ports.dataServiceRunning()) {
            if (dataMissingAt < 0) dataMissingAt = now;
            if (now - dataMissingAt >= DATA_TIMEOUT_MS) fail("data_service_unavailable", gear);
            return;
        }
        dataMissingAt = -1;
        if (!snapshot.receiverBlocked) {
            operation = "receiver_lookup";
            int original = ports.receiverState();
            if (!journal.ownsReceiver && original != 0 && original != 1) {
                fail("receiver_already_disabled", gear);
                return;
            }
            if (!journal.ownsReceiver) {
                journal.originalReceiver = original;
                journal.ownsReceiver = true;
                persist();
            }
            operation = "receiver_disable";
            if (original != 2) ports.receiverState(2);
            operation = "receiver_verify";
            if (ports.receiverState() != 2) throw new IllegalStateException("Receiver disable not verified");
            publish(State.PREPARING, "receiver_blocked", true);
        }

        // Close a pre-existing/manual OEM window a bounded number of times, never race Activities.
        if (oemBusy) {
            publish(State.PREPARING, "closing_existing_oem", true);
            if (closes < 3 && (lastCloseAt < 0 || now - lastCloseAt >= 500)) {
                ports.closeOem(); closes++; lastCloseAt = now;
            }
            if (lastCloseAt >= 0 && closes == 3 && now - lastCloseAt >= 500)
                fail("oem_did_not_close", gear);
            return;
        }
        if (!acquiring) {
            ports.yieldCameras(false);
            ports.acquireCameras();
            acquiring = true;
        }

        age = ports.rearFrameAgeMs();
        if (ports.heldMask() == 15 && age >= 0 && age <= 500) {
            if (journal.pending) { journal.pending = false; persist(); }
            publish(State.EXCLUSIVE, "ready", true);
        } else if (snapshot.state == State.PREPARING && now - beganAt >= ACQUIRE_TIMEOUT_MS) {
            fail("camera_acquisition_failed", gear);
        }
        if (snapshot.state == State.EXCLUSIVE && now - lastDataCheck >= 1000) {
            lastDataCheck = now;
            // receiverState is cheap and detects external component changes without rearming takeover.
            if (ports.receiverState() != 2) fail("receiver_changed", gear);
        }
    }

    private void fail(String reason, int gear) {
        journal.fault = true; journal.failure = reason;
        persist();
        handBack(State.ERROR, reason, gear);
    }

    private void handBack(State state, String reason, int gear) {
        boolean transition = snapshot.state != state || journal.ownsReceiver || journal.pending;
        if (state == State.ERROR && gear != 2) {
            // A control-plane error is not evidence that OEM owns the cameras. Do not
            // black out healthy previews in P/D/N (or unknown gear) indefinitely.
            ports.yieldCameras(false);
            restoreReceiver();
            if (journal.pending) { journal.pending = false; persist(); }
            if (!"interrupted_takeover".equals(journal.failure)) ports.acquireCameras();
            publish(State.ERROR, reason, false);
            return;
        }
        // OEM mode in P/D must still permit our ordinary dashcam and overlays. Fallback/error must not.
        if (transition || state != State.OEM) ports.yieldCameras(true);
        if (!released && (transition || state != State.OEM)) {
            ports.releaseCameras(); released = true; acquiring = false;
        }
        restoreReceiver();
        if (journal.pending) journal.pending = false;
        if (transition) persist();
        if (state == State.OEM) ports.yieldCameras(false);
        publish(state, reason, false);
        if ((state != State.OEM || transition) && gear == 2 && !journal.oemLaunchAttempted) {
            journal.oemLaunchAttempted = true;
            persist(); // One attempt per R phase, including a failed Activity launch.
            ports.openOem();
        }
    }

    private void restoreReceiver() {
        if (!journal.ownsReceiver) return;
        operation = "receiver_restore";
        int target = journal.originalReceiver;
        if (ports.receiverState() != target) ports.receiverState(target);
        if (ports.receiverState() != target) throw new IllegalStateException("Receiver restore not verified");
        journal.ownsReceiver = false;
        persist();
    }

    private void recoverReceiverAfterReinstall() {
        if (oemReceiverChecked) return;
        operation = "receiver_recovery_lookup";
        // Component settings belong to the OEM package and survive removal of this app.
        // Our private journal does not. An explicit/default OEM selection must therefore
        // repair a disabled receiver even when this installation has no ownership record.
        if (!journal.ownsReceiver && ports.receiverAvailable() && ports.receiverState() >= 2) {
            journal.originalReceiver = 1;
            journal.ownsReceiver = true;
            persist(); // A crash during repair must also leave a recoverable record.
            released = false; // Normal OEM-mode previews may have resumed since a previous repair.
            ports.log("recovering disabled OEM receiver without an installation journal");
        }
        // handBack releases our camera consumers before restoreReceiver enables autostart.
        // A denied/unverified restore clears this flag through explicit retryRestore().
        oemReceiverChecked = true;
    }

    private void persist() { ports.save(journal.copy()); }
    private void publish(State state, String detail, boolean blocked) {
        Snapshot previous = snapshot;
        if (previous.state == state && previous.detail.equals(detail) && previous.receiverBlocked == blocked) return;
        snapshot = new Snapshot(state, detail, blocked);
        ports.log("state=" + state + " reason=" + detail + " receiverBlocked=" + blocked);
    }
}
