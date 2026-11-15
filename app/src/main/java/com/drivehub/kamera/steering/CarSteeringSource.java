package com.drivehub.kamera.steering;

import android.content.Context;
import android.os.SystemClock;
import com.drivehub.kamera.helper.vehiclesensors.CarServiceConnection;
import com.drivehub.kamera.helper.vehiclesensors.CarApiFactory;
import java.lang.reflect.*;
import java.util.function.LongSupplier;

/** Subscribe to SAIC's legacy Float sensor; retain modern CarPropertyManager as a fallback. */
final class CarSteeringSource implements SteeringReader.Source {
    private final CarServiceConnection connection;
    private final LongSupplier clock;
    private Object manager,listener;
    private Method getter,unregister;
    private boolean sensor,typed;
    private volatile int sensorGeneration;
    private volatile SteeringSnapshot callback=SteeringSnapshot.EMPTY;
    CarSteeringSource(Context context) {
        Context app=context.getApplicationContext();clock=SystemClock::elapsedRealtime;
        connection=new CarServiceConnection(()->CarApiFactory.create(app),clock);
    }
    CarSteeringSource(CarServiceConnection connection,LongSupplier clock){this.connection=connection;this.clock=clock;}
    @Override public SteeringSnapshot read() {
        long started=clock.getAsLong();
        try {
            if(!connect())return SteeringSnapshot.unavailable(connection.state(),started);
            if(sensor) {
                Object event=getter.invoke(manager,SteeringReader.PROPERTY);
                if(event!=null)return SteeringSnapshot.decodeSensor(event,started).from("sensor");
                SteeringSnapshot last=callback;
                if(last.readElapsedMs>0&&started>=last.readElapsedMs&&started-last.readElapsedMs<=SteeringSnapshot.MAX_AGE_MS)return last.from("sensor callback");
                return SteeringSnapshot.unavailable("sensor: waiting for steering event",started);
            }
            Object value=typed?getter.invoke(manager,Float.class,SteeringReader.PROPERTY,0):getter.invoke(manager,SteeringReader.PROPERTY,0);
            return SteeringSnapshot.decode(value,typed,started).from("property");
        }catch(Exception|LinkageError failure){clearManager();connection.failed(failure);return SteeringSnapshot.unavailable(connection.state(),started);}
    }
    private boolean connect() throws Exception {
        if(!connection.ready()){clearManager();return false;}
        if(manager!=null)return true;
        Exception legacyFailure=null;
        try {
            Object legacy=connection.manager("sensor");
            if(legacy!=null){subscribe(legacy);connection.connected("sensor");return true;}
        }catch(Exception failure){legacyFailure=failure;clearManager();}
        Object modern=connection.manager("property");
        if(modern==null){if(legacyFailure!=null)throw legacyFailure;throw new IllegalStateException("No sensor/property manager");}
        try{getter=modern.getClass().getMethod("getProperty",Class.class,int.class,int.class);typed=true;}
        catch(NoSuchMethodException oldOverload){getter=modern.getClass().getMethod("getProperty",int.class,int.class);typed=false;}
        manager=modern;sensor=false;connection.connected("property");return true;
    }
    private void subscribe(Object legacy) throws Exception {
        Method latest=legacy.getClass().getMethod("getLatestSensorEvent",int.class),register=null;
        for(Method method:legacy.getClass().getMethods()) {
            Class<?>[] p=method.getParameterTypes();
            if(method.getName().equals("registerListener")&&p.length==3&&p[0].isInterface()&&p[1]==int.class&&p[2]==int.class){register=method;break;}
        }
        if(register==null)throw new NoSuchMethodException("CarSensorManager.registerListener");
        Class<?> callbackType=register.getParameterTypes()[0];
        try{unregister=legacy.getClass().getMethod("unregisterListener",callbackType,int.class);}
        catch(NoSuchMethodException oneListener){unregister=legacy.getClass().getMethod("unregisterListener",callbackType);}
        int token=++sensorGeneration;
        listener=Proxy.newProxyInstance(callbackType.getClassLoader(),new Class<?>[]{callbackType},(proxy,method,args)->{
            if(method.getDeclaringClass()==Object.class){
                if(method.getName().equals("hashCode"))return System.identityHashCode(proxy);
                if(method.getName().equals("equals"))return args!=null&&args.length==1&&proxy==args[0];
                return "MG4 steering listener";
            }
            if(token==sensorGeneration&&method.getName().equals("onSensorChanged")&&args!=null&&args.length==1) {
                SteeringSnapshot sample;
                try{sample=SteeringSnapshot.decodeSensor(args[0],clock.getAsLong());}
                catch(Exception failure){sample=SteeringSnapshot.unavailable("sensor decode: "+failure.getClass().getSimpleName(),clock.getAsLong());}
                if(token==sensorGeneration)callback=sample;
            }
            return null;
        });
        manager=legacy;sensor=true;getter=latest;
        Object accepted=register.invoke(manager,listener,SteeringReader.PROPERTY,1);
        if(Boolean.FALSE.equals(accepted))throw new IllegalStateException("Legacy steering sensor not supported");
    }
    private void clearManager() {
        ++sensorGeneration;callback=SteeringSnapshot.EMPTY;
        if(manager!=null&&listener!=null&&unregister!=null)try {
            if(unregister.getParameterTypes().length==2)unregister.invoke(manager,listener,SteeringReader.PROPERTY);
            else unregister.invoke(manager,listener);
        }catch(Exception|LinkageError ignored){}
        manager=null;listener=null;getter=null;unregister=null;sensor=false;
    }
    @Override public void close(){clearManager();connection.close();}
}
