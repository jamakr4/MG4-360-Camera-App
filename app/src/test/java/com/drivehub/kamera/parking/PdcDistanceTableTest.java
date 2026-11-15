package com.drivehub.kamera.parking;

import com.drivehub.kamera.rivela.PdcBars;
import org.junit.Test;
import static org.junit.Assert.*;

public class PdcDistanceTableTest {
    @Test
    public void levelsMatchTheInspectedRivelaBeta8DisplayTable() {
        int[] expected = { 15, 35, 50, 65, 80, 95, 110, 130, 150 };
        for (int level = 1; level <= 9; level++)
            assertEquals(expected[level - 1], PdcDistanceTable.cmForLevel(level));
        for (int invalid : new int[] { Integer.MIN_VALUE, -1, 0, 10, 255, Integer.MAX_VALUE })
            assertEquals(-1, PdcDistanceTable.cmForLevel(invalid));
    }

    @Test
    public void onlyNearestLevelSectorsUseTheReportedMinimumAndOthersStayAtFixedDistances() {
        double[] mm = new double[4];
        int[] bands = new int[4];
        for (int closest : new int[] { 1, 42, 80, 254 }) {
            PdcBars.targets(new int[] { 2, 4, 2, 9 }, closest, PdcDistanceTable::cmForLevel, mm, bands);
            assertArrayEquals(new double[] { closest * 10, 650, closest * 10, 1500 }, mm, 0);
            assertArrayEquals(new int[] { 1, 1, 1, 2 }, bands);
        }

        PdcBars.targets(new int[] { 1, 3, 6, 9 }, 80, PdcDistanceTable::cmForLevel, mm, bands);
        assertArrayEquals(new double[] { 800, 500, 950, 1500 }, mm, 0);
    }

    @Test
    public void missingOrInvalidMinimumFallsBackToTheTableForEveryActiveSector() {
        double[] mm = new double[4];
        int[] bands = new int[4];
        for (int invalid : new int[] { Integer.MIN_VALUE, -1, 0, 255, 999, Integer.MAX_VALUE }) {
            PdcBars.targets(new int[] { 1, 3, 6, 9 }, invalid, PdcDistanceTable::cmForLevel, mm, bands);
            assertArrayEquals(new double[] { 150, 500, 950, 1500 }, mm, 0);
            assertArrayEquals(new int[] { 0, 1, 1, 2 }, bands);
        }
    }

    @Test
    public void inactiveOrInvalidLevelsNeverProduceTargets() {
        double[] mm = new double[4];
        int[] bands = new int[4];
        PdcBars.targets(new int[] { 0, -1, 10, 255 }, 25, PdcDistanceTable::cmForLevel, mm, bands);
        assertArrayEquals(new double[] { -1, -1, -1, -1 }, mm, 0);
        assertArrayEquals(new int[] { -1, -1, -1, -1 }, bands);
    }
}
