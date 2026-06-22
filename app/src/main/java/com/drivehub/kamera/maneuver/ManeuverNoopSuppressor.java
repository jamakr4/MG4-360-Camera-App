package com.drivehub.kamera.maneuver;

final class ManeuverNoopSuppressor implements ManeuverSuppressorStrategy {

    @Override
    public String name() {
        return "off";
    }
}
