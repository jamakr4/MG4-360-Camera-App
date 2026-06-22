package com.drivehub.kamera.maneuver;

import com.drivehub.kamera.dev.DevRuntimeLog;

import android.content.Context;
import android.os.Handler;

import java.util.ArrayList;
import java.util.List;

final class ManeuverSuppressorController {

    private final ManeuverNoopSuppressor noopSuppressor = new ManeuverNoopSuppressor();
    private final ManeuverMediaSessionSuppressor mediaSessionSuppressor;
    private final ManeuverBroadcastAbortSuppressor broadcastAbortSuppressor =
            new ManeuverBroadcastAbortSuppressor();
    private final ManeuverActivityKeySuppressor activityKeySuppressor;
    private final ManeuverShellCounteractionSuppressor shellCounteractionSuppressor;

    private ManeuverSuppressorMode mode;
    private ManeuverSuppressorStrategy activeSuppressor = noopSuppressor;
    private boolean owningSteeringStick;

    ManeuverSuppressorController(Context context, Handler handler) {
        Context appContext = context.getApplicationContext();
        mediaSessionSuppressor = new ManeuverMediaSessionSuppressor(appContext, handler);
        activityKeySuppressor = new ManeuverActivityKeySuppressor(appContext);
        shellCounteractionSuppressor = new ManeuverShellCounteractionSuppressor(appContext, handler);
    }

    void setMode(ManeuverSuppressorMode nextMode) {
        if (nextMode == null) {
            nextMode = ManeuverSuppressorMode.DEFAULT;
        }
        if (nextMode == mode) return;

        activeSuppressor.setOwningSteeringStick(false);
        activeSuppressor.stop();
        mode = nextMode;
        activeSuppressor = createSuppressor(nextMode);
        activeSuppressor.start();
        activeSuppressor.setOwningSteeringStick(owningSteeringStick);
        DevRuntimeLog.add("Maneuver", "suppressor mode=" + nextMode.preferenceValue());
    }

    void setOwningSteeringStick(boolean owning) {
        if (owningSteeringStick == owning) {
            if (owning) {
                activeSuppressor.setOwningSteeringStick(true);
            }
            return;
        }
        owningSteeringStick = owning;
        activeSuppressor.setOwningSteeringStick(owning);
    }

    void onRawHardkey(
            ManeuverHardkeySuppressor.RawEvent event,
            ManeuverHardkeySuppressor.BroadcastAbort abort
    ) {
        activeSuppressor.onRawHardkey(event, abort);
    }

    void onLogicalHardkey(
            ManeuverHardkeySuppressor.LogicalEvent event,
            ManeuverHardkeySuppressor.BroadcastAbort abort
    ) {
        activeSuppressor.onLogicalHardkey(event, abort);
    }

    void onStickDown(int rawCode) {
        activeSuppressor.onStickDown(rawCode);
    }

    void release() {
        activeSuppressor.setOwningSteeringStick(false);
        activeSuppressor.stop();
        mediaSessionSuppressor.release();
        activityKeySuppressor.release();
        shellCounteractionSuppressor.release();
        broadcastAbortSuppressor.release();
        activeSuppressor = noopSuppressor;
        mode = null;
        owningSteeringStick = false;
    }

    private ManeuverSuppressorStrategy createSuppressor(ManeuverSuppressorMode mode) {
        List<ManeuverSuppressorStrategy> strategies = new ArrayList<>();
        if (mode.usesMediaSession()) {
            strategies.add(mediaSessionSuppressor);
        }
        if (mode.usesBroadcastAbort()) {
            strategies.add(broadcastAbortSuppressor);
        }
        if (mode.usesKeyActivity()) {
            strategies.add(activityKeySuppressor);
        }
        if (mode.usesShellCounteraction()) {
            strategies.add(shellCounteractionSuppressor);
        }
        if (strategies.isEmpty()) {
            return noopSuppressor;
        }
        if (strategies.size() == 1) {
            return strategies.get(0);
        }
        return new ManeuverCompositeSuppressor(
                mode.preferenceValue(),
                strategies.toArray(new ManeuverSuppressorStrategy[0])
        );
    }
}
