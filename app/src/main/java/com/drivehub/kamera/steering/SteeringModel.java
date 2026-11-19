package com.drivehub.kamera.steering;

/** Shared rear-axle curvature and ideal Ackermann angles. OEM radius; assumed effective track. */
public final class SteeringModel {
    public static final String PROVENANCE = "OEM radius / assumed 1450 mm track; vehicle unvalidated";
    public static final class Solution {
        public final boolean valid;
        /** Positive right, inverse millimetres. Zero is exactly straight. */
        public final double curvaturePerMm;
        public final float leftDegrees, rightDegrees;
        private Solution(boolean valid, double curvature, float left, float right) {
            this.valid = valid; curvaturePerMm = curvature; leftDegrees = left; rightDegrees = right;
        }
    }
    public static final Solution INVALID = new Solution(false, Double.NaN, Float.NaN, Float.NaN);
    public final VehicleGeometry geometry = VehicleGeometry.MG4;
    private final double maxCurvature = curvature(SteeringSnapshot.MAX_RAW_DEGREES);

    public Solution solve(float sensorDegrees) {
        if (!Float.isFinite(sensorDegrees) || Math.abs(sensorDegrees) > SteeringSnapshot.MAX_RAW_DEGREES)
            return INVALID;
        double k = curvature(Math.abs(sensorDegrees));
        // SAIC input positive left; our wheels/curvature positive right.
        return fromCurvature(sensorDegrees == 0 ? 0 : Math.copySign(k, -sensorDegrees));
    }

    private double curvature(float magnitude) {
        double tangent = Math.tan(Math.toRadians(OemSteeringCurve.magnitudeDegrees(magnitude)));
        // R = L/tan(a) + width/2 + 150; avoids division by zero/cancellation near straight.
        return tangent / (geometry.wheelbaseMm + geometry.oemRadiusOffsetMm() * tangent);
    }

    /** Also used by the common display filter; no independent wheel-angle smoothing. */
    public Solution fromCurvature(double k) {
        if (!Double.isFinite(k) || Math.abs(k) > maxCurvature + 1e-15) return INVALID;
        if (k == 0) return new Solution(true, 0, 0, 0);
        double halfTrack = geometry.effectiveFrontTrackMm / 2;
        float left = (float) Math.toDegrees(Math.atan2(geometry.wheelbaseMm * k, 1 + halfTrack * k));
        float right = (float) Math.toDegrees(Math.atan2(geometry.wheelbaseMm * k, 1 - halfTrack * k));
        return new Solution(true, k, left, right);
    }
}
