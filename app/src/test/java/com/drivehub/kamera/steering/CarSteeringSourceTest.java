package com.drivehub.kamera.steering;
import com.drivehub.kamera.helper.vehiclesensors.CarServiceConnection;
import org.junit.Test;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.Assert.*;
public class CarSteeringSourceTest {
    public static class Event {
        public int sensorType=SteeringReader.PROPERTY;public float[] floatValues;public long timestamp=123;
        Event(float raw){floatValues=new float[]{raw};}
    }
    public interface Listener {void onSensorChanged(Event e);}
    public static class Sensor {
        public Event event=new Event(147.7f);public Listener listener;public boolean supported=true;public int registers,unregisters;
        public boolean registerListener(Listener l,int id,int rate){assertEquals(SteeringReader.PROPERTY,id);assertEquals(1,rate);listener=l;registers++;return supported;}
        public void unregisterListener(Listener l,int id){assertSame(listener,l);assertEquals(SteeringReader.PROPERTY,id);unregisters++;listener=null;}
        public Event getLatestSensorEvent(int id){assertEquals(SteeringReader.PROPERTY,id);return event;}
    }
    public static class Property {
        public Object getProperty(Class<?> type,int id,int area){assertEquals(Float.class,type);assertEquals(SteeringReader.PROPERTY,id);assertEquals(0,area);return -112.75f;}
    }
    public static class Car {
        public boolean connected,connecting;public int connects,disconnects;final Sensor sensor;final Object property;
        Car(Sensor sensor,Object property){this.sensor=sensor;this.property=property;}
        public boolean isConnected(){return connected;}public boolean isConnecting(){return connecting;}
        public void connect(){connecting=true;connects++;}public void disconnect(){connected=false;connecting=false;disconnects++;}
        public Object getCarManager(String service){return service.equals("sensor")?sensor:property;}
    }
    @Test public void actualLegacySensorContractSubscribesAfterAsyncConnectionAndMapsFractionalAngles() {
        AtomicLong now=new AtomicLong(100);Sensor sensor=new Sensor();Car car=new Car(sensor,null);
        CarSteeringSource s=new CarSteeringSource(new CarServiceConnection(()->car,now::get),now::get);
        assertFalse(s.read().available);assertEquals(1,car.connects);assertEquals(0,sensor.registers);
        car.connected=true;now.set(200);SteeringSnapshot sample=s.read();assertTrue(sample.available);assertTrue(sample.state.contains("sensor"));
        assertEquals(147.7f,sample.rawDegrees,0);assertEquals(-10,SteeringMapping.degrees(sample.rawDegrees,14.77f,false),.001f);
        assertEquals(123,sample.sensorTimestampNs);now.set(900);assertTrue(s.read().fresh(900));assertEquals(1,sensor.registers);
        s.close();assertEquals(1,sensor.unregisters);assertEquals(1,car.disconnects);
    }
    @Test public void callbackPrimesAnEmptyCacheAndOldCallbackCannotRestoreAfterClose() {
        AtomicLong now=new AtomicLong(100);Sensor sensor=new Sensor();sensor.event=null;Car car=new Car(sensor,null);car.connected=true;
        CarSteeringSource s=new CarSteeringSource(new CarServiceConnection(()->car,now::get),now::get);
        assertFalse(s.read().available);Listener old=sensor.listener;old.onSensorChanged(new Event(-123.25f));assertEquals(-123.25f,s.read().rawDegrees,0);
        s.close();old.onSensorChanged(new Event(500));car.connected=true;now.set(200);assertFalse(s.read().available);s.close();
    }
    @Test public void rejectedLegacySensorFallsBackToTypedFloatProperty() {
        Sensor sensor=new Sensor();sensor.supported=false;Car car=new Car(sensor,new Property());car.connected=true;
        CarSteeringSource s=new CarSteeringSource(new CarServiceConnection(()->car,()->100L),()->100L);
        assertEquals(-112.75f,s.read().rawDegrees,0);assertTrue(s.read().state.contains("property"));assertEquals(1,sensor.unregisters);s.close();
    }
    @Test public void wrongSensorEmptyPayloadAndInvalidFloatNeverMoveTheWheels() throws Exception {
        Event e=new Event(20);e.sensorType=123;assertFalse(SteeringSnapshot.decodeSensor(e,100).available);
        e.sensorType=SteeringReader.PROPERTY;e.floatValues=new float[0];assertFalse(SteeringSnapshot.decodeSensor(e,100).available);
        for(float bad:new float[]{Float.NaN,Float.POSITIVE_INFINITY,517,-517}){e.floatValues=new float[]{bad};assertFalse(SteeringSnapshot.decodeSensor(e,100).available);}
        assertFalse(SteeringSnapshot.decodeSensor(null,100).available);
    }
}
