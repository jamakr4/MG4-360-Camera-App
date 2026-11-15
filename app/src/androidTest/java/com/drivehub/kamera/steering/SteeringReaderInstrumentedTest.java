package com.drivehub.kamera.steering;

import android.os.Looper;
import android.os.SystemClock;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class SteeringReaderInstrumentedTest {
    private static void ui(Runnable r) { InstrumentationRegistry.getInstrumentation().runOnMainSync(r); }
    private static void await(CountDownLatch latch) throws Exception { assertTrue("reader timed out",latch.await(4,TimeUnit.SECONDS)); }
    @Test public void pollsOffMainAndStopClearsAndResumeReconnects() throws Exception {
        AtomicInteger reads=new AtomicInteger(),closes=new AtomicInteger();
        AtomicReference<SteeringSnapshot> latest=new AtomicReference<>();
        CountDownLatch samples=new CountDownLatch(3);
        SteeringReader reader=new SteeringReader(new SteeringReader.Source(){
            public SteeringSnapshot read(){assertNotEquals(Looper.getMainLooper(),Looper.myLooper());reads.incrementAndGet();return SteeringSnapshot.value(112.75f,0,SystemClock.elapsedRealtime(),1);}
            public void close(){assertNotEquals(Looper.getMainLooper(),Looper.myLooper());closes.incrementAndGet();}
        },s->{assertEquals(Looper.getMainLooper(),Looper.myLooper());latest.set(s);if(s.available)samples.countDown();});
        try {
            ui(()->{reader.start();reader.start();});await(samples);
            assertEquals(112.75f,latest.get().rawDegrees,0);
            ui(reader::stop);assertFalse(latest.get().available);
            SystemClock.sleep(120);int stopped=reads.get();SystemClock.sleep(120);assertEquals(stopped,reads.get());assertTrue(closes.get()>0);
            ui(reader::start);SystemClock.sleep(150);assertTrue(latest.get().available);
        } finally {ui(reader::close);}
    }
    @Test public void lateReadCannotRestoreAngleAfterPause() throws Exception {
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1),closed=new CountDownLatch(1);
        AtomicInteger turns=new AtomicInteger();AtomicReference<SteeringSnapshot> latest=new AtomicReference<>();
        SteeringReader reader=new SteeringReader(new SteeringReader.Source(){
            public SteeringSnapshot read(){entered.countDown();try{release.await(3,TimeUnit.SECONDS);}catch(InterruptedException e){throw new AssertionError(e);}return SteeringSnapshot.value(200,0,SystemClock.elapsedRealtime(),1);}
            public void close(){closed.countDown();}
        },s->{latest.set(s);if(s.available)turns.incrementAndGet();});
        try {ui(reader::start);await(entered);ui(reader::stop);assertSame(SteeringSnapshot.EMPTY,latest.get());release.countDown();await(closed);SystemClock.sleep(150);assertEquals(0,turns.get());assertFalse(latest.get().available);}
        finally {release.countDown();ui(reader::close);}
    }
    @Test public void slowIpcAndReaderFailurePublishUnavailableInsteadOfAFullLock() throws Exception {
        CountDownLatch slow=new CountDownLatch(1),failed=new CountDownLatch(1),recovered=new CountDownLatch(1);
        AtomicInteger reads=new AtomicInteger();
        SteeringReader reader=new SteeringReader(new SteeringReader.Source(){
            public SteeringSnapshot read(){int n=reads.incrementAndGet();long start=SystemClock.elapsedRealtime();if(n==1)SystemClock.sleep(550);if(n==2)throw new SecurityException("denied");return SteeringSnapshot.value(100,0,start,1);}
            public void close(){}
        },s->{if(s.state.contains("500"))slow.countDown();if(s.state.contains("SecurityException"))failed.countDown();if(s.available)recovered.countDown();});
        try {ui(reader::start);await(slow);await(failed);await(recovered);}
        finally {ui(reader::close);}
    }
    public static final class SensorEvent {
        public int sensorType=SteeringReader.PROPERTY;public final float[] floatValues;public long timestamp=10;
        SensorEvent(float angle){floatValues=new float[]{angle};}
    }
    public interface SensorListener {void onSensorChanged(SensorEvent event);}
    public static final class SensorManager {
        volatile SensorEvent event=new SensorEvent(147.7f);volatile SensorListener listener;final AtomicInteger unsubscribed=new AtomicInteger();
        public boolean registerListener(SensorListener listener,int id,int rate){assertEquals(SteeringReader.PROPERTY,id);this.listener=listener;return true;}
        public SensorEvent getLatestSensorEvent(int id){return event;}
        public void unregisterListener(SensorListener listener,int id){unsubscribed.incrementAndGet();this.listener=null;}
    }
    public static final class LegacyCar {
        final SensorManager sensor=new SensorManager();
        public boolean isConnected(){return true;}public boolean isConnecting(){return false;}
        public void connect(){}public void disconnect(){}
        public Object getCarManager(String name){return name.equals("sensor")?sensor:null;}
    }
    @Test public void legacySensorSubscriptionDrivesWheelPoseThroughTheProductionReader() throws Exception {
        LegacyCar car=new LegacyCar();
        CarSteeringSource source=new CarSteeringSource(new com.drivehub.kamera.helper.vehiclesensors.CarServiceConnection(()->car,SystemClock::elapsedRealtime),SystemClock::elapsedRealtime);
        com.drivehub.kamera.birdseye.WheelSteering wheel=new com.drivehub.kamera.birdseye.WheelSteering();SteeringPose pose=new SteeringPose();
        AtomicReference<SteeringSnapshot> latest=new AtomicReference<>();
        SteeringReader reader=new SteeringReader(source,s->{latest.set(s);wheel.setDegrees(pose.update(s,SystemClock.elapsedRealtime(),14.77f,false));});
        try {
            ui(reader::start);long end=SystemClock.elapsedRealtime()+4000;
            while(wheel.degrees()>-9&&SystemClock.elapsedRealtime()<end)SystemClock.sleep(25);
            assertEquals(-10,wheel.degrees(),.1f);assertTrue(latest.get().state.contains("sensor"));
            car.sensor.event=new SensorEvent(-147.7f);end=SystemClock.elapsedRealtime()+4000;
            while(wheel.degrees()<9&&SystemClock.elapsedRealtime()<end)SystemClock.sleep(25);
            assertEquals(10,wheel.degrees(),1f);SensorListener late=car.sensor.listener;
            ui(reader::stop);assertEquals(0,wheel.degrees(),0);SystemClock.sleep(150);
            assertEquals(1,car.sensor.unsubscribed.get());late.onSensorChanged(new SensorEvent(500));
            SystemClock.sleep(100);assertEquals(0,wheel.degrees(),0);
        }finally{ui(reader::close);}
    }

}
