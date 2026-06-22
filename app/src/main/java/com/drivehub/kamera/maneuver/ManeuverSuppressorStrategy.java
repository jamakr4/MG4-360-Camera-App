package com.drivehub.kamera.maneuver;

interface ManeuverSuppressorStrategy {

    String name();

    default void start() {
    }

    default void stop() {
    }

    default void release() {
        stop();
    }

    default void setOwningSteeringStick(boolean owning) {
    }

    default void onRawHardkey(
            ManeuverHardkeySuppressor.RawEvent event,
            ManeuverHardkeySuppressor.BroadcastAbort abort
    ) {
    }

    default void onLogicalHardkey(
            ManeuverHardkeySuppressor.LogicalEvent event,
            ManeuverHardkeySuppressor.BroadcastAbort abort
    ) {
    }

    default void onStickDown(int rawCode) {
    }
}
