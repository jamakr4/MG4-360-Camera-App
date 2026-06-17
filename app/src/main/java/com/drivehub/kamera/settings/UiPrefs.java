package com.drivehub.kamera.settings;

import com.drivehub.kamera.dashcam.DashcamSettingsController;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.View;

public final class UiPrefs {

    public static final String REC_PREFS_NAME = "rec_prefs";
    public static final String KEY_TILE_CORNER_RADIUS = "tileCornerRadius";
    public static final String KEY_ACCENT_COLOR = "accentColor";
    public static final String KEY_ALLOW_BETA_UPDATES = "allowBetaUpdates";
    public static final String KEY_OVERLAY_ON_SIGNAL = "overlayOnSignal";
    public static final String KEY_OVERLAY_ROTATE_TO_DRIVING_DIRECTION = "overlayRotateToDrivingDirection";
    public static final String KEY_OVERLAY_HIDE_DELAY_MS = "overlayHideDelayMs";
    public static final String KEY_OVERLAY_MIN_SHOW_MS = "overlayMinShowMs";
    public static final String KEY_DEV_OVERLAY_TOP_INSET_PX = "devOverlayTopInsetPx";
    public static final String KEY_DEV_FOREGROUND_MODE_POLL_MS = "devForegroundModePollMs";
    public static final String KEY_DEV_DEFAULT_POLL_MS = "devDefaultPollMs";
    public static final String KEY_DEV_SIGNAL_OFF_POLL_MS = "devSignalOffPollMs";
    public static final String KEY_DEV_OEM_AVM_MAX_SPEED_KMH = "devOemAvmMaxSpeedKmh";
    public static final String KEY_SAFETY_WARNING = "safetyWarning";
    public static final String KEY_OEM_AVM_ACTIVE = "oemAvmActive";
    public static final String KEY_DIGITAL_REARVIEW_ENABLED = "digitalRearviewEnabled";
    private static final String LEGACY_AVM_PREFS_NAME = "AVM_Settings";
    private static final String LEGACY_KEY_SAFETY_WARNING = "ShowSafetyWarning";
    public static final int MAX_TILE_CORNER_RADIUS = 35;
    public static final int MAX_OVERLAY_HIDE_DELAY_MS = 3000;
    public static final int MAX_OVERLAY_MIN_SHOW_MS = 6000;
    public static final int MIN_DEV_POLLING_MS = 20;
    public static final int MAX_DEV_POLLING_MS = 5000;
    public static final int MAX_DEV_OVERLAY_TOP_INSET_PX = 200;
    // 0 is intentionally allowed as a dev-only "standstill only" mode, which effectively
    // disables OEM AVM coexistence as soon as the car starts rolling.
    public static final int MIN_DEV_OEM_AVM_MAX_SPEED_KMH = 0;
    public static final int MAX_DEV_OEM_AVM_MAX_SPEED_KMH = 60;
    public static final int OVERLAY_HIDE_DELAY_STEP_MS = 100;
    public static final int OVERLAY_MIN_SHOW_STEP_MS = 100;
    private static final int DEFAULT_TILE_CORNER_RADIUS = 3;
    private static final int DEFAULT_OVERLAY_HIDE_DELAY_MS = 0;
    private static final int DEFAULT_OVERLAY_MIN_SHOW_MS = 3000;
    public static final int DEFAULT_DEV_OVERLAY_TOP_INSET_PX = 80;
    public static final int DEFAULT_DEV_FOREGROUND_MODE_POLL_MS = 1000;
    public static final int DEFAULT_DEV_DEFAULT_POLLING_MS = 100;
    public static final int DEFAULT_DEV_SIGNAL_OFF_POLLING_MS = 20;
    public static final int DEFAULT_DEV_OEM_AVM_MAX_SPEED_KMH = 20;
    private static final String DEFAULT_ACCENT_COLOR = "#E7E7E7";

    private UiPrefs() {
    }

    public static SharedPreferences getPrefs(Context context) {
        return context.getSharedPreferences(REC_PREFS_NAME, Context.MODE_PRIVATE);
    }

    public static int getTileCornerRadiusSetting(SharedPreferences prefs) {
        return clampRadiusSetting(prefs.getInt(KEY_TILE_CORNER_RADIUS, DEFAULT_TILE_CORNER_RADIUS));
    }

