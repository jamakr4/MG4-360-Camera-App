package com.drivehub.kamera.dashcam;

import android.content.Context;
import android.system.Os;
import java.io.File;
import java.io.IOException;
import java.util.Set;
import java.util.function.BooleanSupplier;

final class SurveillanceStorage {
    private SurveillanceStorage() {}

    static File resolve(Context context, SurveillanceSession session) throws Exception {
        File directory;
        if (session.directory.isEmpty()) {
            DashcamStorageManager.Resolution found = DashcamStorageManager.discoverSurveillanceDestination(context);
            directory = found.baseDir;
            session.usingUsb = found.usingUsb;
        } else {
            directory = new File(session.directory);
            if (!directory.isAbsolute()) throw new IOException("invalid_directory");
            // On the head unit /storage is often read-only while the raw mount is writable.
            String path = directory.getAbsolutePath();
            if (!session.prepared && path.startsWith("/storage/") && !path.startsWith("/storage/emulated/")
                    && !path.startsWith("/storage/self/")) {
                File raw = new File("/mnt/media_rw/" + path.substring(9));
                if (DashcamStorageManager.isUsbDirectory(context, raw)) directory = raw;
            }
            session.usingUsb = DashcamStorageManager.isUsbDirectory(context, directory);
            if ((path.startsWith("/mnt/media_rw/") || (path.startsWith("/storage/")
                    && !path.startsWith("/storage/emulated/") && !path.startsWith("/storage/self/")))
                    && !session.usingUsb) throw new IOException("no_medium");
        }
        directory = directory.getCanonicalFile();
        validateIsolation(context, directory);
        if (session.prepared) {
            validatePinned(directory, session.device);
        } else {
            if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("storage_not_writable");
            session.directory = directory.getAbsolutePath();
            session.device = Os.stat(session.directory).st_dev;
        }
        return directory;
    }

    static void validateIsolation(Context context, File directory) throws IOException {
        for (File normal : DashcamStorageManager.normalRecordingDirectories(context))
            if (SurveillancePolicy.overlaps(directory, normal)) throw new IOException("directory_overlaps_dashcam");
    }

    static void validatePinned(File directory, long device) throws Exception {
        if (!directory.isDirectory() || Os.stat(directory.getAbsolutePath()).st_dev != device)
            throw new IOException("storage_unavailable");
    }

    static void prepare(Context context, SurveillanceSession session, Set<String> protectedBases,
                        BooleanSupplier cancelled) throws Exception {
        if (!StorageIoGate.begin()) throw new IOException("storage_busy");
        try {
            File directory = new File(session.directory);
            validatePinned(directory, session.device);
            validateIsolation(context, directory);
            SurveillancePolicy.ensureSpace(session.policy, directory, protectedBases, cancelled,
                    new SurveillancePolicy.SpaceIo() {
                        public long available(File dir) { return DashcamStorageManager.availableBytes(dir); }
                        public boolean deleteAndSync(File file) {
                            try { validatePinned(file.getParentFile(), session.device); }
                            catch (Exception error) { return false; }
                            return DashcamStorageManager.deleteFreeingSpace(file)
                                    && DashcamStorageManager.syncDirectory(file.getParentFile());
                        }
                    });
            if (!cancelled.getAsBoolean() && !DashcamStorageManager.isUsbStillWritable(directory))
                throw new IOException("storage_write_test_failed");
        } finally { StorageIoGate.end(); }
    }
}
