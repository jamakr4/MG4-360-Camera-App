package com.drivehub.kamera.boot;

import com.drivehub.kamera.dashcam.RecordingService;
import com.drivehub.kamera.signal.SignalService;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

public class BootReceiver extends BroadcastReceiver {
    private static final String TAG = "BootReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null)
            return;
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction()))
            return;

        try {
            RecordingService.startIfDashcamEnabled(context);
        } catch (Exception e) {
            Log.w(TAG, "Failed to start RecordingService on boot", e);
        }

        try {
            SignalService.start(context);
        } catch (Exception e) {
            Log.w(TAG, "Failed to start SignalService on boot", e);
        }
    }
}