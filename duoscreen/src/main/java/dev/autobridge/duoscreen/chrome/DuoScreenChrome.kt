package dev.autobridge.duoscreen.chrome

import dev.autobridge.duoscreen.layout.DuoScreenLayout
import dev.autobridge.duoscreen.layout.DuoScreenLayout.Axis
import dev.autobridge.duoscreen.layout.DuoScreenLayout.Bounds
import dev.autobridge.duoscreen.layout.DuoScreenLayout.Divider
import dev.autobridge.duoscreen.layout.DuoScreenLayout.Rect
import dev.autobridge.duoscreen.layout.DuoScreenPane
import dev.autobridge.duoscreen.layout.DuoScreenPreset

/**
 * Sizes of the app-drawn controls, in dp. The seam between the panes is thin ([BAR]) with a small
 * handle in the middle ([GRIP_LENGTH] x [GRIP_THICKNESS], touched over [HANDLE_TOUCH]); tapping it
 * brings up a floating toolbar of [BUTTON]-sized buttons padded by [BAR_PADDING]. A permanent bar of
 * buttons, even shrunk, read as too big on the car display, so the panes get nearly all of it.
 * [REGULAR] is for a large portrait unit and [COMPACT] for a small or short surface.
 */
@Suppress("PropertyName")
data class DuoScreenChromeSpec(
    val BAR: Float,
    val SEAM: Float,
    val BUTTON: Float,
    val BUTTON_RADIUS: Float,
    val BUTTON_GAP: Float,
    val BUTTON_ICON: Float,
    val BAR_PADDING: Float,
    val GRIP_LENGTH: Float,
    val GRIP_THICKNESS: Float,
    val HANDLE_TOUCH: Float,
    val PILL: Float,
    val GRIP_PILL_LENGTH: Float,
    val PILL_PADDING: Float,
    val PILL_ICON: Float,
    val PILL_ICON_GAP: Float,
    val PILL_TEXT: Float,
    val PILL_GROUP_GAP: Float,
    val CHIP: Float,
    val CHIP_RADIUS: Float,
    val CHIP_PADDING: Float,
    val CHIP_GLYPH_WIDTH: Float,
    val CHIP_GLYPH_HEIGHT: Float,
    val CHIP_GLYPH_GAP: Float,
    val CHIP_TEXT: Float,
    val CHIP_GAP: Float,
    val CHIP_MARGIN: Float,
    val EXIT_RESERVE: Float,
    val PANE_RADIUS: Float,
    val FOCUS_EDGE: Float,
    val SELECT_OUTLINE: Float,
    val CARD_TILE: Float,
    val CARD_TILE_RADIUS: Float,
    val CARD_TITLE: Float,
    val CARD_SHARE: Float,
    val CARD_GAP: Float,
    val CARD_BUTTON: Float,
    val CARD_BUTTON_PADDING: Float,
    val CARD_BUTTON_TEXT: Float
) {
    companion object {
        /** Text line box as a multiple of its size. */
        const val LINE = 1.3f

        /** Below this short side, in dp, the surface gets [COMPACT]. */
        const val COMPACT_BELOW_DP = 720f

        val REGULAR = DuoScreenChromeSpec(
        BAR = 12f,
        SEAM = 6f,
        BUTTON = 44f,
        BUTTON_RADIUS = 12f,
        BUTTON_GAP = 6f,
        BUTTON_ICON = 22f,
        BAR_PADDING = 6f,
        GRIP_LENGTH = 44f,
        HANDLE_TOUCH = 56f,
        GRIP_THICKNESS = 6f,
        PILL = 44f,
        GRIP_PILL_LENGTH = 100f,
        PILL_PADDING = 16f,
        PILL_ICON = 20f,
        PILL_ICON_GAP = 8f,
        PILL_TEXT = 18f,
        PILL_GROUP_GAP = 12f,
        CHIP = 64f,
        CHIP_RADIUS = 22f,
        CHIP_PADDING = 20f,
        CHIP_GLYPH_WIDTH = 34f,
        CHIP_GLYPH_HEIGHT = 26f,
        CHIP_GLYPH_GAP = 12f,
        CHIP_TEXT = 20f,
        CHIP_GAP = 10f,
        CHIP_MARGIN = 12f,
        EXIT_RESERVE = 132f,
        PANE_RADIUS = 20f,
        FOCUS_EDGE = 5f,
        SELECT_OUTLINE = 3f,
        CARD_TILE = 80f,
        CARD_TILE_RADIUS = 24f,
        CARD_TITLE = 26f,
        CARD_SHARE = 20f,
        CARD_GAP = 14f,
        CARD_BUTTON = 60f,
        CARD_BUTTON_PADDING = 28f,
        CARD_BUTTON_TEXT = 20f
        )

        val COMPACT = DuoScreenChromeSpec(
        BAR = 10f,
        SEAM = 6f,
        BUTTON = 36f,
        BUTTON_RADIUS = 10f,
        BUTTON_GAP = 4f,
        BUTTON_ICON = 18f,
        BAR_PADDING = 4f,
        GRIP_LENGTH = 36f,
        HANDLE_TOUCH = 48f,
        GRIP_THICKNESS = 5f,
        PILL = 36f,
        GRIP_PILL_LENGTH = 72f,
        PILL_PADDING = 12f,
        PILL_ICON = 16f,
        PILL_ICON_GAP = 6f,
        PILL_TEXT = 14f,
        PILL_GROUP_GAP = 10f,
        CHIP = 44f,
        CHIP_RADIUS = 16f,
        CHIP_PADDING = 14f,
        CHIP_GLYPH_WIDTH = 26f,
        CHIP_GLYPH_HEIGHT = 20f,
        CHIP_GLYPH_GAP = 8f,
        CHIP_TEXT = 16f,
        CHIP_GAP = 8f,
        CHIP_MARGIN = 8f,
        EXIT_RESERVE = 96f,
        PANE_RADIUS = 14f,
        FOCUS_EDGE = 4f,
        SELECT_OUTLINE = 3f,
        CARD_TILE = 52f,
        CARD_TILE_RADIUS = 16f,
        CARD_TITLE = 18f,
        CARD_SHARE = 15f,
        CARD_GAP = 8f,
        CARD_BUTTON = 44f,
        CARD_BUTTON_PADDING = 18f,
        CARD_BUTTON_TEXT = 16f
        )

        fun forSurface(width: Int, height: Int, density: Float): DuoScreenChromeSpec =
            if (minOf(width, height) / density.coerceAtLeast(0.1f) < COMPACT_BELOW_DP) COMPACT else REGULAR
    }
}

