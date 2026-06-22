package com.drivehub.kamera.maneuver;

import com.drivehub.kamera.dev.DevRuntimeLog;

import android.content.Context;

final class ManeuverActivityKeySuppressor implements ManeuverSuppressorStrategy {

    private final Context context;
    private boolean owning;

    ManeuverActivityKeySuppressor(Context context) {
        this.context = context.getApplicationContext();
    }

    @Override
    public String name() {
        return "activity";
    }

    @Override
    public void stop() {
        setOwningSteeringStick(false);
    }

    @Override
    public void setOwningSteeringStick(boolean owning) {
        if (this.owning == owning) {
            if (owning) {
                startActivityIfNeeded();
            }
            return;
        }
        this.owning = owning;
        if (owning) {
            startActivityIfNeeded();
        } else {
            ManeuverKeyActivity.finishIfActive();
            DevRuntimeLog.add("Maneuver", "key activity suppressor=false");
        }
    }

    private void startActivityIfNeeded() {
        if (ManeuverKeyActivity.isActive()) return;
        ManeuverKeyActivity.start(context);
        DevRuntimeLog.add("Maneuver", "key activity suppressor=true");
    }
}
