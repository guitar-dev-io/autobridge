package dev.autobridge.parking

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import java.io.File

/**
 * The photo of where the car is parked: loading it for the screen. A phone's camera usually
 * saves the picture sideways and notes the turn in the file (EXIF), which a plain decode ignores,
 * so the turn is applied here; and it is decoded at screen size, not full size.
 */
object ParkingPhoto {
    /**
     * The largest power-of-two shrink that still leaves the longer side of a [width] x [height]
     * picture at [maxSide] or more: as small as it can be decoded without looking soft on screen.
     */
    fun sampleSize(width: Int, height: Int, maxSide: Int): Int {
        var sample = 1
        var longest = maxOf(width, height)
        while (longest / 2 >= maxSide) {
            sample *= 2
            longest /= 2
        }
        return sample
    }

    /** Degrees to turn a picture clockwise for an EXIF orientation value. */
    fun rotationDegrees(exifOrientation: Int): Int = when (exifOrientation) {
        ExifInterface.ORIENTATION_ROTATE_90 -> 90
        ExifInterface.ORIENTATION_ROTATE_180 -> 180
        ExifInterface.ORIENTATION_ROTATE_270 -> 270
        else -> 0
    }

    fun load(file: File, maxSide: Int = 1600): Bitmap? {
        if (!file.isFile || file.length() == 0L) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val options = BitmapFactory.Options().apply { inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, maxSide) }
        val bitmap = runCatching { BitmapFactory.decodeFile(file.path, options) }.getOrNull() ?: return null
        val degrees = runCatching {
            rotationDegrees(ExifInterface(file.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL))
        }.getOrDefault(0)
        if (degrees == 0) return bitmap
        val turned = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, Matrix().apply { postRotate(degrees.toFloat()) }, true)
        if (turned !== bitmap) bitmap.recycle()
        return turned
    }
}
