package com.drivehub.kamera.dashcam;

import com.drivehub.kamera.dev.DevRuntimeLog;
import com.drivehub.kamera.settings.UiPrefs;
import com.drivehub.kamera.signal.SignalService;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

public class OemAvmReceiver extends BroadcastReceiver {
    private static final String TAG = "OemAvmReceiver";
    private static final String EXTRA_START_MESSAGE = "com.saicmotor.hmi.aroundview.startAVMActivityMsg";

    // Action constants fired by the OEM AVM app (com.saicmotor.hmi.aroundview).
    // Verified from decompiled smali of AVMActivity (ACTION_360_START / ACTION_360_STOP fields).
    // Some variants also use the exported pre-launch broadcast below before AVMActivity becomes visible.
    private static final String ACTION_OEM_LAUNCH = "com.saicmotor.hmi.aroundview.startAVMActivity";
    private static final String ACTION_AVM_START = "com.saicmotor.hmi.aroundview.ACTION_START";
    private static final String ACTION_AVM_STOP = "com.saicmotor.hmi.aroundview.ACTION_STOP";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null || intent == null) {
            return;
        }
        String action = intent.getAction();
        if (action == null) {
            return;
        }
        if (!DashcamSettingsController.isOemCoexistActive(UiPrefs.getPrefs(context))) {
            return;
        }
        int startMessage = intent.getIntExtra(EXTRA_START_MESSAGE, Integer.MIN_VALUE);
        DevRuntimeLog.add("OemAvmReceiver", "received " + action
                + (startMessage == Integer.MIN_VALUE ? "" : " msg=" + startMessage));
        if (ACTION_OEM_LAUNCH.equals(action) || ACTION_AVM_START.equals(action)) {
            Log.i(TAG, "OEM AVM start/launch received: " + action);
            SignalService.setOemAvmActive(context, true);
            RecordingService.pauseForOemRequest(context);
            return;
        }
        if (ACTION_AVM_STOP.equals(action)) {
            Log.i(TAG, "OEM AVM stop received");
            SignalService.setOemAvmActive(context, false);
            RecordingService.resumeAfterOemRequest(context);
        }
    }
}
