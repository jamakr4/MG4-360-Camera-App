package com.drivehub.kamera.camera;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Handler;
import android.os.IBinder;
import android.os.Parcel;
import android.os.SystemClock;

import java.lang.reflect.Method;
import java.util.function.BooleanSupplier;
import com.drivehub.kamera.camera.IpkRoutePolicy.RequestResult;

/** All methods run on the car IO handler, never on the preview or UI thread. */
final class IpkCarClient {
    interface Listener {
        void onTab(Integer tab, String detail);
        void onMessage(String detail);
    }
    private static final String MAP_DESCRIPTOR = "com.saicmotor.adapterservice.IMapService";
    // The exact two writes performed by the OEM ClusterManager.requestProjection().
    private static final int FICM_START_UP = 0x21407b00;
    private static final int FICM_CENTRE_AREA = 0x21407b04;
    private static final long CONNECT_TIMEOUT_MS = 2000;
    private final Context context;
    private final Handler io;
    private final Listener listener;
    private Object car, cluster, properties;
    private IBinder map;
    private Integer currentTab;
    private boolean carBound, mapBound, closed;
    private long mapBindingAt;
    private String bindFailure;

    IpkCarClient(Context context, Handler io, Listener listener) {
        this.context = context; this.io = io; this.listener = listener;
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
                    if (!MAP_DESCRIPTOR.equals(binder.getInterfaceDescriptor()))
                        throw new IllegalStateException("unexpected MapService descriptor");
                    map = binder;
                    bindFailure = null;
                    listener.onMessage("MapService verbunden");
                } catch (Exception e) {
                    unbindMap();
                    bindFailure = rootMessage(e);
                    listener.onMessage("MapService: " + bindFailure);
                }
            });
        }
        @Override public void onServiceDisconnected(ComponentName name) { io.post(() -> map = null); }
        @Override public void onBindingDied(ComponentName name) { io.post(IpkCarClient.this::unbindMap); }
        @Override public void onNullBinding(ComponentName name) {
            io.post(() -> { unbindMap(); bindFailure = "kein Binder"; listener.onMessage(bindFailure); });
        }
    };

    void prepare(boolean autoRoute) {
        readTab();
        if (autoRoute && !closed) bindMap();
    }

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
            currentTab = tab;
            listener.onTab(tab, tab == null ? "IPK-Seite nicht lesbar" : "Seite=" + tab);
        } catch (Exception e) {
            currentTab = null;
            listener.onTab(null, "Car API: " + rootMessage(e));
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

    private void bindMap() {
        if (mapBound || closed) return;
        if (mapBindingAt != 0 && SystemClock.elapsedRealtime() - mapBindingAt < 5000) return;
        bindFailure = null;
        mapBindingAt = SystemClock.elapsedRealtime();
        try {
            Intent intent = new Intent().setComponent(new ComponentName(
                    "com.saicmotor.adapterservice", "com.saicmotor.adapterservice.services.MapService"));
            mapBound = context.bindService(intent, mapConnection, Context.BIND_AUTO_CREATE);
            if (!mapBound) bindFailure = "Bind abgelehnt";
        } catch (Exception e) { bindFailure = rootMessage(e); }
        if (bindFailure != null) listener.onMessage("MapService: " + bindFailure);
    }

    RequestResult requestProjection(boolean directFallback, BooleanSupplier allowed) {
        if (closed || !allowed.getAsBoolean()) return RequestResult.CANCELLED;
        // Recheck after any queued Binder work, so a newly selected priority page
        // does not receive a request based on the main thread's older snapshot.
        readTab();
        if (!allowed.getAsBoolean() || currentTab == null || (currentTab != 0 && currentTab != 5))
            return RequestResult.CANCELLED;
        if (directFallback) return requestDirect(allowed);
        bindMap();
        if (map == null) {
            if (bindFailure == null && SystemClock.elapsedRealtime() - mapBindingAt < CONNECT_TIMEOUT_MS)
                return RequestResult.WAITING;
            listener.onMessage("MapService nicht bereit: " + (bindFailure == null ? "Zeitlimit" : bindFailure));
            return RequestResult.FAILED;
        }
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            if (!allowed.getAsBoolean()) return RequestResult.CANCELLED;
            data.writeInterfaceToken(MAP_DESCRIPTOR);
            if (!map.transact(27, data, reply, 0)) throw new IllegalStateException("transaction 27 refused");
            reply.readException();
            listener.onMessage("MapService-Anforderung gesendet; warte auf IPK-Seite");
            return RequestResult.SENT;
        } catch (Exception e) {
            unbindMap();
            listener.onMessage("MapService-Anforderung fehlgeschlagen: " + rootMessage(e));
            return RequestResult.FAILED;
        } finally { data.recycle(); reply.recycle(); }
    }

    private RequestResult requestDirect(BooleanSupplier allowed) {
        try {
            if (cluster == null && car != null)
                cluster = car.getClass().getMethod("getCarManager", String.class)
                        .invoke(car, "vendorinstrumentcluster");
            if (cluster == null) throw new IllegalStateException("Cluster-Manager nicht bereit");
            Method set = cluster.getClass().getMethod("setGlobalProperty", Class.class, int.class, Object.class);
            if (!allowed.getAsBoolean()) return RequestResult.CANCELLED;
            set.invoke(cluster, Integer.class, FICM_START_UP, 1);
            set.invoke(cluster, Integer.class, FICM_CENTRE_AREA, 1);
            listener.onMessage("Direkte OEM-Anforderung gesendet (7b00=1,7b04=1); warte auf IPK-Seite");
            return RequestResult.SENT;
        } catch (Exception e) {
            listener.onMessage("Direkte OEM-Anforderung fehlgeschlagen: " + rootMessage(e));
            return RequestResult.FAILED;
        }
    }

    void close() {
        closed = true;
        unbindMap();
        if (carBound && car != null) {
            try { car.getClass().getMethod("disconnect").invoke(car); } catch (Exception ignored) { }
        }
        car = null; cluster = null; properties = null;
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
