package com.drivehub.kamera.remote;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import com.drivehub.kamera.dashcam.RecordingService;

/** A read-only snapshot: querying never starts or recovers the recording service. */
public final class QueryStateReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (intent != null && RemoteRecording.QUERY.equals(intent.getAction()))
            RecordingService.querySurveillanceState(context, RemoteRecording.string(intent, "request_id"));
    }
}
