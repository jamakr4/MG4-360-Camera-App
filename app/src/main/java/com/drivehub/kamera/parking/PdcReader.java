package com.drivehub.kamera.parking;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import com.drivehub.kamera.signal.SignalService;
import com.drivehub.kamera.dev.DevRuntimeLog;
import java.util.function.BooleanSupplier;

/**
 * Polls rear sectors, minimum distance and status from OEM system properties off
 * the UI thread.
 */
public final class PdcReader {
    public static final int STATUS = 0x2140b970, CLOSEST_CM = 0x2140b97b;
    public static final int[] SECTORS = { 0x2140b976, 0x2140b977, 0x2140b978, 0x2140b979 };

    public interface Listener {
        void sample(PdcSnapshot snapshot);
    }

    interface Source {
        PdcSnapshot read();

        void close();

        String diagnostic();
    }

    private static volatile PdcSnapshot latest = PdcSnapshot.EMPTY;
    private static volatile String sourceState = "paused";

    public static String diagnostic() {
        return sourceState + "\n" + latest.diagnostic(SystemClock.elapsedRealtime())
                + "\nSpatial positions: new table; valid closest cm replaces nearest-level sectors onl; not calibratedy"
                + "\n" + PdcOverlayView.projectionDiagnostic();
    }

    private final Source source;
    private final BooleanSupplier reverse;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final HandlerThread thread = new HandlerThread("Rear-PDC-reader");
    private final Handler io;
    private boolean running, closed;
    private volatile int generation;
    private String lastState = "";

    public PdcReader(Context context, Listener listener) {
        this(new SystemPropertyPdcSource(), SignalService::isReverseActive, listener);
    }

    PdcReader(Source source, BooleanSupplier reverse, Listener listener) {
        this.source = source;
        this.reverse = reverse;
        this.listener = listener;
        thread.start();
        io = new Handler(thread.getLooper());
    }

    public void start() {
        if (running || closed)
            return;
        running = true;
        int token = ++generation;
        io.post(() -> tick(token));
    }

    public void stop() {
        running = false;
        int token = ++generation;
        io.removeCallbacksAndMessages(null);
        publish(PdcSnapshot.EMPTY, "paused", token);
        io.post(source::close);
    }

    public void close() {
        if (closed)
            return;
        stop();
        closed = true;
        io.post(thread::quitSafely);
    }

    private void publish(PdcSnapshot value, String state, int token) {
        main.post(() -> {
            if (token != generation)
                return;
            latest = value;
            sourceState = state;
            listener.sample(value);
        });
    }

    private void tick(int token) {
        if (token != generation)
            return;
        PdcSnapshot value = PdcSnapshot.EMPTY;
        String state = "outside R";
        try {
            if (reverse.getAsBoolean()) {
                value = source.read();
                state = source.diagnostic();
            } else
                source.close();
        } catch (RuntimeException | LinkageError failure) {
            state = failure.getClass().getSimpleName() + ": " + failure.getMessage();
        }
        if (value == null || !reverse.getAsBoolean() || !value.fresh(SystemClock.elapsedRealtime()))
            value = PdcSnapshot.EMPTY;
        if (!state.equals(lastState)) {
            DevRuntimeLog.add("Rear PDC", state);
            lastState = state;
        }
        publish(value, state, token);
        if (token == generation)
            io.postDelayed(() -> tick(token), 100);
    }

    /**
     * The legacy typed overload returns E directly; the newer overload returns
     * CarPropertyValue<E>.
     */
    static int decode(Object value, boolean typed) {
        try {
            if (typed && value instanceof Number)
                return ((Number) value).intValue();
            if (value == null)
                return -1;
            Object status = value.getClass().getMethod("getStatus").invoke(value);
            if (!(status instanceof Number) || ((Number) status).intValue() != 0)
                return -1;
            Object raw = value.getClass().getMethod("getValue").invoke(value);
            return raw instanceof Number ? ((Number) raw).intValue() : -1;
        } catch (Exception e) {
            return -1;
        }
    }
}
