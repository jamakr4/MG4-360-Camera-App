package com.drivehub.kamera.helper.vehiclesensors;

/** Reads the optional OBD relative accelerator pedal position. */
public final class VehicleAcceleratorReader {
    private static final int RELATIVE_ACCELERATOR_PEDAL_POSITION = 67;
    private final VehicleDataConnection connection;

    public VehicleAcceleratorReader(VehicleDataConnection connection) { this.connection = connection; }

    /** Returns the diagnostic value, or null when unavailable. Vehicle support is unverified. */
    public Float readPosition() { return connection.readDiagnosticFloat(RELATIVE_ACCELERATOR_PEDAL_POSITION); }
}
