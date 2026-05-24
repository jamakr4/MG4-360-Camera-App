package com.drivehub.kamera;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.View;

final class UiPrefs {

    static final String REC_PREFS_NAME = "rec_prefs";
    static final String KEY_TILE_CORNER_RADIUS = "tileCornerRadius";
    static final String KEY_ACCENT_COLOR = "accentColor";
    static final String KEY_ALLOW_BETA_UPDATES = "allowBetaUpdates";
    static final String KEY_OVERLAY_ON_SIGNAL = "overlayOnSignal";
    static final String KEY_OVERLAY_ROTATE_TO_DRIVING_DIRECTION = "overlayRotateToDrivingDirection";
    static final String KEY_OVERLAY_HIDE_DELAY_MS = "overlayHideDelayMs";
    static final String KEY_OVERLAY_MIN_SHOW_MS = "overlayMinShowMs";
    static final String KEY_DEV_DEFAULT_POLL_MS = "devDefaultPollMs";
    static final String KEY_DEV_SIGNAL_OFF_POLL_MS = "devSignalOffPollMs";
    static final int MAX_TILE_CORNER_RADIUS = 35;
    static final int MAX_OVERLAY_HIDE_DELAY_MS = 3000;
    static final int MAX_OVERLAY_MIN_SHOW_MS = 6000;
    static final int MIN_DEV_POLLING_MS = 20;
    static final int MAX_DEV_POLLING_MS = 5000;
    static final int OVERLAY_HIDE_DELAY_STEP_MS = 100;
    static final int OVERLAY_MIN_SHOW_STEP_MS = 100;
    private static final int DEFAULT_TILE_CORNER_RADIUS = 16;
    private static final int DEFAULT_OVERLAY_HIDE_DELAY_MS = 0;
    private static final int DEFAULT_OVERLAY_MIN_SHOW_MS = 3000;
    static final int DEFAULT_DEV_DEFAULT_POLLING_MS = 100;
    static final int DEFAULT_DEV_SIGNAL_OFF_POLLING_MS = 20;
    private static final String DEFAULT_ACCENT_COLOR = "#E7E7E7";

    private UiPrefs() {
    }

    static SharedPreferences getPrefs(Context context) {
        return context.getSharedPreferences(REC_PREFS_NAME, Context.MODE_PRIVATE);
    }

    static int getTileCornerRadiusSetting(SharedPreferences prefs) {
        return clampRadiusSetting(prefs.getInt(KEY_TILE_CORNER_RADIUS, DEFAULT_TILE_CORNER_RADIUS));
    }

    static boolean isBetaUpdatesEnabled(SharedPreferences prefs) {
        return prefs.getBoolean(KEY_ALLOW_BETA_UPDATES, false);
    }

    static int getOverlayHideDelayMs(SharedPreferences prefs) {
        long value = prefs.getLong(KEY_OVERLAY_HIDE_DELAY_MS, DEFAULT_OVERLAY_HIDE_DELAY_MS);
        return clampOverlayHideDelayMs((int) value);
    }

    static int getOverlayMinShowMs(SharedPreferences prefs) {
        long value = prefs.getLong(KEY_OVERLAY_MIN_SHOW_MS, DEFAULT_OVERLAY_MIN_SHOW_MS);
        return clampOverlayMinShowMs((int) value);
    }

    static boolean isOverlayOnSignalEnabled(SharedPreferences prefs) {
        return prefs.getBoolean(KEY_OVERLAY_ON_SIGNAL, true);
    }

    static boolean isOverlayRotationToDrivingDirectionEnabled(SharedPreferences prefs) {
        return prefs.getBoolean(KEY_OVERLAY_ROTATE_TO_DRIVING_DIRECTION, false);
    }

    static int getDevDefaultPollMs(SharedPreferences prefs) {
        return clampDevPollingMs(prefs.getInt(KEY_DEV_DEFAULT_POLL_MS, DEFAULT_DEV_DEFAULT_POLLING_MS));
    }

    static int getDevSignalOffPollMs(SharedPreferences prefs) {
        return clampDevPollingMs(prefs.getInt(KEY_DEV_SIGNAL_OFF_POLL_MS, DEFAULT_DEV_SIGNAL_OFF_POLLING_MS));
    }

    static float getCornerRadiusFraction(SharedPreferences prefs) {
        return clampRadiusSetting(getTileCornerRadiusSetting(prefs)) / (float) MAX_TILE_CORNER_RADIUS;
    }

    static float getCornerRadiusPx(View view, SharedPreferences prefs) {
        int minSize = Math.min(view.getWidth(), view.getHeight());
        return minSize * 0.5f * getCornerRadiusFraction(prefs);
    }

    static String getAccentColorSetting(SharedPreferences prefs) {
        return normalizeAccentColor(prefs.getString(KEY_ACCENT_COLOR, DEFAULT_ACCENT_COLOR));
    }

    static int getAccentColorInt(SharedPreferences prefs) {
        try {
            return android.graphics.Color.parseColor(getAccentColorSetting(prefs));
        } catch (IllegalArgumentException ignored) {
            return android.graphics.Color.parseColor(DEFAULT_ACCENT_COLOR);
        }
    }

    static String normalizeAccentColor(String value) {
        if (value == null) return DEFAULT_ACCENT_COLOR;
        String trimmed = value.trim().toUpperCase(java.util.Locale.US);
        if (trimmed.isEmpty()) return DEFAULT_ACCENT_COLOR;
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

    static Integer tryParseAccentColorOrNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim().toUpperCase(java.util.Locale.US);
        if (trimmed.isEmpty()) return null;
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

    static boolean isLightColor(int color) {
        double luminance = (
                (0.299 * android.graphics.Color.red(color)) +
                (0.587 * android.graphics.Color.green(color)) +
                (0.114 * android.graphics.Color.blue(color))
        ) / 255d;
        return luminance >= 0.72d;
    }

    private static int clampRadiusSetting(int value) {
        return Math.max(0, Math.min(MAX_TILE_CORNER_RADIUS, value));
    }

    static int clampOverlayHideDelayMs(int value) {
        return Math.max(0, Math.min(MAX_OVERLAY_HIDE_DELAY_MS, value));
    }

    static int clampOverlayMinShowMs(int value) {
        return Math.max(0, Math.min(MAX_OVERLAY_MIN_SHOW_MS, value));
    }

    static int clampDevPollingMs(int value) {
        return Math.max(MIN_DEV_POLLING_MS, Math.min(MAX_DEV_POLLING_MS, value));
    }
}
