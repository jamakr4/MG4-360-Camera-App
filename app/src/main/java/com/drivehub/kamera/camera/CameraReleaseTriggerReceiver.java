package com.drivehub.kamera.camera;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public final class CameraReleaseTriggerReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (intent == null || !CameraHandoffController.ACTION_RELEASE.equals(intent.getAction())) return;
        boolean ordered = isOrderedBroadcast();
        CameraHandoffController.request(context, true, ordered, goAsync());
    }
}