    public static boolean isBetaUpdatesEnabled(SharedPreferences prefs) {
        return prefs.getBoolean(KEY_ALLOW_BETA_UPDATES, false);
    }

    public static int getOverlayHideDelayMs(SharedPreferences prefs) {
        return clampOverlayHideDelayMs(
                readIntMigratingLegacyLong(prefs, KEY_OVERLAY_HIDE_DELAY_MS, DEFAULT_OVERLAY_HIDE_DELAY_MS)
        );
    }

    public static int getOverlayMinShowMs(SharedPreferences prefs) {
        return clampOverlayMinShowMs(
                readIntMigratingLegacyLong(prefs, KEY_OVERLAY_MIN_SHOW_MS, DEFAULT_OVERLAY_MIN_SHOW_MS)
        );
    }

    // v0.7.1 wrote these keys as Long; later versions use Int. Rewrite on first read.
    private static int readIntMigratingLegacyLong(SharedPreferences prefs, String key, int defaultValue) {
        try {
            return prefs.getInt(key, defaultValue);
        } catch (ClassCastException legacy) {
            int value = (int) prefs.getLong(key, defaultValue);
            prefs.edit().putInt(key, value).apply();
            return value;
        }
    }

    public static boolean isOverlayOnSignalEnabled(SharedPreferences prefs) {
        return prefs.getBoolean(KEY_OVERLAY_ON_SIGNAL, true);
    }

    public static boolean isDashcamEnabled(SharedPreferences prefs) {
        return prefs.getBoolean(DashcamSettingsController.KEY_ENABLED, false);
    }

    public static boolean isSafetyWarningEnabled(SharedPreferences prefs) {
        return prefs.getBoolean(KEY_SAFETY_WARNING, true);
    }

    public static void setSafetyWarningEnabled(SharedPreferences prefs, boolean enabled) {
        prefs.edit().putBoolean(KEY_SAFETY_WARNING, enabled).apply();
    }

    public static boolean isOemAvmActive(SharedPreferences prefs) {
        return prefs.getBoolean(KEY_OEM_AVM_ACTIVE, false);
    }

    public static void setOemAvmActive(SharedPreferences prefs, boolean active) {
        prefs.edit().putBoolean(KEY_OEM_AVM_ACTIVE, active).apply();
    }

    public static boolean isDigitalRearviewEnabled(SharedPreferences prefs) {
        return prefs.getBoolean(KEY_DIGITAL_REARVIEW_ENABLED, false);
    }

    /**
     * One-shot migration from the legacy AVM_Settings file. Safe to call on every
     * cold start —
     * only copies values when the new key is missing AND the legacy key exists.
     */
    public static void migrateLegacyPrefsIfNeeded(Context context) {
        SharedPreferences prefs = getPrefs(context);
        if (prefs.contains(KEY_SAFETY_WARNING))
            return;
        SharedPreferences legacy = context.getSharedPreferences(LEGACY_AVM_PREFS_NAME, Context.MODE_PRIVATE);
        if (!legacy.contains(LEGACY_KEY_SAFETY_WARNING))
            return;
        prefs.edit()
                .putBoolean(KEY_SAFETY_WARNING, legacy.getBoolean(LEGACY_KEY_SAFETY_WARNING, true))
                .apply();
    }

    public static boolean isOverlayRotationToDrivingDirectionEnabled(SharedPreferences prefs) {
        return prefs.getBoolean(KEY_OVERLAY_ROTATE_TO_DRIVING_DIRECTION, false);
    }

    public static int getDevDefaultPollMs(SharedPreferences prefs) {
        return clampDevPollingMs(prefs.getInt(KEY_DEV_DEFAULT_POLL_MS, DEFAULT_DEV_DEFAULT_POLLING_MS));
    }

    public static int getDevSignalOffPollMs(SharedPreferences prefs) {
        return clampDevPollingMs(prefs.getInt(KEY_DEV_SIGNAL_OFF_POLL_MS, DEFAULT_DEV_SIGNAL_OFF_POLLING_MS));
    }

    public static int getDevOverlayTopInsetPx(SharedPreferences prefs) {
        return clampDevOverlayTopInsetPx(
                prefs.getInt(KEY_DEV_OVERLAY_TOP_INSET_PX, DEFAULT_DEV_OVERLAY_TOP_INSET_PX));
    }

