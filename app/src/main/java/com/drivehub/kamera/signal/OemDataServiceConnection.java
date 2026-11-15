package com.drivehub.kamera.signal;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.SystemClock;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/** Verify the actual service endpoint, including SAIC's valid null-binder implementation.
 * Never use getRunningServices as an ownership gate: that API can hide other packages.
 */
final class OemDataServiceConnection implements AutoCloseable {
    interface Backend {
        ComponentName start(ComponentName target);
        boolean bind(ComponentName target, ServiceConnection connection);
        void unbind(ServiceConnection connection);
    }
    private static final long PROBE_MS = 1000, PROOF_MS = 3000;
    private final Backend backend;
    private final ComponentName target;
    private final LongSupplier clock;
    private final Consumer<String> log;
    private ServiceConnection connection;
    private IBinder binder;
    private boolean started, closed, nullBinder;
    private long confirmedAt = -1, lastProbeAt = -1;
    private int attempts;

    OemDataServiceConnection(Context context, ComponentName target, Consumer<String> log) {
        this(new Backend() {
            public ComponentName start(ComponentName service) { return context.startService(new Intent().setComponent(service)); }
            public boolean bind(ComponentName service, ServiceConnection callback) {
                return context.bindService(new Intent().setComponent(service), callback, Context.BIND_AUTO_CREATE);
            }
            public void unbind(ServiceConnection callback) { context.unbindService(callback); }
        }, target, SystemClock::elapsedRealtime, log);
    }

    OemDataServiceConnection(Backend backend, ComponentName target, LongSupplier clock, Consumer<String> log) {
        this.backend = backend; this.target = target; this.clock = clock; this.log = log;
    }

    synchronized void start() {
        if (closed) throw new IllegalStateException("Data-service connection closed");
        if (!started) {
            if (!target.equals(backend.start(target))) throw new IllegalStateException("OEM data-service start rejected");
            started = true;
            log.accept("OEM data service start accepted");
        }
        if (connection == null) probe();
    }

    synchronized boolean running() {
        if (closed || !started) return false;
        long now = clock.getAsLong();
        if (binder != null && binder.isBinderAlive()) return true;
        // A null binder is normal for CarManagerServer. Re-probe it periodically rather
        // than assuming a cached lifecycle property or an empty service list means alive/dead.
        if ((connection == null || nullBinder) && (lastProbeAt < 0 || now - lastProbeAt >= PROBE_MS)
                && attempts < 3) probe();
        return confirmedAt >= 0 && now - confirmedAt <= PROOF_MS;
    }

    private void probe() {
        lastProbeAt = clock.getAsLong(); attempts++;
        unbind();
        ServiceConnection next = new ServiceConnection() {
            @Override public void onServiceConnected(ComponentName name, IBinder endpoint) { confirm(this, endpoint, false); }
            @Override public void onNullBinding(ComponentName name) { confirm(this, null, true); }
            @Override public void onServiceDisconnected(ComponentName name) { lost(this, "disconnected"); }
            @Override public void onBindingDied(ComponentName name) { lost(this, "binding died"); }
        };
        connection = next;
        try {
            if (!backend.bind(target, next)) {
                try { backend.unbind(next); } catch (IllegalArgumentException ignored) { }
                connection = null; confirmedAt = -1; nullBinder = false;
                log.accept("OEM data-service bind rejected");
            }
        } catch (RuntimeException error) {
            try { backend.unbind(next); } catch (IllegalArgumentException ignored) { }
            connection = null; confirmedAt = -1; throw error;
        }
    }

    private synchronized void confirm(ServiceConnection expected, IBinder endpoint, boolean empty) {
        if (closed || connection != expected) return;
        binder = endpoint; nullBinder = empty; confirmedAt = clock.getAsLong(); attempts = 0;
        log.accept(empty ? "OEM data service confirmed (null binder)" : "OEM data service confirmed (binder)");
    }

    private synchronized void lost(ServiceConnection expected, String reason) {
        if (closed || connection != expected) return;
        confirmedAt = -1; binder = null; nullBinder = true;
        log.accept("OEM data service " + reason);
    }

    private void unbind() {
        ServiceConnection previous = connection; connection = null; binder = null;
        if (previous != null) try { backend.unbind(previous); }
        catch (IllegalArgumentException ignored) { /* The framework may already have removed it. */ }
    }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true; confirmedAt = -1; unbind();
        // It was explicitly started: unbinding must never stop the stock data service.
    }
}
