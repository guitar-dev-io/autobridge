package dev.autobridge.browser

import android.webkit.WebView
import java.util.WeakHashMap
import kotlin.math.roundToInt

/**
 * The zoom the car menu shows between its − and + buttons, as a percent of the page as it loaded.
 *
 * `WebView.zoomBy` applies asynchronously and the WebView exposes no reliable "current zoom versus
 * the page's own" value, so the steps are counted here instead. Every zoom on a car browser goes
 * through [zoomBy], a step the WebView refuses (already at its limit) is not counted, and a new
 * page starts again from 100% ([reset] from `onPageStarted`), as the WebView itself does.
 */
object PageZoom {
    private val factors = WeakHashMap<WebView, Float>()

    fun zoomBy(view: WebView, step: Float) {
        if (!step.isFinite() || step <= 0f || step == 1f) return
        @Suppress("DEPRECATION")
        val allowed = if (step > 1f) view.canZoomIn() else view.canZoomOut()
        if (!allowed) return
        view.zoomBy(step)
        factors[view] = ((factors[view] ?: 1f) * step).coerceIn(MIN_FACTOR, MAX_FACTOR)
    }

    fun percent(view: WebView?): Int = view?.let { percentOf(factors[it] ?: 1f) } ?: 100

    fun reset(view: WebView) {
        factors.remove(view)
    }

    /** Rounded to whole percent, so 1.25 × 0.8 reads 100% rather than drifting by a float bit. */
    fun percentOf(factor: Float): Int = (factor * 100f).roundToInt()

    private const val MIN_FACTOR = 0.1f
    private const val MAX_FACTOR = 10f
}
