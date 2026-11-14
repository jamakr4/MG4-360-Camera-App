package com.drivehub.kamera.remote;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public final class StartContinuousReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (intent != null && RemoteRecording.START.equals(intent.getAction()))
            RemoteRecording.dispatch(context, intent);
    }
}
