package com.drivehub.kamera.birdseye;

import com.drivehub.kamera.steering.WheelGuides;

/** GL-thread reveal state, shared across Normal, Wide and 3D. */
final class GuidelineAnimation {
    static final long DURATION_MS=350;
    static final long RESUME_WINDOW_MS=500;
    private final long durationMs;
    private long lastMs=-1,unavailableSinceMs=-1;
    private float reverse,forward;
    private WheelGuides.Direction direction;
    private boolean crossfading;

    GuidelineAnimation(){this(DURATION_MS);}
    GuidelineAnimation(long durationMs){this.durationMs=Math.max(0,durationMs);}

    float update(boolean show,boolean available,WheelGuides.Direction nextDirection,long nowMs) {
        if(lastMs>=0&&nowMs<lastMs)reset();
        // Hide unavailable predictions immediately, but retain a brief interruption's reveal state.
        if(!available||nextDirection==null) {
            if(!show)reset();
            if(unavailableSinceMs<0)unavailableSinceMs=nowMs;
            lastMs=nowMs;return 0;
        }
        if(unavailableSinceMs>=0) {
            if(!show||direction!=nextDirection||nowMs-unavailableSinceMs>RESUME_WINDOW_MS)reset();
            else {unavailableSinceMs=-1;lastMs=nowMs;} // Pause the animation clock through the gap.
        }
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
        if(unavailableSinceMs>=0)return 0;
        float p=direction==WheelGuides.Direction.REVERSE?reverse:forward;
        return p*p*(3-2*p);
    }
    boolean crossfading(){return crossfading;}
    void invalidateOutgoing(WheelGuides.Direction direction) {
        // The current path is suspended by update(); only discard an expired outgoing layer.
        if(direction==this.direction)return;
        if(direction==WheelGuides.Direction.REVERSE)reverse=0;else forward=0;
    }
    void reset(){lastMs=unavailableSinceMs=-1;reverse=forward=0;direction=null;crossfading=false;}
}
