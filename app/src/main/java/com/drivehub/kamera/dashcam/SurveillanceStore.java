package com.drivehub.kamera.dashcam;

import android.content.Context;
import android.content.SharedPreferences;
import android.provider.Settings;
import com.drivehub.kamera.settings.UiPrefs;
import org.json.JSONObject;

/** Session and original toggle are committed together before temporarily changing the toggle. */
public final class SurveillanceStore {
    public static final String KEY_SESSION = "surveillanceSession";
    public static final String KEY_DIRECTORY = "surveillanceDirectory";
    private static final String KEY_ORIGINAL = "surveillanceOriginalEnabled";

    private SurveillanceStore() {}

    public static int bootCount(Context context) {
        try { return Settings.Global.getInt(context.getContentResolver(), Settings.Global.BOOT_COUNT, -1); }
        catch (RuntimeException error) { return -1; }
    }

    public static boolean isActive(SharedPreferences prefs) { return prefs.contains(KEY_SESSION); }

    public static SurveillanceSession read(Context context) {
        SharedPreferences prefs = UiPrefs.getPrefs(context);
        if (!isActive(prefs)) return null;
        try {
            JSONObject json = new JSONObject(prefs.getString(KEY_SESSION, ""));
            SurveillanceSession session = new SurveillanceSession(json.getLong("deadline"),
                    json.getInt("boot"), prefs.getBoolean(KEY_ORIGINAL, false),
                    SurveillancePolicy.policy(json.getString("policy")), json.getString("directory"),
                    json.optString("request_id", ""));
            session.device = json.optLong("device", -1);
            session.prepared = json.optBoolean("prepared", false);
            session.usingUsb = json.optBoolean("usb", false);
            session.stopRequestId = json.optString("stop_request_id", "");
            if (json.has("stop_reason")) session.stop(json.getString("stop_reason"),
                    json.optBoolean("failed", false), json.optBoolean("defer", false));
            return session;
        } catch (Exception e) {
            restore(context);
            return null;
        }
    }

    public static synchronized boolean save(Context context, SurveillanceSession session, boolean disableNormal) {
        try {
            JSONObject json = new JSONObject().put("deadline", session.deadline).put("boot", session.bootCount)
                    .put("policy", session.policy).put("directory", session.directory)
                    .put("request_id", session.requestId).put("device", session.device)
                    .put("prepared", session.prepared).put("usb", session.usingUsb)
                    .put("stop_request_id", session.stopRequestId);
            if (session.stopReason() != null) json.put("stop_reason", session.stopReason())
                    .put("failed", session.failed()).put("defer", session.deferRestart());
            SharedPreferences.Editor edit = UiPrefs.getPrefs(context).edit()
                    .putBoolean(KEY_ORIGINAL, session.originalEnabled).putString(KEY_SESSION, json.toString());
            if (disableNormal) edit.putBoolean(DashcamSettingsController.KEY_ENABLED, false);
            return edit.commit();
        } catch (Exception e) { return false; }
    }

    public static synchronized boolean restore(Context context) {
        SharedPreferences prefs = UiPrefs.getPrefs(context);
        if (!isActive(prefs)) return true;
        return prefs.edit().putBoolean(DashcamSettingsController.KEY_ENABLED, prefs.getBoolean(KEY_ORIGINAL, false))
                .remove(KEY_SESSION).remove(KEY_ORIGINAL).commit();
    }
}
