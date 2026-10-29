package com.drivehub.kamera.helper.vehiclesensors;

/** Gear values published by the SAIC AVM stack. */
public final class VehicleGearReader {
    public enum Gear { PARK, REVERSE, NEUTRAL, DRIVE, UNKNOWN }

    private VehicleGearReader() { }

    public static Gear readGear() {
        return fromValue(SystemPropertiesHelper.getInt("arcsoft.avm.mCurCarGear", -1));
    }

    public static Gear fromValue(int value) {
        switch (value) {
            case 1: return Gear.PARK;
            case 2: return Gear.REVERSE;
            case 3: return Gear.NEUTRAL;
            case 4: return Gear.DRIVE;
            default: return Gear.UNKNOWN;
        }
    }
}
