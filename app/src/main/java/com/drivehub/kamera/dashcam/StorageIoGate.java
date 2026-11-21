package com.drivehub.kamera.dashcam;

/** Prevent UI probes/exports from starting writes after eject or shutdown begins. */
public final class StorageIoGate {
    private static boolean closed;
    private static int users;
    private static final java.util.Set<Runnable> closeListeners = new java.util.HashSet<>();
    public static synchronized boolean begin() { if (closed) return false; users++; return true; }
    public static synchronized void end() { users--; StorageIoGate.class.notifyAll(); }
    public static void close() {
        Runnable[] listeners;
        synchronized (StorageIoGate.class) {
            closed = true;
            listeners = closeListeners.toArray(new Runnable[0]);
        }
        for (Runnable listener : listeners) listener.run();
    }
    public static synchronized boolean addCloseListener(Runnable listener) {
        if (closed) return false;
        closeListeners.add(listener);
        return true;
    }
    public static synchronized void removeCloseListener(Runnable listener) { closeListeners.remove(listener); }
    public static synchronized void reopen() { closed = false; }
    public static synchronized boolean awaitIdle(long timeoutMs) throws InterruptedException {
        long deadline = System.nanoTime() + timeoutMs * 1_000_000;
        while (users > 0) {
            long left = (deadline - System.nanoTime()) / 1_000_000;
            if (left <= 0) return false;
            StorageIoGate.class.wait(left);
        }
        return true;
    }
}
