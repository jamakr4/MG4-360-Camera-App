package com.drivehub.kamera.side;
import com.drivehub.kamera.birdseye.*;
import org.junit.Test;
import java.io.IOException;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;
import static org.junit.Assert.*;
public class AdvancedWheelProjectionTest {
    private static final BirdseyeCalibration CALIBRATION=BirdseyeCalibration.parse(BirdseyeSynthetic.calibrationBytes());
    private static void await(BooleanSupplier condition)throws Exception{long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(4);while(!condition.getAsBoolean()&&System.nanoTime()<end)Thread.sleep(5);assertTrue(condition.getAsBoolean());}
    private static class Access implements AdvancedWheelProjection.Access {
        volatile int owner;volatile String key,active;volatile boolean ioFailure,invalid;
        final AtomicInteger reads=new AtomicInteger(),uploads=new AtomicInteger(),ioFailures=new AtomicInteger();
        public BirdseyeCalibration load()throws IOException{reads.incrementAndGet();if(ioFailure){ioFailures.incrementAndGet();throw new IOException();}if(invalid)throw new IllegalArgumentException();return CALIBRATION;}
        public synchronized void session(int token){if(token>0){owner=token;active=null;}else if(owner==-token){owner=0;active=null;}}
        public synchronized boolean maps(int token,String identity,float[][] maps){if(token!=owner||owner==0)return false;if(identity==null){active=null;return false;}if(maps!=null){uploads.incrementAndGet();key=identity;}active=identity.equals(key)?identity:null;return active!=null;}
    }
    @Test public void missingAndInvalidStartupCalibrationRecoverWithoutReopeningTheView()throws Exception{
        Access access=new Access();access.ioFailure=true;
        try(AdvancedWheelProjection loader=new AdvancedWheelProjection(access,c->new float[6][1],()->0,s->{},15)){
            loader.setActive(true);int owner=access.owner;
            await(()->access.ioFailures.get()>=2);assertFalse(loader.ready());assertNull(access.active);
            access.invalid=true;access.ioFailure=false;
            int reads=access.reads.get();await(()->access.reads.get()>=reads+2);
            assertFalse(loader.ready());assertEquals(0,access.uploads.get());
            access.invalid=false;await(loader::ready);
            assertEquals(owner,access.owner);assertNotNull(access.active);assertEquals(1,access.uploads.get());
        }
    }
    @Test public void readsOnlyWhileEnabledReusesCachedMapsAndExpiresIoGrace()throws Exception{
        Access access=new Access();AtomicLong clock=new AtomicLong(1000);
        try(AdvancedWheelProjection loader=new AdvancedWheelProjection(access,c->new float[4][1],clock::get,s->{},15)){
            Thread.sleep(40);assertEquals(0,access.reads.get());loader.setActive(true);await(loader::ready);assertEquals(1,access.uploads.get());
            access.ioFailure=true;await(()->access.ioFailures.get()>0);assertTrue(loader.ready());assertNotNull(access.active);
            clock.set(4000);await(()->!loader.ready());assertNull(access.active);
            access.ioFailure=false;await(loader::ready);assertEquals(1,access.uploads.get());
            access.invalid=true;await(()->!loader.ready());assertNull(access.active);
            loader.setActive(false);Thread.sleep(30);int reads=access.reads.get();Thread.sleep(60);assertEquals(reads,access.reads.get());assertEquals(0,access.owner);
        }
    }
    @Test public void lateMapBuildCannotReclaimAfterPauseAndNewSession()throws Exception{
        Access access=new Access();CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        AtomicInteger builds=new AtomicInteger();
        try(AdvancedWheelProjection loader=new AdvancedWheelProjection(access,c->{if(builds.getAndIncrement()==0){entered.countDown();try{release.await(3,TimeUnit.SECONDS);}catch(InterruptedException e){throw new AssertionError(e);}}return new float[4][1];},()->0,s->{},15)){
            loader.setActive(true);assertTrue(entered.await(2,TimeUnit.SECONDS));int first=access.owner;
            loader.setActive(false);assertNull(access.active);loader.setActive(true);assertNotEquals(first,access.owner);
            release.countDown();await(loader::ready);assertEquals(1,access.uploads.get());
            loader.close();assertNull(access.active);assertEquals(0,access.owner);
        }finally{release.countDown();}
    }
    @Test public void cpuMapsAreCachedByCalibrationHashAndHaveSixBoundedPatches(){
        float[][] first=WheelLensMaps.get(CALIBRATION);assertSame(first,WheelLensMaps.get(CALIBRATION));assertEquals(6,first.length);
        for(int map=0;map<first.length;map++){float[] uv=first[map];assertEquals(254*(map<4?224:456)*2,uv.length);int valid=0;for(int i=0;i<uv.length;i+=2){if(Float.isNaN(uv[i]))assertTrue(Float.isNaN(uv[i+1]));else{assertTrue(uv[i]>=0&&uv[i]<=719);assertTrue(uv[i+1]>=0&&uv[i+1]<=479);valid++;}}assertTrue(valid>1000);}
    }
}
