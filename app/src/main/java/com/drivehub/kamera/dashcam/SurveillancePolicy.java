package com.drivehub.kamera.dashcam;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;

/** Surveillance owns its prefixed clips, never the normal dashcam ring. */
public final class SurveillancePolicy {
    public static final long GIB = 1024L * 1024 * 1024;
    public static final long REQUIRED_FREE_BYTES = GIB + 64L * 1024 * 1024;
    public static final int MAX_MINUTES = 120;

    private SurveillancePolicy() {}

    public static int minutes(String value) {
        try {
            long parsed = Long.parseLong(value == null ? "" : value.trim());
            return parsed > 0 ? (int) Math.min(MAX_MINUTES, parsed) : MAX_MINUTES;
        } catch (NumberFormatException e) { return MAX_MINUTES; }
    }

    public static String policy(String value) {
        String policy = value == null || value.trim().isEmpty() ? "recycle" : value.trim();
        if (!policy.equals("recycle") && !policy.equals("stop") && !policy.equals("warn"))
            throw new IllegalArgumentException("invalid_storage_policy");
        return policy;
    }

    public static String storageLevel(long bytes) {
        if (bytes < 0) return "unknown";
        if (bytes < GIB) return "very_low";
        if (bytes < 3 * GIB) return "low";
        if (bytes < 9 * GIB) return "limited";
        return "ok";
    }

    /** Normalise the two public USB mount aliases as well as symlinks and dot segments. */
    public static String identity(File file) throws IOException {
        String path = file.getCanonicalPath();
        if (path.startsWith("/mnt/media_rw/")) path = "/storage/" + path.substring(14);
        if (path.equals("/sdcard")) return "/storage/emulated/0";
        if (path.startsWith("/sdcard/")) path = "/storage/emulated/0/" + path.substring(8);
        if (path.equals("/storage/self/primary")) return "/storage/emulated/0";
        if (path.startsWith("/storage/self/primary/"))
            path = "/storage/emulated/0/" + path.substring(22);
        return path;
    }

    public static boolean overlaps(File first, File second) throws IOException {
        String a = identity(first), b = identity(second);
        return a.equals(b) || a.startsWith(b + "/") || b.startsWith(a + "/")
                || a.equals("/") || b.equals("/");
    }

    public static boolean isClip(String name) {
        return name.matches("surv_[0-9]{17}_[a-f0-9]{8}\\.mp4");
    }

    static List<File> candidates(File[] files, Set<String> protectedBases) {
        List<File> result = new ArrayList<>();
        if (files != null) for (File file : files) {
            String name = file.getName();
            if (file.isFile() && !java.nio.file.Files.isSymbolicLink(file.toPath()) && isClip(name)
                    && !protectedBases.contains(name.substring(0, name.length() - 4))) result.add(file);
        }
        result.sort(Comparator.comparing(File::getName));
        return result;
    }

    interface SpaceIo {
        long available(File directory);
        boolean deleteAndSync(File file);
    }

    /** One deletion at a time, with confirmation; a stale FAT reading must not erase an archive. */
    static void ensureSpace(String policy, File directory, Set<String> protectedBases,
                            BooleanSupplier cancelled, SpaceIo io) throws IOException {
        if (policy.equals("warn")) return;
        long free = io.available(directory);
        if (free < 0) throw new IOException("storage_status_unavailable");
        if (free >= REQUIRED_FREE_BYTES) return;
        if (policy.equals("stop")) throw new IOException("low_storage");
        for (File file : candidates(directory.listFiles(), protectedBases)) {
            if (cancelled.getAsBoolean()) return;
            if (!io.deleteAndSync(file)) throw new IOException("storage_reclaim_failed");
            long after = io.available(directory);
            if (after <= free) throw new IOException("storage_reclaim_unconfirmed");
            free = after;
            if (free >= REQUIRED_FREE_BYTES) return;
        }
        throw new IOException("low_storage");
    }
}
