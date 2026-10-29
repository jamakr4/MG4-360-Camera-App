package com.drivehub.kamera.helper.vehiclesensors;

/** Read-only raw vehicle values. Null means unavailable; validate values on the vehicle before use. */
public final class VehicleAccTjaModeReader {
    private final VehicleDataConnection connection;

    public VehicleAccTjaModeReader(VehicleDataConnection connection) { this.connection = connection; }

    /** AAD_ACC_SWITCH as read by the OEM vehicle settings service; not an active-state indicator. */
    public Number readMode() { return connection.readProperty(0x2140a17f); }
}
