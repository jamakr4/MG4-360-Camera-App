package com.drivehub.kamera.helper.vehiclesensors;

/**
 * Convenience reader for the current vehicle speed.
 * <p>
 * Reads the {@code arcsoft.avm.mCurCarSpeed} system property published by the
 * SAIC AVM stack. Returns 0 km/h when the property is unavailable (e.g. while
 * the OEM 360-camera app is not running, or on non-SAIC firmware).
 */
public final class VehicleSpeedReader {

    private static final String SPEED_PROP = "arcsoft.avm.mCurCarSpeed";

    private VehicleSpeedReader() {
    }

    /** Returns the current vehicle speed in km/h, or 0 if unavailable. */
    public static int readSpeedKmh() {
        return Math.max(0, Math.round(SystemPropertiesHelper.getFloat(SPEED_PROP, 0f)));
    }
}
