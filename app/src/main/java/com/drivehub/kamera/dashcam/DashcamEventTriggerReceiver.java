package com.drivehub.kamera.dashcam;

import com.drivehub.kamera.dev.DevRuntimeLog;
import com.drivehub.kamera.settings.UiPrefs;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
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

        boolean dashcamRunning = UiPrefs.getPrefs(context).getBoolean(DashcamSettingsController.KEY_ENABLED, false)
                && RecordingService.isRunning();
        DevRuntimeLog.add(
                "DashcamEventTriggerReceiver",
                dashcamRunning
                        ? "external trigger accepted: rolling event save"
                        : "external trigger accepted: future-only event save");
        RecordingService.triggerEventSaveOrFutureOnly(context);
    }
}
