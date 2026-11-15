package com.drivehub.kamera.parking;

/**
 * These represent levels, not independent centimetre measurements per sector
 * or verified sensor detection thresholds. PdcBars substitutes a valid minimum
 * only for sectors at the nearest active level; other sectors use this table
 * directly.
 * THESE LEVELS ARE NOT VERIEFIED AND JUST REPRESENT AN ASSUMPTION BASED ON THE
 * AUTHORS ANALYSIS!!
 * They are NOT the same as a factory calibration and should be treated as
 * such!!
 */
public final class PdcDistanceTable {
    private static final int[] LEVEL_CM = { -1, 15, 35, 50, 65, 80, 95, 110, 130, 150 };

    private PdcDistanceTable() {
    }

    public static int cmForLevel(int level) {
        return level >= 1 && level <= 9 ? LEVEL_CM[level] : -1;
    }

    static int nearestLevel(int[] levels) {
        int nearest = 10;
        for (int level : levels)
            if (level >= 1 && level <= 9)
                nearest = Math.min(nearest, level);
        return nearest == 10 ? -1 : nearest;
    }

}
