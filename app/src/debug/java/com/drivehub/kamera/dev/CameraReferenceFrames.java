package com.drivehub.kamera.dev;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import com.drivehub.kamera.side.SideCameraGeometry;
import java.io.IOException;
import java.io.InputStream;

/** Local montage used by the earlier side-camera crop checks; never a live camera input. */
final class CameraReferenceFrames {
    final Bitmap[] sources;
    final Bitmap[] wheelTiles;

    CameraReferenceFrames(Context context) throws IOException {
        Bitmap montage;
        try (InputStream input = context.getAssets().open("side-cameras.png")) {
            montage = BitmapFactory.decodeStream(input);
        }
        if (montage == null || montage.getWidth() != 1162 || montage.getHeight() != 700) {
            throw new IOException("Referenzmontage side-cameras.png (1162 × 700) fehlt oder hat ein anderes Format.");
        }
        // The two side images are already upright (front above rear) in the montage.
        Bitmap left = extract(montage, 14, 7, 228, 681, 480, 720);
        Bitmap right = extract(montage, 934, 7, 228, 681, 480, 720);
        Bitmap front = extract(montage, 247, 118, 682, 236, 720, 480);
        Bitmap rear = extract(montage, 247, 355, 682, 223, 720, 480);
        montage.recycle();
        sources = new Bitmap[]{front, rear, left, right};
        wheelTiles = new Bitmap[]{crop(left, SideCameraGeometry.wheel(true, true)),
                crop(right, SideCameraGeometry.wheel(false, true)),
                crop(left, SideCameraGeometry.wheel(true, false)),
                crop(right, SideCameraGeometry.wheel(false, false))};
    }

    private static Bitmap extract(Bitmap input, int x, int y, int w, int h, int outW, int outH) {
        Bitmap cut = Bitmap.createBitmap(input, x, y, w, h);
        Bitmap scaled = Bitmap.createScaledBitmap(cut, outW, outH, true);
        if (scaled != cut) cut.recycle();
        return scaled;
    }

    private static Bitmap crop(Bitmap input, SideCameraGeometry.Crop crop) {
        int x = Math.round(crop.left * input.getWidth()), y = Math.round(crop.top * input.getHeight());
        int right = Math.min(input.getWidth(), Math.round(crop.right * input.getWidth()));
        int bottom = Math.min(input.getHeight(), Math.round(crop.bottom * input.getHeight()));
        return extract(input, x, y, right-x, bottom-y,
                SideCameraGeometry.TILE_WIDTH, SideCameraGeometry.WHEEL_HEIGHT);
    }
}
