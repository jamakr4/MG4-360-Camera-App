package com.drivehub.kamera.remote;

import android.content.Context;
import android.content.Intent;
import com.drivehub.kamera.dashcam.DashcamStorageManager;
import com.drivehub.kamera.dashcam.RecordingService;
import com.drivehub.kamera.dashcam.SurveillancePolicy;
import com.drivehub.kamera.dev.DevRuntimeLog;

/** Public, string-friendly contract for MG4Control, Tasker and adb. */
public final class RemoteRecording {
    public static final String PREFIX = "com.drivehub.kamera.action.";
    public static final String START = PREFIX + "START_CONTINUOUS";
    public static final String STOP = PREFIX + "STOP_CONTINUOUS";
    public static final String QUERY = PREFIX + "QUERY_STATE";
    public static final String TEST_USB = PREFIX + "TEST_USB_WRITE";
    public static final String STATE = PREFIX + "RECORDING_STATE";
    public static final String USB_RESULT = PREFIX + "USB_WRITE_TEST_RESULT";
    public static final String RECOVER = "recover_surveillance";

    private RemoteRecording() {}

    public static String string(Intent intent, String name) {
        try {
            String value = intent.getStringExtra(name);
            return value == null ? "" : value;
        } catch (RuntimeException e) { return ""; }
    }

    static void dispatch(Context context, Intent command) {
        Intent service = new Intent(context, RecordingService.class).setAction(command.getAction());
        for (String key : new String[]{"max_minutes", "directory", "storage_policy", "request_id"})
            service.putExtra(key, string(command, key));
        try { context.startForegroundService(service); }
        catch (RuntimeException e) {
            DevRuntimeLog.add("RemoteRecording", "foreground start rejected: " + e);
            state(context, "error", "foreground_start_rejected", "", -1, string(command, "request_id"));
        }
    }

    public static void state(Context context, String state, String reason, String directory,
                             long freeBytes, String requestId) {
        Intent result = new Intent(STATE).putExtra("state", state).putExtra("reason", reason)
                .putExtra("directory", directory).putExtra("free_bytes", freeBytes)
                .putExtra("storage_level", SurveillancePolicy.storageLevel(freeBytes));
        if (requestId != null && !requestId.isEmpty()) result.putExtra("request_id", requestId);
        context.sendBroadcast(result);
    }

    static void usbResult(Context context, DashcamStorageManager.UsbDiagnostic diagnostic, String requestId) {
        Intent result = new Intent(USB_RESULT).putExtra("status", diagnostic.status)
                .putExtra("writable", diagnostic.writable).putExtra("free_bytes", diagnostic.freeBytes)
                .putExtra("total_bytes", diagnostic.totalBytes)
                .putExtra("directory", diagnostic.directory == null ? "" : diagnostic.directory.getAbsolutePath())
                .putExtra("storage_level", SurveillancePolicy.storageLevel(diagnostic.freeBytes));
        if (!requestId.isEmpty()) result.putExtra("request_id", requestId);
        context.sendBroadcast(result);
    }
}
