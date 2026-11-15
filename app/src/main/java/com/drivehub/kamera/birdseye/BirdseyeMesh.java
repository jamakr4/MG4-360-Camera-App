package com.drivehub.kamera.birdseye;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.ShortBuffer;

/** Static 4-pixel mesh, rebuilt only for calibration/view changes, never per frame. */
public final class BirdseyeMesh {
    public static final float CAR_WIDTH = 1836, CAR_LENGTH = 4287, MARGIN = 1500;
    public static final float GROUND_HEIGHT = CAR_LENGTH + 2*MARGIN;
    public static final float GROUND_WIDTH = GROUND_HEIGHT * 450/640;
    public static final int FLOATS = 14, COLS = 113, ROWS = 160;
    public final FloatBuffer vertices;
    public final ShortBuffer indices;
    public final int indexCount = COLS * ROWS * 6;
    public final int coveredVertices;

    public BirdseyeMesh(BirdseyeCalibration calibration) {
        vertices = ByteBuffer.allocateDirect((COLS+1)*(ROWS+1)*FLOATS*4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        indices = ByteBuffer.allocateDirect(indexCount*2).order(ByteOrder.nativeOrder()).asShortBuffer();
        int covered = 0;
        for (int row = 0; row <= ROWS; row++) for (int col = 0; col <= COLS; col++) {
            float nx = 2f*col/COLS-1, ny = 1-2f*row/ROWS;
            double x = calibration.centerX + nx*GROUND_WIDTH/2, y = calibration.centerY - ny*GROUND_HEIGHT/2;
            vertices.put(nx).put(ny);
            double weight = 0;
            for (int camera = 0; camera < 4; camera++) {
                BirdseyeCalibration.Projection p = calibration.project(camera, x, y);
                vertices.put((float)Math.max(-10000, Math.min(10000, p.u)));
                vertices.put((float)Math.max(-10000, Math.min(10000, p.v))).put((float)p.weight);
                weight += p.weight;
            }
            if (weight > 1e-8 && (Math.abs(x-calibration.centerX) > CAR_WIDTH/2
                    || Math.abs(y-calibration.centerY) > CAR_LENGTH/2)) covered++;
        }
        for (int row = 0; row < ROWS; row++) for (int col = 0; col < COLS; col++) {
            int a = row*(COLS+1)+col, b = a+COLS+1;
            indices.put((short)a).put((short)b).put((short)(a+1));
            indices.put((short)(a+1)).put((short)b).put((short)(b+1));
        }
        coveredVertices = covered;
        if (covered == 0) throw new IllegalArgumentException("Calibration has no ground coverage");
        vertices.position(0); indices.position(0);
    }
}
