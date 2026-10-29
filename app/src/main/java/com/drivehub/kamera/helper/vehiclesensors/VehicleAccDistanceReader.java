package com.drivehub.kamera.helper.vehiclesensors;

/** Read-only raw vehicle values. Null means unavailable; validate values on the vehicle before use. */
public final class VehicleAccDistanceReader {
    private final VehicleDataConnection connection;

    public VehicleAccDistanceReader(VehicleDataConnection connection) { this.connection = connection; }

    /** ACC detected distance or selected distance; meaning and scale are unverified. */
    public Number readDistance() { return connection.readProperty(0x21407b48); }
}
