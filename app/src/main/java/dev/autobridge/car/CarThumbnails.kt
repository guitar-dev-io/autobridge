package dev.autobridge.car

import android.content.Context
import android.graphics.Bitmap
import dev.autobridge.ui.ImageLoader
import dev.autobridge.youtube.YouTubeUrls

/**
 * Where a dashboard thumbnail comes from, and nowhere else.
 *
 * Only addresses that publish a still at a known URL get a real image. For YouTube that is
 * `i.ytimg.com`, which serves the video's own thumbnail from the id [YouTubeUrls.videoId] already
 * parses for SponsorBlock — no API key, no scraping, and it is the same image the watch page
 * shows. Every other host returns null and the renderer draws its fallback artwork instead, which
 * is why nothing here ever has to guess.
 *
 * Bitmaps come from [ImageLoader], the loader the IPTV and weather screens already use, so the
 * dashboard adds no second cache and no image dependency.
 */
internal object CarThumbnails {
    /** `mqdefault` is 320×180: wider than the biggest row thumbnail a head unit asks for. */
    private const val YOUTUBE_STILL = "https://i.ytimg.com/vi/%s/mqdefault.jpg"

    /** The remote still for [url], or null when this source does not publish one. */
    fun url(url: String): String? {
        if (url.isBlank()) return null
        val videoId = YouTubeUrls.videoId(url) ?: return null
        return YOUTUBE_STILL.format(videoId)
    }

    /**
     * The decoded still for [url] if it is already cached, else null while it loads.
     *
     * [onReady] is called on the main thread once a fetch lands, and is the dashboard's cue to
     * re-render; it never fires for a cached hit (the bitmap is returned instead) and never fires
     * for a failure, which leaves the fallback artwork in place for good.
     */
    fun bitmap(context: Context, url: String?, onReady: () -> Unit): Bitmap? {
        if (url.isNullOrBlank()) return null
        return ImageLoader.bitmap(context, url, onReady)
    }
}
