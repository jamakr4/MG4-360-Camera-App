package com.drivehub.kamera.birdseye;

import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** Optional, separately licensed artwork. Absence/corruption keeps the black ground mask. */
final class BirdseyeCarArtwork {
    static final String DIRECTORY = "mg4/birdseye/";
    interface Source { BirdseyeCarArtwork load(BirdseyeCarColor color); }
    final Bitmap bitmap;
    final BirdseyeCarGeometry geometry;

    BirdseyeCarArtwork(Bitmap bitmap, BirdseyeCarGeometry geometry) {
        this.bitmap = bitmap; this.geometry = geometry;
    }

    static BirdseyeCarArtwork load(AssetManager assets, BirdseyeCarColor color) {
        String imagePath = DIRECTORY + "colors/" + color.id + ".png";
        Bitmap bitmap = null;
        try {
            JSONObject metadata;
            try (InputStream in = assets.open(DIRECTORY+"topview.json")) {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                byte[] chunk = new byte[1024]; int n;
                while ((n = in.read(chunk)) != -1) {
                    if (bytes.size()+n > 16384) throw new IOException("Car metadata is too large");
                    bytes.write(chunk,0,n);
                }
                metadata = new JSONObject(new String(bytes.toByteArray(), StandardCharsets.UTF_8));
            }
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            try (InputStream in = assets.open(imagePath)) { BitmapFactory.decodeStream(in,null,options); }
            // GLES2 guarantees textures up to 2048; also bound memory before decoding.
            if (options.outWidth < 1 || options.outHeight < 1 || options.outWidth > 2048 || options.outHeight > 2048
                    || metadata.getJSONArray("resolution").getInt(0) != options.outWidth
                    || metadata.getJSONArray("resolution").getInt(1) != options.outHeight)
                throw new IOException("Invalid car image dimensions");
            JSONObject bounds = metadata.getJSONObject("body_image_bounds_normalized");
            BirdseyeCarGeometry geometry = new BirdseyeCarGeometry(options.outWidth, options.outHeight,
                    bounds.getDouble("x0"), bounds.getDouble("y0"), bounds.getDouble("x1"), bounds.getDouble("y1"));
            options.inJustDecodeBounds = false; options.inScaled = false;
            options.inPreferredConfig = Bitmap.Config.ARGB_8888;
            options.inMutable = true;
            options.inPremultiplied = false; // Shader explicitly composites straight alpha over its background.
            try (InputStream in = assets.open(imagePath)) { bitmap = BitmapFactory.decodeStream(in,null,options); }
            if (bitmap == null || !bitmap.hasAlpha() || bitmap.getWidth() != options.outWidth || bitmap.getHeight() != options.outHeight)
                throw new IOException("Cannot decode car artwork");
            int firstRow=BirdseyeRearMask.firstShadowRow(bitmap.getHeight());
            int[] rearPixels=new int[bitmap.getWidth()*(bitmap.getHeight()-firstRow)];
            bitmap.getPixels(rearPixels,0,bitmap.getWidth(),0,firstRow,bitmap.getWidth(),bitmap.getHeight()-firstRow);
            BirdseyeRearMask.addShadow(rearPixels,bitmap.getWidth(),bitmap.getHeight(),firstRow,geometry);
            bitmap.setPixels(rearPixels,0,bitmap.getWidth(),0,firstRow,bitmap.getWidth(),bitmap.getHeight()-firstRow);
            return new BirdseyeCarArtwork(bitmap,geometry);
        } catch (Exception | OutOfMemoryError error) {
            if (bitmap != null) bitmap.recycle();
            Log.i("Birdseye", "Optional car artwork unavailable ("+color.id+"): "+error.getMessage());
            return null;
        }
    }
}
