package com.drivehub.kamera.signal;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.SystemClock;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.drivehub.kamera.MainActivity;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class OemDataServiceConnectionInstrumentedTest {
    private final Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
    private static void await(BooleanSupplier ready) {
        long deadline=SystemClock.elapsedRealtime()+4000;
        while(!ready.getAsBoolean()&&SystemClock.elapsedRealtime()<deadline)SystemClock.sleep(20);
        assertTrue("Service endpoint did not confirm its process",ready.getAsBoolean());
    }

    @Test public void realNullBinderAndBinderEndpointsProveRunningWithoutServiceEnumeration() {
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)) {
            for(Class<?> type:new Class<?>[]{OemDataProbeDebugServices.NullService.class,OemDataProbeDebugServices.BinderService.class}) {
                ComponentName component=new ComponentName(context,type);
                ComponentName receiver=new ComponentName(context,OemDataProbeDebugServices.StartReceiver.class);
                PackageManager packages=context.getPackageManager();int original=packages.getComponentEnabledSetting(receiver);
                OemDataServiceConnection probe=new OemDataServiceConnection(context,component,message->{});
                try {
                    probe.start();await(probe::running);
                    int pid=android.os.Process.myPid();
                    packages.setComponentEnabledSetting(receiver,PackageManager.COMPONENT_ENABLED_STATE_DISABLED,PackageManager.DONT_KILL_APP);
                    assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DISABLED,packages.getComponentEnabledSetting(receiver));
                    // The null endpoint must survive its periodic re-probe without a false outage.
                    SystemClock.sleep(1100);assertTrue(probe.running());await(probe::running);
                    assertEquals(pid,android.os.Process.myPid());
                } finally {
                    packages.setComponentEnabledSetting(receiver,original,PackageManager.DONT_KILL_APP);
                    probe.close();context.stopService(new Intent().setComponent(component));
                }
                assertFalse(probe.running());
            }
        }
    }

    private static final class Fake implements OemDataServiceConnection.Backend {
        ServiceConnection callback;
        int binds,unbinds,starts;
        boolean allow=true;
        public ComponentName start(ComponentName target){starts++;return target;}
        public boolean bind(ComponentName target,ServiceConnection connection){binds++;callback=connection;return allow;}
        public void unbind(ServiceConnection connection){unbinds++;}
    }

    @Test public void staleNullCallbacksCannotReviveAClosedOrReplacedConnection() {
        Fake platform=new Fake();AtomicLong now=new AtomicLong();ComponentName target=new ComponentName(context,OemDataProbeDebugServices.NullService.class);
        OemDataServiceConnection probe=new OemDataServiceConnection(platform,target,now::get,message->{});
        probe.start();ServiceConnection old=platform.callback;old.onNullBinding(target);assertTrue(probe.running());
        now.set(1000);assertTrue(probe.running());ServiceConnection current=platform.callback;assertNotSame(old,current);
        now.set(4000);old.onNullBinding(target);assertFalse("Old callback cannot refresh proof",probe.running());
        probe.close();current.onNullBinding(target);assertFalse(probe.running());
    }

    @Test public void rejectedBindingHasABoundedRetryBudgetAndDoesNotClaimRunning() {
        Fake platform=new Fake();platform.allow=false;AtomicLong now=new AtomicLong();
        ComponentName target=new ComponentName(context,OemDataProbeDebugServices.NullService.class);
        try(OemDataServiceConnection probe=new OemDataServiceConnection(platform,target,now::get,message->{})) {
            probe.start();
            for(int second=0;second<20;second++){now.set(second*1000L);assertFalse(probe.running());}
            assertEquals(1,platform.starts);assertEquals(3,platform.binds);
        }
    }
}
