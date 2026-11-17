package com.drivehub.kamera.parking;
import com.drivehub.kamera.helper.vehiclesensors.CarServiceConnection;
import org.junit.Test;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.Assert.*;
public class CarPdcSourceTest {
    public static class Car {
        public boolean connected,connecting;public int connects,disconnects,managerCalls;
        final List<String> requested=new ArrayList<>();
        final Object parking,property;
        Car(Object parking,Object property){this.parking=parking;this.property=property;}
        public boolean isConnected(){return connected;}public boolean isConnecting(){return connecting;}
        public void connect(){connects++;connecting=true;}public void disconnect(){disconnects++;connected=false;connecting=false;}
        public Object getCarManager(String name){managerCalls++;requested.add(name);return name.equals("parkingassistance")?parking:property;}
    }
    public static class Parking {
        final List<Integer> properties=new ArrayList<>();
        public Object getProperty(Class<?> type,int property,int area){assertEquals(Integer.class,type);assertEquals(0,area);properties.add(property);
            if(property==PdcReader.STATUS)return 0;if(property==PdcReader.CLOSEST_CM)return 80;
            return property==PdcReader.SECTORS[0]?2:0;}
    }
    public static class Modern {
        public Object getProperty(int id,int area){return new PdcReaderTest.Property(0,id==PdcReader.CLOSEST_CM?95:id==PdcReader.STATUS?0:3);}
    }
    public static class FourSectors extends Parking {
        @Override public Object getProperty(Class<?> type,int property,int area) {
            super.getProperty(type,property,area);
            switch(property) {
                case 0x2140b976:return 1; // rear left
                case 0x2140b977:return 3; // rear mid-left
                case 0x2140b978:return 6; // rear mid-right
                case 0x2140b979:return 9; // rear right
                case 0x2140b97b:return 80;
                case 0x2140b970:return 0;
                default:throw new AssertionError("Unexpected vehicle property");
            }
        }
    }
    @Test public void distinctPhysicalRearPropertiesReachAllFourBandsIncludingBothOuterSectors() {
        FourSectors parking=new FourSectors();Car car=new Car(parking,null);car.connected=true;
        CarPdcSource source=new CarPdcSource(new CarServiceConnection(()->car,()->100L),()->100L);
        try {
            PdcSnapshot value=source.read();assertArrayEquals(new int[]{1,3,6,9},value.spatialLevels());
            assertEquals(80,value.closestCm);assertEquals(4,new PdcGeometryTestHelper().bars(value));
            assertEquals(Arrays.asList(0x2140b976,0x2140b977,0x2140b978,0x2140b979,0x2140b97b,0x2140b970),parking.properties);
        } finally {source.close();}
    }
    @Test public void legacyParkingAssistanceWaitsForAsyncConnectionAndReadsAllSixProperties() {
        AtomicLong now=new AtomicLong(100);Parking parking=new Parking();Car car=new Car(parking,new Modern());
        CarPdcSource source=new CarPdcSource(new CarServiceConnection(()->car,now::get),now::get);
        assertFalse(source.read().hasSectors());assertEquals(1,car.connects);assertEquals(0,car.managerCalls);
        now.set(200);assertFalse(source.read().hasSectors());assertEquals(1,car.connects);assertEquals(0,car.disconnects);
        car.connected=true;PdcSnapshot s=source.read();assertTrue(s.hasSectors());assertEquals(80,s.closestCm);assertEquals(2,s.level(0));
        assertEquals(Arrays.asList(0x2140b976,0x2140b977,0x2140b978,0x2140b979,0x2140b97b,0x2140b970),parking.properties);
        assertEquals(Collections.singletonList("parkingassistance"),car.requested);
        assertTrue(source.diagnostic().contains("parkingassistance"));
        assertEquals(1,new PdcGeometryTestHelper().bars(s));source.close();assertEquals(1,car.disconnects);
    }
    private static class PdcGeometryTestHelper {
        int bars(PdcSnapshot s){PdcGeometry g=new PdcGeometry();g.set(s);return g.bars(new com.drivehub.kamera.rivela.CarFrame(0,0,4287,1836),s.sampledMs).size();}
    }
    @Test public void modernPropertyManagerFallbackStillDecodesStatusAndCentimetres() {
        Car car=new Car(null,new Modern());car.connected=true;
        CarPdcSource source=new CarPdcSource(new CarServiceConnection(()->car,()->100L),()->100L);
        assertEquals(95,source.read().closestCm);assertTrue(source.diagnostic().contains("property connected"));
        assertEquals(Arrays.asList("parkingassistance","property"),car.requested);
    }

    public static class FailedParking extends Parking {
        @Override public Object getProperty(Class<?> type,int property,int area){throw new SecurityException("unavailable");}
    }
    @Test public void sixFailedReadsCloseTheConnectionAndRespectTheRetryPause() {
        AtomicLong now=new AtomicLong(100);Car car=new Car(new FailedParking(),new Modern());car.connected=true;
        CarPdcSource source=new CarPdcSource(new CarServiceConnection(()->car,now::get),now::get);
        assertSame(PdcSnapshot.EMPTY,source.read());assertEquals(1,car.disconnects);
        assertTrue(source.diagnostic().contains("SecurityException"));
        for(int time=200;time<2100;time+=100){now.set(time);assertFalse(source.read().hasSectors());}
        assertEquals(1,car.managerCalls);assertEquals(0,car.connects);
        now.set(2100);source.read();assertEquals(1,car.connects);
        assertEquals(Collections.singletonList("parkingassistance"),car.requested);
    }

    public static class PartialParking extends FourSectors {
        @Override public Object getProperty(Class<?> type,int property,int area){
            if(property==PdcReader.SECTORS[0])throw new SecurityException("one sector unavailable");
            return super.getProperty(type,property,area);
        }
    }
    @Test public void oneFailedSectorStaysUnknownWithoutSwitchingManagersOrHidingValidBars() {
        Car car=new Car(new PartialParking(),new Modern());car.connected=true;
        CarPdcSource source=new CarPdcSource(new CarServiceConnection(()->car,()->100L),()->100L);
        PdcSnapshot value=source.read();assertEquals(-1,value.level(0));assertEquals(80,value.closestCm);
        assertArrayEquals(new int[]{0,3,6,9},value.spatialLevels());
        assertEquals(3,new PdcGeometryTestHelper().bars(value));
        source.read();assertEquals(0,car.disconnects);
        assertEquals(Collections.singletonList("parkingassistance"),car.requested);
    }
}
