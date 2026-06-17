package com.drivehub.kamera.helper.app;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;

import androidx.annotation.StringRes;

/**
 * One-shot notification channel registration.
 * <p>
 * Idempotent – Android's {@code createNotificationChannel} is a no-op if the
 * channel already exists, so calling this on every service startup is safe.
 */
public final class NotificationChannelHelper {

    private NotificationChannelHelper() {
    }

    /**
     * Ensures a low-importance notification channel exists for the given id and
     * human-readable name resource.
     */
    public static void ensureChannel(Context context, String channelId, @StringRes int nameResId) {
        ensureChannel(context, channelId, context.getString(nameResId));
    }

    /**
     * Ensures a low-importance notification channel exists for the given id and
     * human-readable name string.
     */
    public static void ensureChannel(Context context, String channelId, String name) {
        NotificationChannel ch = new NotificationChannel(
                channelId, name, NotificationManager.IMPORTANCE_LOW);
        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.createNotificationChannel(ch);
        }
    }
}
