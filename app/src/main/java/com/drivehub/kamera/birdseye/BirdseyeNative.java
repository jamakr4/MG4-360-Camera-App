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
    static native long clockUs();
}
