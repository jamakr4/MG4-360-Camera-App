package com.drivehub.kamera.remote;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import com.drivehub.kamera.dashcam.DashcamStorageManager;
import java.util.concurrent.atomic.AtomicBoolean;

public final class TestUsbWriteReceiver extends BroadcastReceiver {
    private static final AtomicBoolean probing = new AtomicBoolean();

    @Override public void onReceive(Context context, Intent intent) {
        if (intent == null || !RemoteRecording.TEST_USB.equals(intent.getAction())) return;
        String requestId = RemoteRecording.string(intent, "request_id");
        if (!probing.compareAndSet(false, true)) {
            RemoteRecording.usbResult(context, new DashcamStorageManager.UsbDiagnostic("busy", null, false), requestId);
            return;
        }
        PendingResult pending = goAsync();
        Context app = context.getApplicationContext();
        Handler handler = new Handler(Looper.getMainLooper());
        AtomicBoolean finished = new AtomicBoolean();
        Runnable timeout = () -> {
            if (!finished.compareAndSet(false, true)) return;
            try {
                RemoteRecording.usbResult(app, new DashcamStorageManager.UsbDiagnostic("timeout", null, false), requestId);
            } finally { pending.finish(); }
        };
        handler.postDelayed(timeout, 8000);
        new Thread(() -> {
            DashcamStorageManager.UsbDiagnostic result;
            try { result = DashcamStorageManager.testUsbWrite(app); }
            catch (Exception e) { result = new DashcamStorageManager.UsbDiagnostic("error", null, false); }
            finally { probing.set(false); }
            if (!finished.compareAndSet(false, true)) return;
            handler.removeCallbacks(timeout);
            try { RemoteRecording.usbResult(app, result, requestId); }
            finally { pending.finish(); }
        }, "RemoteUsbWriteTest").start();
    }
}
