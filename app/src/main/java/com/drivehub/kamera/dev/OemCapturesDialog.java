package com.drivehub.kamera.dev;

import com.drivehub.kamera.R;
import com.drivehub.kamera.dashcam.DashcamStorageManager;
import com.drivehub.kamera.ui.AppSystemBars;

/** Read/export tools available from Engineering Mode. */
public final class OemCapturesDialog {
    private OemCapturesDialog() {}

    public static void show(android.app.Activity activity) {
        android.os.Handler mainHandler = new android.os.Handler(android.os.Looper.getMainLooper());
        new Thread(() -> {
            String report = com.drivehub.kamera.dev.OemCaptures.describe();
            mainHandler.post(() -> {
                if (activity.isFinishing() || activity.isDestroyed()) return;
                AppSystemBars.showDialog(new androidx.appcompat.app.AlertDialog.Builder(activity)
                    .setTitle(R.string.dashcam_oem_captures)
                    .setMessage(report)
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(R.string.dashcam_oem_export, (dialog, which) -> {
                        new Thread(() -> {
                            DashcamStorageManager.Resolution resolution = DashcamStorageManager.resolve(activity);
                            String result = resolution.baseDir == null ? "Storage unavailable"
                                    : com.drivehub.kamera.dev.OemCaptures.copyTo(resolution.baseDir);
                            mainHandler.post(() -> {
                                if (activity.isFinishing() || activity.isDestroyed()) return;
                                AppSystemBars.showDialog(new androidx.appcompat.app.AlertDialog.Builder(activity)
                                        .setMessage(result).setPositiveButton(android.R.string.ok, null).create());
                            });
                        }, "OemCaptureExport").start();
                    }).create());
            });
        }, "OemCaptureList").start();
    }

}
