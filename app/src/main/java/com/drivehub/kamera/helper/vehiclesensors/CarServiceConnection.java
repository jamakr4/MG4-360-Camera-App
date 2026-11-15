package com.drivehub.kamera.helper.vehiclesensors;

import java.lang.reflect.InvocationTargetException;
import java.util.function.LongSupplier;

/** Independent reflection adapter for asynchronous legacy and modern android.car connections. */
public final class CarServiceConnection implements AutoCloseable {
    public interface Factory {Object create() throws Exception;}
    private final Factory factory;
    private final LongSupplier clock;
    private Object car;
    private long connectingSince,retryAfter;
    private String state="connecting";
    public CarServiceConnection(Factory factory,LongSupplier clock){this.factory=factory;this.clock=clock;}
    public boolean ready() throws Exception {
        long now=clock.getAsLong();
        if(now<retryAfter)return false;
        if(car==null) {
            car=factory.create();if(car==null)throw new IllegalStateException("Car API unavailable");
            connectingSince=now;
            boolean connecting=false;
            try{connecting=Boolean.TRUE.equals(car.getClass().getMethod("isConnecting").invoke(car));}catch(NoSuchMethodException ignored){}
            if(!Boolean.TRUE.equals(car.getClass().getMethod("isConnected").invoke(car))&&!connecting)
                car.getClass().getMethod("connect").invoke(car);
        }
        if(Boolean.TRUE.equals(car.getClass().getMethod("isConnected").invoke(car)))return true;
        state="connecting";
        if(now-connectingSince>=5000){close();retryAfter=now+2000;state="Car connection timeout";}
        return false;
    }
    public Object manager(String name) throws Exception {
        if(!ready())return null;
        return car.getClass().getMethod("getCarManager",String.class).invoke(car,name);
    }
    public void connected(String service){state=service+" connected";}
    public String state(){return state;}
    public static Throwable cause(Throwable failure) {
        while(failure instanceof InvocationTargetException&&failure.getCause()!=null)failure=failure.getCause();
        return failure;
    }
    public void failed(Throwable failure) {
        Throwable cause=cause(failure);close();retryAfter=clock.getAsLong()+2000;
        state=cause.getClass().getSimpleName()+": "+String.valueOf(cause.getMessage());
    }
    @Override public void close() {
        if(car!=null)try{car.getClass().getMethod("disconnect").invoke(car);}catch(Exception|LinkageError ignored){}
        car=null;
    }
}
