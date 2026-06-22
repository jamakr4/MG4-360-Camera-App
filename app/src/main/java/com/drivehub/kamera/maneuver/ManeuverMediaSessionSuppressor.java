package com.drivehub.kamera.maneuver;

import com.drivehub.kamera.helper.audio.MediaKeySuppressor;
import com.drivehub.kamera.helper.audio.VolumeRestoreGuard;

import android.content.Context;
import android.os.Handler;

final class ManeuverMediaSessionSuppressor implements ManeuverSuppressorStrategy {

    private final MediaKeySuppressor mediaKeySuppressor;
    private final VolumeRestoreGuard volumeRestoreGuard;
    private boolean owning;

    ManeuverMediaSessionSuppressor(Context context, Handler handler) {
        this.mediaKeySuppressor = new MediaKeySuppressor(context.getApplicationContext(), handler);
        this.volumeRestoreGuard = new VolumeRestoreGuard(context.getApplicationContext(), handler);
    }

    @Override
    public String name() {
        return "media_session";
    }

    @Override
    public void stop() {
        setOwningSteeringStick(false);
    }

    @Override
    public void release() {
        owning = false;
        mediaKeySuppressor.setActive(false);
        mediaKeySuppressor.release();
    }

    @Override
    public void setOwningSteeringStick(boolean owning) {
        if (this.owning == owning) return;
        this.owning = owning;
        if (owning) {
            volumeRestoreGuard.captureBaseline();
        }
        mediaKeySuppressor.setActive(owning);
    }

    @Override
    public void onStickDown(int rawCode) {
        if (!owning || !ManeuverHardkeySuppressor.isVolumeMappedStickCode(rawCode)) return;
        volumeRestoreGuard.restoreBaselineSoon(ManeuverHardkeySuppressor.formatHex(rawCode));
    }
}
