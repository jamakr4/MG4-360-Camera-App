package com.drivehub.kamera.helper.vehiclesensors;

/** Read-only raw vehicle values. Null means unavailable; validate values on the vehicle before use. */
public final class VehicleAccStatusReader {
    private final VehicleDataConnection connection;

    public VehicleAccStatusReader(VehicleDataConnection connection) { this.connection = connection; }

    /** ACC system status; value meanings require vehicle validation. */
    public Number readStatus() { return connection.readProperty(0x2140a1d9); }
}
