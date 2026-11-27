package com.drivehub.kamera.birdseye;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.Assert.*;

public class CalibrationStoreTest {
    @Rule public TemporaryFolder folder=new TemporaryFolder();

    @Test public void storedCopyBridgesALateMountUntilTheRealFileIsRead() throws IOException {
        Path real=folder.getRoot().toPath().resolve("vehicle.bin");
        Files.write(real,BirdseyeSynthetic.calibrationBytes());
        new CalibrationStore(folder.getRoot()).load(real); // previous process run
        Files.delete(real);
        CalibrationStore boot=new CalibrationStore(folder.getRoot());
        BirdseyeCalibration cached=boot.load(real);
        assertTrue(boot.isFallback(cached));
        Files.write(real,BirdseyeSynthetic.calibrationBytes());
        BirdseyeCalibration confirmed=boot.load(real);
        assertFalse(boot.isFallback(confirmed));assertEquals(cached.sha256,confirmed.sha256);
        Files.delete(real);
        try{boot.load(real);fail("After the real file was seen, IO failures must not fall back");}catch(IOException expected){}
    }
    @Test public void noStoredCopyBehavesAsBefore() throws IOException {
        Path real=folder.getRoot().toPath().resolve("missing.bin");
        try{new CalibrationStore(folder.getRoot()).load(real);fail();}catch(IOException expected){}
    }
    @Test public void invalidRealFileIsNeverMaskedByTheStoredCopy() throws IOException {
        Path real=folder.getRoot().toPath().resolve("vehicle.bin");
        Files.write(real,BirdseyeSynthetic.calibrationBytes());new CalibrationStore(folder.getRoot()).load(real);
        Files.write(real,new byte[520]);
        try{new CalibrationStore(folder.getRoot()).load(real);fail();}catch(IllegalArgumentException expected){}
    }
    @Test public void corruptStoredCopyIsIgnored() throws IOException {
        Files.write(folder.getRoot().toPath().resolve(CalibrationStore.FILE),new byte[100]);
        try{new CalibrationStore(folder.getRoot()).load(folder.getRoot().toPath().resolve("missing.bin"));fail();}catch(IOException expected){}
    }
    @Test public void changedRealCalibrationReplacesTheStoredCopy() throws IOException {
        Path real=folder.getRoot().toPath().resolve("vehicle.bin");
        byte[] changed=BirdseyeSynthetic.calibrationBytes();
        java.nio.ByteBuffer.wrap(changed).order(java.nio.ByteOrder.LITTLE_ENDIAN).putDouble(43);
        Files.write(real,BirdseyeSynthetic.calibrationBytes());new CalibrationStore(folder.getRoot()).load(real);
        Files.write(real,changed);String sha=new CalibrationStore(folder.getRoot()).load(real).sha256;
        Files.delete(real);
        assertEquals(sha,new CalibrationStore(folder.getRoot()).load(real).sha256);
    }
}
