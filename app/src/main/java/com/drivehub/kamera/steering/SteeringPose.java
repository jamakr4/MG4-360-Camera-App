package com.drivehub.kamera.steering;

/** UI-thread pose smoothing; missing or expired input clears immediately instead of holding a turn. */
public final class SteeringPose {
    private boolean initialized;
    private long lastMs;
    private float degrees;
    public float update(SteeringSnapshot sample, long nowMs, float scale, boolean invert) {
        if (!sample.fresh(nowMs)) { reset(); return 0; }
        float target=SteeringMapping.degrees(sample.rawDegrees,scale,invert);
        if (!initialized) { degrees=target; initialized=true; }
        else {
            long dt=Math.max(0,Math.min(200,nowMs-lastMs));
            degrees+=(target-degrees)*(1-(float)Math.exp(-dt/80.0));
        }
        lastMs=nowMs;
        return degrees;
    }
    public void reset() { initialized=false; degrees=0; lastMs=0; }
}
