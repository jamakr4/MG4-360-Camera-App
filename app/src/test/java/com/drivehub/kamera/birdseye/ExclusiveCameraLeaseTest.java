package com.drivehub.kamera.birdseye;
import org.junit.Test;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.Assert.*;
public class ExclusiveCameraLeaseTest {
    private static void await(java.util.function.BooleanSupplier ready)throws Exception{
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
        while(!ready.getAsBoolean()&&System.nanoTime()<end)Thread.sleep(5);
        assertTrue(ready.getAsBoolean());
    }
    @Test public void leaseSurvivesUiAndRecordingAbsenceUntilCoexistHandoff() throws Exception {
        AtomicInteger mask=new AtomicInteger(),detaches=new AtomicInteger(),calls=new AtomicInteger();
        AtomicReference<String> thread=new AtomicReference<>();CountDownLatch attached=new CountDownLatch(4),released=new CountDownLatch(1);
        ExclusiveCameraLease lease=new ExclusiveCameraLease(new ExclusiveCameraLease.Access(){
            public boolean attach(int owner,int c){calls.incrementAndGet();mask.getAndUpdate(v->v|(1<<c));thread.set(Thread.currentThread().getName());attached.countDown();return true;}
            public void detach(int owner){mask.set(0);detaches.incrementAndGet();released.countDown();}
        },s->{});
        try {
            Thread.sleep(50);assertEquals(0,calls.get());lease.setActive(true);
            assertTrue(attached.await(2,TimeUnit.SECONDS));await(()->lease.heldMask()==15);assertEquals(15,mask.get());assertEquals("Exclusive-camera-lease",thread.get());
            // No Activity lifecycle method is involved: a temporary OEM takeover cannot release it.
            lease.setActive(true);assertEquals(0,detaches.get());
            lease.setActive(false);assertEquals(0,lease.heldMask());assertTrue(released.await(2,TimeUnit.SECONDS));assertEquals(0,mask.get());
            int stopped=calls.get();Thread.sleep(600);assertEquals(stopped,calls.get());
        }finally{lease.close();}
    }
    @Test public void cancellingDuringAttachmentEventuallyReleasesOnlyItsOwnOwner() throws Exception {
        CountDownLatch entered=new CountDownLatch(1),proceed=new CountDownLatch(1),released=new CountDownLatch(1);
        AtomicInteger owner=new AtomicInteger(),detachedOwner=new AtomicInteger();
        ExclusiveCameraLease lease=new ExclusiveCameraLease(new ExclusiveCameraLease.Access(){
            public boolean attach(int id,int c){owner.set(id);entered.countDown();try{proceed.await(2,TimeUnit.SECONDS);}catch(InterruptedException e){throw new AssertionError(e);}return true;}
            public void detach(int id){detachedOwner.set(id);released.countDown();}
        },s->{});
        try{lease.setActive(true);assertTrue(entered.await(2,TimeUnit.SECONDS));lease.setActive(false);proceed.countDown();assertTrue(released.await(2,TimeUnit.SECONDS));assertEquals(owner.get(),detachedOwner.get());}
        finally{proceed.countDown();lease.close();}
    }
    @Test public void missingCameraIsRetriedWithoutDroppingAcquiredOwners()throws Exception{
        AtomicBoolean missing=new AtomicBoolean(true);AtomicInteger releases=new AtomicInteger(),calls=new AtomicInteger(),owner=new AtomicInteger();
        try(ExclusiveCameraLease lease=new ExclusiveCameraLease(new ExclusiveCameraLease.Access(){
            public boolean attach(int id,int camera){owner.compareAndSet(0,id);assertEquals(owner.get(),id);calls.incrementAndGet();return camera!=2||!missing.get();}
            public void detach(int id){assertEquals(owner.get(),id);releases.incrementAndGet();}
        },s->{})){
            lease.setActive(true);await(()->lease.heldMask()==11);
            missing.set(false);await(()->lease.heldMask()==15);
            assertEquals(0,releases.get());lease.setActive(true);assertEquals(0,releases.get());
            lease.setActive(false);assertEquals(0,lease.heldMask());await(()->releases.get()>0);
            int stopped=calls.get();Thread.sleep(600);assertEquals(stopped,calls.get());
        }
    }
}
