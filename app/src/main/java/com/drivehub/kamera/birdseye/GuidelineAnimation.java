package com.drivehub.kamera.birdseye;

import com.drivehub.kamera.steering.WheelGuides;

/** GL-thread reveal state, shared across Normal, Wide and 3D. */
final class GuidelineAnimation {
    static final long DURATION_MS=350;
    private final long durationMs;
    private long lastMs=-1;
    private float reverse,forward;
    private WheelGuides.Direction direction;
    private boolean crossfading;

    GuidelineAnimation(){this(DURATION_MS);}
    GuidelineAnimation(long durationMs){this.durationMs=Math.max(0,durationMs);}

    float update(boolean show,boolean available,WheelGuides.Direction nextDirection,long nowMs) {
        // Unavailable geometry/cameras must never leave a stale predicted path on screen.
        if(!available||nextDirection==null){reset();return 0;}
        if(durationMs==0) {
            reverse=show&&nextDirection==WheelGuides.Direction.REVERSE?1:0;
            forward=show&&nextDirection==WheelGuides.Direction.FORWARD?1:0;
            direction=nextDirection;crossfading=false;return progress(nextDirection);
        }
        if(lastMs<0||nowMs<lastMs) {
            reset();lastMs=nowMs;direction=nextDirection;return 0;
        }
        if(direction!=nextDirection&&(reverse>0||forward>0))crossfading=true;
        direction=nextDirection;
        float step=(float)(nowMs-lastMs)/durationMs;
        reverse=approach(reverse,show&&direction==WheelGuides.Direction.REVERSE,step);
        forward=approach(forward,show&&direction==WheelGuides.Direction.FORWARD,step);
        lastMs=nowMs;
        float target=direction==WheelGuides.Direction.REVERSE?reverse:forward,other=direction==WheelGuides.Direction.REVERSE?forward:reverse;
        if(show?target==1&&other==0:reverse==0&&forward==0)crossfading=false;
        return progress(nextDirection);
    }
    private static float approach(float value,boolean show,float step){return Math.max(0,Math.min(1,value+(show?step:-step)));}
    float progress(WheelGuides.Direction direction) {
        float p=direction==WheelGuides.Direction.REVERSE?reverse:forward;
        return p*p*(3-2*p);
    }
    boolean crossfading(){return crossfading;}
    void invalidate(WheelGuides.Direction direction) {
        if(direction==WheelGuides.Direction.REVERSE)reverse=0;else forward=0;
    }
    void reset(){lastMs=-1;reverse=forward=0;direction=null;crossfading=false;}
}
