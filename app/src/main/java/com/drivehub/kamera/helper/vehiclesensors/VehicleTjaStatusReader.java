package com.drivehub.kamera.helper.vehiclesensors;

/** Read-only raw vehicle values. Null means unavailable; validate values on the vehicle before use. */
public final class VehicleTjaStatusReader {
    private final VehicleDataConnection connection;

    public VehicleTjaStatusReader(VehicleDataConnection connection) { this.connection = connection; }

    /** TJA pilot system status; value meanings are unverified. */
    public Number readPilotSystem() { return connection.readProperty(0x21407b44); }

    /** Lane/TJA/ICA operating mode; value meanings are unverified. */
    public Number readLaneOperatingMode() { return connection.readProperty(0x2140a12a); }
}
