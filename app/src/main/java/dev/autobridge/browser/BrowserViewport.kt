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
    /**
     * Page zoom to pin on the WebView ([android.webkit.WebView.setInitialScale]) so that a
     * [webWidth]-pixel view lays the page out at [contentWidthDp] CSS pixels.
     *
     * This is the whole mechanism that keeps the raster 1:1. Left to itself the WebView picks the
     * *phone's* density as its scale, so reaching a given CSS width meant inflating the view by
     * that factor — a 1280 CSS px page on a 2.75-density phone became a 3520px-wide view. Pinning
     * the scale instead lets the view stay the size of the surface and the CSS width be whatever
     * the car panel needs, independently.
     */
    val pageScalePercent: Int,
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
         * CSS width the page is told it has in desktop mode. A desktop User-Agent alone does not
         * change the layout a modern site picks — sites choose their breakpoint from the viewport
         * width, and at 720–1280 CSS px they still serve the mobile/tablet layout. Pinning the
         * viewport to a width past the common desktop breakpoints (≈1024–1280) is what makes the
         * page actually lay out as desktop. It is scaled down to the panel afterwards, so the trade
         * is smaller text for the wide layout the user asked for.
         */
        const val DESKTOP_CONTENT_WIDTH_DP = 1280

        /**
         * `setInitialScale` takes whole percent, and both ends have to stay sane: 0 would mean
         * "use the default" — the very behaviour being replaced — and an unbounded value would let
         * an implausible surface/content pair zoom the page past anything readable.
         */
        const val MIN_PAGE_SCALE_PERCENT = 1
        const val MAX_PAGE_SCALE_PERCENT = 1000

        /**
         * @param surfaceDensity density of the *display the pixels land on* — the car panel's own
         *   dpi/160. This decides how many CSS pixels of content are appropriate; using the phone's
         *   density here would size car content to a screen the user is not looking at.
         * @param desktop when true the page is laid out at [DESKTOP_CONTENT_WIDTH_DP] regardless of
         *   the panel size, so a desktop User-Agent is matched by a desktop-width viewport.
         *
         * The phone's density is deliberately **not** a parameter. It used to multiply [webWidth],
         * which is what made the off-screen WebView 23 times larger than the surface it is drawn
         * to (an 800x400 panel in desktop mode laid the page out at 3840x1920) until Chromium gave
         * up on it outright — `tile memory limits exceeded, some content may not draw` — and the
         * page came out half-painted, showing the white [BrowserViewport]-sized fill underneath.
         * The view is now the size of the surface and [pageScalePercent] carries the CSS width.
         */
        fun create(
            surfaceWidth: Int, surfaceHeight: Int,
            surfaceDensity: Float,
            left: Int = 0, top: Int = 0, right: Int = surfaceWidth, bottom: Int = surfaceHeight,
            desktop: Boolean = false,
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
            // Content width follows the car panel's physical size, not its raw pixel count — except
            // in desktop mode, where it is pinned wide so the page picks a desktop layout.
            val contentWidthDp = if (desktop) {
                DESKTOP_CONTENT_WIDTH_DP
            } else {
                (width / panelDensity).roundToInt().coerceIn(MIN_CONTENT_WIDTH_DP, MAX_CONTENT_WIDTH_DP)
            }
            // One WebView pixel per surface pixel. The CSS width is reached by zooming the page
            // ([pageScalePercent]) rather than by making the view bigger, so no part of this
            // rasterises anything that is not shown. Full viewport height, too: chrome overlays the
            // page and never shortens it.
            val webWidth = width
            val webHeight = height
            val scale = 1f
            val pageScalePercent = (webWidth * 100f / contentWidthDp.coerceAtLeast(1))
                .roundToInt().coerceIn(MIN_PAGE_SCALE_PERCENT, MAX_PAGE_SCALE_PERCENT)
            return BrowserViewport(
                x, y, width, height, webWidth, webHeight, scale, contentWidthDp, pageScalePercent
            )
        }
    }
}
