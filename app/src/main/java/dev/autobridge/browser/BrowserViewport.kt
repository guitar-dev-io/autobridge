package dev.autobridge.browser

import kotlin.math.roundToInt

/**
 * Shared geometry for page layout, canvas rendering and inverse touch mapping.
 *
 * **Overlay model.** The page is always laid out to the *full* viewport. Chrome (toolbar, drawer,
 * fullscreen handle) is composited on top of it and is deliberately absent from this calculation.
 * The previous model subtracted the toolbar height from [webHeight], so every toolbar show/hide and
 * every fullscreen toggle re-measured the WebView and forced the page to re-run layout — the page
 * visibly jumped and its scroll position moved. Keeping the page size independent of chrome state
 * is what makes "toggle chrome without touching the page" structurally true rather than a value
 * that happens to be tuned.
 */
data class BrowserViewport(
    val left: Int, val top: Int, val width: Int, val height: Int,
    val webWidth: Int, val webHeight: Int, val scale: Float,
    /** CSS pixel width the page sees; useful for diagnostics and responsive-layout checks. */
    val contentWidthDp: Int,
) {
    fun contains(x: Float, y: Float): Boolean =
        x >= left && y >= top && x < left + width && y < top + height

    /** Maps a surface x into the off-screen WebView's coordinate space. */
    fun toWebX(x: Float): Float = (x - left) / scale

    /** Maps a surface y into the off-screen WebView's coordinate space. */
    fun toWebY(y: Float): Float = (y - top) / scale

    companion object {
        /**
         * Narrower than this and a landscape page renders as a cramped mobile layout; wider and
         * text becomes too small to read at arm's length in a car.
         */
        const val MIN_CONTENT_WIDTH_DP = 720
        const val MAX_CONTENT_WIDTH_DP = 1280

        /**
         * @param webViewDensity density of the process hosting the off-screen [android.webkit.WebView]
         *   (it inherits the phone's density, which is why the page must be laid out larger and
         *   scaled down rather than sized to the surface directly).
         * @param surfaceDensity density of the *display the pixels land on* — the car panel's own
         *   dpi/160. This decides how many CSS pixels of content are appropriate; using the phone's
         *   density here would size car content to a screen the user is not looking at.
         */
        fun create(
            surfaceWidth: Int, surfaceHeight: Int,
            webViewDensity: Float, surfaceDensity: Float,
            left: Int = 0, top: Int = 0, right: Int = surfaceWidth, bottom: Int = surfaceHeight,
        ): BrowserViewport {
            val sw = surfaceWidth.coerceAtLeast(1)
            val sh = surfaceHeight.coerceAtLeast(1)
            val valid = left >= 0 && top >= 0 && right <= sw && bottom <= sh &&
                right > left && bottom > top
            val x = if (valid) left else 0
            val y = if (valid) top else 0
            val width = if (valid) right - left else sw
            val height = if (valid) bottom - top else sh

            val panelDensity = surfaceDensity.takeIf { it.isFinite() && it > 0f } ?: 1f
            val pageDensity = webViewDensity.takeIf { it.isFinite() && it > 0f } ?: 1f
            // Content width follows the car panel's physical size, not its raw pixel count.
            val contentWidthDp = (width / panelDensity).roundToInt()
                .coerceIn(MIN_CONTENT_WIDTH_DP, MAX_CONTENT_WIDTH_DP)
            val webWidth = (contentWidthDp * pageDensity).roundToInt().coerceAtLeast(1)
            val scale = width.toFloat() / webWidth
            // Full viewport height: chrome overlays the page and never shortens it.
            val webHeight = (height / scale).roundToInt().coerceAtLeast(1)
            return BrowserViewport(x, y, width, height, webWidth, webHeight, scale, contentWidthDp)
        }
    }
}