    public static int getDevForegroundModePollMs(SharedPreferences prefs) {
        return clampDevPollingMs(
                prefs.getInt(KEY_DEV_FOREGROUND_MODE_POLL_MS, DEFAULT_DEV_FOREGROUND_MODE_POLL_MS));
    }

    public static int getDevOemAvmMaxSpeedKmh(SharedPreferences prefs) {
        return clampDevOemAvmMaxSpeedKmh(
                prefs.getInt(KEY_DEV_OEM_AVM_MAX_SPEED_KMH, DEFAULT_DEV_OEM_AVM_MAX_SPEED_KMH));
    }

    public static float getCornerRadiusFraction(SharedPreferences prefs) {
        return clampRadiusSetting(getTileCornerRadiusSetting(prefs)) / (float) MAX_TILE_CORNER_RADIUS;
    }

    public static float getCornerRadiusPx(View view, SharedPreferences prefs) {
        int minSize = Math.min(view.getWidth(), view.getHeight());
        return minSize * 0.5f * getCornerRadiusFraction(prefs);
    }

    public static String getAccentColorSetting(SharedPreferences prefs) {
        return normalizeAccentColor(prefs.getString(KEY_ACCENT_COLOR, DEFAULT_ACCENT_COLOR));
    }

    public static int getAccentColorInt(SharedPreferences prefs) {
        try {
            return android.graphics.Color.parseColor(getAccentColorSetting(prefs));
        } catch (IllegalArgumentException ignored) {
            return android.graphics.Color.parseColor(DEFAULT_ACCENT_COLOR);
        }
    }

    public static String normalizeAccentColor(String value) {
        if (value == null)
            return DEFAULT_ACCENT_COLOR;
        String trimmed = value.trim().toUpperCase(java.util.Locale.US);
        if (trimmed.isEmpty())
            return DEFAULT_ACCENT_COLOR;
        if (!trimmed.startsWith("#")) {
            trimmed = "#" + trimmed;
        }
        if (trimmed.matches("^#[0-9A-F]{6}$")) {
            return trimmed;
        }
        if (trimmed.matches("^#[0-9A-F]{8}$")) {
            return trimmed;
        }
        return DEFAULT_ACCENT_COLOR;
    }

    public static Integer tryParseAccentColorOrNull(String value) {
        if (value == null)
            return null;
        String trimmed = value.trim().toUpperCase(java.util.Locale.US);
        if (trimmed.isEmpty())
            return null;
        if (!trimmed.startsWith("#")) {
            trimmed = "#" + trimmed;
        }
        if (!trimmed.matches("^#[0-9A-F]{6}$") && !trimmed.matches("^#[0-9A-F]{8}$")) {
            return null;
        }
        try {
            return android.graphics.Color.parseColor(trimmed);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    public static boolean isLightColor(int color) {
        double luminance = ((0.299 * android.graphics.Color.red(color)) +
                (0.587 * android.graphics.Color.green(color)) +
                (0.114 * android.graphics.Color.blue(color))) / 255d;
        return luminance >= 0.72d;
    }

    private static int clampRadiusSetting(int value) {
        return Math.max(0, Math.min(MAX_TILE_CORNER_RADIUS, value));
    }

    public static int clampOverlayHideDelayMs(int value) {
        return Math.max(0, Math.min(MAX_OVERLAY_HIDE_DELAY_MS, value));
    }

    public static int clampOverlayMinShowMs(int value) {
        return Math.max(0, Math.min(MAX_OVERLAY_MIN_SHOW_MS, value));
    }

    public static int clampDevPollingMs(int value) {
        return Math.max(MIN_DEV_POLLING_MS, Math.min(MAX_DEV_POLLING_MS, value));
    }

    public static int clampDevOverlayTopInsetPx(int value) {
        return Math.max(0, Math.min(MAX_DEV_OVERLAY_TOP_INSET_PX, value));
    }

    public static int clampDevOemAvmMaxSpeedKmh(int value) {
        return Math.max(MIN_DEV_OEM_AVM_MAX_SPEED_KMH, Math.min(MAX_DEV_OEM_AVM_MAX_SPEED_KMH, value));
    }
}
