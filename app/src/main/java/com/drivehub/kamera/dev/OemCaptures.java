package com.drivehub.kamera.dev;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.Arrays;
import java.util.Locale;

/**
 * Reads what the factory 360 app's hidden RecordActivity leaves behind.
 *
 * That screen (launchable with
 * {@code am start -n com.saicmotor.hmi.aroundview/.RecordActivity}) writes raw NV12 stills and
 * an H.264 clip into the OEM app's own private directory, and never deletes them. Normally
 * that directory would be out of reach — but the factory app declares
 * {@code android:sharedUserId="android.uid.system"}, exactly as this one does, so both run as
 * uid 1000 and the files belong to us as much as to it. No root and no permission involved:
 * same owner.
 *
 * The filenames are the interesting part. The OEM formats them as
 * {@code %dx%d_nv12_%d_%d.nv12}, so each name states the true per-camera resolution — which is
 * what decides whether the recording pipeline here is keeping a whole camera frame or half of
 * one.
 */
public final class OemCaptures {

    /** The factory around-view app. Fixed on this vehicle. */
    private static final String OEM_PACKAGE = "com.saicmotor.hmi.aroundview";
    private static final File OEM_FILES_DIR = new File("/data/user/0/" + OEM_PACKAGE + "/files");

    /** Subdirectory of the dashcam records dir that copies land in. */
    public static final String COPY_DIR_NAME = "avm";

    private OemCaptures() {
    }

    /** A listing for the on-screen report, or the reason there is not one. */
    public static String describe() {
        StringBuilder sb = new StringBuilder();
        sb.append(OEM_FILES_DIR.getAbsolutePath()).append('\n');
        File[] files = list();
        if (files == null) {
            sb.append("  unreadable (exists=").append(OEM_FILES_DIR.exists())
                    .append(" read=").append(OEM_FILES_DIR.canRead())
                    .append(") - SELinux or the 360 app has never run\n");
            return sb.toString();
        }
        if (files.length == 0) {
            sb.append("  empty — nothing captured yet\n");
            return sb.toString();
        }
        long total = 0L;
        for (File file : files) {
            total += file.length();
            sb.append(String.format(Locale.US, "  %-44s %8d bytes%n", file.getName(), file.length()));
        }
        sb.append(String.format(Locale.US, "  %d file(s), %.1f MB%n",
                files.length, total / (1024.0 * 1024.0)));
        return sb.toString();
    }

    /**
     * Copies everything into {@code <recordsBaseDir>/avm/} so it can be read off the car.
     * Returns a one-line outcome for the screen.
     */
    public static String copyTo(File recordsBaseDir) {
        if (!com.drivehub.kamera.dashcam.StorageIoGate.begin()) return "Storage is stopped for eject/shutdown";
        try { return copyToUnchecked(recordsBaseDir); }
        finally { com.drivehub.kamera.dashcam.StorageIoGate.end(); }
    }

    private static String copyToUnchecked(File recordsBaseDir) {
        File[] files = list();
        if (files == null) {
            return "Cannot read " + OEM_FILES_DIR.getAbsolutePath();
        }
        if (files.length == 0) {
            return "Nothing to copy: the 360 app has captured nothing.";
        }
        File target = new File(recordsBaseDir, COPY_DIR_NAME);
        if (!target.isDirectory() && !target.mkdirs() && !target.isDirectory()) {
            return "Cannot create " + target.getAbsolutePath();
        }
        int copied = 0;
        long bytes = 0L;
        String firstError = null;
        for (File source : files) {
            try {
                bytes += copyFile(source, new File(target, source.getName()));
                copied++;
            } catch (Throwable t) {
                if (firstError == null) {
                    firstError = source.getName() + ": " + t;
                }
            }
        }
        String summary = String.format(Locale.US, "Copied %d/%d file(s), %.1f MB into %s",
                copied, files.length, bytes / (1024.0 * 1024.0), target.getAbsolutePath());
        return firstError == null ? summary : summary + " — first failure: " + firstError;
    }

    /**
     * Removes the captures, and only those: names ending in .nv12 or .h264, which is what
     * RecordActivity writes. Anything else in that directory belongs to the factory app's own
     * business and is left alone and reported, because this is another app's data dir and the
     * fact that we may write there is not a reason to sweep it.
     */
    private static boolean isCapture(String name) {
        String lower = name.toLowerCase(Locale.US);
        return lower.endsWith(".nv12") || lower.endsWith(".h264");
    }

    private static File[] list() {
        File[] files;
        try {
            files = OEM_FILES_DIR.listFiles(file -> file.isFile() && isCapture(file.getName()));
        } catch (Throwable t) {
            return null;
        }
        if (files == null) {
            return null;
        }
        Arrays.sort(files, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        return files;
    }

    /** Writes to a temp name and renames, so a half-copied file never looks complete. */
    private static long copyFile(File source, File target) throws Exception {
        if (target.exists()) throw new Exception("already exported: " + target.getName());
        long expected = source.length();
        long modified = source.lastModified();
        File temp = File.createTempFile("oem-", ".tmp", target.getParentFile());
        try {
        byte[] buffer = new byte[64 * 1024];
        long total = 0L;
        try (FileInputStream in = new FileInputStream(source);
                FileOutputStream out = new FileOutputStream(temp)) {
            int read;
            while (total < expected && (read = in.read(buffer, 0, (int)Math.min(buffer.length, expected - total))) > 0) {
                out.write(buffer, 0, read);
                total += read;
            }
            out.flush();
            out.getFD().sync();
        }
        if (total != expected || source.length() != expected || source.lastModified() != modified) {
            // noinspection ResultOfMethodCallIgnored
            temp.delete();
            throw new Exception("cannot replace " + target.getAbsolutePath());
        }
        if (!temp.renameTo(target)) {
            // noinspection ResultOfMethodCallIgnored
            temp.delete();
            throw new Exception("rename failed");
        }
        if (!com.drivehub.kamera.dashcam.DashcamStorageManager.syncDirectory(target.getParentFile()))
            throw new Exception("directory sync failed");
        return total;
        } finally { if (temp.exists()) temp.delete(); }
    }
}
