package com.drivehub.kamera.side;

import android.content.Context;
import android.content.SharedPreferences;
import com.drivehub.kamera.birdseye.BirdseyeCalibration;
import com.drivehub.kamera.birdseye.CalibrationStore;
import com.drivehub.kamera.dev.DevRuntimeLog;
import com.drivehub.kamera.settings.UiPrefs;
import java.io.IOException;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.*;

/** Warmup prepares maps; only the visible session may activate them. */
public final class AdvancedWheelProjection implements AutoCloseable {
    interface Access {
        BirdseyeCalibration load()throws IOException;
        void session(int signedToken);
        boolean maps(int token,String key,float[][] maps);
    }
    private static final AtomicInteger NEXT=new AtomicInteger();
    private static final ScheduledThreadPoolExecutor IO=new ScheduledThreadPoolExecutor(1,r->{Thread t=new Thread(r,"Curbside-lens-maps");t.setDaemon(true);return t;});
    static{IO.setRemoveOnCancelPolicy(true);}
    private final Access access;
    private final Function<BirdseyeCalibration,float[][]> factory;
    private final LongSupplier clock;
    private final Consumer<String> log;
    private final long intervalMs;
    private ScheduledFuture<?> task;
    private volatile int token;
    private volatile boolean ready;
    private boolean closed;
    private String status="";
    private long failureSince=-1;
    static boolean requested(SharedPreferences prefs) {
        if (!prefs.getBoolean(UiPrefs.KEY_MAIN_SIDE_CAMERA_WHEELS, false)) return UiPrefs.isParkingSideCorrectionEnabled(prefs);
        return UiPrefs.getParkingWheelMode(prefs, true) == WheelAlignmentMode.ADVANCED
                || UiPrefs.getParkingWheelMode(prefs, false) == WheelAlignmentMode.ADVANCED;
    }

    static AdvancedWheelProjection forView(Context context) {
        CalibrationStore store = CalibrationStore.get(context);
        WheelLensMapCache disk = new WheelLensMapCache(CalibrationStore.directory(context));
        return new AdvancedWheelProjection(new Access() {
            public BirdseyeCalibration load() throws IOException { return store.loadVehicle(); }
            public void session(int token) { SideCameraNative.setAdvancedSession(token); }
            public boolean maps(int token, String key, float[][] maps) { return SideCameraNative.maps(token, key, maps); }
        }, calibration -> WheelLensMaps.get(calibration, disk, IO), () -> System.nanoTime() / 1_000_000,
                message -> DevRuntimeLog.add("Curbside", message), 1000);
    }

    /** Runs alongside Birdseye initialization, without attaching a camera or owning a view session. */
    public static void prewarm(Context context, BirdseyeCalibration calibration) {
        if (!requested(UiPrefs.getPrefs(context))) return;
        WheelLensMapCache disk = new WheelLensMapCache(CalibrationStore.directory(context));
        IO.execute(() -> {
            try {
                String key = WheelLensMaps.key(calibration);
                if (!SideCameraNative.maps(0, key, null)) {
                    SideCameraNative.maps(0, key, WheelLensMaps.get(calibration, disk, IO));
                }
            } catch (RuntimeException | LinkageError | OutOfMemoryError failure) {
                DevRuntimeLog.add("Curbside", "Map warmup deferred: " + failure.getClass().getSimpleName());
            }
        });
    }
    AdvancedWheelProjection(Access access,Function<BirdseyeCalibration,float[][]> factory,LongSupplier clock,Consumer<String> log,long intervalMs){this.access=access;this.factory=factory;this.clock=clock;this.log=log;this.intervalMs=intervalMs;}
    synchronized void setActive(boolean active){
        if(closed||active==(token!=0))return;
        if(task!=null){task.cancel(false);task=null;}
        int previous=token;token=0;ready=false;
        if(previous!=0)access.session(-previous);
        if(!active)return;
        int owner=NEXT.incrementAndGet();if(owner<=0)throw new IllegalStateException("Projection session IDs exhausted");
        token=owner;access.session(owner);failureSince=-1;
        task=IO.scheduleWithFixedDelay(()->poll(owner),0,intervalMs,TimeUnit.MILLISECONDS);
    }
    boolean ready(){return ready;}
    private void poll(int owner){
        if(token!=owner)return;
        try{
            BirdseyeCalibration calibration=access.load();if(token!=owner)return;
            failureSince=-1;String key=WheelLensMaps.key(calibration);
            boolean available=access.maps(owner,key,null);
            if(!available){float[][] maps=factory.apply(calibration);if(token!=owner)return;available=access.maps(owner,key,maps);}
            if(token!=owner)return;
            ready=available;report(available?"Advanced curbside ready":"Advanced unavailable; basic fallback");
        }catch(IOException failure){
            if(token!=owner)return;long now=clock.getAsLong();if(failureSince<0)failureSince=now;
            if(!ready||now-failureSince>=2000){access.maps(owner,null,null);ready=false;}
            report(ready?"Advanced calibration rechecking":"Advanced calibration unavailable; basic fallback");
        }catch(RuntimeException|LinkageError|OutOfMemoryError failure){
            if(token!=owner)return;access.maps(owner,null,null);ready=false;
            report("Advanced rejected ("+failure.getClass().getSimpleName()+"); basic fallback");
        }
    }
    private void report(String value){if(!value.equals(status)){status=value;log.accept(value);}}
    @Override public synchronized void close(){if(closed)return;setActive(false);closed=true;}
}
