package com.drivehub.kamera.camera;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Handler;
import android.os.IBinder;
import android.os.Parcel;

import java.lang.reflect.Method;

/** Vehicle reads and the existing SAIC projection request; never writes navigation content. */
final class IpkCarClient {
    interface Listener {
        void onTab(Integer tab, String detail);
        void onMessage(String detail);
    }
    private final Context context;
    private final Handler io;
    private final Listener listener;
    private Object car;
    private Object cluster;
    private Object properties;
    private IBinder map;
    private boolean carBound, mapBound;
    private volatile boolean closed;

    IpkCarClient(Context context, Handler io, Listener listener) {
        this.context = context;
        this.io = io;
        this.listener = listener;
    }

    private final ServiceConnection carConnection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            io.post(() -> { if (!closed) { cluster = null; properties = null; readTab(); } });
        }
        @Override public void onServiceDisconnected(ComponentName name) {
            io.post(() -> { cluster = null; properties = null; });
        }
    };

    private final ServiceConnection mapConnection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            io.post(() -> {
                if (closed) return;
                try {
                    if (!"com.saicmotor.adapterservice.IMapService".equals(binder.getInterfaceDescriptor()))
                        throw new IllegalStateException("unexpected MapService descriptor");
                    map = binder;
                    listener.onMessage("MapService connected");
                } catch (Exception e) { listener.onMessage("MapService: " + e.getMessage()); }
            });
        }
        @Override public void onServiceDisconnected(ComponentName name) {
            io.post(() -> { map = null; });
        }
        @Override public void onBindingDied(ComponentName name) {
            io.post(IpkCarClient.this::unbindMap);
        }
        @Override public void onNullBinding(ComponentName name) {
            io.post(() -> { unbindMap(); listener.onMessage("MapService returned no Binder"); });
        }
    };

    void readTab() {
        if (closed) return;
        try {
            if (car == null) {
                Class<?> type = Class.forName("android.car.Car");
                car = type.getMethod("createCar", Context.class, ServiceConnection.class)
                        .invoke(null, context, carConnection);
                if (car == null) throw new IllegalStateException("Car API unavailable");
                type.getMethod("connect").invoke(car);
                carBound = true;
            }
            Method manager = car.getClass().getMethod("getCarManager", String.class);
            if (cluster == null) cluster = manager.invoke(car, "vendorinstrumentcluster");
            Integer tab = readManager(cluster, true);
            if (tab == null) {
                if (properties == null) properties = manager.invoke(car, "property");
                tab = readManager(properties, false);
            }
            listener.onTab(tab, tab == null ? "IPK tab unavailable" : "tab=" + tab);
        } catch (Exception e) {
            listener.onTab(null, "Car API: " + rootMessage(e));
        }
    }

    private Integer readManager(Object manager, boolean vendor) {
        if (manager == null) return null;
        for (int area : vendor ? new int[]{0} : new int[]{0x1000000, 0}) {
            try {
                Object value;
                if (vendor) {
                    value = manager.getClass().getMethod("getGlobalProperty", Class.class, int.class)
                            .invoke(manager, Integer.class, IpkRoutePolicy.TAB_PROPERTY);
                } else {
                    value = manager.getClass().getMethod("getProperty", Class.class, int.class, int.class)
                            .invoke(manager, Integer.class, IpkRoutePolicy.TAB_PROPERTY, area);
                }
                if (value != null && !(value instanceof Integer)) {
                    try {
                        Object status = value.getClass().getMethod("getStatus").invoke(value);
                        if (status instanceof Integer && (Integer) status != 0) continue;
                    } catch (NoSuchMethodException ignored) { }
                    value = value.getClass().getMethod("getValue").invoke(value);
                }
                if (value instanceof Integer && (Integer) value >= 0 && (Integer) value <= 7)
                    return (Integer) value;
            } catch (Exception ignored) { }
        }
        return null;
    }

    /** Returns true only for an accepted Binder call; caller verifies the actual tab separately. */
    boolean requestProjection() {
        if (closed) return false;
        if (map == null) {
            if (!mapBound) {
                try {
                    Intent intent = new Intent().setComponent(new ComponentName(
                            "com.saicmotor.adapterservice", "com.saicmotor.adapterservice.services.MapService"));
                    mapBound = context.bindService(intent, mapConnection, Context.BIND_AUTO_CREATE);
                    if (!mapBound) listener.onMessage("MapService bind refused");
                } catch (Exception e) { listener.onMessage("MapService bind: " + rootMessage(e)); }
            }
            return false;
        }
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            data.writeInterfaceToken("com.saicmotor.adapterservice.IMapService");
            if (!map.transact(27, data, reply, 0)) throw new IllegalStateException("transaction 27 refused");
            reply.readException();
            listener.onMessage("projection request accepted; waiting for IPK tab");
            return true;
        } catch (Exception e) {
            unbindMap();
            listener.onMessage("projection failed: " + rootMessage(e));
            return false;
        } finally { data.recycle(); reply.recycle(); }
    }

    void close() {
        closed = true;
        // Called on the same IO handler as reads/transacts.
        unbindMap();
        if (carBound && car != null) {
            try { car.getClass().getMethod("disconnect").invoke(car); } catch (Exception ignored) { }
        }
        map = null; car = null; cluster = null; properties = null;
    }

    private void unbindMap() {
        map = null;
        if (mapBound) {
            try { context.unbindService(mapConnection); } catch (Exception ignored) { }
            mapBound = false;
        }
    }

    private static String rootMessage(Throwable e) {
        while (e.getCause() != null) e = e.getCause();
        return e.getClass().getSimpleName() + ": " + e.getMessage();
    }
}
