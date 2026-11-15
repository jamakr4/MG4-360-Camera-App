package com.drivehub.kamera.helper.vehiclesensors;

import org.junit.Test;
import static org.junit.Assert.*;

public class VehicleSpeedReaderTest {
    @Test public void validReadingsKeepFractionalPrecisionAtTheThreshold() {
        assertEquals(0, VehicleSpeedReader.parseSpeedKmh("0"), 0);
        assertEquals(24.99f, VehicleSpeedReader.parseSpeedKmh("24.99"), 0);
        assertEquals(25, VehicleSpeedReader.parseSpeedKmh("25"), 0);
    }
    @Test public void missingMalformedAndNonFiniteValuesAreNotZero() {
        for (String value : new String[]{null, "", "unknown", "-1", "NaN", "Infinity", "-Infinity"})
            assertTrue(String.valueOf(value), Float.isNaN(VehicleSpeedReader.parseSpeedKmh(value)));
    }
}
