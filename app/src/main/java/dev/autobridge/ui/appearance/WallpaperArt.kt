package dev.autobridge.ui.appearance

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import dev.autobridge.design.WallpaperCatalog
import dev.autobridge.settings.AppearanceStore
import java.io.File

/**
 * The custom wallpaper photo: picked once through the Android photo picker, saved downscaled as a
 * JPEG at [AppearanceStore.customWallpaperFile], and read back small for the previews.
 */
object WallpaperPhoto {
    private const val PREVIEW_SIDE = 480
    private const val JPEG_QUALITY = 85

    @Volatile
    private var cached: Pair<Long, Bitmap>? = null

    /** The saved photo, decoded small enough for a preview tile, or null when there is none. */
    fun preview(context: Context): Bitmap? {
        val file = AppearanceStore.customWallpaperFile(context)
        if (!file.isFile) return null
        val stamp = file.lastModified() xor file.length()
        cached?.let { (key, bitmap) -> if (key == stamp) return bitmap }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= PREVIEW_SIDE) sample *= 2
        val bitmap = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return null
        cached = stamp to bitmap
        return bitmap
    }

    /**
     * The saved photo decoded for a view [targetWidth] pixels wide that may show only a third of
     * it (a full zoom), so the crop stays sharp. Null when there is no photo or it is unreadable.
     * Blocking: call it off the main thread.
     */
    fun decode(context: Context, targetWidth: Int): Bitmap? = runCatching {
        val file = AppearanceStore.customWallpaperFile(context)
        if (!file.isFile) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0) return null
        val needed = targetWidth * (WallpaperCatalog.ZOOM_MAX / 100)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= needed) sample *= 2
        BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
    }.getOrNull()

    /** Whether a photo has been saved. */
    fun exists(context: Context): Boolean = AppearanceStore.customWallpaperFile(context).isFile

    /** Deletes the saved photo. */
    fun remove(context: Context) {
        AppearanceStore.customWallpaperFile(context).delete()
        cached = null
    }

    /** Average relative luminance of the saved photo (0..1), for the contrast estimate; null with no photo. */
    fun luminance(context: Context): Double? {
        val photo = preview(context) ?: return null
        val tiny = Bitmap.createScaledBitmap(photo, 8, 8, true)
        var sum = 0.0
        for (y in 0 until 8) for (x in 0 until 8) sum += WallpaperCatalog.luminanceOf(tiny.getPixel(x, y))
        if (tiny !== photo) tiny.recycle()
        return sum / 64.0
    }

    /**
     * Decodes [uri] (EXIF rotation applied by [ImageDecoder]), shrinks it to
     * [AppearanceModel.PHOTO_MAX_SIDE] and writes it over the saved photo. Blocking: call it off
     * the main thread. Returns false when the image could not be read.
     */
    fun save(context: Context, uri: Uri): Boolean = runCatching {
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        val bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            val (w, h) = AppearanceModel.fitWithin(info.size.width, info.size.height)
            decoder.setTargetSize(w, h)
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
        val target = AppearanceStore.customWallpaperFile(context)
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, target.name + ".tmp")
        temp.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
        bitmap.recycle()
        if (!temp.renameTo(target)) {
            temp.copyTo(target, overwrite = true)
            temp.delete()
        }
        cached = null
        true
    }.getOrDefault(false)
}
