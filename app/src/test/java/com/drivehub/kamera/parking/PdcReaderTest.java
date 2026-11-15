package com.drivehub.kamera.parking;
import org.junit.Test;
import static org.junit.Assert.*;
public class PdcReaderTest {
    public static final class Property {
        private final int status;private final Object value;
        Property(int status,Object value){this.status=status;this.value=value;}
        public int getStatus(){return status;}
        public Object getValue(){return value;}
    }
    @Test public void legacyTypedValuesAndAvailablePropertyValuesAreAcceptedButUnavailableOnesAreNot() {
        assertEquals(80,PdcReader.decode(80,true));
        assertEquals(-1,PdcReader.decode(80,false));
        assertEquals(80,PdcReader.decode(new Property(0,80),false));
        assertEquals(80,PdcReader.decode(new Property(0,80),true));
        assertEquals(-1,PdcReader.decode(new Property(1,80),false));
        assertEquals(-1,PdcReader.decode(new Property(2,80),false));
        assertEquals(-1,PdcReader.decode(new Property(0,"80"),false));
        assertEquals(-1,PdcReader.decode(null,true));
    }
}
