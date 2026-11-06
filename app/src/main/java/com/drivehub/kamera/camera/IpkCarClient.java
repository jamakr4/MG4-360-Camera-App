package com.drivehub.kamera.camera;

import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.os.Handler;
import android.os.IBinder;

import java.lang.reflect.Method;

/** Reads the current IPK page on its own IO handler without changing vehicle UI or focus. */
final class IpkCarClient implements AutoCloseable {
    interface Listener {
        void onTab(Integer tab, String detail);
    }

    private final Context context;
    private final Handler io;
    private final Listener listener;
    private Object car, cluster, properties;
    private boolean carBound, closed;

    IpkCarClient(Context context, Handler io, Listener listener) {
        this.context = context;
        this.io = io;
        this.listener = listener;
    }

    private final ServiceConnection carConnection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            io.post(() -> {
                if (!closed) { cluster = null; properties = null; readTab(); }
            });
        }
        @Override public void onServiceDisconnected(ComponentName name) {
            io.post(() -> { cluster = null; properties = null; });
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
            listener.onTab(tab, tab == null ? "IPK page unavailable" : "IPK page=" + tab);
        } catch (Exception e) {
            listener.onTab(null, "Car API: " + e.getClass().getSimpleName());
        }
    }

    private Integer readManager(Object manager, boolean vendor) {
        if (manager == null) return null;
        for (int area : vendor ? new int[]{0} : new int[]{0x1000000, 0}) {
            try {
                Object value = vendor
                        ? manager.getClass().getMethod("getGlobalProperty", Class.class, int.class)
                            .invoke(manager, Integer.class, IpkRoutePolicy.TAB_PROPERTY)
                        : manager.getClass().getMethod("getProperty", Class.class, int.class, int.class)
                            .invoke(manager, Integer.class, IpkRoutePolicy.TAB_PROPERTY, area);
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

    @Override public void close() {
        closed = true;
        if (carBound && car != null) {
            try { car.getClass().getMethod("disconnect").invoke(car); } catch (Exception ignored) { }
        }
        car = null; cluster = null; properties = null;
    }
}
