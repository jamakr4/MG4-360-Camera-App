package com.drivehub.kamera.dev;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;
import com.drivehub.kamera.side.SideCameraGeometry;

/** Optical experiment: fixed output rectangles, unchanged crop centre/scale, no camera access. */
final class WheelRotationPreview extends View {
    private static final int MESH = 24;
    private final CameraReferenceFrames frames;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint lines = new Paint(Paint.ANTI_ALIAS_FLAG);
    boolean wheels = true, showOriginal, pinCorners, grid;
    float frontLeftDegrees, frontRightDegrees, rearLeftDegrees, rearRightDegrees;

    WheelRotationPreview(Context context, CameraReferenceFrames frames) {
        super(context);
        this.frames = frames;
        setContentDescription("Referenzbilder für vier Radansichten; feste Kachelrahmen");
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float scale = Math.min(getWidth() / 540f, getHeight() / 640f);
        canvas.save();
        canvas.translate((getWidth()-540*scale)/2, (getHeight()-640*scale)/2);
        canvas.scale(scale, scale);
        for (int side = 0; side < 2; side++) {
            Bitmap source = frames.sources[side+2];
            float x = 12 + side*262;
            if (wheels) {
                float frontAngle = showOriginal ? 0 : (side == 0 ? frontLeftDegrees : frontRightDegrees);
                drawTile(canvas, source, SideCameraGeometry.wheel(side == 0, true),
                        frames.wheelTiles[side], new RectF(x, 76, x+254, 300), frontAngle);
                float rearAngle = showOriginal ? 0 : (side == 0 ? rearLeftDegrees : rearRightDegrees);
                drawTile(canvas, source, SideCameraGeometry.wheel(side == 0, false),
                        frames.wheelTiles[side+2], new RectF(x, 308, x+254, 532), rearAngle);
            } else {
                drawTile(canvas, source, SideCameraGeometry.side(side == 0), null,
                        new RectF(x, 76, x+254, 532), 0);
            }
        }
        canvas.restore();
    }

    private void drawTile(Canvas canvas, Bitmap source, SideCameraGeometry.Crop crop,
                          Bitmap tile, RectF target, float degrees) {
        canvas.save();
        canvas.clipRect(target);
        // Expose missing source data; do not invent camera pixels or zoom the selected crop.
        paint.setColor(0xFF30232E);
        canvas.drawRect(target, paint);
        lines.setColor(0xFF79506B); lines.setStrokeWidth(1);
        for (float x = target.left-target.height(); x < target.right; x += 12)
            canvas.drawLine(x, target.top, x+target.height(), target.bottom, lines);
        if (pinCorners && degrees != 0 && tile != null) {
            drawPinned(canvas, tile, target, degrees);
        } else {
            RectF region = new RectF(crop.left*source.getWidth(), crop.top*source.getHeight(),
                    crop.right*source.getWidth(), crop.bottom*source.getHeight());
            Matrix mapping = new Matrix();
            mapping.setRectToRect(region, target, Matrix.ScaleToFit.FILL);
            canvas.rotate(degrees, target.centerX(), target.centerY());
            paint.setColor(Color.WHITE);
            canvas.drawBitmap(source, mapping, paint);
        }
        canvas.restore();
        if (grid) {
            lines.setStyle(Paint.Style.STROKE); lines.setStrokeWidth(1); lines.setColor(0xB3FFFFFF);
            canvas.drawRect(target, lines);
            canvas.drawLine(target.centerX(), target.top, target.centerX(), target.bottom, lines);
            canvas.drawLine(target.left, target.centerY(), target.right, target.centerY(), lines);
            lines.setStyle(Paint.Style.FILL);
            for (float x : new float[]{target.left, target.right})
                for (float y : new float[]{target.top, target.bottom}) canvas.drawCircle(x, y, 3, lines);
        }
    }

    private void drawPinned(Canvas canvas, Bitmap tile, RectF target, float degrees) {
        float[] vertices = new float[(MESH+1)*(MESH+1)*2];
        int i = 0;
        for (int row = 0; row <= MESH; row++) for (int col = 0; col <= MESH; col++) {
            float u = col/(float) MESH, v = row/(float) MESH;
            float x = (u-.5f)*target.width(), y = (v-.5f)*target.height();
            float edge = Math.max(Math.abs(2*u-1), Math.abs(2*v-1));
            // Taper the rotation to zero at the boundary: preserve every original edge/corner.
            double angle = Math.toRadians(degrees)*(1-edge)*(1-edge);
            vertices[i++] = target.centerX()+(float) (x*Math.cos(angle)-y*Math.sin(angle));
            vertices[i++] = target.centerY()+(float) (x*Math.sin(angle)+y*Math.cos(angle));
        }
        canvas.drawBitmapMesh(tile, MESH, MESH, vertices, 0, null, 0, paint);
    }
}
