package com.drivehub.kamera.side;
/** Feeds generated/private in-memory stills into the exact production compositor, without camera access. */
public final class SidePreviewDebugBridge {
    static{System.loadLibrary("cameraprobe");}
    private SidePreviewDebugBridge(){}
    public static int[] compose(int[] argb,int height,boolean left,boolean wheels,int frontMode,int rearMode,
            float frontDegrees,float rearDegrees,boolean highlight,float[] crops,float[] frontUv,float[] rearUv){
        return composeNative(argb,height,left,wheels,frontMode,rearMode,frontDegrees,rearDegrees,highlight,crops,frontUv,rearUv,false,null);
    }
    public static int[] composeSides(int[] argb,int height,boolean left,boolean corrected,boolean highlight,float[] crops,float[] sideUv){
        return composeNative(argb,height,left,false,0,0,0,0,highlight,crops,null,null,corrected,sideUv);
    }
    private static native int[] composeNative(int[] argb,int height,boolean left,boolean wheels,int frontMode,int rearMode,
            float frontDegrees,float rearDegrees,boolean highlight,float[] crops,float[] frontUv,float[] rearUv,boolean correctSides,float[] sideUv);
}
