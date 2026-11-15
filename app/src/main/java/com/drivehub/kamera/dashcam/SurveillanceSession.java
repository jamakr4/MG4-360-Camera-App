package com.drivehub.kamera.dashcam;

/** The original deadline and dashcam state survive process death. First terminal command wins. */
public final class SurveillanceSession {
    public final long deadline;
    public final int bootCount;
    public final boolean originalEnabled;
    public final String policy;
    public final String requestId;
    public volatile String directory;
    public volatile long device = -1;
    public volatile boolean prepared;
    public volatile boolean usingUsb;
    public volatile String stopRequestId = "";
    private volatile String stopReason;
    private volatile boolean failed;
    private volatile boolean deferRestart;

    public SurveillanceSession(long deadline, int bootCount, boolean originalEnabled,
                               String policy, String directory, String requestId) {
        this.deadline = deadline;
        this.bootCount = bootCount;
        this.originalEnabled = originalEnabled;
        this.policy = policy;
        this.directory = directory;
        this.requestId = requestId;
    }

    public boolean canResume(int currentBoot, long now) {
        return bootCount >= 0 && bootCount == currentBoot && now < deadline && stopReason == null;
    }

    public synchronized void stop(String reason, boolean error, boolean defer) {
        deferRestart |= defer;
        if (stopReason != null) return;
        stopReason = reason;
        failed = error;
    }

    public String stopReason() { return stopReason; }
    public boolean failed() { return failed; }
    public boolean deferRestart() { return deferRestart; }
}
