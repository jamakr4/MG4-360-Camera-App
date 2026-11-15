package com.drivehub.kamera.remote;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class RemoteRecordingContractTest {
    private final Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();

    @Test public void receiversArePublicLabelledAndDiscoverableWithoutSignaturePermission() throws Exception {
        for (Class<?> receiver : new Class<?>[]{StartContinuousReceiver.class, StopContinuousReceiver.class,
                QueryStateReceiver.class, TestUsbWriteReceiver.class}) {
            ActivityInfo info = target.getPackageManager().getReceiverInfo(new ComponentName(target, receiver), 0);
            assertTrue(info.exported);
            assertNull(info.permission);
            assertTrue(info.loadLabel(target.getPackageManager()).length() > 0);
            assertTrue(target.getPackageManager().queryBroadcastReceivers(new Intent("com.mg4.control.action.SHORTCUT")
                    .setComponent(new ComponentName(target, receiver)), PackageManager.GET_RESOLVED_FILTER).size() == 1);
        }
    }

    @Test public void discoveryAndWrongActionsNeverExecuteCommands() {
        Capture context = new Capture();
        for (BroadcastReceiver receiver : new BroadcastReceiver[]{new StartContinuousReceiver(), new StopContinuousReceiver(),
                new QueryStateReceiver(), new TestUsbWriteReceiver()}) {
            receiver.onReceive(context, null);
            receiver.onReceive(context, new Intent("com.mg4.control.action.SHORTCUT"));
            receiver.onReceive(context, new Intent("wrong_action"));
        }
        assertNull(context.service);
        assertNull(context.result);
    }

    @Test public void startForwardsStringExtrasToForegroundServiceWithoutAnActivity() {
        Capture context = new Capture();
        new StartContinuousReceiver().onReceive(context, new Intent(RemoteRecording.START)
                .putExtra("max_minutes", " 12 ").putExtra("directory", "/storage/USB/surveillance")
                .putExtra("storage_policy", "stop").putExtra("request_id", "start-1"));
        assertEquals(RemoteRecording.START, context.service.getAction());
        assertEquals(" 12 ", context.service.getStringExtra("max_minutes"));
        assertEquals("stop", context.service.getStringExtra("storage_policy"));
        assertEquals("/storage/USB/surveillance", context.service.getStringExtra("directory"));
        assertEquals("start-1", context.service.getStringExtra("request_id"));
    }

    @Test public void stopCanBeDeliveredToAColdService() {
        Capture context = new Capture();
        new StopContinuousReceiver().onReceive(context, new Intent(RemoteRecording.STOP));
        assertEquals(RemoteRecording.STOP, context.service.getAction());
    }

    @Test public void rejectedForegroundStartReturnsCorrelatedError() {
        Capture context = new Capture();
        context.reject = true;
        new StartContinuousReceiver().onReceive(context, new Intent(RemoteRecording.START).putExtra("request_id", "denied-1"));
        assertEquals(RemoteRecording.STATE, context.result.getAction());
        assertEquals("error", context.result.getStringExtra("state"));
        assertEquals("foreground_start_rejected", context.result.getStringExtra("reason"));
        assertEquals("denied-1", context.result.getStringExtra("request_id"));
        assertEquals(-1, context.result.getLongExtra("free_bytes", 0));
        assertEquals("unknown", context.result.getStringExtra("storage_level"));
    }

    private final class Capture extends ContextWrapper {
        Intent service, result;
        boolean reject;
        Capture() { super(target); }
        @Override public ComponentName startForegroundService(Intent intent) {
            if (reject) throw new SecurityException("test denial");
            service = intent;
            return intent.getComponent();
        }
        @Override public void sendBroadcast(Intent intent) { result = intent; }
        @Override public void startActivity(Intent intent) { fail("Remote commands must not open an activity"); }
    }
}
