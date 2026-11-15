package com.drivehub.kamera.helper.vehiclesensors;

import java.util.concurrent.atomic.AtomicLong;
import org.junit.Test;
import static org.junit.Assert.*;

/** The asynchronous adapter remains in use for steering, independently of the PDC rollback. */
public class CarServiceConnectionTest {
    public static class Car {
        public boolean connecting;
        public int connects,disconnects;
        public boolean isConnected(){return false;}
        public boolean isConnecting(){return connecting;}
        public void connect(){connects++;connecting=true;}
        public void disconnect(){disconnects++;connecting=false;}
    }
    @Test public void timedOutSteeringConnectionBacksOffInsteadOfReconnectingEveryRead() throws Exception {
        AtomicLong now=new AtomicLong(100);Car car=new Car();
        CarServiceConnection connection=new CarServiceConnection(()->car,now::get);
        assertFalse(connection.ready());now.set(5100);assertFalse(connection.ready());assertEquals(1,car.disconnects);
        now.set(6000);assertFalse(connection.ready());assertEquals(1,car.connects);
        now.set(7100);assertFalse(connection.ready());assertEquals(2,car.connects);
    }
}
