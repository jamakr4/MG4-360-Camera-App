package com.drivehub.kamera.signal;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.drivehub.kamera.R;
import com.drivehub.kamera.dashcam.DashcamSettingsController;
import com.drivehub.kamera.settings.UiPrefs;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class DigitalRearviewViewTriggerInstrumentedTest {
    private static final String ACTION=DigitalRearviewViewTriggerReceiver.ACTION_SET_DIGITAL_REARVIEW_IPK_VIEW;
    private static final String KEY=UiPrefs.KEY_DIGITAL_REARVIEW_IPK_NORMAL;
    private final Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
    private SharedPreferences prefs;
    private Map<String,?> saved;

    @Before public void prepare() {
        prefs=UiPrefs.getPrefs(context);saved=prefs.getAll();assertTrue(prefs.edit().remove(KEY).commit());
    }
    @After public void restore() {
        SharedPreferences.Editor edit=prefs.edit();Object value=saved.get(KEY);
        if(value instanceof Boolean)edit.putBoolean(KEY,(Boolean)value);else edit.remove(KEY);
        assertTrue(edit.commit());
    }
    @Test public void exportedReceiverIsLabelledAndDiscoverableAsAnMg4ControlAppFunction() throws Exception {
        PackageManager pm=context.getPackageManager();ComponentName component=new ComponentName(context,DigitalRearviewViewTriggerReceiver.class);
        ActivityInfo info=pm.getReceiverInfo(component,0);
        assertTrue(info.enabled);assertTrue(info.exported);assertNull(info.permission);
        assertEquals(context.getString(R.string.shortcut_toggle_digital_rearview_view),info.loadLabel(pm).toString());
        for(String action:new String[]{ACTION,"com.mg4.control.action.SHORTCUT"}) {
            assertTrue(action,pm.queryBroadcastReceivers(new Intent(action).setPackage(context.getPackageName()),
                    PackageManager.GET_RESOLVED_FILTER).stream().anyMatch(result->component.getClassName().equals(result.activityInfo.name)));
        }
        assertFalse(UiPrefs.isDigitalRearviewIpkNormal(prefs));
    }
    @Test public void manifestBroadcastWithoutExtrasTogglesTheSavedViewOnly() throws Exception {
        assertFalse(UiPrefs.isDigitalRearviewIpkNormal(prefs));
        send(new Intent(ACTION));assertTrue(UiPrefs.isDigitalRearviewIpkNormal(prefs));assertOtherSettingsUnchanged();
        send(new Intent(ACTION));assertFalse(UiPrefs.isDigitalRearviewIpkNormal(prefs));assertOtherSettingsUnchanged();
    }
    @Test public void explicitNormalAndWideSelectionsAreIdempotent() throws Exception {
        for(boolean normal:new boolean[]{true,true,false,false}) {
            send(new Intent(ACTION).putExtra(DigitalRearviewViewTriggerReceiver.EXTRA_NORMAL,normal));
            assertEquals(normal,UiPrefs.isDigitalRearviewIpkNormal(prefs));assertOtherSettingsUnchanged();
        }
    }
    @Test public void discoveryWrongAndMissingActionsAreIgnoredAndTogglingStartsNoServiceOrActivity() {
        DigitalRearviewViewTriggerReceiver receiver=new DigitalRearviewViewTriggerReceiver();
        Context noLaunch=new ContextWrapper(context) {
            @Override public ComponentName startService(Intent intent){throw new AssertionError("View trigger must not start a service");}
            @Override public ComponentName startForegroundService(Intent intent){throw new AssertionError("View trigger must not start a service");}
            @Override public void startActivity(Intent intent){throw new AssertionError("View trigger must not open an activity");}
        };
        receiver.onReceive(null,new Intent(ACTION));receiver.onReceive(noLaunch,null);
        for(String action:new String[]{null,"wrong_action","com.mg4.control.action.SHORTCUT"})
            receiver.onReceive(noLaunch,new Intent().setAction(action).putExtra(DigitalRearviewViewTriggerReceiver.EXTRA_NORMAL,true));
        assertFalse(UiPrefs.isDigitalRearviewIpkNormal(prefs));assertFalse(prefs.contains(KEY));
        receiver.onReceive(noLaunch,new Intent(ACTION));assertTrue(UiPrefs.isDigitalRearviewIpkNormal(prefs));
        assertOtherSettingsUnchanged();
    }
    private void assertOtherSettingsUnchanged() {
        Map<String,?> current=prefs.getAll();
        for(String key:new String[]{UiPrefs.KEY_DIGITAL_REARVIEW_ENABLED,UiPrefs.KEY_DIGITAL_REARVIEW_DISPLAY_TARGET,
                UiPrefs.KEY_DIGITAL_REARVIEW_IPK_SCREEN,DashcamSettingsController.KEY_ENABLED})
            assertEquals(key,saved.get(key),current.get(key));
    }
    private void send(Intent intent)throws Exception {
        CountDownLatch received=new CountDownLatch(1);
        context.sendOrderedBroadcast(intent.setComponent(new ComponentName(context,DigitalRearviewViewTriggerReceiver.class)),null,
                new BroadcastReceiver(){@Override public void onReceive(Context context,Intent intent){received.countDown();}},
                new Handler(Looper.getMainLooper()),Activity.RESULT_OK,null,null);
        assertTrue("Manifest receiver did not complete",received.await(5,TimeUnit.SECONDS));
    }
}
