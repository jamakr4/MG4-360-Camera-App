package com.drivehub.kamera.signal;

import android.app.ActivityManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.SystemClock;
import androidx.core.content.ContextCompat;
import com.drivehub.kamera.MainActivity;
import com.drivehub.kamera.dev.DevRuntimeLog;
import com.drivehub.kamera.settings.UiPrefs;
import com.drivehub.kamera.steering.GearSnapshot;
import java.lang.ref.WeakReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Predicate;

/** Task inspection uses the existing vehicle worker; decisions/lifecycle stay on the main thread. */
final class ReverseForegroundController {
    private static final long POLL_MS=250, MAX_AGE_MS=500;
    private final Context context;
    private final Handler main;
    private final BooleanSupplier cameraAllowed;
    private final Predicate<Runnable> worker;
    private final Consumer<String> abandoned;
    private final ReverseForegroundPolicy policy=new ReverseForegroundPolicy();
    private WeakReference<MainActivity> activity=new WeakReference<>(null);
    private GearSnapshot gear=GearSnapshot.EMPTY;
    private boolean resumed,closed,reopenFromRecents;
    private volatile boolean monitoring;
    private volatile long generation;
    private final java.util.concurrent.atomic.AtomicBoolean samplePending=new java.util.concurrent.atomic.AtomicBoolean();
    private long lastReadMs=-1; // Vehicle worker only.
    private Snapshot foreground=Snapshot.unknown();
    private volatile String diagnostic="R foreground: idle";
    private String lastLog="";
    private final BroadcastReceiver home=new BroadcastReceiver(){
        @Override public void onReceive(Context ignored,Intent intent){
            if(!Intent.ACTION_CLOSE_SYSTEM_DIALOGS.equals(intent.getAction()))return;
            String reason=intent.getStringExtra("reason");
            if("homekey".equals(reason)){reopenFromRecents=false;dismiss("Home");}
            else if("recentapps".equals(reason))reopenFromRecents=true;
        }
    };
    ReverseForegroundController(Context context,Handler main,BooleanSupplier cameraAllowed,
                                Predicate<Runnable> worker,Consumer<String> abandoned) {
        this.context=context;this.main=main;this.cameraAllowed=cameraAllowed;this.worker=worker;this.abandoned=abandoned;
        ContextCompat.registerReceiver(context,home,new IntentFilter(Intent.ACTION_CLOSE_SYSTEM_DIALOGS),ContextCompat.RECEIVER_EXPORTED);
    }
    private void configure() {
        long now=SystemClock.elapsedRealtime();
        int fresh=now>=gear.readElapsedMs&&now-gear.readElapsedMs<=GearSnapshot.MAX_AGE_MS?gear.gear:0;
        boolean hadAnchor=policy.anchored(), hidden=policy.pausedCamera();
        policy.configure(UiPrefs.isParkingReverseForegroundEnabled(UiPrefs.getPrefs(context)),fresh,cameraAllowed.getAsBoolean());
        if(hadAnchor&&!policy.anchored()&&hidden)abandoned.accept("R foreground ended");
    }
    void vehicleSample(GearSnapshot sample) {
        if(closed)return;
        gear=sample;configure();
        MainActivity current=activity.get();
        if(resumed&&current!=null&&!current.isFinishing())policy.cameraVisible(current.getTaskId(),false,SystemClock.elapsedRealtime());
        evaluate();
    }
    void cameraResumed(MainActivity current,boolean userOpen) {
        if(closed)return;
        activity=new WeakReference<>(current);resumed=true;configure();
        policy.cameraVisible(current.getTaskId(),userOpen||reopenFromRecents,SystemClock.elapsedRealtime());
        reopenFromRecents=false;evaluate();
    }
    void cameraPaused(MainActivity current) {
        if(activity.get()!=current)return;
        resumed=false;foreground=Snapshot.unknown();
        if(current.isFinishing())dismiss("camera finished");
        else {policy.cameraPaused(current.getTaskId());evaluate();}
    }
    boolean retainSession(MainActivity current) {
        configure();
        return !current.isFinishing()&&policy.anchored()&&policy.task()==current.getTaskId();
    }
    void dismiss(String reason) {
        if(closed||!policy.anchored())return;
        policy.dismiss(reason);abandoned.accept("R foreground: "+reason);publish();
    }
    String diagnostic(){return diagnostic;}

