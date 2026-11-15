package com.drivehub.kamera.side;

import com.drivehub.kamera.birdseye.BirdseyeCalibration;
import java.io.IOException;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.*;

/** Visible-view calibration loader only. Late work cannot publish into a newer native session. */
final class AdvancedWheelProjection implements AutoCloseable {
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
    AdvancedWheelProjection(Access access,Consumer<String> log){this(access,WheelLensMaps::get,()->System.nanoTime()/1_000_000,log,1000);}
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
