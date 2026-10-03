package dev.autobridge.browser

import android.content.Context
import androidx.annotation.StringRes
import androidx.core.content.edit
import dev.autobridge.R
import kotlin.math.roundToInt

/**
 * How the car browser divides its surface between the main page and an optional side page.
 *
 * The main page is the full browser (tabs, toolbar, history); the side page is a second, simpler
 * page — typically a map — shown next to it. Fractions are the side pane's share of the width.
 */
enum class BrowserSplitLayout(@StringRes val labelRes: Int, val glyph: String) {
    SINGLE(R.string.split_single, "▭"),
    HALF(R.string.split_half, "◫"),
    FORTY_SIXTY(R.string.split_forty_sixty, "◧"),

    /**
     * The main pane takes 65% (video / web), the side pane 35% (map / navigation / info). This is
     * the "video beside a map" preset: wide enough for a 16:9-ish main picture while still leaving a
     * usable navigation strip, without pinning either pane to a fixed aspect the way
     * [PORTRAIT_LANDSCAPE] does.
     */
    SIXTY_FIVE_THIRTY_FIVE(R.string.split_sixty_five_thirty_five, "◧"),

    /**
     * A tall side pane next to a 16:9 main pane: e.g. a portrait map beside a landscape video. The
     * main pane is sized to exactly 16:9 where the surface allows, the side pane takes the rest.
     */
    PORTRAIT_LANDSCAPE(R.string.split_portrait_landscape, "▯▭");

    fun next(): BrowserSplitLayout = entries[(ordinal + 1) % entries.size]

    /** The name to show for this layout; a string id for the reason [FloatingButtonAction] gives. */
    fun label(context: Context): String = context.getString(labelRes)
}

/** A pane rectangle in car-surface pixels. Plain ints so the geometry is testable on the JVM. */
data class PaneRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    fun contains(x: Float, y: Float): Boolean = x >= left && y >= top && x < right && y < bottom
}

/** The two panes of a split. [main] holds the full browser, [side] the second page. */
data class SplitPanes(val main: PaneRect, val side: PaneRect)

object BrowserSplitGeometry {
    /** Side share of the width for the fixed-ratio layouts. */
    private const val HALF_FRACTION = 0.5f
    private const val FORTY_FRACTION = 0.4f

    /** Side (map) share for the 65/35 preset: the main pane keeps 65%, the map strip takes 35%. */
    private const val THIRTY_FIVE_FRACTION = 0.35f

    /** In [BrowserSplitLayout.PORTRAIT_LANDSCAPE], the 16:9 main pane never takes more than this. */
    private const val LANDSCAPE_MAX_FRACTION = 0.7f

    /**
     * Splits the card rect into two panes, or returns null when [layout] is
     * [BrowserSplitLayout.SINGLE] or either pane would be narrower than [minPanePx] (a split
     * nobody can use is worse than no split).
     *
     * [gapPx] is left empty between the panes for the divider. [sideOnRight] mirrors the layout so
     * the side pane can sit on either side of the driver.
     */
    fun panes(
        layout: BrowserSplitLayout,
        left: Int, top: Int, right: Int, bottom: Int,
        sideOnRight: Boolean,
        gapPx: Int,
        minPanePx: Int,
    ): SplitPanes? {
        val width = right - left
        val height = bottom - top
        if (width <= 0 || height <= 0) return null
        val gap = gapPx.coerceAtLeast(0)
        val usable = width - gap
        val (sideWidth, mainWidth, mainHeight) = when (layout) {
            BrowserSplitLayout.SINGLE -> return null
            BrowserSplitLayout.HALF -> fixed(usable, height, HALF_FRACTION)
            BrowserSplitLayout.FORTY_SIXTY -> fixed(usable, height, FORTY_FRACTION)
            BrowserSplitLayout.SIXTY_FIVE_THIRTY_FIVE -> fixed(usable, height, THIRTY_FIVE_FRACTION)
            BrowserSplitLayout.PORTRAIT_LANDSCAPE -> {
                val main = minOf((height * 16f / 9f).roundToInt(), (usable * LANDSCAPE_MAX_FRACTION).roundToInt())
                Triple(usable - main, main, (main * 9f / 16f).roundToInt().coerceAtMost(height))
            }
        }
        if (sideWidth < minPanePx || mainWidth < minPanePx) return null

        // Left-to-right in the unmirrored layout: side, gap, main.
        val sideLeft = if (sideOnRight) right - sideWidth else left
        val mainLeft = if (sideOnRight) left else right - mainWidth
        // A main pane shorter than the card (the 16:9 preset) is centred vertically.
        val mainTop = top + (height - mainHeight) / 2
        return SplitPanes(
            main = PaneRect(mainLeft, mainTop, mainLeft + mainWidth, mainTop + mainHeight),
            side = PaneRect(sideLeft, top, sideLeft + sideWidth, bottom),
        )
    }

    private fun fixed(usable: Int, height: Int, sideFraction: Float): Triple<Int, Int, Int> {
        val side = (usable * sideFraction).roundToInt()
        return Triple(side, usable - side, height)
    }
}

/** Persisted split preferences, in the browser's shared preference file. */
object BrowserSplitStore {
    private const val PREFS_NAME = "autobridge_browser"
    private const val KEY_LAYOUT = "split_layout"
    private const val KEY_SIDE_ON_RIGHT = "split_side_on_right"
    private const val KEY_SIDE_URL = "split_side_url"

    /** What the side pane opens the first time: the map case this feature exists for. */
    const val DEFAULT_SIDE_URL = "https://www.google.com/maps"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun layout(context: Context): BrowserSplitLayout =
        prefs(context).getString(KEY_LAYOUT, null)
            ?.let { runCatching { BrowserSplitLayout.valueOf(it) }.getOrNull() }
            ?: BrowserSplitLayout.SINGLE

    fun setLayout(context: Context, layout: BrowserSplitLayout) {
        prefs(context).edit { putString(KEY_LAYOUT, layout.name) }
    }

    /** Off by default: the side pane (map) sits on the left, the main page on the right. */
    fun sideOnRight(context: Context): Boolean = prefs(context).getBoolean(KEY_SIDE_ON_RIGHT, false)

    fun setSideOnRight(context: Context, onRight: Boolean) {
        prefs(context).edit { putBoolean(KEY_SIDE_ON_RIGHT, onRight) }
    }

    fun sideUrl(context: Context): String =
        prefs(context).getString(KEY_SIDE_URL, null)?.let(dev.autobridge.entertainment.ContentAddress::https)
            ?: DEFAULT_SIDE_URL

    fun setSideUrl(context: Context, url: String) {
        val valid = dev.autobridge.entertainment.ContentAddress.https(url) ?: return
        prefs(context).edit { putString(KEY_SIDE_URL, valid) }
    }
}
