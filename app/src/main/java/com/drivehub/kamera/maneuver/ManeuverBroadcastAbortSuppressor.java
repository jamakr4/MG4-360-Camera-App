package com.drivehub.kamera.maneuver;

import com.drivehub.kamera.dev.DevRuntimeLog;

final class ManeuverBroadcastAbortSuppressor implements ManeuverSuppressorStrategy {

    @Override
    public String name() {
        return "broadcast_abort";
    }

    @Override
    public void onRawHardkey(
            ManeuverHardkeySuppressor.RawEvent event,
            ManeuverHardkeySuppressor.BroadcastAbort abort
    ) {
        if (event == null || !event.ownsStick) return;
        abortBroadcast("raw=" + ManeuverHardkeySuppressor.formatHex(event.rawCode), event.down, abort);
    }

    @Override
    public void onLogicalHardkey(
            ManeuverHardkeySuppressor.LogicalEvent event,
            ManeuverHardkeySuppressor.BroadcastAbort abort
    ) {
        if (event == null || !event.ownsStick) return;
        abortBroadcast("logical=" + event.logicalCode, event.down, abort);
    }

    private void abortBroadcast(String label, boolean logOnDown, ManeuverHardkeySuppressor.BroadcastAbort abort) {
        if (abort == null) return;
        if (!abort.isOrdered()) {
            if (logOnDown) {
                DevRuntimeLog.add("Maneuver", label + " not ordered");
            }
            return;
        }
        try {
            abort.abort();
            if (logOnDown) {
                DevRuntimeLog.add("Maneuver", "suppressed " + label);
            }
        } catch (Throwable t) {
            if (logOnDown) {
                DevRuntimeLog.add("Maneuver", label + " suppress failed: "
                        + t.getClass().getSimpleName());
            }
        }
    }
}