    /** Called on the existing OEM lifecycle worker, never from a display callback. */
    void sampleOnWorker() {
        long now=SystemClock.elapsedRealtime();
        if(!monitoring||lastReadMs>=0&&now-lastReadMs<POLL_MS||!samplePending.compareAndSet(false,true))return;
        lastReadMs=now;long token=generation;
        Snapshot sample=readForeground();
        main.post(()->{
            try {
                if(closed||token!=policy.generation())return;
                foreground=sample;configure();evaluate();
            } finally {samplePending.set(false);}
        });
    }
    private Snapshot readForeground() {
        try {
            ActivityManager am=(ActivityManager)context.getSystemService(Context.ACTIVITY_SERVICE);
            java.util.List<ActivityManager.RunningTaskInfo> tasks=am==null?null:am.getRunningTasks(1);
            if(tasks!=null&&!tasks.isEmpty()&&tasks.get(0).topActivity!=null) {
                ActivityManager.RunningTaskInfo task=tasks.get(0);
                return new Snapshot(task.id,task.topActivity,SystemClock.elapsedRealtime());
            }
        }catch(RuntimeException ignored){ /* Unknown state cannot authorize a task switch. */ }
        return Snapshot.unknown();
    }
    private void evaluate() {
        long now=SystemClock.elapsedRealtime();
        ReverseForegroundPolicy.Foreground kind=foreground.kind(context.getPackageName(),now);
        boolean wasExhausted=policy.exhausted();
        boolean move=policy.update(kind,foreground.description(),now);
        if(!wasExhausted&&policy.exhausted())abandoned.accept("R foreground retry limit");
        publish();
        if(move)requestReturn(policy.task(),policy.generation());
    }
    private void requestReturn(int taskId,long token) {
        if(!worker.test(()->{
            ActivityManager.AppTask target=null;
            try {
                ActivityManager am=(ActivityManager)context.getSystemService(Context.ACTIVITY_SERVICE);
                if(am!=null)for(ActivityManager.AppTask candidate:am.getAppTasks()) {
                    ActivityManager.RecentTaskInfo info=candidate.getTaskInfo();
                    if(info.id==taskId&&info.topActivity!=null
                            &&context.getPackageName().equals(info.topActivity.getPackageName())
                            &&MainActivity.class.getName().equals(info.topActivity.getClassName())){target=candidate;break;}
                }
            }catch(RuntimeException ignored){ }
            ActivityManager.AppTask found=target;Snapshot latest=readForeground();
            main.post(()->{
                if(closed)return;
                configure();
                if(!policy.dispatchAllowed(token)){publish();return;}
                if(found==null){policy.abandon("paused: camera task unavailable");abandoned.accept("R foreground task unavailable");publish();return;}
                foreground=latest;
                if(latest.kind(context.getPackageName(),SystemClock.elapsedRealtime())!=ReverseForegroundPolicy.Foreground.FOREIGN){evaluate();return;}
                try {found.moveToFront();}
                catch(RuntimeException error){policy.requestFailed();DevRuntimeLog.add("R foreground","Task return rejected: "+error);}
                publish();
            });
        })) {policy.requestFailed();publish();}
    }
    private void publish() {
        generation=policy.generation();monitoring=!closed&&policy.monitor();
        diagnostic="R foreground: "+policy.status()+"; top="+foreground.description();
        if(!diagnostic.equals(lastLog)){lastLog=diagnostic;DevRuntimeLog.add("R foreground",diagnostic);}
    }
    void close() {
        closed=true;monitoring=false;policy.reset();activity.clear();
        context.unregisterReceiver(home);
    }
    private static final class Snapshot {
        final int task;final ComponentName component;final long at;
        Snapshot(int task,ComponentName component,long at){this.task=task;this.component=component;this.at=at;}
        static Snapshot unknown(){return new Snapshot(-1,null,0);}
        String description(){return component==null?"unknown":task+":"+component.flattenToShortString();}
        ReverseForegroundPolicy.Foreground kind(String own,long now) {
            if(component==null||now<at||now-at>MAX_AGE_MS)return ReverseForegroundPolicy.Foreground.UNKNOWN;
            if(ReverseForegroundPolicy.protectedComponent(component.getPackageName(),component.getClassName()))return ReverseForegroundPolicy.Foreground.PROTECTED;
            return own.equals(component.getPackageName())?ReverseForegroundPolicy.Foreground.OWN:ReverseForegroundPolicy.Foreground.FOREIGN;
        }
    }
}