/** Something on the drawn chrome a tap can hit. */
sealed interface ChromeTarget {
    enum class Kind { LAYOUT, SWAP, RELOAD, ARRANGE, DONE, GRIP }

    data class Control(val kind: Kind) : ChromeTarget
    data class Chip(val preset: DuoScreenPreset) : ChromeTarget
    data class ChangeApp(val paneId: Int) : ChromeTarget
}

/** The labels the geometry has to measure; the renderer draws the same strings. */
data class ChromeLabels(
    val swap: String,
    val done: String,
    val changeApp: String,
    val chips: Map<DuoScreenPreset, String>
)

/**
 * Where everything the app draws over the panes sits, for one arrangement and mode. Pure: the
 * renderer paints from it and the controller hit-tests against it, so the two can never disagree
 * about where a button is.
 *
 * The seam lives in the widest gap between two panes — which the presets leave exactly
 * [DuoScreenChromeSpec.BAR] wide — and runs along it, so on a stacked layout it is a horizontal
 * strip between the two panes and on a side-by-side one a vertical strip. With no such gap
 * (picture-in-picture, or panes moved by hand) it takes the strip along the bottom. In normal use
 * it shows only its handle; the toolbar ([toolbar]) floats over the seam while it is open.
 */
class DuoScreenChrome private constructor(
    /** The size set this was laid out with; the renderer draws to the same one. */
    val spec: DuoScreenChromeSpec,
    val bounds: Bounds,
    /** Every pane's id and rect, in z-order, as laid out when this was computed. */
    private val panes: List<Pair<Int, Rect>>,
    /** The bar's whole rect. */
    val bar: Rect,
    /** True when the bar runs left-to-right (a horizontal strip), false for a vertical one. */
    val barHorizontal: Boolean,
    /** The seam the bar sits on, which the grip grabs; null when the bar is the bottom strip. */
    val barDivider: Divider?,
    /** True when no gap was free and the bar is drawn over the panes' bottom edge. */
    val barOverlapsPanes: Boolean,
    val editing: Boolean,
    /** The handle in the middle of the seam, which opens the toolbar. Normal mode only. */
    val handle: Rect?,
    /** The toolbar floating over the seam while open, in normal mode; null when closed. */
    val toolbar: Rect?,
    /** The toolbar's buttons, by kind; empty while it is closed and while arranging. */
    val buttons: List<Pair<ChromeTarget.Kind, Rect>>,
    /** Arrange mode's grip pill on the seam; null when there is no seam to grab. */
    val grip: Rect?,
    /** Arrange mode's Swap and Done on the bar. */
    val swapPill: Rect?,
    val donePill: Rect?,
    /** Arrange mode's preset chips; [chipsIconOnly] when their labels did not fit. */
    val chips: List<Pair<DuoScreenPreset, Rect>>,
    val chipsIconOnly: Boolean,
    /** Arrange mode's card in each pane, in z-order. */
    val cards: List<Card>,
    /** The gap between toolbar buttons, and the handle's touch square, in px. */
    private val gapPx: Int,
    private val handleTouchPx: Int
) {
    /**
     * One pane's card while arranging. [tile] and [share] are null when the pane is too small to
     * hold them; the title and the Change app button always stay.
     */
    data class Card(
        val paneId: Int,
        val pane: Rect,
        val tile: Rect?,
        val title: Rect,
        val share: Rect?,
        val button: Rect,
        val sharePercent: Int,
        val shareAxis: ShareAxis
    )

    /** What a pane's share is a share of: the height of a stacked pane, the width of a column. */
    enum class ShareAxis { HEIGHT, WIDTH, AREA }

    /**
     * What a tap at ([x], [y]) hits, or null to let it through to the panes. Buttons take a
     * generous area around what is drawn: a fingertip on a car panel lands well off target.
     */
    fun hit(x: Int, y: Int): ChromeTarget? {
        if (editing) {
            chips.firstOrNull { (_, rect) -> rect.contains(x, y) }?.let { return ChromeTarget.Chip(it.first) }
            donePill?.let { if (it.inflate(gapPx).contains(x, y)) return ChromeTarget.Control(ChromeTarget.Kind.DONE) }
            swapPill?.let { if (it.inflate(gapPx).contains(x, y)) return ChromeTarget.Control(ChromeTarget.Kind.SWAP) }
            grip?.let { if (it.inflate(gapPx).contains(x, y)) return ChromeTarget.Control(ChromeTarget.Kind.GRIP) }
            cards.lastOrNull { it.button.contains(x, y) }?.let { return ChromeTarget.ChangeApp(it.paneId) }
            return null
        }
        buttons.firstOrNull { (_, rect) -> rect.inflate(gapPx / 2).contains(x, y) }
            ?.let { return ChromeTarget.Control(it.first) }
        if (toolbar == null) handle?.let { if (handleTouchArea(it).contains(x, y)) return ChromeTarget.Control(ChromeTarget.Kind.GRIP) }
        return null
    }

    val paneRects: List<Rect> get() = panes.map { it.second }

    fun paneRectOf(id: Int): Rect? = panes.firstOrNull { it.first == id }?.second

    /**
     * Whether a tap at ([x], [y]) lands on the chrome at all, hit or not: the seam belongs to no
     * pane, and neither does the open toolbar between its buttons.
     */
    fun onBar(x: Int, y: Int): Boolean = bar.contains(x, y) || toolbar?.contains(x, y) == true

    /** The handle is a few dp to look at but takes a finger-sized square around its centre. */
    private fun handleTouchArea(handle: Rect): Rect {
        val cx = handle.left + handle.width / 2
        val cy = handle.top + handle.height / 2
        return Rect(cx - handleTouchPx / 2, cy - handleTouchPx / 2, handleTouchPx, handleTouchPx)
    }

    companion object {
        /** The presets offered as chips while arranging, as in the design: side by side, stacked, overlay. */
        val CHIP_PRESETS = listOf(
            DuoScreenPreset.EVEN_COLUMNS,
            DuoScreenPreset.STACKED_60_40,
            DuoScreenPreset.PICTURE_IN_PICTURE
        )

        /** The [Bounds] for a surface, with the bar and seam gaps at this [density]. */
        fun boundsFor(width: Int, height: Int, density: Float): Bounds {
            val spec = DuoScreenChromeSpec.forSurface(width, height, density)
            return Bounds(width, height, barPx = (spec.BAR * density).toInt(), seamPx = (spec.SEAM * density).toInt())
        }

        /**
         * True when [panes] leave a gap the bar fits — between two of them, or along the bottom.
         * The caller lays out afresh a saved arrangement that fails this: one from before the bar
         * existed tiles edge to edge and would get the bar drawn over its panes, and one saved at
         * a larger bar size would keep that size, since the bar fills the gap it is given.
         */
        fun hasRoomForBar(panes: List<DuoScreenPane>, bounds: Bounds): Boolean {
            val gap = widestGap(panes, bounds)
                ?: return bottomStripFree(panes, bounds)
            val thickness = if (gap.first.axis == Axis.HORIZONTAL) gap.second.height else gap.second.width
            return thickness <= bounds.barPx * 3 / 2
        }

        /**
         * @param panes in z-order (last on top), as the pane set holds them.
         * @param measure width in px of [String] at a text size in px, bold.
         */
        fun compute(
            panes: List<DuoScreenPane>,
            bounds: Bounds,
            density: Float,
            editing: Boolean,
            labels: ChromeLabels,
            measure: (String, Float) -> Float,
            toolbarOpen: Boolean = false
        ): DuoScreenChrome {
            val s = DuoScreenChromeSpec.forSurface(bounds.width, bounds.height, density)
            fun px(dp: Float) = (dp * density).toInt()
            val barPx = bounds.barPx.coerceAtLeast(px(s.BAR))

            // --- where the bar goes ---------------------------------------------------------
            val gap = widestGap(panes, bounds)
            val bar: Rect
            val horizontal: Boolean
            val overlaps: Boolean
            if (gap != null) {
                bar = gap.second
                horizontal = gap.first.axis == Axis.HORIZONTAL
                overlaps = false
            } else {
                bar = Rect(0, bounds.height - barPx, bounds.width, barPx)
                horizontal = true
                overlaps = !bottomStripFree(panes, bounds)
            }
            val divider = gap?.first

            val button = px(s.BUTTON)
            val buttonGap = px(s.BUTTON_GAP)
            val pad = px(s.BAR_PADDING)
            val along = if (horizontal) bar.width else bar.height
            val acrossCenter = if (horizontal) bar.top + bar.height / 2 else bar.left + bar.width / 2
            val alongStart = if (horizontal) bar.left else bar.top

            /** A [size]-square (or [length] x [thickness]) box at [offset] along the bar, centred across it. */
            fun boxAlong(offset: Int, length: Int, thickness: Int): Rect =
                if (horizontal) Rect(alongStart + offset, acrossCenter - thickness / 2, length, thickness)
                else Rect(acrossCenter - thickness / 2, alongStart + offset, thickness, length)

            val buttons = mutableListOf<Pair<ChromeTarget.Kind, Rect>>()
            var handle: Rect? = null
            var toolbar: Rect? = null
            var grip: Rect? = null
            var swapPill: Rect? = null
            var donePill: Rect? = null
            if (!editing) {
                val handleLength = px(s.GRIP_LENGTH)
                handle = boxAlong((along - handleLength) / 2, handleLength, px(s.GRIP_THICKNESS))
                if (toolbarOpen) {
                    // Layout, Swap, Reload, Arrange on a pill centred on the seam, along it, kept
                    // on the surface when the seam runs close to an edge.
                    val kinds = listOf(
                        ChromeTarget.Kind.LAYOUT, ChromeTarget.Kind.SWAP,
                        ChromeTarget.Kind.RELOAD, ChromeTarget.Kind.ARRANGE
                    )
                    val length = pad * 2 + button * kinds.size + buttonGap * (kinds.size - 1)
                    val thickness = button + pad * 2
                    val cx = bar.left + bar.width / 2
                    val cy = bar.top + bar.height / 2
                    val w = if (horizontal) length else thickness
                    val h = if (horizontal) thickness else length
                    val left = (cx - w / 2).coerceIn(0, (bounds.width - w).coerceAtLeast(0))
                    val top = (cy - h / 2).coerceIn(0, (bounds.height - h).coerceAtLeast(0))
                    toolbar = Rect(left, top, w, h)
                    kinds.forEachIndexed { index, kind ->
                        val offset = pad + index * (button + buttonGap)
                        buttons += kind to if (horizontal) Rect(left + offset, top + pad, button, button)
                        else Rect(left + pad, top + offset, button, button)
                    }
                }
            } else {
                val pill = px(s.PILL)
                val groupGap = px(s.PILL_GROUP_GAP)
                if (horizontal) {
                    fun pillWidth(text: String) =
                        px(s.PILL_PADDING) * 2 + px(s.PILL_ICON) + px(s.PILL_ICON_GAP) +
                            measure(text, s.PILL_TEXT * density).toInt()
                    val gripLength = if (divider != null) px(s.GRIP_PILL_LENGTH) else 0
                    val swapWidth = pillWidth(labels.swap)
                    val doneWidth = pillWidth(labels.done)
                    val total = gripLength + swapWidth + doneWidth + groupGap * (if (gripLength > 0) 2 else 1)
                    var cursor = ((along - total) / 2).coerceAtLeast(pad)
                    if (gripLength > 0) {
                        grip = boxAlong(cursor, gripLength, pill)
                        cursor += gripLength + groupGap
                    }
                    swapPill = boxAlong(cursor, swapWidth, pill)
                    cursor += swapWidth + groupGap
                    donePill = boxAlong(cursor, doneWidth, pill)
                } else {
                    // A vertical bar is one button wide: icon-only Swap and Done around the grip.
                    val gripLength = if (divider != null) px(s.GRIP_PILL_LENGTH) else 0
                    val total = gripLength + pill * 2 + groupGap * (if (gripLength > 0) 2 else 1)
                    var cursor = ((along - total) / 2).coerceAtLeast(pad)
                    if (gripLength > 0) {
                        grip = boxAlong(cursor, gripLength, pill)
                        cursor += gripLength + groupGap
                    }
                    swapPill = boxAlong(cursor, pill, pill)
                    cursor += pill + groupGap
                    donePill = boxAlong(cursor, pill, pill)
                }
            }

            // --- arrange mode: chips and cards -------------------------------------------------
            val chips = mutableListOf<Pair<DuoScreenPreset, Rect>>()
            var iconOnly = false
            val cards = mutableListOf<Card>()
            if (editing) {
                val chipHeight = px(s.CHIP)
                val margin = px(s.CHIP_MARGIN)
                val room = bounds.width - margin - px(s.EXIT_RESERVE)
                fun chipWidth(preset: DuoScreenPreset, withText: Boolean): Int =
                    px(s.CHIP_PADDING) * 2 + px(s.CHIP_GLYPH_WIDTH) +
                        if (withText) px(s.CHIP_GLYPH_GAP) +
                            measure(labels.chips[preset].orEmpty(), s.CHIP_TEXT * density).toInt() else 0
                val withText = CHIP_PRESETS.sumOf { chipWidth(it, true) } + px(s.CHIP_GAP) * (CHIP_PRESETS.size - 1)
                iconOnly = withText > room
                var left = margin
                CHIP_PRESETS.forEach { preset ->
                    val width = chipWidth(preset, !iconOnly)
                    chips += preset to Rect(left, margin, width, chipHeight)
                    left += width + px(s.CHIP_GAP)
                }

                val sharing = shares(panes, bounds)
                panes.forEach { pane ->
                    val r = pane.rect
                    val tile = px(s.CARD_TILE)
                    val title = (s.CARD_TITLE * DuoScreenChromeSpec.LINE * density).toInt()
                    val share = (s.CARD_SHARE * DuoScreenChromeSpec.LINE * density).toInt()
                    val cardGap = px(s.CARD_GAP)
                    val buttonHeight = px(s.CARD_BUTTON)
                    // Drop the tile first, then the share line, until the card fits the pane.
                    val full = tile + title + share + buttonHeight + cardGap * 3
                    val noTile = title + share + buttonHeight + cardGap * 2
                    val showTile = r.height >= full + cardGap * 2
                    val showShare = showTile || r.height >= noTile + cardGap * 2
                    val block = when {
                        showTile -> full
                        showShare -> noTile
                        else -> title + buttonHeight + cardGap
                    }
                    var top = r.top + (r.height - block) / 2
                    val centerX = r.left + r.width / 2
                    val tileRect = if (showTile) Rect(centerX - tile / 2, top, tile, tile).also { top += tile + cardGap } else null
                    val titleRect = Rect(r.left, top, r.width, title).also { top += title + cardGap }
                    val shareRect = if (showShare) Rect(r.left, top, r.width, share).also { top += share + cardGap } else null
                    val buttonWidth = (px(s.CARD_BUTTON_PADDING) * 2 +
                        measure(labels.changeApp, s.CARD_BUTTON_TEXT * density).toInt()).coerceAtMost(r.width)
                    val (percent, axis) = sharing[pane.id] ?: (100 to ShareAxis.AREA)
                    cards += Card(
                        paneId = pane.id,
                        pane = r,
                        tile = tileRect,
                        title = titleRect,
                        share = shareRect,
                        button = Rect(centerX - buttonWidth / 2, top, buttonWidth, buttonHeight),
                        sharePercent = percent,
                        shareAxis = axis
                    )
                }
            }

            return DuoScreenChrome(
                s, bounds, panes.map { it.id to it.rect }, bar, horizontal, divider, overlaps, editing,
                handle, toolbar, buttons, grip, swapPill, donePill, chips, iconOnly, cards, buttonGap,
                px(s.HANDLE_TOUCH)
            )
        }

        /**
         * The seam with the widest gap between its two panes, at least three quarters of the bar,
         * as (divider, the gap's rect). Ties go to the longer seam.
         */
        private fun widestGap(panes: List<DuoScreenPane>, bounds: Bounds): Pair<Divider, Rect>? {
            val minimum = bounds.barPx * 3 / 4
            if (minimum <= 0) return null
            val byId = panes.associateBy { it.id }
            val found = mutableListOf<Pair<Divider, Rect>>()
            for (i in panes.indices) for (j in i + 1 until panes.size) {
                Axis.entries.forEach { axis ->
                    val d = DuoScreenLayout.dividerBetween(
                        panes[i].id, panes[i].rect, panes[j].id, panes[j].rect, axis, bounds.seamTolerance
                    ) ?: return@forEach
                    val first = byId.getValue(d.first).rect
                    val second = byId.getValue(d.second).rect
                    val rect = when (axis) {
                        Axis.HORIZONTAL -> Rect(d.from, first.bottom, d.to - d.from, second.top - first.bottom)
                        Axis.VERTICAL -> Rect(first.right, d.from, second.left - first.right, d.to - d.from)
                    }
                    val thickness = if (axis == Axis.HORIZONTAL) rect.height else rect.width
                    if (thickness >= minimum) found += d to rect
                }
            }
            return found.maxWithOrNull(
                compareBy<Pair<Divider, Rect>> { (d, r) -> if (d.axis == Axis.HORIZONTAL) r.height else r.width }
                    .thenBy { (d, _) -> d.to - d.from }
            )
        }

        private fun bottomStripFree(panes: List<DuoScreenPane>, bounds: Bounds): Boolean {
            if (bounds.barPx <= 0) return false
            val strip = Rect(0, bounds.height - bounds.barPx, bounds.width, bounds.barPx)
            return panes.none { DuoScreenLayout.overlaps(it.rect, strip) }
        }

        /**
         * Each pane's share, as the Arrange card states it: a pane as wide as the surface is a share
         * of the height those panes split, one as tall as it of the width, anything else of the area.
         */
        private fun shares(panes: List<DuoScreenPane>, bounds: Bounds): Map<Int, Pair<Int, ShareAxis>> {
            val slack = bounds.seamTolerance
            val fullWidth = panes.filter { it.rect.width >= bounds.width - slack }
            val fullHeight = panes.filter { it.rect.height >= bounds.height - slack }
            val heights = fullWidth.sumOf { it.rect.height }.coerceAtLeast(1)
            val widths = fullHeight.sumOf { it.rect.width }.coerceAtLeast(1)
            val area = bounds.width.toLong() * bounds.height
            return panes.associate { pane ->
                pane.id to when {
                    fullWidth.size >= 2 && pane in fullWidth ->
                        (pane.rect.height * 100 + heights / 2) / heights to ShareAxis.HEIGHT
                    fullHeight.size >= 2 && pane in fullHeight ->
                        (pane.rect.width * 100 + widths / 2) / widths to ShareAxis.WIDTH
                    else -> ((pane.rect.width.toLong() * pane.rect.height * 100 + area / 2) / area.coerceAtLeast(1)).toInt() to
                        ShareAxis.AREA
                }
            }
        }
    }
}

/** [this] grown by [by] px on every side. */
internal fun Rect.inflate(by: Int): Rect = Rect(left - by, top - by, width + by * 2, height + by * 2)
