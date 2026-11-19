package com.drivehub.kamera.parking;

import android.os.SystemClock;
import com.drivehub.kamera.helper.vehiclesensors.SystemPropertiesHelper;
import java.util.function.Function;
import java.util.function.LongSupplier;

/** Reads the rear PDC snapshot published by the OEM CarManagerServer. */
final class SystemPropertyPdcSource implements PdcReader.Source {
    static final String SECTORS = "arcsoft.avm.mPdcBackObsShow";
    static final String CLOSEST_CM = "arcsoft.avm.mBackObsClosestDistance";
    static final String STATUS = "arcsoft.avm.ID_PDC_FRONT_REAR_STS";

    private final Function<String, String> properties;
    private final LongSupplier clock;

    SystemPropertyPdcSource() {
        this(SystemPropertiesHelper::getString, SystemClock::elapsedRealtime);
    }

    SystemPropertyPdcSource(Function<String, String> properties, LongSupplier clock) {
        this.properties = properties;
        this.clock = clock;
    }

    @Override public PdcSnapshot read() {
        long started = clock.getAsLong();
        // Read the complete sector list once, preserving the OEM's rear left-to-right order.
        int[] levels = parseSectors(properties.apply(SECTORS));
        int cm = parseInt(properties.apply(CLOSEST_CM));
        int status = parseInt(properties.apply(STATUS));
        return new PdcSnapshot(levels, cm, status, started);
    }

    private static int[] parseSectors(String value) {
        int[] levels = { -1, -1, -1, -1 };
        if (value == null)
            return levels;
        value = value.trim();
        if (!value.startsWith("[") || !value.endsWith("]"))
            return levels;
        String[] sectors = value.substring(1, value.length() - 1).split(",", -1);
        if (sectors.length != 4)
            return levels;
        for (int i = 0; i < levels.length; i++)
            levels[i] = parseInt(sectors[i]);
        return levels;
    }

    private static int parseInt(String value) {
        try {
            return value == null ? -1 : Integer.parseInt(value.trim());
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    @Override public String diagnostic() {
        return "system properties / " + SECTORS + " / " + CLOSEST_CM + " / " + STATUS;
    }

    @Override public void close() {
        // No connection or cached sensor values to release.
    }
}
