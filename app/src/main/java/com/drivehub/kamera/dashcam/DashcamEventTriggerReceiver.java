package com.drivehub.kamera.dashcam;

import com.drivehub.kamera.dev.DevRuntimeLog;
import com.drivehub.kamera.settings.UiPrefs;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.widget.Toast;

//External trigger via the trigger app see: https://github.com/jamakr4/MG4-Dashcam-Trigger

public class DashcamEventTriggerReceiver extends BroadcastReceiver {
    public static final String ACTION_TRIGGER_DASHCAM_EVENT = "com.drivehub.kamera.action.TRIGGER_DASHCAM_EVENT";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null || intent == null) {
            return;
        }
        if (!ACTION_TRIGGER_DASHCAM_EVENT.equals(intent.getAction())) {
            return;
        }

        boolean dashcamEnabled = UiPrefs.getPrefs(context).getBoolean(DashcamSettingsController.KEY_ENABLED, false);
        if (!dashcamEnabled || !RecordingService.isRunning()) {
            DevRuntimeLog.add(
                    "DashcamEventTriggerReceiver",
                    "external trigger ignored: dashcam disabled or not running");
            Toast.makeText(context, "Dashcam ist nicht aktiv", Toast.LENGTH_SHORT).show();
            return;
        }

        DevRuntimeLog.add("DashcamEventTriggerReceiver", "external trigger accepted");
        RecordingService.triggerEventSave(context);
    }
}
