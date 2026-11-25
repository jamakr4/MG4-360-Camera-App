package com.drivehub.kamera.dev;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class PerformanceMetricsTest {

    @Test
    public void parseProcStatCpuLine_sumsExpectedFieldsAndTreatsIoWaitAsIdle() {
        PerformanceMetrics.CpuTimes times = PerformanceMetrics.parseProcStatCpuLine(
                "cpu  100 10 30 800 20 5 7 3 9 4");

        assertNotNull(times);
        assertEquals(975L, times.total);
        assertEquals(820L, times.idle);
    }

    @Test
    public void parseProcStatCpuLine_rejectsPerCoreAndInvalidValues() {
        assertNull(PerformanceMetrics.parseProcStatCpuLine("cpu0 10 0 5 100"));
        assertNull(PerformanceMetrics.parseProcStatCpuLine("cpu 10 nope 5 100"));
        assertNull(PerformanceMetrics.parseProcStatCpuLine("cpu 10 0 5 -100"));
    }

    @Test
    public void calculateSystemCpuPercent_usesCounterDeltas() {
        PerformanceMetrics.CpuTimes before = new PerformanceMetrics.CpuTimes(1_000L, 700L);
        PerformanceMetrics.CpuTimes after = new PerformanceMetrics.CpuTimes(1_400L, 900L);

        assertEquals(50d, PerformanceMetrics.calculateSystemCpuPercent(before, after), 0.001d);
    }

    @Test
    public void calculateSystemCpuPercent_rejectsResetCountersAndZeroDelta() {
        PerformanceMetrics.CpuTimes before = new PerformanceMetrics.CpuTimes(1_000L, 700L);

        assertTrue(Double.isNaN(PerformanceMetrics.calculateSystemCpuPercent(
                before, new PerformanceMetrics.CpuTimes(1_000L, 700L))));
        assertTrue(Double.isNaN(PerformanceMetrics.calculateSystemCpuPercent(
                before, new PerformanceMetrics.CpuTimes(900L, 600L))));
    }

    @Test
    public void calculateAppCpuPercent_normalizesAcrossAllProcessors() {
        // 1,000 ms CPU during one second occupies one of four cores: 25% device capacity.
        assertEquals(25d, PerformanceMetrics.calculateAppCpuPercent(
                2_000L, 3_000L, 10_000L, 11_000L, 4), 0.001d);
    }

    @Test
    public void calculateAppCpuPercent_clampsImpossibleSample() {
        assertEquals(100d, PerformanceMetrics.calculateAppCpuPercent(
                0L, 5_000L, 0L, 1_000L, 2), 0.001d);
    }

    @Test
    public void calculateRamPercent_validatesBounds() {
        assertEquals(75d, PerformanceMetrics.calculateRamPercent(3_000L, 4_000L), 0.001d);
        assertTrue(Double.isNaN(PerformanceMetrics.calculateRamPercent(5_000L, 4_000L)));
        assertTrue(Double.isNaN(PerformanceMetrics.calculateRamPercent(-1L, 4_000L)));
    }

    @Test
    public void snapshotFormatting_outputsThreeStableCompactLines() {
        long gib = 1024L * 1024L * 1024L;
        long mib = 1024L * 1024L;
        PerformanceMetrics.Snapshot snapshot = new PerformanceMetrics.Snapshot(
                42.4d, 5.6d, 34L * gib / 10L, 58L * gib / 10L,
                182L * mib, 1.27d, 54d, -1);

        assertEquals(
                "CPU 42 % · App 6 %\n"
                        + "RAM 3.4/5.8 GB 59 % · App 182 MB\n"
                        + "Load 1m 1.27 · CPU 54 °C",
                snapshot.format("CPU 54 °C"));
    }

    @Test
    public void formatting_usesPlaceholdersForUnavailableValues() {
        PerformanceMetrics.Snapshot snapshot = new PerformanceMetrics.Snapshot(
                Double.NaN, Double.NaN, -1L, -1L, -1L,
                Double.NaN, Double.NaN, -1);

        assertEquals(
                "CPU – · App –\nRAM – · App –\nLoad 1m – · Temp –",
                snapshot.format(null));
    }

    @Test
    public void parseThermalZoneCelsius_handlesMilliAndWholeDegrees() {
        assertEquals(48.5d, PerformanceMetrics.parseThermalZoneCelsius("48500\n"), 0.001d);
        assertEquals(52d, PerformanceMetrics.parseThermalZoneCelsius(" 52 "), 0.001d);
        assertEquals(-5d, PerformanceMetrics.parseThermalZoneCelsius("-5000"), 0.001d);
    }

    @Test
    public void parseThermalZoneCelsius_rejectsPlaceholdersAndGarbage() {
        assertTrue(Double.isNaN(PerformanceMetrics.parseThermalZoneCelsius(null)));
        assertTrue(Double.isNaN(PerformanceMetrics.parseThermalZoneCelsius("")));
        assertTrue(Double.isNaN(PerformanceMetrics.parseThermalZoneCelsius("0")));
        assertTrue(Double.isNaN(PerformanceMetrics.parseThermalZoneCelsius("-127000")));
        assertTrue(Double.isNaN(PerformanceMetrics.parseThermalZoneCelsius("127000")));
        assertTrue(Double.isNaN(PerformanceMetrics.parseThermalZoneCelsius("n/a")));
    }

    @Test
    public void thermalZonePriority_prefersCpuThenSocAndSkipsUnrelatedSensors() {
        assertEquals(3, PerformanceMetrics.thermalZonePriority("mtktscpu"));
        assertEquals(3, PerformanceMetrics.thermalZonePriority("cpu_thermal"));
        assertEquals(2, PerformanceMetrics.thermalZonePriority("mtktsAP"));
        assertEquals(2, PerformanceMetrics.thermalZonePriority("soc_thermal"));
        assertEquals(1, PerformanceMetrics.thermalZonePriority("thermal-zone7"));
        assertEquals(0, PerformanceMetrics.thermalZonePriority("mtktsbattery"));
        assertEquals(0, PerformanceMetrics.thermalZonePriority("mtktspmic"));
        assertEquals(0, PerformanceMetrics.thermalZonePriority("mtktswmt"));
    }
}
