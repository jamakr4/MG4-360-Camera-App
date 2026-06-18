package com.drivehub.kamera.dev;

import com.drivehub.kamera.R;

import android.content.Context;

import androidx.appcompat.app.AlertDialog;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

final class DashcamFolderPicker {

    interface Listener {
        void onFolderSelected(String absolutePath);

        void onUseDefault();
    }

    private static final File STORAGE_ROOT = new File("/storage");

    private final Context context;
    private final Listener listener;

    private DashcamFolderPicker(Context context, Listener listener) {
        this.context = context;
        this.listener = listener;
    }

    static void show(Context context, String currentPath, Listener listener) {
        new DashcamFolderPicker(context, listener).showDialog(resolvePickerStartDir(currentPath));
    }

    private void showDialog(File currentDir) {
        File dir = sanitizePickerDir(currentDir);
        File[] children = dir.listFiles(file -> file != null && file.isDirectory() && file.canRead());
        List<File> directories = new ArrayList<>();
        if (children != null) {
            directories.addAll(Arrays.asList(children));
            directories.sort(Comparator.comparing(file -> file.getName().toLowerCase()));
        }

        List<String> labels = new ArrayList<>();
        List<File> targets = new ArrayList<>();
        File parent = getPickerParent(dir);
        if (parent != null) {
            labels.add("..");
            targets.add(parent);
        }
        for (File child : directories) {
            labels.add(child.getName());
            targets.add(child);
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(context)
                .setTitle(dir.getAbsolutePath())
                .setNegativeButton(android.R.string.cancel, null)
                .setNeutralButton(R.string.settings_records_path_use_default,
                        (dialog, which) -> listener.onUseDefault())
                .setPositiveButton(R.string.settings_records_path_use_this_folder,
                        (dialog, which) -> listener.onFolderSelected(dir.getAbsolutePath()));

        if (labels.isEmpty()) {
            builder.setMessage(R.string.settings_records_path_empty_folder);
        } else {
            builder.setItems(labels.toArray(new String[0]), (dialog, which) -> showDialog(targets.get(which)));
        }
        builder.show();
    }

    private static File resolvePickerStartDir(String rawPath) {
        String normalized = normalizeRecordsPath(rawPath);
        if (normalized.isEmpty()) {
            return STORAGE_ROOT;
        }
        File candidate = new File(normalized);
        if (!candidate.isDirectory()) {
            candidate = candidate.getParentFile();
        }
        return sanitizePickerDir(candidate);
    }

    private static File sanitizePickerDir(File dir) {
        if (dir == null || !dir.exists() || !dir.isDirectory() || !dir.canRead()) {
            return STORAGE_ROOT;
        }
        String rootPath = STORAGE_ROOT.getAbsolutePath();
        String dirPath = dir.getAbsolutePath();
        if (!dirPath.equals(rootPath) && !dirPath.startsWith(rootPath + "/")) {
            return STORAGE_ROOT;
        }
        return dir;
    }

    private static File getPickerParent(File dir) {
        if (dir == null) {
            return null;
        }
        File parent = dir.getParentFile();
        if (parent == null) {
            return null;
        }
        File sanitized = sanitizePickerDir(parent);
        return sanitized.equals(dir) ? null : sanitized;
    }

    private static String normalizeRecordsPath(String value) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return "";
        }
        while (trimmed.endsWith("/") && trimmed.length() > 1) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }
}
