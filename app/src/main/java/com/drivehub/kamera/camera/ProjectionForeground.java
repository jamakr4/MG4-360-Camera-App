package com.drivehub.kamera.camera;

import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Context;

/** Detects projection apps using the same privileged task access as the camera overlay. */
public final class ProjectionForeground {
    public static final int LAUNCHER_BAR_WIDTH_PX = 142;

    private ProjectionForeground() {}

    public static boolean isActive(Context context) {
        try {
            ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            if (am == null) return false;
            java.util.List<ActivityManager.RunningTaskInfo> tasks = am.getRunningTasks(1);
            if (tasks == null || tasks.isEmpty()) return false;
            ComponentName top = tasks.get(0).topActivity;
            if (top == null) return false;
            String name = top.getPackageName();
            return "com.allgo.app.androidauto".equals(name)
                    || "com.allgo.remoteui.mediabrowserservice".equals(name);
        } catch (RuntimeException ignored) {
            return false;
        }
    }
}
