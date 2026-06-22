package com.drivehub.kamera.maneuver;

final class ManeuverCompositeSuppressor implements ManeuverSuppressorStrategy {

    private final String name;
    private final ManeuverSuppressorStrategy[] strategies;

    ManeuverCompositeSuppressor(String name, ManeuverSuppressorStrategy... strategies) {
        this.name = name;
        this.strategies = strategies == null ? new ManeuverSuppressorStrategy[0] : strategies;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public void start() {
        for (ManeuverSuppressorStrategy strategy : strategies) {
            strategy.start();
        }
    }

    @Override
    public void stop() {
        for (ManeuverSuppressorStrategy strategy : strategies) {
            strategy.stop();
        }
    }

    @Override
    public void setOwningSteeringStick(boolean owning) {
        for (ManeuverSuppressorStrategy strategy : strategies) {
            strategy.setOwningSteeringStick(owning);
        }
    }

    @Override
    public void onRawHardkey(
            ManeuverHardkeySuppressor.RawEvent event,
            ManeuverHardkeySuppressor.BroadcastAbort abort
    ) {
        for (ManeuverSuppressorStrategy strategy : strategies) {
            strategy.onRawHardkey(event, abort);
        }
    }

    @Override
    public void onLogicalHardkey(
            ManeuverHardkeySuppressor.LogicalEvent event,
            ManeuverHardkeySuppressor.BroadcastAbort abort
    ) {
        for (ManeuverSuppressorStrategy strategy : strategies) {
            strategy.onLogicalHardkey(event, abort);
        }
    }

    @Override
    public void onStickDown(int rawCode) {
        for (ManeuverSuppressorStrategy strategy : strategies) {
            strategy.onStickDown(rawCode);
        }
    }
}
