package com.drivehub.kamera.dev;

import android.app.ActivityManager;
import android.content.Context;
import android.os.Build;
import android.os.CpuUsageInfo;
import android.os.Debug;
import android.os.HardwarePropertiesManager;
import android.os.PowerManager;
import android.os.Process;
import android.os.SystemClock;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.List;

/** Reads system and app performance counters. All calls must run off the main thread. */
final class SystemPerformanceSampler {

    private final ActivityManager activityManager;
    private final HardwarePropertiesManager hardwarePropertiesManager;
    private final PowerManager powerManager;
    private final int processorCount;

    private PerformanceMetrics.CpuTimes previousHardwareCpuTimes;
    private PerformanceMetrics.CpuTimes previousProcCpuTimes;
    private long previousAppCpuMs = -1L;
    private long previousElapsedMs = -1L;

    private double systemCpuPercent = Double.NaN;
    private double appCpuPercent = Double.NaN;
    private long usedRamBytes = -1L;
    private long totalRamBytes = -1L;
    private long appPssBytes = -1L;
    private double loadAverageOneMinute = Double.NaN;
    private double cpuTemperatureCelsius = Double.NaN;
    private int thermalStatus = -1;
    /** Null until discovered; empty when no readable zone exists (re-scanned periodically). */
    private List<ThermalZone> thermalZones;
    private int thermalZoneRescanCountdown;
    /** Android 9 rejects non-privileged callers with SecurityException; stop asking after that. */
    private boolean hardwareTemperaturesDenied;
    private boolean thermalSourceLogged;

    private static final String THERMAL_ROOT = "/sys/class/thermal";
    private static final int THERMAL_RESCAN_INTERVAL = 30;

    private static final class ThermalZone {
        final File tempFile;
        final String type;
        final int priority;

        ThermalZone(File tempFile, String type, int priority) {
            this.tempFile = tempFile;
            this.type = type;
            this.priority = priority;
        }
    }

    SystemPerformanceSampler(Context context) {
        activityManager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        hardwarePropertiesManager = (HardwarePropertiesManager)
                context.getSystemService(Context.HARDWARE_PROPERTIES_SERVICE);
        powerManager = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        processorCount = Math.max(1, Runtime.getRuntime().availableProcessors());
    }

    PerformanceMetrics.Snapshot sample(boolean includeSlowMetrics) {
        refreshCpu();
        refreshMemory();
        refreshLoadAverage();
        if (includeSlowMetrics) {
            refreshAppPss();
            refreshThermalData();
        }
        return new PerformanceMetrics.Snapshot(
                systemCpuPercent,
                appCpuPercent,
                usedRamBytes,
                totalRamBytes,
                appPssBytes,
                loadAverageOneMinute,
                cpuTemperatureCelsius,
                thermalStatus);
    }

    private void refreshCpu() {
        double systemValue = Double.NaN;
        PerformanceMetrics.CpuTimes currentHardwareCpuTimes = readHardwareCpuTimes();
        if (currentHardwareCpuTimes != null) {
            systemValue = PerformanceMetrics.calculateSystemCpuPercent(
                    previousHardwareCpuTimes, currentHardwareCpuTimes);
            previousHardwareCpuTimes = currentHardwareCpuTimes;
        }
        if (Double.isNaN(systemValue)) {
            PerformanceMetrics.CpuTimes currentProcCpuTimes = readProcCpuTimes();
            if (currentProcCpuTimes != null) {
                systemValue = PerformanceMetrics.calculateSystemCpuPercent(
                        previousProcCpuTimes, currentProcCpuTimes);
                previousProcCpuTimes = currentProcCpuTimes;
            }
        }
        if (!Double.isNaN(systemValue)) systemCpuPercent = systemValue;

        long currentAppCpuMs = Process.getElapsedCpuTime();
        long currentElapsedMs = SystemClock.elapsedRealtime();
        double value = PerformanceMetrics.calculateAppCpuPercent(
                previousAppCpuMs,
                currentAppCpuMs,
                previousElapsedMs,
                currentElapsedMs,
                processorCount);
        if (!Double.isNaN(value)) appCpuPercent = value;
        previousAppCpuMs = currentAppCpuMs;
        previousElapsedMs = currentElapsedMs;
    }

