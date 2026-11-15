package com.drivehub.kamera.birdseye;
import java.util.concurrent.*;
import java.util.function.Consumer;
/** Retain the four shared camera sessions while OEM coexist is off, independent of UI, gear and recording. */
public final class ExclusiveCameraLease implements AutoCloseable {
    public interface Access {
        boolean attach(int owner,int camera);
        void detach(int owner);
        default void demand(int owner,boolean needed) {}
    }
    private final Access access;
    private final Consumer<String> log;
    private final int owner=BirdseyeOwnerIds.next();
    private final ScheduledThreadPoolExecutor io=new ScheduledThreadPoolExecutor(1,r->new Thread(r,"Exclusive-camera-lease"));
    private volatile int generation;
    private volatile boolean active;
    private volatile int readyMask;
    private boolean closed;
    private ScheduledFuture<?> task;
    private int lastMask=-1;
    public ExclusiveCameraLease(Consumer<String> log){this(new Access(){
        public boolean attach(int o,int c){
            long token=com.drivehub.kamera.camera.CameraHandoffController.generation();
            boolean held=BirdseyeNative.hold(o,c);
            com.drivehub.kamera.camera.CameraHandoffController.ready("lease-"+o+"-"+c,held,token);
            return held;
        }
        public void detach(int o){BirdseyeNative.releaseHold(o);}
        public void demand(int o,boolean needed){
            for(int c=0;c<4;c++)com.drivehub.kamera.camera.CameraHandoffController.demand("lease-"+o+"-"+c,needed);
        }
    },log);}
    public ExclusiveCameraLease(Access access,Consumer<String> log){this.access=access;this.log=log;io.setRemoveOnCancelPolicy(true);}
    public synchronized int heldMask(){return active?readyMask:0;}
    public synchronized void setActive(boolean enabled) {
        if(closed||active==enabled)return;
        active=enabled;readyMask=0;int token=++generation;
        access.demand(owner,enabled);
        if(task!=null){task.cancel(false);task=null;}
        if(!enabled){io.execute(this::detach);return;}
        task=io.scheduleWithFixedDelay(()->{
            if(!active||token!=generation)return;
            int mask=0;
            for(int c=0;c<4&&active&&token==generation;c++)try{if(access.attach(owner,c))mask|=1<<c;}
            catch(RuntimeException|LinkageError failure){log.accept("Attach failed: "+failure.getClass().getSimpleName());}
            publish(token,mask);
        },0,500,TimeUnit.MILLISECONDS);
    }
    private synchronized void publish(int token,int mask){
        if(!active||token!=generation)return;
        readyMask=mask;if(mask!=lastMask){lastMask=mask;log.accept("Exclusive camera lease: "+Integer.bitCount(mask)+"/4, mask="+mask);}
    }
    private void detach(){try{access.detach(owner);}catch(RuntimeException|LinkageError failure){log.accept("Detach failed: "+failure.getClass().getSimpleName());}lastMask=-1;}
    @Override public synchronized void close(){if(closed)return;setActive(false);closed=true;io.execute(this::detach);io.shutdown();}
}
