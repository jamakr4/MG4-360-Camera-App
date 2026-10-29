package com.drivehub.kamera.helper.vehiclesensors;

/** Read-only raw vehicle values. Null means unavailable; validate values on the vehicle before use. */
public final class VehicleSteeringAngleReader {
    private final VehicleDataConnection connection;

    public VehicleSteeringAngleReader(VehicleDataConnection connection) { this.connection = connection; }

    /** Wheel angle; unit, sign and scaling require vehicle validation. */
    public Number readAngle() { return connection.readProperty(0x21601563); }

    /** Wheel angle validity signal; semantics require vehicle validation. */
    public Number readValidity() { return connection.readProperty(0x214015a8); }
}
