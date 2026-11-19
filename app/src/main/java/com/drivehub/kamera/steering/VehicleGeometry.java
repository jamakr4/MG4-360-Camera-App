package com.drivehub.kamera.steering;

/** Millimetres, rear-axle origin; X right, Y rear, Z down. */
public final class VehicleGeometry {
    public static final VehicleGeometry MG4 = new VehicleGeometry();
    public final double widthMm = 1836, lengthMm = 4279, heightMm = 1504;
    /** MG4 EV published mirror-inclusive width; clearance display only, not the OEM radius reference. */
    public final double widthWithMirrorsMm = 2060;
    public final double wheelbaseMm = 2690, rearOverhangMm = 745;
    /** Working assumption only: the Java OEM parameter is not a measured front track. */
    public final double effectiveFrontTrackMm = 1450;
    /** Rear track is provisionally assumed equal to the effective front track, not vehicle measured. */
    public final double effectiveRearTrackMm = 1450;
    /** Native auxiliary-line reference sits 150 mm outside each body side. */
    public final double oemLineMarginMm = 150;
    private VehicleGeometry() {}
    public double oemRadiusOffsetMm() { return widthMm / 2 + oemLineMarginMm; }
    public double frontY() { return rearOverhangMm - lengthMm; }
    public double rearY() { return rearOverhangMm; }
}
