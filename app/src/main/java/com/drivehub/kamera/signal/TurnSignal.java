package com.drivehub.kamera.signal;

/**
 * Values reported by the vehicle's turn-signal lamp system.
 * <p>
 * These correspond to {@code android.car.hardware.CarLampManager} property
 * {@code 0x21409326} (turn signal) and the
 * {@code arcsoft.avm.mCurCarTurnLamp} system-property fallback.
 */
public enum TurnSignal {

    OFF(0),
    LEFT(1),
    RIGHT(2),
    HAZARD(3);

    private final int value;

    TurnSignal(int value) {
        this.value = value;
    }

    /** Raw integer value as returned by the Car API / system property. */
    public int getValue() {
        return value;
    }

    /** Looks up the constant for a raw lamp value. Returns {@code OFF} for unknown values. */
    public static TurnSignal fromValue(int value) {
        for (TurnSignal ts : values()) {
            if (ts.value == value) return ts;
        }
        return OFF;
    }
}
