package com.drivehub.kamera.birdseye;

import java.nio.ByteBuffer;

/** JNI contract: bounded UYVY top-field mailbox; see birdseye_mailbox.h. */
final class BirdseyeNative {
    static { System.loadLibrary("cameraprobe"); }
    private BirdseyeNative() {}
    static native boolean attach(int owner, int camera);
    static native boolean hold(int owner,int camera);
    static native void releaseHold(int owner);
    static native boolean attachSnapshot(int owner,int camera);
    static native long[] copySnapshot(int owner,int camera,ByteBuffer output,long afterUs);
    static native void detach(int owner);
    static native long copy(int owner, int camera, ByteBuffer output, long afterUs);
    /** Newest receipt per camera (0 = none) without copying pixels; false if the registry is busy. */
    static native boolean latest(int owner, long[] latestUs);
    static native long clockUs();
}
