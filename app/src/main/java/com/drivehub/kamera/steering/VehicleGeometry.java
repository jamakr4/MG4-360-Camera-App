package com.drivehub.kamera.steering;

/** Shared MG4 dimensions in millimetres. Owner's manual, dimensions tables pp. 324–325.
 * Local motion uses the rear-axle centre: X right, Y rear, Z down. */
public final class VehicleGeometry {
    public static final VehicleGeometry MG4 = new VehicleGeometry();
    public final double widthMm = 1836, lengthMm = 4287, heightMm = 1504;
    /** MG4 EV published mirror-inclusive width; clearance display only, not the OEM radius reference. */
    public final double widthWithMirrorsMm = 2060;
    public final double wheelbaseMm = 2705, frontOverhangMm = 841, rearOverhangMm = 741;
    public final double frontTrackMm = 1550, rearTrackMm = 1551, groundClearanceMm = 117;
    /** Native auxiliary-line reference sits 150 mm outside each body side. */
    public final double oemLineMarginMm = 150;
    private VehicleGeometry() {}
    public double oemRadiusOffsetMm() { return widthMm / 2 + oemLineMarginMm; }
    public double frontY() { return rearOverhangMm - lengthMm; }
    public double rearY() { return rearOverhangMm; }
    public double rearAxleY(double bodyCenterY) { return bodyCenterY+lengthMm/2-rearOverhangMm; }
    public double frontAxleY(double bodyCenterY) { return rearAxleY(bodyCenterY)-wheelbaseMm; }
}
