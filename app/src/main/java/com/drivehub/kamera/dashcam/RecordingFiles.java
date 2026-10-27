package com.drivehub.kamera.dashcam;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

/** File policy independent of Android, shared by rotation and failure cleanup. */
final class RecordingFiles {
    private RecordingFiles() {}

    static boolean isLoopClip(String name) {
        return name.matches("[0-9]{12,17}(_[FRXY])?\\.mp4");
    }

    static java.util.List<File> deletionCandidates(File[] files, int keep, java.util.Set<String> protectedBases) {
        java.util.Map<String, java.util.List<File>> groups = new java.util.TreeMap<>();
        if (files == null) return new java.util.ArrayList<>();
        for (File file : files) {
            if (!file.isFile() || !isLoopClip(file.getName())) continue;
            String base = file.getName().replaceFirst("(_[FRXY])?\\.mp4$", "");
            groups.computeIfAbsent(base, ignored -> new java.util.ArrayList<>()).add(file);
        }
        int excess = Math.max(0, groups.size() - Math.max(0, keep));
        java.util.List<File> result = new java.util.ArrayList<>();
        for (java.util.Map.Entry<String, java.util.List<File>> group : groups.entrySet()) {
            if (excess == 0) break;
            if (protectedBases.contains(group.getKey())) continue;
            result.addAll(group.getValue());
            excess--;
        }
        return result;
    }

    // Adapted from Tommasov/MG4_Dashcam 1.4.3: release FAT chains before unlink.
    // If truncate/fsync fails, retain the directory entry for diagnosis/retry.
    static boolean deleteFreeingSpace(File file) throws IOException {
        if (!file.isFile()) return false;
        if (file.length() > 0) {
            try (FileOutputStream out = new FileOutputStream(file)) {
                out.getFD().sync();
            }
        }
        return file.delete();
    }
}
