package com.drivehub.kamera.boot;

import com.drivehub.kamera.dashcam.RecordingService;
import com.drivehub.kamera.signal.SignalService;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null)
            return;
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction()))
            return;

        RecordingService.startIfDashcamEnabled(context);

        // SignalService startup binds the Car API and may fall back to polling — guard
        // so a binding failure surfaces in logcat instead of crashing the boot receiver.
        try {
            SignalService.start(context);
        } catch (Exception e) {
            Log.w("BootReceiver", "Failed to start SignalService on boot", e);
        }
    }
}
