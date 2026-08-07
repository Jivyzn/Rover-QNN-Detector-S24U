package com.jivyzn.roverqnn;

import android.graphics.Bitmap;
import android.graphics.Matrix;

import androidx.camera.core.ImageProxy;

/**
 * CameraX already has a fast conversion path. use it instead of doing YUV math
 * one pixel at a time in Java.
 */
final class YuvConverter {
    private YuvConverter() {}

    static Bitmap toUprightBitmap(ImageProxy image) {
        Bitmap bitmap = image.toBitmap();
        int rotation = image.getImageInfo().getRotationDegrees();
        if (rotation == 0) return bitmap;

        Matrix matrix = new Matrix();
        matrix.postRotate(rotation);
        Bitmap rotated = Bitmap.createBitmap(
                bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
        if (rotated != bitmap) bitmap.recycle();
        return rotated;
    }
}
