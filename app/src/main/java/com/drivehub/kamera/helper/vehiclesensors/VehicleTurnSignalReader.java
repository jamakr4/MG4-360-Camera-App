package com.drivehub.kamera.helper.vehiclesensors;

/** AVM turn lamp property; unknown values are not treated as off. */
public final class VehicleTurnSignalReader {
    public enum State { OFF, LEFT, RIGHT, HAZARD, UNKNOWN }
    private VehicleTurnSignalReader() { }

    public static State readState() {
        switch (SystemPropertiesHelper.getInt("arcsoft.avm.mCurCarTurnLamp", -1)) {
            case 0: return State.OFF;
            case 1: return State.LEFT;
            case 2: return State.RIGHT;
            case 3: return State.HAZARD;
            default: return State.UNKNOWN;
        }
    }
}
