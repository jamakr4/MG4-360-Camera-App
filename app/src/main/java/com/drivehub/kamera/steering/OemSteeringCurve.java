package com.drivehub.kamera.steering;

/** Independently recovered ArcSoft 0x226858 coefficients; magnitude in degrees, not a road-wheel angle. */
public final class OemSteeringCurve {
    // upper bound, input offset, numerator, denominator, output offset.
    // Preserve the recovered operations/constants instead of interpolating rounded output samples.
    private static final double[][] SEGMENTS = {
        {99.65, 0, .06914199698946312, 1, 0},
        {199.3, -99.65, 7.180000000000001, 99.65, 6.89},
        {249.13, -199.3, 3.7399999999999984, 49.829999999999984, 14.07},
        {298.96, -249.13, 3.8800000000000026, 49.829999999999984, 17.81},
        {348.78, -298.96, 4.029999999999998, 49.81999999999999, 21.69},
        {398.61, -348.78, 4.23, 49.83000000000004, 25.72},
        {448.44, -398.61, 4.459999999999997, 49.829999999999984, 29.95},
        {485.81, -448.44, 3.530000000000001, 37.370000000000005, 34.41},
        {517, -485.81, 3.1000000000000014, 31.140000000000043, 37.94}
    };
    private OemSteeringCurve() {}
    public static float magnitudeDegrees(float input) {
        if (!Float.isFinite(input) || input < 0 || input > SteeringSnapshot.MAX_RAW_DEGREES)
            return Float.NaN;
        for (double[] s : SEGMENTS) if (input <= s[0])
            return (float) (((input + s[1]) * s[2]) / s[3] + s[4]);
        return Float.NaN;
    }
}
