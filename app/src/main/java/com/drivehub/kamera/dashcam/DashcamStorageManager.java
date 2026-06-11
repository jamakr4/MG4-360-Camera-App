package com.drivehub.kamera.dashcam;

import com.drivehub.kamera.settings.UiPrefs;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Single source of truth for where the dashcam writes its footage.
 *
 * Resolution policy:
 *  - AUTO:          USB when a writable medium passes the write test, otherwise internal.
 *  - USB_ONLY:      USB or nothing (resolution.baseDir == null when no usable medium).
 *  - INTERNAL_ONLY: internal storage, USB is never probed.
 *
 * "Internal" is whatever {@link DashcamSettingsController#getRecordsBaseDir} resolves to
 * (default Downloads/dashcam or the dev-mode override path).
 */
public final class DashcamStorageManager {

    private static final String TAG = "DashcamStorage";

    public static final int TARGET_AUTO = 0;
    public static final int TARGET_USB_ONLY = 1;
    public static final int TARGET_INTERNAL_ONLY = 2;

    public static final String KEY_STORAGE_TARGET = "dashcamStorageTarget";
    private static final String KEY_USB_RETENTION_CLIP_COUNT = "dashcamUsbRetentionClipCount";
    private static final String KEY_USB_MAX_RETAINED_EVENT_DIRS = "dashcamUsbMaxRetainedEventDirs";

    // USB media are larger and tolerate write load better, so defaults are much more generous
    // than the conservative internal defaults (10 clips / 5 events).
    public static final int DEFAULT_USB_RETENTION_CLIP_COUNT = 100;
    public static final int DEFAULT_USB_MAX_RETAINED_EVENT_DIRS = 25;

    private static final String USB_RECORDS_DIR_NAME = "dashcam";
    private static final String WRITE_PROBE_FILE_NAME = ".dashcam_write_probe";

    public enum UsbState {
        NOT_CHECKED,        // INTERNAL_ONLY mode: USB is deliberately ignored
        OK,                 // exactly one medium found, dir created, write test passed
        NO_MEDIUM,          // no removable volume mounted under /storage
        NOT_WRITABLE,       // volume(s) found but the dashcam dir cannot be created/written
        WRITE_TEST_FAILED,  // dir exists but the actual write probe failed
        MULTIPLE_MEDIA      // more than one usable medium — refusing to pick one silently
    }

    /** Immutable outcome of one storage resolution pass. */
    public static final class Resolution {
        /** Directory to record into; null only in USB_ONLY mode without a usable medium. */
        public final File baseDir;
        public final boolean usingUsb;
        public final int target;
        public final UsbState usbState;

        Resolution(File baseDir, boolean usingUsb, int target, UsbState usbState) {
            this.baseDir = baseDir;
            this.usingUsb = usingUsb;
            this.target = target;
            this.usbState = usbState;
        }
    }

    private DashcamStorageManager() {
    }

    // ---------- Preferences ----------

    public static int getStorageTarget(SharedPreferences prefs) {
        return clampTarget(prefs.getInt(KEY_STORAGE_TARGET, TARGET_AUTO));
    }

    public static void setStorageTarget(SharedPreferences prefs, int target) {
        prefs.edit().putInt(KEY_STORAGE_TARGET, clampTarget(target)).apply();
    }

    public static int clampTarget(int target) {
        return Math.max(TARGET_AUTO, Math.min(TARGET_INTERNAL_ONLY, target));
    }

    public static int getUsbRetentionClipCount(SharedPreferences prefs) {
        return DashcamSettingsController.clampRetentionClipCount(
                prefs.getInt(KEY_USB_RETENTION_CLIP_COUNT, DEFAULT_USB_RETENTION_CLIP_COUNT));
    }

    public static void setUsbRetentionClipCount(SharedPreferences prefs, int count) {
        prefs.edit().putInt(KEY_USB_RETENTION_CLIP_COUNT,
                DashcamSettingsController.clampRetentionClipCount(count)).apply();
    }

    public static int getUsbMaxRetainedEventDirs(SharedPreferences prefs) {
        return DashcamSettingsController.clampMaxRetainedEventDirs(
                prefs.getInt(KEY_USB_MAX_RETAINED_EVENT_DIRS, DEFAULT_USB_MAX_RETAINED_EVENT_DIRS));
    }

    public static void setUsbMaxRetainedEventDirs(SharedPreferences prefs, int count) {
        prefs.edit().putInt(KEY_USB_MAX_RETAINED_EVENT_DIRS,
                DashcamSettingsController.clampMaxRetainedEventDirs(count)).apply();
    }

    // ---------- Per-target retention policy ----------

    public static int getActiveRetentionClipCount(SharedPreferences prefs, boolean usingUsb) {
        return usingUsb
                ? getUsbRetentionClipCount(prefs)
                : DashcamSettingsController.getRetentionClipCount(prefs);
    }

    public static int getActiveMaxRetainedEventDirs(SharedPreferences prefs, boolean usingUsb) {
        return usingUsb
                ? getUsbMaxRetainedEventDirs(prefs)
                : DashcamSettingsController.getMaxRetainedEventDirs(prefs);
    }

    // ---------- Resolution ----------

    /**
     * Resolves the storage target for the configured mode. Performs real IO (USB write test)
     * unless the mode is INTERNAL_ONLY — do not call on the main thread.
     */
    public static Resolution resolve(Context context) {
        SharedPreferences prefs = UiPrefs.getPrefs(context);
        int target = getStorageTarget(prefs);

        if (target == TARGET_INTERNAL_ONLY) {
            return new Resolution(
                    DashcamSettingsController.getRecordsBaseDir(context),
                    false, target, UsbState.NOT_CHECKED);
        }

        UsbProbe probe = probeUsb(context);
        if (probe.state == UsbState.OK) {
            return new Resolution(probe.recordsDir, true, target, UsbState.OK);
        }
        if (target == TARGET_USB_ONLY) {
            return new Resolution(null, false, target, probe.state);
        }
        // AUTO: fall back to internal, but keep the probe result so callers can surface it.
        return new Resolution(
                DashcamSettingsController.getRecordsBaseDir(context),
                false, target, probe.state);
    }

    /**
     * Re-runs only the USB write test for the currently known USB records dir. Used by the
     * recording loop to distinguish "USB died" from "camera problem" after a failed segment.
     */
    public static boolean isUsbStillWritable(File usbRecordsDir) {
        if (usbRecordsDir == null) return false;
        return runWriteProbe(usbRecordsDir);
    }

    private static final class UsbProbe {
        final UsbState state;
        final File recordsDir;

        UsbProbe(UsbState state, File recordsDir) {
            this.state = state;
            this.recordsDir = recordsDir;
        }
    }

    private static final class UsbCandidate {
        final File rootDir;
        final String description;
        final boolean usbLike;

        UsbCandidate(File rootDir, String description, boolean usbLike) {
            this.rootDir = rootDir;
            this.description = description;
            this.usbLike = usbLike;
        }
    }

    /**
     * Probes every system-known removable-volume candidate instead of blindly scanning /storage.
     * We strongly prefer candidates that look like USB/OTG media by system label/path. If the
     * system exposes no explicit USB hint, we only fall back to a generic removable volume when
     * there is exactly one such candidate.
     */
    private static UsbProbe probeUsb(Context context) {
        List<UsbCandidate> candidates = findUsbCandidates(context);
        if (candidates.isEmpty()) {
            return new UsbProbe(UsbState.NO_MEDIUM, null);
        }
        List<UsbCandidate> prioritized = prioritizeUsbCandidates(candidates);
        List<File> usable = new ArrayList<>();
        boolean anyWriteTestFailed = false;
        for (UsbCandidate candidate : prioritized) {
            File recordsDir = new File(candidate.rootDir, USB_RECORDS_DIR_NAME);
            if ((!recordsDir.isDirectory() && !recordsDir.mkdirs() && !recordsDir.isDirectory())
                    || !recordsDir.canWrite()) {
                continue;
            }
            if (!runWriteProbe(recordsDir)) {
                anyWriteTestFailed = true;
                continue;
            }
            usable.add(recordsDir);
        }
        if (usable.size() == 1) {
            return new UsbProbe(UsbState.OK, usable.get(0));
        }
        if (usable.size() > 1) {
            Log.w(TAG, "Multiple usable USB/external media detected (" + usable.size()
                    + ") — refusing to pick one");
            return new UsbProbe(UsbState.MULTIPLE_MEDIA, null);
        }
        return new UsbProbe(
                anyWriteTestFailed ? UsbState.WRITE_TEST_FAILED : UsbState.NOT_WRITABLE, null);
    }

    /**
     * Returns removable, non-primary storage roots known to the framework for this app. We start
     * from app-visible external dirs so we only consider mounted media the process can actually
     * access, then map them back to the volume root.
     */
    private static List<UsbCandidate> findUsbCandidates(Context context) {
        Map<String, UsbCandidate> deduped = new LinkedHashMap<>();
        StorageManager storageManager = context.getSystemService(StorageManager.class);
        File[] externalDirs = context.getExternalFilesDirs(null);
        if (externalDirs == null) {
            return new ArrayList<>();
        }
        for (File appExternalDir : externalDirs) {
            if (appExternalDir == null) {
                continue;
            }
            StorageVolume volume = storageManager == null ? null : storageManager.getStorageVolume(appExternalDir);
            if (volume == null || volume.isPrimary() || !volume.isRemovable()) {
                continue;
            }
            File rootDir = resolveVolumeRoot(appExternalDir, volume);
            if (rootDir == null || !rootDir.isDirectory() || !rootDir.canRead()) {
                continue;
            }
            String key = rootDir.getAbsolutePath();
            if (deduped.containsKey(key)) {
                continue;
            }
            String description = safeDescription(volume, context);
            deduped.put(key, new UsbCandidate(rootDir, description, looksLikeUsb(rootDir, description)));
        }
        return new ArrayList<>(deduped.values());
    }

    private static List<UsbCandidate> prioritizeUsbCandidates(List<UsbCandidate> candidates) {
        List<UsbCandidate> usbLike = new ArrayList<>();
        List<UsbCandidate> genericRemovable = new ArrayList<>();
        for (UsbCandidate candidate : candidates) {
            if (candidate.usbLike) {
                usbLike.add(candidate);
            } else {
                genericRemovable.add(candidate);
            }
        }
        if (!usbLike.isEmpty()) {
            return usbLike;
        }
        if (genericRemovable.size() == 1) {
            Log.i(TAG, "No explicit USB-labelled volume found; using the only removable candidate: "
                    + genericRemovable.get(0).rootDir.getAbsolutePath());
            return genericRemovable;
        }
        return genericRemovable;
    }

    private static File resolveVolumeRoot(File appExternalDir, StorageVolume volume) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            File directory = volume.getDirectory();
            if (directory != null) {
                return directory;
            }
        }
        String path = appExternalDir.getAbsolutePath();
        int androidIdx = path.indexOf("/Android/");
        if (androidIdx > 0) {
            return new File(path.substring(0, androidIdx));
        }
        File parent = appExternalDir;
        while (parent != null) {
            File next = parent.getParentFile();
            if (next == null || next.getAbsolutePath().equals("/storage")) {
                return parent;
            }
            parent = next;
        }
        return null;
    }

    private static String safeDescription(StorageVolume volume, Context context) {
        try {
            CharSequence description = volume.getDescription(context);
            return description == null ? "" : description.toString();
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static boolean looksLikeUsb(File rootDir, String description) {
        String haystack = ((description == null ? "" : description) + " "
                + (rootDir == null ? "" : rootDir.getAbsolutePath())).toLowerCase(Locale.US);
        return haystack.contains("usb")
                || haystack.contains("otg")
                || haystack.contains("flash")
                || haystack.contains("thumb")
                || haystack.contains("mass_storage")
                || haystack.contains("usbdisk")
                || haystack.contains("udisk");
    }

    private static boolean runWriteProbe(File dir) {
        File probe = new File(dir, WRITE_PROBE_FILE_NAME);
        try {
            try (FileOutputStream out = new FileOutputStream(probe)) {
                out.write("dashcam".getBytes());
                out.getFD().sync();
            }
            boolean ok = probe.isFile() && probe.length() > 0;
            // noinspection ResultOfMethodCallIgnored
            probe.delete();
            return ok;
        } catch (Throwable t) {
            Log.w(TAG, "USB write probe failed in " + dir.getAbsolutePath() + ": " + t);
            // noinspection ResultOfMethodCallIgnored
            probe.delete();
            return false;
        }
    }
}
