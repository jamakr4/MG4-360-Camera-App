package com.drivehub.kamera.side;

/** Shared stream-manager taps for the two side cameras; no OEM renderer or assets. */
final class SideCameraNative {
    static {
        System.loadLibrary("cameraprobe");
    }

    private SideCameraNative() {}

    static native void enablePreviews();
    static native void setWheelsMode(boolean wheels);
    static native void setWheelAlignment(boolean frontEnabled, boolean rearEnabled,
                                         float frontDegrees, float rearDegrees);
    static native void setWheelRendering(int frontMode,int rearMode,float frontDegrees,float rearDegrees);
    static native void setAdvancedSession(int token);
    static native boolean setAdvancedMaps(int token,String key,float[] leftFront,float[] leftRear,float[] rightFront,float[] rightRear,float[] leftSide,float[] rightSide);
    static boolean maps(int token, String key, float[][] maps) {
        if (maps == null) return setAdvancedMaps(token, key, null, null, null, null, null, null);
        return setAdvancedMaps(token, key, maps[0], maps[1], maps[2], maps[3], maps[4], maps[5]);
    }
    static native void setSideCorrection(boolean enabled);
    static native void setHighlightMissingAreas(boolean enabled);
    static native boolean attachPreview(int side, android.view.Surface surface, float[] crops);
    static native void detachPreview(int side);
    static native void detachPreviews();
    static native long previewAgeMs(int side);
}