    private PerformanceMetrics.CpuTimes readHardwareCpuTimes() {
        if (hardwarePropertiesManager == null) return null;
        try {
            CpuUsageInfo[] usages = hardwarePropertiesManager.getCpuUsages();
            if (usages == null || usages.length == 0) return null;
            long active = 0L;
            long total = 0L;
            for (CpuUsageInfo usage : usages) {
                if (usage == null || usage.getActive() < 0L || usage.getTotal() <= 0L
                        || usage.getActive() > usage.getTotal()) {
                    continue;
                }
                active = Math.addExact(active, usage.getActive());
                total = Math.addExact(total, usage.getTotal());
            }
            return total > 0L ? new PerformanceMetrics.CpuTimes(total, total - active) : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private PerformanceMetrics.CpuTimes readProcCpuTimes() {
        try (BufferedReader reader = new BufferedReader(new FileReader("/proc/stat"))) {
            return PerformanceMetrics.parseProcStatCpuLine(reader.readLine());
        } catch (Throwable ignored) {
            return null;
        }
    }

    private void refreshMemory() {
        if (activityManager == null) return;
        try {
            ActivityManager.MemoryInfo memoryInfo = new ActivityManager.MemoryInfo();
            activityManager.getMemoryInfo(memoryInfo);
            if (memoryInfo.totalMem > 0L && memoryInfo.availMem >= 0L
                    && memoryInfo.availMem <= memoryInfo.totalMem) {
                totalRamBytes = memoryInfo.totalMem;
                usedRamBytes = memoryInfo.totalMem - memoryInfo.availMem;
            }
        } catch (Throwable ignored) {
        }
    }

    private void refreshAppPss() {
        try {
            long pssKb = Debug.getPss();
            if (pssKb >= 0L && pssKb <= Long.MAX_VALUE / 1024L) {
                appPssBytes = pssKb * 1024L;
            }
        } catch (Throwable ignored) {
        }
    }

    private void refreshLoadAverage() {
        try (BufferedReader reader = new BufferedReader(new FileReader("/proc/loadavg"))) {
            String line = reader.readLine();
            if (line == null) return;
            String[] fields = line.trim().split("\\s+");
            if (fields.length == 0) return;
            double value = Double.parseDouble(fields[0]);
            if (PerformanceMetrics.isFiniteInRange(value, 0d, 10_000d)) {
                loadAverageOneMinute = value;
            }
        } catch (Throwable ignored) {
        }
    }

    private void refreshThermalData() {
        double temperature = readHardwareCpuTemperature();
        if (!Double.isNaN(temperature)) {
            logThermalSourceOnce("HardwarePropertiesManager (thermal HAL)");
        } else {
            temperature = readSysfsCpuTemperature();
        }
        if (!Double.isNaN(temperature)) {
            cpuTemperatureCelsius = temperature;
            return;
        }
        cpuTemperatureCelsius = Double.NaN;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && powerManager != null) {
            try {
                int status = powerManager.getCurrentThermalStatus();
                if (status >= PowerManager.THERMAL_STATUS_NONE
                        && status <= PowerManager.THERMAL_STATUS_SHUTDOWN) {
                    thermalStatus = status;
                }
            } catch (Throwable ignored) {
            }
        }
    }

    private double readHardwareCpuTemperature() {
        if (hardwarePropertiesManager == null || hardwareTemperaturesDenied) return Double.NaN;
        try {
            float[] temperatures = hardwarePropertiesManager.getDeviceTemperatures(
                    HardwarePropertiesManager.DEVICE_TEMPERATURE_CPU,
                    HardwarePropertiesManager.TEMPERATURE_CURRENT);
            double maximum = Double.NaN;
            if (temperatures != null) {
                for (float temperature : temperatures) {
                    if (PerformanceMetrics.isFiniteInRange(temperature, -50d, 250d)
                            && (Double.isNaN(maximum) || temperature > maximum)) {
                        maximum = temperature;
                    }
                }
            }
            return maximum;
        } catch (SecurityException denied) {
            hardwareTemperaturesDenied = true;
            DevRuntimeLog.add("Performance", "Thermal HAL denied: " + denied.getMessage());
            return Double.NaN;
        } catch (Throwable ignored) {
            return Double.NaN;
        }
    }

    /**
     * Reads the Linux thermal framework (MT2712: mtktscpu / cpu_thermal zones). Uses the
     * highest-priority zone class that yields a plausible value and reports its maximum.
     */
    private double readSysfsCpuTemperature() {
        if (thermalZones == null || (thermalZones.isEmpty() && --thermalZoneRescanCountdown <= 0)) {
            thermalZones = discoverThermalZones();
            thermalZoneRescanCountdown = THERMAL_RESCAN_INTERVAL;
            if (!thermalSourceLogged) logThermalZones();
        }
        int bestPriority = -1;
        double best = Double.NaN;
        for (ThermalZone zone : thermalZones) {
            if (zone.priority < bestPriority) continue;
            double celsius = PerformanceMetrics.parseThermalZoneCelsius(readFirstLine(zone.tempFile));
            if (Double.isNaN(celsius)) continue;
            if (zone.priority > bestPriority || celsius > best) {
                bestPriority = zone.priority;
                best = celsius;
            }
        }
        return best;
    }

    private void logThermalZones() {
        if (thermalZones.isEmpty()) {
            DevRuntimeLog.add("Performance", "No readable CPU thermal zone in " + THERMAL_ROOT);
            return;
        }
        StringBuilder text = new StringBuilder("sysfs thermal zones:");
        for (ThermalZone zone : thermalZones) {
            text.append(' ').append(zone.tempFile.getParentFile().getName())
                    .append('=').append(zone.type).append("(p").append(zone.priority).append(')');
        }
        logThermalSourceOnce(text.toString());
    }

    private void logThermalSourceOnce(String source) {
        if (thermalSourceLogged) return;
        thermalSourceLogged = true;
        DevRuntimeLog.add("Performance", "CPU temperature source: " + source);
    }

    private static List<ThermalZone> discoverThermalZones() {
        List<ThermalZone> zones = new ArrayList<>();
        try {
            File[] entries = new File(THERMAL_ROOT).listFiles();
            if (entries == null) return zones;
            for (File entry : entries) {
                if (!entry.getName().startsWith("thermal_zone")) continue;
                File tempFile = new File(entry, "temp");
                String type = readFirstLine(new File(entry, "type"));
                int priority = PerformanceMetrics.thermalZonePriority(type);
                if (priority <= 0) continue;
                if (Double.isNaN(PerformanceMetrics.parseThermalZoneCelsius(readFirstLine(tempFile)))) {
                    continue;
                }
                zones.add(new ThermalZone(tempFile, type == null ? "?" : type.trim(), priority));
            }
        } catch (Throwable ignored) {
        }
        return zones;
    }

    private static String readFirstLine(File file) {
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            return reader.readLine();
        } catch (Throwable ignored) {
            return null;
        }
    }
}
