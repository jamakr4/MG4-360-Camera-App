package com.drivehub.kamera.helper.vehiclesensors;
import org.junit.Test;
import java.lang.reflect.InvocationTargetException;
import static org.junit.Assert.*;
public class CarApiFactoryTest {
    public static final class OldThree {
        public static Object createCar(String context,Runnable observer,Integer handler){assertEquals("context",context);assertNotNull(observer);assertEquals(Integer.valueOf(7),handler);return "AAOS9";}
    }
    public static final class OldTwo {public static Object createCar(String context,Runnable observer){assertNotNull(observer);return "old-two";}}
    public static final class Modern {public static Object createCar(String context){return "modern";}}
    public static final class Denied {
        public static Object createCar(String c,Runnable o,Integer h){throw new SecurityException("denied");}
        public static Object createCar(String c){throw new AssertionError("Do not bypass a real factory error by choosing another API");}
    }
    private Object create(Class<?> type)throws Exception{return CarApiFactory.create(type,String.class,"context",Runnable.class,(Runnable)()->{},Integer.class,7);}
    @Test public void aaos9FactoryDoesNotRequireAModernOneArgumentOverload() throws Exception {assertEquals("AAOS9",create(OldThree.class));}
    @Test public void twoArgumentAndModernFactoriesAreSupported() throws Exception {assertEquals("old-two",create(OldTwo.class));assertEquals("modern",create(Modern.class));}
    @Test public void runtimePermissionFailuresAreNotTreatedAsMissingSignatures() throws Exception {
        try{create(Denied.class);fail();}catch(InvocationTargetException expected){assertTrue(expected.getCause() instanceof SecurityException);}
    }
}
