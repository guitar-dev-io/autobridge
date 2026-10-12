package dev.autobridge.car

import android.content.Context
import dev.autobridge.R
import dev.autobridge.logging.StructuredLog

/**
 * The car surface geometry Android Auto last handed this app, for diagnostics.
 *
 * Android Auto and the head unit negotiate the video stream on connect; the app only ever sees
 * the result through `SurfaceContainer`. When that result is the 800x480 floor, the head unit
 * upscales it to its panel and *every* app (Maps included) looks enlarged — nothing an app can
 * render fixes that. Recording what was negotiated lets the diagnostics screen say so plainly
 * instead of leaving it to be mistaken for an app scaling bug.
 *
 * ## Fingerprint
 *
 * Size and dpi alone do not describe what a head unit gives an app: the stable area (the host's
 * permanent chrome) and the visible area (split-screen rails, cards) decide where content and
 * touches actually land. Those are what a vehicle profile or touch calibration has to be checked
 * against, so all of it is kept together as one [Fingerprint] and written to the log once per
 * change, whichever car screen received it first. Nothing about the person or what is shown is
 * recorded, only screen geometry.
 */
object CarDisplayInfo {
    private const val TAG = "CAR_DISPLAY"

    data class Surface(val width: Int, val height: Int, val dpi: Int)

    /** A host-reported rectangle in surface pixels; kept free of `android.graphics.Rect` for JVM tests. */
    data class Area(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        override fun toString(): String = "[$left,$top][$right,$bottom]"
    }

    data class Fingerprint(val surface: Surface?, val stable: Area?, val visible: Area?) {
        override fun toString(): String =
            "surface=${surface?.let { "${it.width}x${it.height}@${it.dpi}" } ?: "none"} " +
                "stable=${stable ?: "none"} visible=${visible ?: "none"}"
    }

    /** Width and height of Android Auto's lowest stream, 800x480. */
    private const val MIN_STREAM_LONG_EDGE = 800
    private const val MIN_STREAM_SHORT_EDGE = 480

    @Volatile
    var fingerprint: Fingerprint = Fingerprint(null, null, null)
        private set

    val last: Surface?
        get() = fingerprint.surface

    /** Records the surface; returns true when the fingerprint changed (and was logged). */
    fun record(width: Int, height: Int, dpi: Int, source: String = "car"): Boolean {
        if (width <= 0 || height <= 0) return false
        return update(source) { it.copy(surface = Surface(width, height, dpi)) }
    }

    fun recordStableArea(left: Int, top: Int, right: Int, bottom: Int, source: String = "car"): Boolean =
        update(source) { it.copy(stable = Area(left, top, right, bottom)) }

    fun recordVisibleArea(left: Int, top: Int, right: Int, bottom: Int, source: String = "car"): Boolean =
        update(source) { it.copy(visible = Area(left, top, right, bottom)) }

    /** Forgets everything; the next car connection starts a new fingerprint. */
    @Synchronized
    fun reset() {
        fingerprint = Fingerprint(null, null, null)
    }

    @Synchronized
    private fun update(source: String, change: (Fingerprint) -> Fingerprint): Boolean {
        val next = change(fingerprint)
        if (next == fingerprint) return false
        fingerprint = next
        StructuredLog.i(TAG, "$next via=$source")
        return true
    }

    /**
     * True when the surface fits inside the 800x480 stream. The surface is the stream minus host
     * chrome (800x480 arrives as 800x400 on a unit with a status bar), so this compares edges.
     */
    fun isLowestStream(surface: Surface): Boolean =
        maxOf(surface.width, surface.height) <= MIN_STREAM_LONG_EDGE &&
            minOf(surface.width, surface.height) <= MIN_STREAM_SHORT_EDGE

    /** [context] resolves the "nothing connected yet" placeholder, which is shown to the user. */
    fun label(context: Context, surface: Surface?): String =
        surface?.let { "${it.width} x ${it.height} @ ${it.dpi} dpi" }
            ?: context.getString(R.string.car_display_none)
}
