package dev.autobridge.duoscreen.layout

import android.content.Context
import androidx.annotation.StringRes
import dev.autobridge.duoscreen.R
import dev.autobridge.duoscreen.layout.DuoScreenLayout.Bounds
import dev.autobridge.duoscreen.layout.DuoScreenLayout.Rect

/**
 * The named arrangements Duo Screen can start from, the counterpart of
 * [dev.autobridge.browser.BrowserSplitLayout] for real panes rather than web views.
 *
 * A preset is only a starting point: [dev.autobridge.duoscreen.input.DuoScreenInputRouter]'s edit mode still moves, resizes and
 * drags the seam between panes afterwards, and what the driver leaves behind is what
 * [DuoScreenStore] restores next time. Picking a preset again is how you get back to a clean split.
 *
 * Every preset produces rects that already satisfy [DuoScreenLayout.MIN_WIDTH_FRACTION] and
 * [DuoScreenLayout.MIN_HEIGHT_FRACTION] at both supported pane counts — that is why the main share
 * of [WIDE_LEFT]/[WIDE_RIGHT] drops from 65% to 50% at three panes, where 65/17.5/17.5 would put
 * the two side panes under the minimum and the clamp would overlap them.
 */
enum class DuoScreenPreset(@StringRes val labelRes: Int, val glyph: String) {
    EVEN_COLUMNS(R.string.duo_screen_preset_even_columns, "◫"),
    WIDE_LEFT(R.string.duo_screen_preset_wide_left, "◧"),
    WIDE_RIGHT(R.string.duo_screen_preset_wide_right, "◨"),
    EVEN_ROWS(R.string.duo_screen_preset_even_rows, "⊟"),

    /**
     * Stacked, the top pane the larger: 60% over 40% (a third pane takes the bottom 40% as two halves).
     * The shape of a portrait head unit with a map above and the music below.
     */
    STACKED_60_40(R.string.duo_screen_preset_stacked_60_40, "⬒"),

    /** Pane 0 fills the surface; the rest float over its bottom-right corner as small tiles. */
    PICTURE_IN_PICTURE(R.string.duo_screen_preset_pip, "◰");

    fun next(): DuoScreenPreset = entries[(ordinal + 1) % entries.size]

    fun label(context: Context): String = context.getString(labelRes)

    /** Rects for [paneCount] panes, in pane-id order (pane 0 first), tiling [bounds] exactly. */
    fun rects(paneCount: Int, bounds: Bounds): List<Rect> =
        DuoScreenPresetGeometry.rects(this, paneCount, bounds)
}

/** Pure preset geometry, so it runs on the JVM without a Context. */
object DuoScreenPresetGeometry {
    /** The main pane's share in [DuoScreenPreset.WIDE_LEFT]/[DuoScreenPreset.WIDE_RIGHT]. */
    private const val MAIN_FRACTION_TWO_PANES = 0.65f
    private const val MAIN_FRACTION_THREE_PANES = 0.5f

    /** Picture-in-picture tile size and the gap it keeps from the surface edges, as fractions. */
    private const val PIP_TILE_FRACTION = 0.32f
    private const val PIP_MARGIN_FRACTION = 0.02f

    /**
     * The layout to start from when the driver has never picked one: side by side on a wide
     * surface, stacked on one that is as tall as it is wide or taller (a portrait head unit gives
     * an app a nearly square area, where two columns would each be too narrow to use).
     */
    fun defaultFor(bounds: Bounds): DuoScreenPreset =
        if (bounds.width > 0 && bounds.height >= bounds.width * SQUARISH) DuoScreenPreset.EVEN_ROWS
        else DuoScreenPreset.EVEN_COLUMNS

    /** Height over width from which a surface counts as "tall enough to stack". */
    private const val SQUARISH = 0.9f

    fun rects(preset: DuoScreenPreset, paneCount: Int, bounds: Bounds): List<Rect> {
        require(paneCount in 2..3) { "Duo Screen supports 2-3 panes, got $paneCount" }
        if (bounds.width <= 0 || bounds.height <= 0) {
            return List(paneCount) { Rect(0, 0, bounds.width, bounds.height) }
        }
        return when (preset) {
            DuoScreenPreset.EVEN_COLUMNS -> columns(evenWeights(paneCount), bounds)
            DuoScreenPreset.EVEN_ROWS -> rows(evenWeights(paneCount), bounds)
            DuoScreenPreset.STACKED_60_40 -> rows(stackedWeights(paneCount), bounds)
            DuoScreenPreset.WIDE_LEFT -> columns(mainFirstWeights(paneCount), bounds)
            DuoScreenPreset.WIDE_RIGHT -> columns(mainFirstWeights(paneCount).reversed(), bounds)
            DuoScreenPreset.PICTURE_IN_PICTURE -> pictureInPicture(paneCount, bounds)
        }
    }

    private fun evenWeights(paneCount: Int): List<Float> = List(paneCount) { 1f / paneCount }

    /**
     * 60/40 for two panes. Three panes cannot be 60/20/20 (20% is under the minimum height), so
     * the top pane takes half and the other two a quarter each.
     */
    private fun stackedWeights(paneCount: Int): List<Float> =
        if (paneCount == 2) listOf(0.6f, 0.4f) else listOf(0.5f, 0.25f, 0.25f)

    private fun mainFirstWeights(paneCount: Int): List<Float> {
        val main = if (paneCount == 2) MAIN_FRACTION_TWO_PANES else MAIN_FRACTION_THREE_PANES
        val side = (1f - main) / (paneCount - 1)
        return listOf(main) + List(paneCount - 1) { side }
    }

    // The last column/row absorbs the rounding remainder so the panes tile the surface exactly
    // instead of leaving a sliver of uncovered car surface on the far edge.
    private fun columns(weights: List<Float>, bounds: Bounds): List<Rect> {
        var left = 0
        return weights.mapIndexed { index, weight ->
            val width = if (index == weights.lastIndex) {
                bounds.width - left
            } else {
                (bounds.width * weight).toInt()
            }
            Rect(left, 0, width, bounds.height).also { left += width }
        }
    }

    private fun rows(weights: List<Float>, bounds: Bounds): List<Rect> {
        var top = 0
        return weights.mapIndexed { index, weight ->
            val height = if (index == weights.lastIndex) {
                bounds.height - top
            } else {
                (bounds.height * weight).toInt()
            }
            Rect(0, top, bounds.width, height).also { top += height }
        }
    }

    /**
     * Pane 0 keeps the whole surface and the others sit on top of it, stacked up from the
     * bottom-right corner. They overlap pane 0 on purpose: the pane set's z-order (last = topmost)
     * already draws and hit-tests them above it.
     */
    private fun pictureInPicture(paneCount: Int, bounds: Bounds): List<Rect> {
        val tileWidth = (bounds.width * PIP_TILE_FRACTION).toInt().coerceAtLeast(bounds.minPaneWidth)
        val tileHeight = (bounds.height * PIP_TILE_FRACTION).toInt().coerceAtLeast(bounds.minPaneHeight)
        val margin = (bounds.width * PIP_MARGIN_FRACTION).toInt()
        val tiles = (0 until paneCount - 1).map { index ->
            val top = bounds.height - margin - (index + 1) * (tileHeight + margin)
            DuoScreenLayout.clamp(
                Rect(bounds.width - margin - tileWidth, top, tileWidth, tileHeight),
                bounds
            )
        }
        return listOf(Rect(0, 0, bounds.width, bounds.height)) + tiles
    }
}
