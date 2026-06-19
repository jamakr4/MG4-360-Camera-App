package com.drivehub.kamera.helper.audio;

import com.drivehub.kamera.dev.DevRuntimeLog;

import android.content.Context;
import android.media.AudioManager;
import android.os.Handler;
import android.util.Log;

public final class VolumeRestoreGuard {

    private static final String TAG = "VolumeRestoreGuard";
    private static final int STREAM = AudioManager.STREAM_MUSIC;
    private static final int[] RESTORE_DELAYS_MS = {60, 180, 420};

    private final AudioManager audioManager;
    private final Handler handler;
    private int baselineVolume = -1;

    public VolumeRestoreGuard(Context context, Handler handler) {
        this.audioManager = (AudioManager) context.getApplicationContext()
                .getSystemService(Context.AUDIO_SERVICE);
        this.handler = handler;
    }

    public void captureBaseline() {
        int volume = readVolume();
        if (volume >= 0) {
            baselineVolume = volume;
        }
    }

    public void restoreBaselineSoon(String reason) {
        final int target = baselineVolume >= 0 ? baselineVolume : readVolume();
        if (target < 0) return;
        for (int delayMs : RESTORE_DELAYS_MS) {
            handler.postDelayed(() -> restoreIfNeeded(target, reason), delayMs);
        }
    }

    private int readVolume() {
        if (audioManager == null) return -1;
        try {
            return audioManager.getStreamVolume(STREAM);
        } catch (Throwable t) {
            Log.w(TAG, "Failed to read stream volume", t);
            return -1;
        }
    }

    private void restoreIfNeeded(int target, String reason) {
        if (audioManager == null) return;
        try {
            int current = audioManager.getStreamVolume(STREAM);
            if (current == target) return;
            audioManager.setStreamVolume(STREAM, target, 0);
            baselineVolume = target;
            DevRuntimeLog.add("Maneuver", "volume restored " + current + "=>" + target
                    + " (" + reason + ")");
        } catch (Throwable t) {
            DevRuntimeLog.add("Maneuver", "volume restore failed: " + t.getClass().getSimpleName());
            Log.w(TAG, "Failed to restore stream volume", t);
        }
    }
}
