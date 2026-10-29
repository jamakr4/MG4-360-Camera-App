package com.drivehub.kamera.dashcam;

/** Prevent UI probes/exports from starting writes after eject or shutdown begins. */
public final class StorageIoGate {
    private static boolean closed;
    private static int users;
    public static synchronized boolean begin() { if (closed) return false; users++; return true; }
    public static synchronized void end() { users--; StorageIoGate.class.notifyAll(); }
    public static synchronized void close() { closed = true; }
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
