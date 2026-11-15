package com.drivehub.kamera.birdseye;

/** Registers visible body bounds to the metric vehicle footprint, independently of transparent PNG margins. */
public final class BirdseyeCarGeometry {
    public static final float MASK_INSET_MM = 12;
    public final float leftMm, topMm, widthMm, heightMm, mmPerPixel;

    public BirdseyeCarGeometry(int width, int height, double x0, double y0, double x1, double y1) {
        if (width < 1 || height < 1 || !Double.isFinite(x0) || !Double.isFinite(y0)
                || !Double.isFinite(x1) || !Double.isFinite(y1) || x0 < 0 || y0 < 0
                || x1 > 1 || y1 > 1 || x1 <= x0 || y1 <= y0
                || (x1-x0)*width < 1 || (y1-y0)*height < 1)
            throw new IllegalArgumentException("Invalid car image/body bounds");
        double scale = Math.min((BirdseyeMesh.CAR_WIDTH-2*MASK_INSET_MM)/((x1-x0)*width),
                (BirdseyeMesh.CAR_LENGTH-2*MASK_INSET_MM)/((y1-y0)*height));
        mmPerPixel = (float)scale;
        widthMm = (float)(width*scale); heightMm = (float)(height*scale);
        leftMm = (float)(-(x0+x1)*width*scale/2);
        topMm = (float)(-(y0+y1)*height*scale/2);
    }
}
