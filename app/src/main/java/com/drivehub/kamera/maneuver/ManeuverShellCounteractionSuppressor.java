package com.drivehub.kamera.maneuver;

import com.drivehub.kamera.dev.DevRuntimeLog;

import android.content.Context;
import android.media.AudioManager;
import android.os.Handler;

import java.io.IOException;

final class ManeuverShellCounteractionSuppressor implements ManeuverSuppressorStrategy {

    private static final int STREAM = AudioManager.STREAM_MUSIC;
    private static final int[] RESTORE_DELAYS_MS = {0, 60, 160};

    private final AudioManager audioManager;
    private final Handler handler;
    private boolean owning;
    private int baselineVolume = -1;

    ManeuverShellCounteractionSuppressor(Context context, Handler handler) {
        this.audioManager = (AudioManager) context.getApplicationContext()
                .getSystemService(Context.AUDIO_SERVICE);
        this.handler = handler;
    }

    @Override
    public String name() {
        return "shell";
    }

    @Override
    public void stop() {
        owning = false;
    }

    @Override
    public void setOwningSteeringStick(boolean owning) {
        if (this.owning == owning) return;
        this.owning = owning;
        if (owning) {
            baselineVolume = readVolume();
            DevRuntimeLog.add("Maneuver", "shell suppressor=true baseline=" + baselineVolume);
        } else {
            DevRuntimeLog.add("Maneuver", "shell suppressor=false");
        }
    }

    @Override
    public void onStickDown(int rawCode) {
        if (!owning || !ManeuverHardkeySuppressor.isVolumeMappedStickCode(rawCode)) return;
        int target = baselineVolume >= 0 ? baselineVolume : readVolume();
        if (target < 0) return;
        for (int delayMs : RESTORE_DELAYS_MS) {
            if (delayMs <= 0) {
                restoreVolumeWithShell(target, rawCode);
            } else {
                handler.postDelayed(() -> restoreVolumeWithShell(target, rawCode), delayMs);
            }
        }
    }

    private int readVolume() {
        if (audioManager == null) return -1;
        try {
            return audioManager.getStreamVolume(STREAM);
        } catch (Throwable ignored) {
            return -1;
        }
    }

    private void restoreVolumeWithShell(int target, int rawCode) {
        try {
            Runtime.getRuntime().exec(new String[]{
                    "/system/bin/cmd",
                    "media_session",
                    "volume",
                    "--stream",
                    String.valueOf(STREAM),
                    "--set",
                    String.valueOf(target)
            });
            DevRuntimeLog.add("Maneuver", "shell volume set=" + target
                    + " (" + ManeuverHardkeySuppressor.formatHex(rawCode) + ")");
        } catch (IOException e) {
            DevRuntimeLog.add("Maneuver", "shell volume failed: " + e.getClass().getSimpleName());
        } catch (Throwable t) {
            DevRuntimeLog.add("Maneuver", "shell counter failed: " + t.getClass().getSimpleName());
        }
    }
}
