package com.drivehub.kamera.helper.vehiclesensors;

/** Read-only raw vehicle values. Null means unavailable; validate values on the vehicle before use. */
public final class VehicleBrakeReader {
    private final VehicleDataConnection connection;

    public VehicleBrakeReader(VehicleDataConnection connection) { this.connection = connection; }

    /** Driver-applied brake pedal pressure; scale requires vehicle validation. */
    public Number readPressure() { return connection.readProperty(0x2140159d); }

    /** Brake pressure validity; value meanings require vehicle validation. */
    public Number readValidity() { return connection.readProperty(0x214015a3); }

    /** Brake pedal sensing candidate; meaning and scale are unverified. */
    public Number readPedalSensing() { return connection.readProperty(0x2140a1e1); }
}
