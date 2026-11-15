package com.drivehub.kamera.signal;

import android.app.Service;
import android.content.Intent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.os.Binder;
import android.os.IBinder;

/** Synthetic endpoints for platform binding tests; no OEM code or vehicle access. */
public final class OemDataProbeDebugServices {
    private OemDataProbeDebugServices() {}
    public static final class NullService extends Service {
        @Override public IBinder onBind(Intent intent) { return null; }
    }
    public static final class BinderService extends Service {
        private final Binder endpoint = new Binder();
        @Override public IBinder onBind(Intent intent) { return endpoint; }
    }
    public static final class StartReceiver extends BroadcastReceiver {
        @Override public void onReceive(Context context, Intent intent) { }
    }
}
