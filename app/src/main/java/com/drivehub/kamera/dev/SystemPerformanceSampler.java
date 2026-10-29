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
import java.io.FileReader;

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
        boolean foundTemperature = false;
        if (hardwarePropertiesManager != null) {
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
                if (!Double.isNaN(maximum)) {
                    cpuTemperatureCelsius = maximum;
                    foundTemperature = true;
                }
            } catch (Throwable ignored) {
            }
        }
        if (!foundTemperature && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && powerManager != null) {
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
}
