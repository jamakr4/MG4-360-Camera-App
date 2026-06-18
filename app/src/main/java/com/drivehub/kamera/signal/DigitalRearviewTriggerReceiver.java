package com.drivehub.kamera.signal;

import com.drivehub.kamera.dev.DevRuntimeLog;
import com.drivehub.kamera.settings.UiPrefs;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

// External trigger for companion apps to update the digital rearview toggle quickly.
public class DigitalRearviewTriggerReceiver extends BroadcastReceiver {
    public static final String ACTION_SET_DIGITAL_REARVIEW =
            "com.drivehub.kamera.action.SET_DIGITAL_REARVIEW";
    public static final String EXTRA_ENABLED = "enabled";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null || intent == null) {
            return;
        }
        if (!ACTION_SET_DIGITAL_REARVIEW.equals(intent.getAction())) {
            return;
        }

        SharedPreferences prefs = UiPrefs.getPrefs(context);
        boolean enabled = intent.hasExtra(EXTRA_ENABLED)
                ? intent.getBooleanExtra(EXTRA_ENABLED, false)
                : !UiPrefs.isDigitalRearviewEnabled(prefs);
        UiPrefs.setDigitalRearviewEnabled(prefs, enabled);
        DevRuntimeLog.add(
                "DigitalRearviewTriggerReceiver",
                "external trigger updated digital rearview=" + enabled);
        SignalService.requestRecheck();
    }
}
