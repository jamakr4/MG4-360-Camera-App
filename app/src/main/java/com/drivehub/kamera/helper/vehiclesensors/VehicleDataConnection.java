package com.drivehub.kamera.helper.vehiclesensors;

import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.util.Log;

import java.lang.reflect.Method;

/** Shared, read-only connection to the vehicle service. Call start and close with the consumer's lifecycle. */
public final class VehicleDataConnection implements AutoCloseable {
    private static final String TAG = "VehicleDataConnection";
    private final Context context;
    private Object car;
    private Object properties;
    private Object diagnostics;
    private boolean closed;
    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            synchronized (VehicleDataConnection.this) {
                if (closed || car == null) return;
                try {
                    Method getManager = car.getClass().getMethod("getCarManager", String.class);
                    properties = getManager.invoke(car, "property");
                    try { diagnostics = getManager.invoke(car, "diagnostic"); }
                    catch (ReflectiveOperationException | RuntimeException e) {
                        Log.d(TAG, "Diagnostic manager unavailable", e);
                    }
                } catch (ReflectiveOperationException | RuntimeException e) {
                    Log.w(TAG, "Vehicle property manager unavailable", e);
                }
            }
        }
        @Override public void onServiceDisconnected(ComponentName name) {
            synchronized (VehicleDataConnection.this) { properties = null; diagnostics = null; }
        }
    };

    public VehicleDataConnection(Context context) { this.context = context.getApplicationContext(); }

    /** The car service connects asynchronously; readings are null until it is connected. */
    public synchronized void start() {
        if (car != null || closed) return;
        try {
            Class<?> carClass = Class.forName("android.car.Car");
            car = carClass.getMethod("createCar", Context.class, ServiceConnection.class)
                    .invoke(null, context, serviceConnection);
            if (car != null) carClass.getMethod("connect").invoke(car);
        } catch (ReflectiveOperationException | RuntimeException e) {
            Log.w(TAG, "Could not connect to vehicle service", e);
            car = null;
        }
    }

    /** Returns null on disconnect, denied access, unsupported properties or unavailable status. */
    public synchronized Number readProperty(int propertyId) {
        if (properties == null) return null;
        try {
            Object value = properties.getClass().getMethod("getProperty", int.class, int.class)
                    .invoke(properties, propertyId, 0);
            if (value == null || ((Number) value.getClass().getMethod("getStatus").invoke(value)).intValue() != 0) return null;
            Object raw = value.getClass().getMethod("getValue").invoke(value);
            if (!(raw instanceof Number)) return null;
            Number number = (Number) raw;
            return Double.isFinite(number.doubleValue()) ? number : null;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    /** Returns null when OBD live frames or the selected diagnostic sensor are unavailable. */
    public synchronized Float readDiagnosticFloat(int sensorIndex) {
        if (diagnostics == null) return null;
        try {
            Object event = diagnostics.getClass().getMethod("getLatestLiveFrame").invoke(diagnostics);
            if (event == null) return null;
            Object value = event.getClass().getMethod("getSystemFloatSensor", int.class).invoke(event, sensorIndex);
            return value instanceof Float && Float.isFinite((Float) value) ? (Float) value : null;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    @Override public synchronized void close() {
        closed = true;
        properties = null;
        diagnostics = null;
        if (car != null) {
            try { car.getClass().getMethod("disconnect").invoke(car); }
            catch (ReflectiveOperationException | RuntimeException e) { Log.d(TAG, "Disconnect failed", e); }
            car = null;
        }
    }
}
