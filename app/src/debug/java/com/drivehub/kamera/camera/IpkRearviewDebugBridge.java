package com.drivehub.kamera.camera;

/** In-memory pixels through the live IPK compositor; no camera hardware is opened. */
public final class IpkRearviewDebugBridge {
    static{System.loadLibrary("cameraprobe");}
    private IpkRearviewDebugBridge(){}
    public static native int[] render(int[] pixels,int height,float[] coordinates);
}
