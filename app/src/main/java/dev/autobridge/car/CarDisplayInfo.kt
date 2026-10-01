package dev.autobridge.car

/**
 * The car surface geometry Android Auto last handed this app, for diagnostics.
 *
 * Android Auto and the head unit negotiate the video stream on connect; the app only ever sees
 * the result through `SurfaceContainer`. When that result is the 800x480 floor, the head unit
 * upscales it to its panel and *every* app (Maps included) looks enlarged — nothing an app can
 * render fixes that. Recording what was negotiated lets the diagnostics screen say so plainly
 * instead of leaving it to be mistaken for an app scaling bug.
 */
object CarDisplayInfo {
    data class Surface(val width: Int, val height: Int, val dpi: Int)

    /** Width and height of Android Auto's lowest stream, 800x480. */
    private const val MIN_STREAM_LONG_EDGE = 800
    private const val MIN_STREAM_SHORT_EDGE = 480

    @Volatile
    var last: Surface? = null
        private set

    fun record(width: Int, height: Int, dpi: Int) {
        if (width > 0 && height > 0) last = Surface(width, height, dpi)
    }

    /**
     * True when the surface fits inside the 800x480 stream. The surface is the stream minus host
     * chrome (800x480 arrives as 800x400 on a unit with a status bar), so this compares edges.
     */
    fun isLowestStream(surface: Surface): Boolean =
        maxOf(surface.width, surface.height) <= MIN_STREAM_LONG_EDGE &&
            minOf(surface.width, surface.height) <= MIN_STREAM_SHORT_EDGE

    fun label(surface: Surface?): String =
        surface?.let { "${it.width} x ${it.height} @ ${it.dpi} dpi" } ?: "— (open Browser or Mirror first)"
}
