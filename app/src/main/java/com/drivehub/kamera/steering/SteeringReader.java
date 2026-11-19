package com.drivehub.kamera.steering;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import java.util.Locale;

/** Lifecycle-bound 20 Hz reader; all Car API/IPC work runs on its own worker. */
public final class SteeringReader {
    public static final int PROPERTY=0x21601563;
    public static final long POLL_MS=50;
    public interface Listener { void sample(SteeringSnapshot snapshot); }
    interface Source { SteeringSnapshot read(); void close(); }
    private static volatile SteeringSnapshot latest=SteeringSnapshot.EMPTY;
    private final Listener listener;
    private final Source source;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final HandlerThread thread=new HandlerThread("Vehicle-steering-reader");
    private final Handler io;
    private String lastSourceState="";
    private boolean running,closed; // Lifecycle methods are called on the main thread.
    private volatile int generation;

    public SteeringReader(Context context,Listener listener) { this(new CarSteeringSource(context),listener); }
    SteeringReader(Source source,Listener listener) {
        this.source=source; this.listener=listener; thread.start(); io=new Handler(thread.getLooper());
    }
    public void start() {
        if (running || closed) return;
        running=true; int token=++generation;
        io.post(()->tick(token));
    }
    public void stop() {
        running=false; ++generation;
        io.removeCallbacksAndMessages(null);
        main.removeCallbacksAndMessages(null);
        latest=SteeringSnapshot.EMPTY; listener.sample(SteeringSnapshot.EMPTY);
        io.post(source::close);
    }
    public void close() {
        if (closed) return;
        stop(); closed=true; io.post(thread::quitSafely);
    }
    private void tick(int token) {
        if (token!=generation) return;
        SteeringSnapshot value;
        try { value=source.read(); }
        catch (RuntimeException | LinkageError error) {
            value=SteeringSnapshot.unavailable(error.getClass().getSimpleName(),SystemClock.elapsedRealtime());
        }
        if (value==null) value=SteeringSnapshot.unavailable("no sample",SystemClock.elapsedRealtime());
        if (value.available && !value.fresh(SystemClock.elapsedRealtime()))
            value=SteeringSnapshot.unavailable("read exceeded 500 ms",value.readElapsedMs);
        final SteeringSnapshot result=value;
        if(!result.state.equals(lastSourceState)){com.drivehub.kamera.dev.DevRuntimeLog.add("Vehicle steering",result.state);lastSourceState=result.state;}
        main.post(()->{
            if (token!=generation) return;
            latest=result; listener.sample(result);
        });
        if (token==generation) io.postDelayed(()->tick(token),POLL_MS);
    }
    public static String diagnostic() {
        SteeringSnapshot s=latest;
        long now=SystemClock.elapsedRealtime();
        SteeringModel.Solution target=new SteeringModel().solve(s.rawDegrees);
        GearSnapshot gear=com.drivehub.kamera.signal.SignalService.guidanceGear();
        return String.format(Locale.ROOT,
                "0x21601563 / Float (sensor or property API)\n%s%s\nraw=%.3f°; OEM radius model\ntarget k=%.5f /m; left/right=%.2f° / %.2f°\ngear=%d; guidance direction=%s\nread age=%d ms; sensor timestamp=%d ns",
                s.state,s.available&&!s.fresh(now)?" (stale)":"",s.rawDegrees,target.curvaturePerMm*1000,
                target.leftDegrees,target.rightDegrees,gear.gear,gear.direction(now),Math.max(0,now-s.readElapsedMs),s.sensorTimestampNs);
    }
}
