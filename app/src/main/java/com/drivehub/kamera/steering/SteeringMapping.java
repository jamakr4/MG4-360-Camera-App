package com.drivehub.kamera.steering;

import com.drivehub.kamera.birdseye.WheelSteering;

/** SAIC positive-left input -> positive-right model. Scale is visual, not a measured MG4 steering ratio. */
public final class SteeringMapping {
    // Map the OEM buffer limit to the model's visual limit until calibrated on the vehicle.
    public static final float DEFAULT_SCALE = OemSteeringCurve.MAX_INPUT_DEGREES / WheelSteering.MAX_DEGREES;
    private SteeringMapping() {}
    public static float scale(float value) {
        return Float.isFinite(value) && value>=1 && value<=30 ? value : DEFAULT_SCALE;
    }
    public static float degrees(float raw, float scale, boolean invert) {
        raw=OemSteeringCurve.clampSensorDegrees(raw);
        if (!Float.isFinite(raw)) return 0;
        float angle=-raw/scale(scale)*(invert?-1:1);
        return Math.max(-WheelSteering.MAX_DEGREES,Math.min(WheelSteering.MAX_DEGREES,angle));
    }
}
