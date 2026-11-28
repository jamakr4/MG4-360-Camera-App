package com.drivehub.kamera.birdseye;

import android.content.Context;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

/**
 * Last known good vehicle calibration (issue #152). Only used while the real file has not been
 * readable yet in this process (e.g. /configure mounted late at boot). As soon as the real file
 * is read it wins; an invalid real file is never replaced by this copy.
 */
public final class CalibrationStore {
    static final String FILE="calibration-last-good.bin";
    private static CalibrationStore instance;
    private final File file;
    private String storedSha;
    private boolean realSeen;
    private BirdseyeCalibration fallback;

    CalibrationStore(File directory) {file=new File(directory,FILE);}

    /** Shared directory for the persisted calibration and projection meshes. */
    public static File directory(Context context) {
        return new File(context.getApplicationContext().getNoBackupFilesDir(),"birdseye");
    }
    public static synchronized CalibrationStore get(Context context) {
        if(instance==null)instance=new CalibrationStore(directory(context));
        return instance;
    }

    /** Record a calibration that was just read and validated from the vehicle file. */
    synchronized void remember(BirdseyeCalibration real) {
        realSeen=true;fallback=null;
        if(real.sha256.equals(storedSha))return;
        File temp=new File(file.getParentFile(),FILE+".tmp");
        try {
            File parent=file.getParentFile();
            if(parent!=null&&!parent.isDirectory()&&!parent.mkdirs())throw new IOException("No directory");
            Files.write(temp.toPath(),real.bytes());
            Files.move(temp.toPath(),file.toPath(),StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);
            storedSha=real.sha256;
        }catch(IOException|RuntimeException failure) {
            //noinspection ResultOfMethodCallIgnored
            temp.delete();
            ProjectionDiskCache.warn("Cannot persist calibration: "+failure.getMessage());
        }
    }

    /** The stored copy if the real file was never readable in this process; null otherwise. */
    synchronized BirdseyeCalibration fallback() {
        if(realSeen)return null;
        if(fallback!=null)return fallback;
        try {
            fallback=BirdseyeCalibration.load(file.toPath());storedSha=fallback.sha256;
            ProjectionDiskCache.info("Using last known good calibration until the vehicle file is readable");
            return fallback;
        }catch(IOException|RuntimeException failure) {
            return null; // Absent or corrupt: behave exactly as without a store.
        }
    }

    synchronized boolean isFallback(BirdseyeCalibration calibration) {return calibration!=null&&calibration==fallback;}

    public BirdseyeCalibration loadVehicle() throws IOException {
        return load(java.nio.file.Paths.get(BirdseyeCalibration.VEHICLE_PATH));
    }

    /** Reads the vehicle file, falling back to the stored copy only under the rules above. */
    BirdseyeCalibration load(java.nio.file.Path real) throws IOException {
        try {
            BirdseyeCalibration loaded=BirdseyeCalibration.load(real);remember(loaded);return loaded;
        }catch(IOException unreadable) {
            BirdseyeCalibration cached=fallback();
            if(cached==null)throw unreadable;
            return cached;
        }
    }
}
