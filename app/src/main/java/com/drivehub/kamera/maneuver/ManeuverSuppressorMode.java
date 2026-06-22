package com.drivehub.kamera.maneuver;

import com.drivehub.kamera.R;

public enum ManeuverSuppressorMode {
    OFF("off", R.string.settings_dev_maneuver_suppressor_off,
            false, false, false, false),
    MEDIA_SESSION("media_session", R.string.settings_dev_maneuver_suppressor_media_session,
            true, false, false, false),
    BROADCAST_ABORT("broadcast_abort", R.string.settings_dev_maneuver_suppressor_broadcast_abort,
            false, true, false, false),
    ACTIVITY("activity", R.string.settings_dev_maneuver_suppressor_activity,
            false, false, true, false),
    SHELL("shell", R.string.settings_dev_maneuver_suppressor_shell,
            false, false, false, true),
    ACTIVITY_MEDIA("activity_media", R.string.settings_dev_maneuver_suppressor_activity_media,
            true, false, true, false),
    BROADCAST_ACTIVITY("broadcast_activity", R.string.settings_dev_maneuver_suppressor_broadcast_activity,
            false, true, true, false),
    ALL("all", R.string.settings_dev_maneuver_suppressor_all,
            true, true, true, true);

    public static final ManeuverSuppressorMode DEFAULT = ACTIVITY_MEDIA;

    private final String preferenceValue;
    private final int labelResId;
    private final boolean mediaSession;
    private final boolean broadcastAbort;
    private final boolean keyActivity;
    private final boolean shellCounteraction;

    ManeuverSuppressorMode(
            String preferenceValue,
            int labelResId,
            boolean mediaSession,
            boolean broadcastAbort,
            boolean keyActivity,
            boolean shellCounteraction
    ) {
        this.preferenceValue = preferenceValue;
        this.labelResId = labelResId;
        this.mediaSession = mediaSession;
        this.broadcastAbort = broadcastAbort;
        this.keyActivity = keyActivity;
        this.shellCounteraction = shellCounteraction;
    }

    public String preferenceValue() {
        return preferenceValue;
    }

    public int labelResId() {
        return labelResId;
    }

    boolean usesMediaSession() {
        return mediaSession;
    }

    boolean usesBroadcastAbort() {
        return broadcastAbort;
    }

    boolean usesKeyActivity() {
        return keyActivity;
    }

    boolean usesShellCounteraction() {
        return shellCounteraction;
    }

    public static ManeuverSuppressorMode fromPreferenceValue(String value) {
        if (value != null) {
            for (ManeuverSuppressorMode mode : values()) {
                if (mode.preferenceValue.equals(value)) {
                    return mode;
                }
            }
        }
        return DEFAULT;
    }
}
