package com.drivehub.kamera.dashcam;

import java.io.File;
import java.io.IOException;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Coordinates playback with recording/retention. Keys identify files across USB mount aliases. */
public final class ReviewFileAccess {
    public static final Object LOCK = new Object();
    private static final Map<String, Integer> readers = new HashMap<>();
    private static final Set<String> writers = new HashSet<>();

    private ReviewFileAccess() {}

    public static String key(File file) {
        String path = file.toPath().toAbsolutePath().normalize().toString();
        String alias = volumeKey(path);
        if (alias != null) return alias;
        try { path = file.getCanonicalPath(); }
        catch (IOException ignored) { }
        alias = volumeKey(path);
        return alias == null ? path : alias;
    }

    private static String volumeKey(String path) {
        if (path.equals("/storage/emulated/0")) return "volume:primary";
        if (path.startsWith("/storage/emulated/0/")) return "volume:primary/" + path.substring(20);
        for (String prefix : new String[]{"/mnt/media_rw/", "/storage/"}) {
            if (path.startsWith(prefix)) {
                String tail = path.substring(prefix.length());
                int slash = tail.indexOf('/');
                if (!tail.isEmpty() && !tail.equals("emulated") && !tail.startsWith("emulated/"))
                    return "volume:" + (slash < 0 ? tail : tail.substring(0, slash)).toLowerCase(java.util.Locale.ROOT)
                            + (slash < 0 ? "" : tail.substring(slash));
            }
        }
        return null;
    }

    public static void beginWrite(File file) { synchronized (LOCK) { writers.add(key(file)); } }
    public static void endWrite(File file) { synchronized (LOCK) { writers.remove(key(file)); } }
    public static boolean isWriting(String key) { synchronized (LOCK) { return writers.contains(key); } }

    public static boolean isProtected(File file) {
        String candidate = key(file);
        synchronized (LOCK) {
            for (String selected : readers.keySet()) {
                if (within(candidate, selected) || within(selected, candidate)) return true;
            }
            return false;
        }
    }

    private static boolean within(String child, String parent) {
        return child.equals(parent) || child.startsWith(parent + "/");
    }

    public static Lease acquire(Collection<String> keys) {
        synchronized (LOCK) {
            Set<String> unique = new HashSet<>(keys);
            for (String key : unique) if (writers.contains(key)) return null;
            if (!StorageIoGate.begin()) return null;
            for (String key : unique) readers.put(key, readers.getOrDefault(key, 0) + 1);
            return new Lease(unique);
        }
    }

    public static final class Lease implements AutoCloseable {
        private Set<String> keys;
        private Lease(Set<String> keys) { this.keys = keys; }
        @Override public void close() {
            synchronized (LOCK) {
                if (keys == null) return;
                for (String key : keys) {
                    int count = readers.get(key);
                    if (count == 1) readers.remove(key); else readers.put(key, count - 1);
                }
                keys = null;
                StorageIoGate.end();
            }
        }
    }
}
