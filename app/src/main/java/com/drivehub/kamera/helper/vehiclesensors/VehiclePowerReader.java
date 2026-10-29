package com.drivehub.kamera.helper.vehiclesensors;

/** Read-only raw vehicle values. Null means unavailable; validate values on the vehicle before use. */
public final class VehiclePowerReader {
    private final VehicleDataConnection connection;

    public VehiclePowerReader(VehicleDataConnection connection) { this.connection = connection; }

    /** Power demand candidate; scale and relationship to the IPK display are unverified. */
    public Number readPowerDemand() { return connection.readProperty(0x2140a1fc); }
}
