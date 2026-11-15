package com.drivehub.kamera.helper.vehiclesensors;

import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

/** Old AAOS requires a ServiceConnection; the one-Context factory only exists on newer systems. */
public final class CarApiFactory {
    private CarApiFactory(){}
    public static Object create(Context context) throws Exception {
        ServiceConnection observer=new ServiceConnection(){
            @Override public void onServiceConnected(ComponentName name,IBinder service){}
            @Override public void onServiceDisconnected(ComponentName name){}
        };
        return create(Class.forName("android.car.Car"),Context.class,context.getApplicationContext(),
                ServiceConnection.class,observer,Handler.class,new Handler(Looper.getMainLooper()));
    }
    /** Signature selection is tested without loading an android.car implementation. */
    static Object create(Class<?> car,Class<?> contextType,Object context,Class<?> callbackType,Object callback,
                         Class<?> handlerType,Object handler) throws Exception {
        try{return car.getMethod("createCar",contextType,callbackType,handlerType).invoke(null,context,callback,handler);}
        catch(NoSuchMethodException withoutHandler){
            try{return car.getMethod("createCar",contextType,callbackType).invoke(null,context,callback);}
            catch(NoSuchMethodException modern){return car.getMethod("createCar",contextType).invoke(null,context);}
        }
    }
}
