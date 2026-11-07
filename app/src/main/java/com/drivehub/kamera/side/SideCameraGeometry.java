package com.drivehub.kamera.side;

/** Screenshot-derived regions in the upright side image: front at y=0, rear at y=1. */
public final class SideCameraGeometry {
    public static final int SOURCE_WIDTH = 720;
    public static final int SOURCE_HEIGHT = 480;
    public static final int TILE_WIDTH = 254;
    public static final int WHEEL_HEIGHT = 224;
    public static final int SIDE_HEIGHT = 456;
    // The supplied image puts the wheel/body edge in the inner part of each side camera.
    // Keep that edge and adjacent ground, including both ends of the fisheye image.
    private static final float FRONT_BAND_WIDTH = .66f;
    private static final float REAR_BAND_WIDTH = .48f;

    public static final class Crop {
        public final float left, top, right, bottom;
        Crop(float left, float top, float right, float bottom) {
            this.left=left; this.top=top; this.right=right; this.bottom=bottom;
        }
    }
    private SideCameraGeometry() {}

    public static Crop side(boolean leftCamera) {
        float band = (TILE_WIDTH / (float) SIDE_HEIGHT) * SOURCE_WIDTH / SOURCE_HEIGHT;
        return new Crop(leftCamera ? 1-band : 0, 0, leftCamera ? 1 : band, 1);
    }

    public static Crop wheel(boolean leftCamera, boolean front) {
        // Front tires run along the body edge around y=0.05..0.35 in the reference.
        // Rear rims sit around x=.64/.36, y=.95, rather than at the inner image boundary.
        float band=front?FRONT_BAND_WIDTH:REAR_BAND_WIDTH;
        float span=band*SOURCE_HEIGHT/SOURCE_WIDTH*WHEEL_HEIGHT/TILE_WIDTH;
        float x0=front?(leftCamera?1-band:0):(leftCamera?.40f:.12f);
        float y0=front?.01f:1-span;
        return new Crop(x0,y0,x0+band,y0+span);
    }

    /** JNI crop order: portrait side, front wheel, rear wheel; each left/top/right/bottom. */
    public static float[] previewCrops(boolean leftCamera) {
        Crop[] crops = {side(leftCamera), wheel(leftCamera, true), wheel(leftCamera, false)};
        float[] values = new float[12];
        for (int i = 0; i < crops.length; i++) {
            Crop crop = crops[i];
            values[i*4] = crop.left;
            values[i*4+1] = crop.top;
            values[i*4+2] = crop.right;
            values[i*4+3] = crop.bottom;
        }
        return values;
    }
}
