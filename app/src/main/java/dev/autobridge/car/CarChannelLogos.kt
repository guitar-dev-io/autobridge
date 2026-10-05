package dev.autobridge.car

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import androidx.car.app.model.CarIcon
import androidx.core.graphics.drawable.IconCompat
import dev.autobridge.ui.ImageLoader

/**
 * Channel logos for the car's list rows.
 *
 * A head unit row can carry an image, but not by address: `CarIconConstraints.DEFAULT` accepts
 * only `TYPE_BITMAP` and `TYPE_RESOURCE` for a list row — `TYPE_URI` is allowed solely in the few
 * templates that document it — so the host never fetches a logo itself. Every pixel a row shows
 * has to be decoded here and sent across the binder inside the template.
 *
 * That is the whole design constraint. A binder transaction is capped at about 1 MB and the
 * template has to fit in one, so two things are bounded:
 *
 *  - **Size.** A logo is scaled to [MAX_PIXELS] on its long edge. `IMAGE_TYPE_SMALL` targets an
 *    88 × 88 dp box and the host scales down anyway, so a bigger bitmap buys nothing and costs
 *    4 bytes a pixel in the transaction.
 *  - **Count.** At most [MAX_PER_TEMPLATE] rows of one template get an image — 36 × 72 px ARGB is
 *    roughly 745 KB at the very worst, before the host's own scaling. Past that a row stays
 *    text-only rather than risking a template the host drops on the floor, which means the logos
 *    land on the top of a page: the rows a driver is actually looking at.
 *
 * Nothing blocks. [ImageLoader] answers from its cache or starts a fetch and returns null, and the
 * arrival of a batch of logos repaints the screen once — [REFRESH_DELAY_MS] after the first of
 * them — because a host rejects templates pushed in a tight loop, and 36 logos arriving one by one
 * is exactly such a loop.
 */
internal class CarChannelLogos(
    private val context: Context,
    private val onRefresh: () -> Unit
) {
    private val handler = Handler(Looper.getMainLooper())
    /** Insertion-ordered so the oldest icon is the one dropped when the cache is full. */
    private val icons = LinkedHashMap<String, CarIcon>()
    private var budget = 0
    private var refreshPending = false

    /** Call once per template build: the row budget starts over for the rows about to be built. */
    fun beginTemplate() {
        budget = MAX_PER_TEMPLATE
    }

    /**
     * The icon for [url] if there is one to draw, else null — which is not a hole: a row without
     * an image is the row every IPTV screen drew before logos existed.
     *
     * A row that gets an image and a row that merely starts a fetch both spend a slot, so the
     * budget bounds the downloads a single page kicks off as well as the bytes it sends.
     */
    fun icon(url: String?): CarIcon? {
        val address = url?.trim().orEmpty()
        if (address.isEmpty() || budget <= 0) return null
        budget--
        icons[address]?.let { return it }
        val bitmap = ImageLoader.bitmap(context, address) { scheduleRefresh() } ?: return null
        val icon = CarIcon.Builder(IconCompat.createWithBitmap(scaled(bitmap))).build()
        remember(address, icon)
        return icon
    }

    /** Drops the pending repaint when the screen goes away, so a dead screen is never pushed. */
    fun stop() {
        handler.removeCallbacksAndMessages(null)
        refreshPending = false
    }

    private fun scheduleRefresh() {
        if (refreshPending) return
        refreshPending = true
        handler.postDelayed({
            refreshPending = false
            onRefresh()
        }, REFRESH_DELAY_MS)
    }

    private fun remember(url: String, icon: CarIcon) {
        icons[url] = icon
        while (icons.size > MAX_CACHED) {
            val oldest = icons.keys.firstOrNull() ?: break
            icons.remove(oldest)
        }
    }

    private fun scaled(bitmap: Bitmap): Bitmap {
        val longest = maxOf(bitmap.width, bitmap.height)
        if (longest <= MAX_PIXELS || longest <= 0) return bitmap
        val factor = MAX_PIXELS.toFloat() / longest
        return Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * factor).toInt().coerceAtLeast(1),
            (bitmap.height * factor).toInt().coerceAtLeast(1),
            true
        )
    }

    private companion object {
        const val MAX_PIXELS = 72
        const val MAX_PER_TEMPLATE = 36

        /** Scaled icons are small; this only has to outlast paging back and forth on one source. */
        const val MAX_CACHED = 180

        /** One repaint per batch of arrivals, spaced far enough apart for the host to accept it. */
        const val REFRESH_DELAY_MS = 700L
    }
}
