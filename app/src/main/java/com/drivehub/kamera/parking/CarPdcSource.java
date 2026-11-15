package com.drivehub.kamera.parking;

import android.content.Context;
import android.os.SystemClock;
import com.drivehub.kamera.helper.vehiclesensors.CarServiceConnection;
import com.drivehub.kamera.helper.vehiclesensors.CarApiFactory;
import java.lang.reflect.Method;
import java.util.function.LongSupplier;

/** SAIC exposes rear PDC through parkingassistance; newer platforms may expose property. */
final class CarPdcSource implements PdcReader.Source {
    private final CarServiceConnection connection;
    private final LongSupplier clock;
    private Object manager;
    private Method getter;
    private boolean typed;
    private String service="",readError="";
    private int errors;
    CarPdcSource(Context context) {
        Context app=context.getApplicationContext();clock=SystemClock::elapsedRealtime;
        connection=new CarServiceConnection(()->CarApiFactory.create(app),clock);
    }
    CarPdcSource(CarServiceConnection connection,LongSupplier clock){this.connection=connection;this.clock=clock;}
    @Override public PdcSnapshot read() {
        long started=clock.getAsLong();
        try {
            if(!connect())return PdcSnapshot.EMPTY;
            errors=0;readError="";
            int[] levels=new int[4];for(int i=0;i<4;i++)levels[i]=read(PdcReader.SECTORS[i]);
            int cm=read(PdcReader.CLOSEST_CM),status=read(PdcReader.STATUS);
            if(errors==6) {connection.failed(new IllegalStateException(readError));manager=null;getter=null;return PdcSnapshot.EMPTY;}
            return new PdcSnapshot(levels,cm,status,started);
        }catch(Exception|LinkageError failure){manager=null;getter=null;connection.failed(failure);return PdcSnapshot.EMPTY;}
    }
    private boolean connect() throws Exception {
        if(manager!=null)return true;
        if(!connection.ready())return false;
        Exception last=null;
        for(String candidate:new String[]{"parkingassistance","property"}) {
            try {
                Object found=connection.manager(candidate);if(found==null)continue;
                Method method;boolean usesType;
                try{method=found.getClass().getMethod("getProperty",Class.class,int.class,int.class);usesType=true;}
                catch(NoSuchMethodException newer){method=found.getClass().getMethod("getProperty",int.class,int.class);usesType=false;}
                manager=found;getter=method;typed=usesType;service=candidate;connection.connected(candidate);return true;
            }catch(Exception failure){last=failure;}
        }
        if(last!=null)throw last;throw new IllegalStateException("No readable parkingassistance/property manager");
    }
    private int read(int property) {
        try {
            Object value=typed?getter.invoke(manager,Integer.class,property,0):getter.invoke(manager,property,0);
            return PdcReader.decode(value,typed);
        }catch(Exception failure){errors++;Throwable cause=CarServiceConnection.cause(failure);
            readError="0x"+Integer.toHexString(property)+": "+cause.getClass().getSimpleName()+": "+cause.getMessage();return -1;}
    }
    @Override public String diagnostic(){return connection.state()+(service.isEmpty()?"":" / "+(typed?"typed Integer":"CarPropertyValue"))+(readError.isEmpty()?"":" / "+readError);}
    @Override public void close(){manager=null;getter=null;service="";readError="";connection.close();}
}
