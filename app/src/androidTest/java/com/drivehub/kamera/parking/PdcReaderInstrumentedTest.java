package com.drivehub.kamera.parking;
import android.os.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.Assert.*;
@RunWith(AndroidJUnit4.class)
public class PdcReaderInstrumentedTest {
    private void ui(Runnable job){InstrumentationRegistry.getInstrumentation().runOnMainSync(job);}
    private void await(java.util.function.BooleanSupplier condition){long end=SystemClock.elapsedRealtime()+4000;do{if(condition.getAsBoolean())return;SystemClock.sleep(20);}while(SystemClock.elapsedRealtime()<end);assertTrue(condition.getAsBoolean());}
    @Test public void systemPropertiesArePolledOnlyInReverseAndPartialSamplesKeepTheirBars() {
        AtomicBoolean reverse=new AtomicBoolean();AtomicInteger reads=new AtomicInteger();
        AtomicReference<String> sectors=new AtomicReference<>("[1,3,6,9]");
        AtomicReference<PdcSnapshot> latest=new AtomicReference<>(PdcSnapshot.EMPTY);
        SystemPropertyPdcSource source=new SystemPropertyPdcSource(key->{
            assertNotEquals(Looper.getMainLooper(),Looper.myLooper());reads.incrementAndGet();
            if(key.equals(SystemPropertyPdcSource.SECTORS))return sectors.get();
            return key.equals(SystemPropertyPdcSource.CLOSEST_CM)?"80":"2";
        },SystemClock::elapsedRealtime);
        PdcReader reader=new PdcReader(source,reverse::get,latest::set);
        try {
            ui(reader::start);SystemClock.sleep(150);assertEquals(0,reads.get());
            reverse.set(true);await(()->latest.get().hasSectors());
            assertArrayEquals(new int[]{1,3,6,9},latest.get().spatialLevels());
            assertEquals(80,latest.get().closestCm);assertEquals(2,latest.get().status);
            assertTrue(PdcReader.diagnostic().contains(SystemPropertyPdcSource.SECTORS));
            sectors.set("[1,unknown,6,9]");await(()->latest.get().level(1)==-1);
            PdcGeometry geometry=new PdcGeometry();geometry.set(latest.get());
            assertEquals(3,geometry.bars(new com.drivehub.kamera.rivela.CarFrame(0,0,4287,1836),SystemClock.elapsedRealtime()).size());
            reverse.set(false);await(()->!latest.get().hasSectors());
            int stoppedReads=reads.get();SystemClock.sleep(150);assertEquals(stoppedReads,reads.get());
        } finally {ui(reader::close);}
    }
    public static class ParkingCar {
        final java.util.List<String> managers=new java.util.ArrayList<>();
        volatile int disconnected;
        volatile boolean connected,connecting;
        public boolean isConnected(){return connected;}
        public boolean isConnecting(){return connecting;}
        public void connect(){connecting=true;}
        public void disconnect(){disconnected++;connected=connecting=false;}
        public Object getCarManager(String name){assertTrue(connected);assertEquals("parkingassistance",name);managers.add(name);return new Properties();}
    }
    public static class Properties {
        public Object getProperty(Class<?> type,int id,int area){assertEquals(Integer.class,type);assertEquals(0,area);
            return id==PdcReader.CLOSEST_CM?80:id==PdcReader.STATUS?0:2;}
    }
    @Test public void restoredParkingSourceWaitsForConnectionOnlyInReverseAndClearsOnExit() {
        AtomicBoolean reverse=new AtomicBoolean();AtomicInteger created=new AtomicInteger();
        AtomicReference<PdcSnapshot> latest=new AtomicReference<>(PdcSnapshot.EMPTY);
        ParkingCar car=new ParkingCar();
        com.drivehub.kamera.helper.vehiclesensors.CarServiceConnection connection=
                new com.drivehub.kamera.helper.vehiclesensors.CarServiceConnection(
                        ()->{created.incrementAndGet();return car;},SystemClock::elapsedRealtime);
        CarPdcSource source=new CarPdcSource(connection,SystemClock::elapsedRealtime);
        PdcReader reader=new PdcReader(source,reverse::get,latest::set);
        try {
            ui(reader::start);SystemClock.sleep(150);assertEquals(0,created.get());
            reverse.set(true);await(()->car.connecting);assertEquals(1,created.get());
            assertFalse(latest.get().hasSectors());assertTrue(car.managers.isEmpty());
            car.connected=true;await(()->latest.get().hasSectors());assertEquals(80,latest.get().closestCm);
            assertEquals(java.util.Collections.singletonList("parkingassistance"),car.managers);
            assertTrue(PdcReader.diagnostic().contains("parkingassistance"));
            reverse.set(false);await(()->!latest.get().hasSectors());assertTrue(car.disconnected>0);
        }finally{ui(reader::close);}
    }
    @Test public void realReaderOnlyPollsInReverseAndStopPreventsLateSensorResurrection() throws Exception {
        AtomicBoolean reverse=new AtomicBoolean();AtomicInteger reads=new AtomicInteger();
        AtomicReference<PdcSnapshot> latest=new AtomicReference<>(PdcSnapshot.EMPTY);
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        PdcReader reader=new PdcReader(new PdcReader.Source(){
            public PdcSnapshot read(){assertNotEquals(Looper.getMainLooper(),Looper.myLooper());reads.incrementAndGet();entered.countDown();
                try{release.await(3,TimeUnit.SECONDS);}catch(InterruptedException e){throw new AssertionError(e);}
                return new PdcSnapshot(new int[]{1,2,3,0},60,0,SystemClock.elapsedRealtime());}
            public void close(){assertNotEquals(Looper.getMainLooper(),Looper.myLooper());}public String diagnostic(){return "test parkingassistance";}
        },reverse::get,s->{assertEquals(Looper.getMainLooper(),Looper.myLooper());latest.set(s);});
        try {
            ui(reader::start);SystemClock.sleep(150);assertEquals(0,reads.get());
            reverse.set(true);assertTrue(entered.await(3,TimeUnit.SECONDS));ui(reader::stop);
            release.countDown();SystemClock.sleep(150);assertFalse(latest.get().hasSectors());
            ui(reader::start);await(()->latest.get().hasObstacle());assertEquals(60,latest.get().closestCm);
            reverse.set(false);await(()->!latest.get().hasSectors());
        }finally{release.countDown();ui(reader::close);}
    }
    @Test public void aReadSlowerThanHalfASecondIsNotPublishedAsFresh() {
        AtomicInteger reads=new AtomicInteger();AtomicBoolean staleSeen=new AtomicBoolean();
        AtomicReference<PdcSnapshot> latest=new AtomicReference<>(PdcSnapshot.EMPTY);
        PdcReader reader=new PdcReader(new PdcReader.Source(){
            public PdcSnapshot read(){long time=SystemClock.elapsedRealtime();if(reads.incrementAndGet()==1)SystemClock.sleep(550);
                return new PdcSnapshot(new int[]{1,0,0,0},80,0,time);}
            public void close(){}public String diagnostic(){return "test";}
        },()->true,s->{latest.set(s);if(!s.hasSectors())staleSeen.set(true);});
        try{ui(reader::start);await(staleSeen::get);await(()->latest.get().hasSectors());assertEquals(80,latest.get().closestCm);}
        finally{ui(reader::close);}
    }
}
