package com.drivehub.kamera.dev;

import java.util.Locale;

/** Pure calculations and formatting used by the head-unit performance overlay. */
final class PerformanceMetrics {

    private static final double BYTES_PER_MIB = 1024d * 1024d;
    private static final double BYTES_PER_GIB = 1024d * 1024d * 1024d;

    private PerformanceMetrics() {
    }

    static final class CpuTimes {
        final long total;
        final long idle;

        CpuTimes(long total, long idle) {
            this.total = total;
            this.idle = idle;
        }
    }

    static final class Snapshot {
        final double systemCpuPercent;
        final double appCpuPercent;
        final long usedRamBytes;
        final long totalRamBytes;
        final long appPssBytes;
        final double loadAverageOneMinute;
        final double cpuTemperatureCelsius;
        final int thermalStatus;

        Snapshot(
                double systemCpuPercent,
                double appCpuPercent,
                long usedRamBytes,
                long totalRamBytes,
                long appPssBytes,
                double loadAverageOneMinute,
                double cpuTemperatureCelsius,
                int thermalStatus
        ) {
            this.systemCpuPercent = systemCpuPercent;
            this.appCpuPercent = appCpuPercent;
            this.usedRamBytes = usedRamBytes;
            this.totalRamBytes = totalRamBytes;
            this.appPssBytes = appPssBytes;
            this.loadAverageOneMinute = loadAverageOneMinute;
            this.cpuTemperatureCelsius = cpuTemperatureCelsius;
            this.thermalStatus = thermalStatus;
        }

        String format(String thermalText) {
            return formatCpuLine(systemCpuPercent, appCpuPercent)
                    + "\n" + formatRamLine(usedRamBytes, totalRamBytes, appPssBytes)
                    + "\n" + formatLoadLine(loadAverageOneMinute, thermalText);
        }
    }

    static CpuTimes parseProcStatCpuLine(String line) {
        if (line == null) return null;
        String trimmed = line.trim();
        if (!trimmed.startsWith("cpu ") && !trimmed.startsWith("cpu\t")) return null;
        String[] fields = trimmed.split("\\s+");
        if (fields.length < 5 || !"cpu".equals(fields[0])) return null;
        try {
            // Linux reports guest/guest_nice inside user/nice already, so only sum through steal.
            long total = 0L;
            int lastIncludedField = Math.min(fields.length - 1, 8);
            for (int i = 1; i <= lastIncludedField; i++) {
                long value = Long.parseLong(fields[i]);
                if (value < 0L) return null;
                total = Math.addExact(total, value);
            }
            long idle = Long.parseLong(fields[4]);
            if (idle < 0L) return null;
            if (fields.length > 5) {
                long ioWait = Long.parseLong(fields[5]);
                if (ioWait < 0L) return null;
                idle = Math.addExact(idle, ioWait);
            }
            return total > 0L && idle <= total ? new CpuTimes(total, idle) : null;
        } catch (ArithmeticException | NumberFormatException ignored) {
            return null;
        }
    }

    static double calculateSystemCpuPercent(CpuTimes previous, CpuTimes current) {
        if (previous == null || current == null) return Double.NaN;
        long totalDelta = current.total - previous.total;
        long idleDelta = current.idle - previous.idle;
        if (totalDelta <= 0L || idleDelta < 0L) return Double.NaN;
        return clampPercent(((totalDelta - idleDelta) * 100d) / totalDelta);
    }

    static double calculateAppCpuPercent(
            long previousCpuMs,
            long currentCpuMs,
            long previousElapsedMs,
            long currentElapsedMs,
            int processorCount
    ) {
        long cpuDelta = currentCpuMs - previousCpuMs;
        long elapsedDelta = currentElapsedMs - previousElapsedMs;
        if (previousCpuMs < 0L || previousElapsedMs < 0L || cpuDelta < 0L
                || elapsedDelta <= 0L || processorCount <= 0) {
            return Double.NaN;
        }
        double deviceCapacityMs = elapsedDelta * (double) processorCount;
        return clampPercent((cpuDelta * 100d) / deviceCapacityMs);
    }

    static double calculateRamPercent(long usedBytes, long totalBytes) {
        if (usedBytes < 0L || totalBytes <= 0L || usedBytes > totalBytes) return Double.NaN;
        return clampPercent((usedBytes * 100d) / totalBytes);
    }

    static String formatCpuLine(double systemPercent, double appPercent) {
        return "CPU " + formatPercent(systemPercent) + " · App " + formatPercent(appPercent);
    }

    static String formatRamLine(long usedBytes, long totalBytes, long appPssBytes) {
        String systemRam = "–";
        double ramPercent = calculateRamPercent(usedBytes, totalBytes);
        if (!Double.isNaN(ramPercent)) {
            systemRam = String.format(Locale.US, "%.1f/%.1f GB %s",
                    usedBytes / BYTES_PER_GIB,
                    totalBytes / BYTES_PER_GIB,
                    formatPercent(ramPercent));
        }
        String appRam = appPssBytes >= 0L
                ? String.format(Locale.US, "%.0f MB", appPssBytes / BYTES_PER_MIB)
                : "–";
        return "RAM " + systemRam + " · App " + appRam;
    }

    static String formatLoadLine(double loadAverageOneMinute, String thermalText) {
        String load = isFiniteInRange(loadAverageOneMinute, 0d, 10_000d)
                ? String.format(Locale.US, "%.2f", loadAverageOneMinute)
                : "–";
        String safeThermalText = thermalText == null || thermalText.trim().isEmpty()
                ? "Temp –"
                : thermalText;
        return "Load 1m " + load + " · " + safeThermalText;
    }

    private static String formatPercent(double value) {
        return isFiniteInRange(value, 0d, 100d)
                ? String.format(Locale.US, "%.0f %%", value)
                : "–";
    }

    private static double clampPercent(double value) {
        if (!Double.isFinite(value)) return Double.NaN;
        return Math.max(0d, Math.min(100d, value));
    }

    static boolean isFiniteInRange(double value, double minimum, double maximum) {
        return Double.isFinite(value) && value >= minimum && value <= maximum;
    }
}
