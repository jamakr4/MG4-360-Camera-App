package com.drivehub.kamera.parking;

/**
 * Read-only rear PDC values, in cm; sector order physical rear left to right.
 */
public final class PdcSnapshot {
    public static final long MAX_AGE_MS = 500;
    public static final PdcSnapshot EMPTY = new PdcSnapshot(new int[] { -1, -1, -1, -1 }, -1, -1, 0);
    private final int[] levels, rawLevels;
    private final int rawClosestCm;
    public final int closestCm, status;
    public final long sampledMs;

    public PdcSnapshot(int[] levels, int closestCm, int status, long sampledMs) {
        if (levels.length != 4)
            throw new IllegalArgumentException("Four rear sectors required");
        this.rawLevels = levels.clone();
        this.levels = levels.clone();
        this.rawClosestCm = closestCm;
        for (int i = 0; i < 4; i++)
            if (this.levels[i] < 0 || this.levels[i] > 9)
                this.levels[i] = -1;
        this.closestCm = closestCm > 0 && closestCm < 255 ? closestCm : -1;
        this.status = status;
        this.sampledMs = sampledMs;
    }

    public int level(int sector) {
        return levels[sector];
    }

    public boolean fresh(long nowMs) {
        return sampledMs > 0 && nowMs >= sampledMs && nowMs - sampledMs <= MAX_AGE_MS;
    }

    public boolean hasSectors() {
        for (int v : levels)
            if (v >= 0)
                return true;
        return false;
    }

    public boolean hasObstacle() {
        for (int v : levels)
            if (v > 0)
                return true;
        return false;
    }

    public int[] spatialLevels() {
        for (int v : levels)
            if (v < 0)
                return new int[4];
        return levels.clone();
    }

    public String diagnostic(long nowMs) {
        return "raw=" + java.util.Arrays.toString(rawLevels) + " closest=" + rawClosestCm + "cm status=" + status
                + " valid=" + java.util.Arrays.toString(levels) + " cmAvailable=" + (closestCm > 0) + " available="
                + hasSectors() + " age="
                + (sampledMs == 0 ? "unavailable" : Math.max(0, nowMs - sampledMs) + "ms") + " fresh=" + fresh(nowMs);
    }
}
