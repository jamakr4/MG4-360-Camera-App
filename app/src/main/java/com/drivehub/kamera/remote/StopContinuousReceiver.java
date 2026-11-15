package com.drivehub.kamera.remote;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public final class StopContinuousReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (intent != null && RemoteRecording.STOP.equals(intent.getAction()))
            RemoteRecording.dispatch(context, intent);
    }
}
