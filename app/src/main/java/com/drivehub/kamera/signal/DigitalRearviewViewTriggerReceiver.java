package com.drivehub.kamera.signal;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import com.drivehub.kamera.dev.DevRuntimeLog;
import com.drivehub.kamera.settings.UiPrefs;

/** External IPK view selection; the existing IPK preference listener applies it live. */
public final class DigitalRearviewViewTriggerReceiver extends BroadcastReceiver {
    public static final String ACTION_SET_DIGITAL_REARVIEW_IPK_VIEW=
            "com.drivehub.kamera.action.SET_DIGITAL_REARVIEW_IPK_VIEW";
    public static final String EXTRA_NORMAL="normal";

    @Override public void onReceive(Context context,Intent intent) {
        if(context==null||intent==null||!ACTION_SET_DIGITAL_REARVIEW_IPK_VIEW.equals(intent.getAction()))return;
        SharedPreferences prefs=UiPrefs.getPrefs(context);
        boolean normal=intent.hasExtra(EXTRA_NORMAL)?intent.getBooleanExtra(EXTRA_NORMAL,false)
                :!UiPrefs.isDigitalRearviewIpkNormal(prefs);
        UiPrefs.setDigitalRearviewIpkNormal(prefs,normal);
        DevRuntimeLog.add("DigitalRearviewViewTriggerReceiver","external trigger updated IPK view="+(normal?"Normal":"Wide"));
    }
}
