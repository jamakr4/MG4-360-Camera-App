package com.drivehub.kamera.side;

import com.drivehub.kamera.birdseye.BirdseyeCalibration;
import com.drivehub.kamera.birdseye.BirdseyeSynthetic;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.io.RandomAccessFile;

import static org.junit.Assert.*;

public class WheelLensMapCacheTest {
    @Rule public TemporaryFolder folder = new TemporaryFolder();

    @Test public void newProcessRestoresOnlyIntactMapsForItsCalibration() throws Exception {
        BirdseyeCalibration calibration = BirdseyeCalibration.parse(BirdseyeSynthetic.calibrationBytes());
        String key = WheelLensMaps.key(calibration);
        float[][] maps = WheelLensMaps.get(calibration);
        new WheelLensMapCache(folder.getRoot()).write(key, maps);

        WheelLensMapCache reopened = new WheelLensMapCache(folder.getRoot());
        assertNull(reopened.read(key + "-changed"));
        float[][] restored = reopened.read(key);
        for (int i = 0; i < maps.length; i++) assertArrayEquals(maps[i], restored[i], 0f);

        try (RandomAccessFile file = new RandomAccessFile(folder.getRoot().listFiles()[0], "rw")) {
            file.seek(1024);
            int original = file.readUnsignedByte();
            file.seek(1024);
            file.write(original ^ 1);
        }
        assertThrows(IOException.class, () -> reopened.read(key));
    }
}
