package com.drivehub.kamera.parking;

import com.drivehub.kamera.rivela.CarFrame;
import com.drivehub.kamera.rivela.PdcBars;
import java.util.Collections;
import java.util.List;

/** Shared Rivela animation. */
public class PdcGeometry {
    private final PdcBars.LevelToCm distances;
    private final boolean liveTable;

    public PdcGeometry() {
        distances = PdcDistanceTable::cmForLevel;
        liveTable = true;
    }

    public PdcGeometry(PdcBars.LevelToCm distances) {
        this.distances = java.util.Objects.requireNonNull(distances);
        liveTable = false;
    }

    private volatile PdcSnapshot snapshot = PdcSnapshot.EMPTY;
    private PdcSnapshot applied;
    private PdcBars.Anim animation = new PdcBars.Anim();
    private long last;

    public void set(PdcSnapshot snapshot) {
        this.snapshot = snapshot;
    }

    public PdcSnapshot snapshot() {
        return snapshot;
    }

    public boolean hasEstimatedPositions(long nowMs) {
        PdcSnapshot s = snapshot;
        if (!liveTable || !s.fresh(nowMs))
            return false;
        int[] levels = s.spatialLevels();
        int nearest = PdcDistanceTable.nearestLevel(levels);
        for (int level : levels)
            if (PdcDistanceTable.cmForLevel(level) > 0 && (s.closestCm < 0 || level > nearest))
                return true;
        return false;
    }

    public synchronized List<PdcBars.Bar> bars(CarFrame car, long nowMs) {
        PdcSnapshot s = snapshot;
        if (!s.fresh(nowMs)) {
            applied = null;
            animation = new PdcBars.Anim();
            last = 0;
            return Collections.emptyList();
        }
        if (s != applied) {
            animation.set(s.spatialLevels(), s.closestCm, distances);
            applied = s;
        }
        last = Math.max(last, nowMs);
        return animation.bars(car, last, 1f);
    }
}
