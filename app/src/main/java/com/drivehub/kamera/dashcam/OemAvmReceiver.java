package com.drivehub.kamera.dashcam;

import com.drivehub.kamera.dev.DevRuntimeLog;
import com.drivehub.kamera.signal.SignalService;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

public class OemAvmReceiver extends BroadcastReceiver {
    private static final String TAG = "OemAvmReceiver";

    private static final String ACTION_CAMERA_SHOW = "com.saicmotor.360camera.show";
    private static final String ACTION_CAMERA_CLOSE = "com.saicmotor.360camera.close";
    private static final String ACTION_OEM_STOP = "com.saicmotor.hmi.aroundview.mod.ACTION_STOP";

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
        if (ACTION_CAMERA_SHOW.equals(action)) {
            Log.i(TAG, "OEM AVM show request received");
            SignalService.setOemAvmActive(context, true);
            RecordingService.pauseForOemRequest(context);
            return;
        }
        if (ACTION_CAMERA_CLOSE.equals(action) || ACTION_OEM_STOP.equals(action)) {
            Log.i(TAG, "OEM AVM close/stop received: " + action);
            SignalService.setOemAvmActive(context, false);
            RecordingService.resumeAfterOemRequest(context);
        }
    }
}
