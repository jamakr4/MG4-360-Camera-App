package com.drivehub.kamera.dashcam;

import com.drivehub.kamera.dev.DevRuntimeLog;
import com.drivehub.kamera.signal.SignalService;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

public class OemAvmReceiver extends BroadcastReceiver {
    private static final String TAG = "OemAvmReceiver";

    // Action constants fired by the OEM AVM app (com.saicmotor.hmi.aroundview).
    // Verified from decompiled smali of AVMActivity (ACTION_360_START / ACTION_360_STOP fields).
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
        DevRuntimeLog.add("OemAvmReceiver", "received " + action);
        if (ACTION_AVM_START.equals(action)) {
            Log.i(TAG, "OEM AVM start received");
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
