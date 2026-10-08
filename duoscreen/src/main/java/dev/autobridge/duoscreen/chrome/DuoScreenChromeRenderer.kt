package dev.autobridge.duoscreen.chrome

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import dev.autobridge.duoscreen.layout.DuoScreenLayout.Rect
import dev.autobridge.duoscreen.layout.DuoScreenPreset

/** What a pane is running, as its Arrange card shows it. */
data class PaneApp(val label: String, val icon: Bitmap?)

/**
 * Everything one chrome frame needs, captured on the controller's thread and painted on the GL
 * thread. Immutable, so the hand-over needs no locking.
 */
data class ChromeFrame(
    val chrome: DuoScreenChrome,
    val density: Float,
    /** The pane plain taps go to: the accent edge along its top. Null before the first tap. */
    val focusedPaneId: Int?,
    /** The pane outlined while arranging. */
    val selectedPaneId: Int?,
    val gripGrabbed: Boolean,
    val pressed: ChromeTarget?,
    val currentPreset: DuoScreenPreset,
    /** Pane id to its card title ("Pane 1 · Maps") and share line ("56% of the height"). */
    val titles: Map<Int, String>,
    val shares: Map<Int, String>,
    val apps: Map<Int, PaneApp>,
    val labels: ChromeLabels
)

/**
 * Paints a [ChromeFrame]: the gaps between panes in the surface colour with the panes' corners
 * rounded off, the focused pane's accent edge, the control bar, and in Arrange mode the preset
 * chips, the dimmed panes with their cards and the seam's grip, Swap and Done.
 *
 * Runs on the compositor's GL thread only, which is why its paints can be reused across frames.
 */
class DuoScreenChromeRenderer {
    private companion object {
        const val SURFACE = 0xFF0B0D10.toInt()
        const val BUTTON = 0xFF262A31.toInt()
        const val BUTTON_PRESSED = 0xFF343A43.toInt()
        const val ICON = 0xFFD5DAE0.toInt()
        const val GRIP = 0xFF4A5260.toInt()
        const val ACCENT = 0xFFB3C7F5.toInt()
        const val ON_ACCENT = 0xFF142540.toInt()
        const val DIM = 0xB80B0D10.toInt()
        const val CHIP = 0xFF2C3036.toInt()
        const val CHIP_SELECTED = 0xFF16213A.toInt()
        const val CHIP_TEXT = 0xFFD5DAE0.toInt()
        const val CHIP_TEXT_SELECTED = 0xFFDCE6FF.toInt()
        const val CHIP_GLYPH = 0xFF6B7380.toInt()
        const val SWAP = 0xFF2D4470.toInt()
        const val TEXT = 0xFFF1F3F5.toInt()
        const val TEXT_MUTED = 0xFFC7CCD2.toInt()
        const val CARD_BUTTON = 0xFFF1F3F5.toInt()
        const val CARD_BUTTON_TEXT = 0xFF0F1114.toInt()
        const val TILE = 0xFF1C2330.toInt()
    }

    private enum class Glyph { LAYOUT_STACKED, LAYOUT_COLUMNS, SWAP, RELOAD, ARRANGE, DONE }

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val text = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val image = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val rect = RectF()
    private val path = Path()
    private val scratch = Path()

    /** Bold text width, for [DuoScreenChrome.compute]; any thread, it uses its own paint. */
    fun measure(value: String, sizePx: Float): Float =
        TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textSize = sizePx
        }.measureText(value)

    fun draw(canvas: Canvas, frame: ChromeFrame) {
        val c = frame.chrome
        val d = frame.density
        val s = c.spec

        // Gaps in the surface colour, and every pane's corners rounded off by the same paint.
        path.reset()
        path.addRect(0f, 0f, c.bounds.width.toFloat(), c.bounds.height.toFloat(), Path.Direction.CW)
        val radius = s.PANE_RADIUS * d
        panesOf(c).forEach { pane ->
            scratch.reset()
            scratch.addRoundRect(pane.toRectF(), radius, radius, Path.Direction.CW)
            path.op(scratch, Path.Op.DIFFERENCE)
        }
        fill.color = SURFACE
        canvas.drawPath(path, fill)

        if (!c.editing) {
            frame.focusedPaneId?.let { id -> paneRect(c, id) }?.let { pane ->
                canvas.save()
                scratch.reset()
                scratch.addRoundRect(pane.toRectF(), radius, radius, Path.Direction.CW)
                canvas.clipPath(scratch)
                fill.color = ACCENT
                canvas.drawRect(pane.left.toFloat(), pane.top.toFloat(), pane.right.toFloat(), pane.top + s.FOCUS_EDGE * d, fill)
                canvas.restore()
            }
        } else {
            drawCards(canvas, frame)
        }

        drawBar(canvas, frame)
        if (c.editing) drawChips(canvas, frame)
    }

    // ---------------------------------------------------------------------------------- bar

    private fun drawBar(canvas: Canvas, frame: ChromeFrame) {
        val c = frame.chrome
        val d = frame.density
        val s = frame.chrome.spec
        if (c.barOverlapsPanes) {
            fill.color = SURFACE
            canvas.drawRect(c.bar.toRectF(), fill)
        }
        if (!c.editing) {
            c.buttons.forEach { (kind, box) ->
                val pressed = frame.pressed == ChromeTarget.Control(kind)
                fill.color = if (pressed) BUTTON_PRESSED else BUTTON
                rect.set(box.toRectF())
                canvas.drawRoundRect(rect, s.BUTTON_RADIUS * d, s.BUTTON_RADIUS * d, fill)
                val glyph = when (kind) {
                    ChromeTarget.Kind.LAYOUT ->
                        if (frame.currentPreset == DuoScreenPreset.EVEN_COLUMNS ||
                            frame.currentPreset == DuoScreenPreset.WIDE_LEFT ||
                            frame.currentPreset == DuoScreenPreset.WIDE_RIGHT
                        ) Glyph.LAYOUT_COLUMNS else Glyph.LAYOUT_STACKED
                    ChromeTarget.Kind.SWAP -> Glyph.SWAP
                    ChromeTarget.Kind.RELOAD -> Glyph.RELOAD
                    else -> Glyph.ARRANGE
                }
                glyph(canvas, glyph, box.centerX(), box.centerY(), s.BUTTON_ICON * d, ICON)
            }
            c.grip?.let { grip ->
                fill.color = if (frame.gripGrabbed) ACCENT else GRIP
                rect.set(grip.toRectF())
                val r = minOf(grip.width, grip.height) / 2f
                canvas.drawRoundRect(rect, r, r, fill)
            }
            return
        }

        // Arrange: the grip pill (three dots), Swap and Done.
        c.grip?.let { grip ->
            fill.color = if (frame.gripGrabbed) ACCENT else GRIP
            rect.set(grip.toRectF())
            val r = minOf(grip.width, grip.height) / 2f
            canvas.drawRoundRect(rect, r, r, fill)
            fill.color = if (frame.gripGrabbed) ON_ACCENT else ICON
            val dot = 4f * d
            val step = 16f * d
            for (i in -1..1) {
                if (c.barHorizontal) canvas.drawCircle(grip.centerX() + i * step, grip.centerY(), dot, fill)
                else canvas.drawCircle(grip.centerX(), grip.centerY() + i * step, dot, fill)
            }
        }
        c.swapPill?.let { pill(canvas, frame, it, SWAP, TEXT, Glyph.SWAP, frame.labels.swap, ChromeTarget.Kind.SWAP) }
        c.donePill?.let { pill(canvas, frame, it, ACCENT, ON_ACCENT, Glyph.DONE, frame.labels.done, ChromeTarget.Kind.DONE) }
    }

    /** A rounded pill with an icon and, when there is room for it, its label. */
    private fun pill(
        canvas: Canvas,
        frame: ChromeFrame,
        box: Rect,
        background: Int,
        foreground: Int,
        glyph: Glyph,
        label: String,
        kind: ChromeTarget.Kind
    ) {
        val d = frame.density
        val s = frame.chrome.spec
        val pressed = frame.pressed == ChromeTarget.Control(kind)
        fill.color = if (pressed) mix(background, 0xFFFFFFFF.toInt(), 0.2f) else background
        rect.set(box.toRectF())
        val r = minOf(box.width, box.height) / 2f
        canvas.drawRoundRect(rect, r, r, fill)
        val icon = s.PILL_ICON * d
        if (box.width <= box.height + 2) {
            glyph(canvas, glyph, box.centerX(), box.centerY(), icon, foreground)
            return
        }
        val left = box.left + s.PILL_PADDING * d
        glyph(canvas, glyph, left + icon / 2f, box.centerY(), icon, foreground)
        text.textSize = s.PILL_TEXT * d
        text.color = foreground
        canvas.drawText(label, left + icon + s.PILL_ICON_GAP * d, baseline(box), text)
    }

    // -------------------------------------------------------------------------------- chips

    private fun drawChips(canvas: Canvas, frame: ChromeFrame) {
        val d = frame.density
        val s = frame.chrome.spec
        frame.chrome.chips.forEach { (preset, box) ->
            val selected = preset == frame.currentPreset ||
                (preset == DuoScreenPreset.STACKED_60_40 && frame.currentPreset == DuoScreenPreset.EVEN_ROWS)
            val pressed = frame.pressed == ChromeTarget.Chip(preset)
            rect.set(box.toRectF())
            val r = s.CHIP_RADIUS * d
            fill.color = when {
                pressed -> BUTTON_PRESSED
                selected -> CHIP_SELECTED
                else -> CHIP
            }
            canvas.drawRoundRect(rect, r, r, fill)
            if (selected) {
                stroke.color = ACCENT
                stroke.strokeWidth = 3f * d
                rect.inset(1.5f * d, 1.5f * d)
                canvas.drawRoundRect(rect, r, r, stroke)
            }
            val glyphLeft = box.left + s.CHIP_PADDING * d
            val glyphTop = box.centerY() - s.CHIP_GLYPH_HEIGHT * d / 2f
            chipGlyph(canvas, preset, glyphLeft, glyphTop, s.CHIP_GLYPH_WIDTH * d, s.CHIP_GLYPH_HEIGHT * d, selected, d)
            if (!frame.chrome.chipsIconOnly) {
                text.textSize = s.CHIP_TEXT * d
                text.color = if (selected) CHIP_TEXT_SELECTED else CHIP_TEXT
                canvas.drawText(
                    frame.labels.chips[preset].orEmpty(),
                    glyphLeft + (s.CHIP_GLYPH_WIDTH + s.CHIP_GLYPH_GAP) * d, baseline(box), text
                )
            }
        }
    }

    /** The preset's shape in miniature: two columns, a 56/44 stack, or a tile over a pane. */
    private fun chipGlyph(canvas: Canvas, preset: DuoScreenPreset, left: Float, top: Float, w: Float, h: Float, on: Boolean, d: Float) {
        val main = if (on) ACCENT else CHIP_GLYPH
        val second = if (on) 0xFFC7D3EC.toInt() else CHIP_GLYPH
        val gap = 3f * d
        val r = 3f * d
        when (preset) {
            DuoScreenPreset.EVEN_COLUMNS -> {
                val half = (w - gap) / 2f
                fill.color = main
                rect.set(left, top, left + half, top + h); canvas.drawRoundRect(rect, r, r, fill)
                fill.color = second
                rect.set(left + half + gap, top, left + w, top + h); canvas.drawRoundRect(rect, r, r, fill)
            }
            DuoScreenPreset.PICTURE_IN_PICTURE -> {
                fill.color = main
                rect.set(left, top, left + w, top + h); canvas.drawRoundRect(rect, r, r, fill)
                fill.color = if (on) ON_ACCENT else 0xFFC2C8D0.toInt()
                rect.set(left + w - 3 * d - 13 * d, top + h - 3 * d - 10 * d, left + w - 3 * d, top + h - 3 * d)
                canvas.drawRoundRect(rect, 2 * d, 2 * d, fill)
            }
            else -> {
                val upper = (h - gap) * 0.56f
                fill.color = main
                rect.set(left, top, left + w, top + upper); canvas.drawRoundRect(rect, r, r, fill)
                fill.color = second
                rect.set(left, top + upper + gap, left + w, top + h); canvas.drawRoundRect(rect, r, r, fill)
            }
        }
    }

    // -------------------------------------------------------------------------------- cards

    private fun drawCards(canvas: Canvas, frame: ChromeFrame) {
        val d = frame.density
        val s = frame.chrome.spec
        val radius = s.PANE_RADIUS * d
        frame.chrome.cards.forEach { card ->
            rect.set(card.pane.toRectF())
            fill.color = DIM
            canvas.drawRoundRect(rect, radius, radius, fill)
            if (card.paneId == frame.selectedPaneId) {
                stroke.color = ACCENT
                stroke.strokeWidth = s.SELECT_OUTLINE * d
                rect.inset(stroke.strokeWidth / 2f, stroke.strokeWidth / 2f)
                canvas.drawRoundRect(rect, radius, radius, stroke)
            }

            card.tile?.let { tile ->
                rect.set(tile.toRectF())
                fill.color = TILE
                val tr = s.CARD_TILE_RADIUS * d
                canvas.drawRoundRect(rect, tr, tr, fill)
                frame.apps[card.paneId]?.icon?.let { icon ->
                    val inset = tile.width * 0.2f
                    rect.inset(inset, inset)
                    canvas.drawBitmap(icon, null, rect, image)
                }
            }

            text.textSize = s.CARD_TITLE * d
            text.color = TEXT
            drawCentred(canvas, frame.titles[card.paneId].orEmpty(), card.title, card.pane.width - 2 * s.CARD_GAP * d)
            card.share?.let { share ->
                text.textSize = s.CARD_SHARE * d
                text.color = TEXT_MUTED
                drawCentred(canvas, frame.shares[card.paneId].orEmpty(), share, card.pane.width - 2 * s.CARD_GAP * d)
            }

            val pressed = frame.pressed == ChromeTarget.ChangeApp(card.paneId)
            rect.set(card.button.toRectF())
            fill.color = if (pressed) 0xFFC9CED6.toInt() else CARD_BUTTON
            val br = card.button.height / 2f
            canvas.drawRoundRect(rect, br, br, fill)
            text.textSize = s.CARD_BUTTON_TEXT * d
            text.color = CARD_BUTTON_TEXT
            drawCentred(canvas, frame.labels.changeApp, card.button, card.button.width.toFloat())
        }
    }

    private fun drawCentred(canvas: Canvas, value: String, box: Rect, maxWidth: Float) {
        if (value.isBlank() || maxWidth <= 0f) return
        val clipped = TextUtils.ellipsize(value, text, maxWidth, TextUtils.TruncateAt.END)
        val width = text.measureText(clipped, 0, clipped.length)
        canvas.drawText(clipped, 0, clipped.length, box.centerX() - width / 2f, baseline(box), text)
    }

    // ------------------------------------------------------------------------------- glyphs

    /** The design's line icons, on their 24-unit grid, centred on ([cx], [cy]) at [size]. */
    private fun glyph(canvas: Canvas, kind: Glyph, cx: Float, cy: Float, size: Float, color: Int) {
        val k = size / 24f
        val ox = cx - size / 2f
        val oy = cy - size / 2f
        fun x(v: Float) = ox + v * k
        fun y(v: Float) = oy + v * k
        stroke.color = color
        stroke.strokeWidth = 2.2f * k
        path.reset()
        when (kind) {
            Glyph.LAYOUT_STACKED -> {
                rect.set(x(4f), y(3f), x(20f), y(12f)); canvas.drawRoundRect(rect, 1.5f * k, 1.5f * k, stroke)
                rect.set(x(4f), y(14f), x(20f), y(21f)); canvas.drawRoundRect(rect, 1.5f * k, 1.5f * k, stroke)
            }
            Glyph.LAYOUT_COLUMNS -> {
                rect.set(x(3f), y(4f), x(11f), y(20f)); canvas.drawRoundRect(rect, 1.5f * k, 1.5f * k, stroke)
                rect.set(x(13f), y(4f), x(21f), y(20f)); canvas.drawRoundRect(rect, 1.5f * k, 1.5f * k, stroke)
            }
            Glyph.SWAP -> {
                path.moveTo(x(7f), y(4f)); path.lineTo(x(7f), y(18f))
                path.moveTo(x(4f), y(7f)); path.lineTo(x(7f), y(4f)); path.lineTo(x(10f), y(7f))
                path.moveTo(x(17f), y(20f)); path.lineTo(x(17f), y(6f))
                path.moveTo(x(14f), y(17f)); path.lineTo(x(17f), y(20f)); path.lineTo(x(20f), y(17f))
                canvas.drawPath(path, stroke)
            }
            Glyph.RELOAD -> {
                rect.set(x(4f), y(4f), x(20f), y(20f))
                path.addArc(rect, -40f, -290f)
                path.moveTo(x(20f), y(4f)); path.lineTo(x(20f), y(8.5f)); path.lineTo(x(15.5f), y(8.5f))
                canvas.drawPath(path, stroke)
            }
            Glyph.ARRANGE -> {
                path.moveTo(x(4f), y(9f)); path.lineTo(x(4f), y(4f)); path.lineTo(x(9f), y(4f))
                path.moveTo(x(20f), y(9f)); path.lineTo(x(20f), y(4f)); path.lineTo(x(15f), y(4f))
                path.moveTo(x(4f), y(15f)); path.lineTo(x(4f), y(20f)); path.lineTo(x(9f), y(20f))
                path.moveTo(x(20f), y(15f)); path.lineTo(x(20f), y(20f)); path.lineTo(x(15f), y(20f))
                canvas.drawPath(path, stroke)
            }
            Glyph.DONE -> {
                stroke.strokeWidth = 2.8f * k
                path.moveTo(x(5f), y(12.5f)); path.lineTo(x(9.5f), y(17f)); path.lineTo(x(19f), y(7.5f))
                canvas.drawPath(path, stroke)
            }
        }
    }

    // ------------------------------------------------------------------------------ helpers

    private fun panesOf(c: DuoScreenChrome): List<Rect> = c.paneRects

    private fun paneRect(c: DuoScreenChrome, id: Int): Rect? = c.paneRectOf(id)

    private fun baseline(box: Rect): Float {
        val m = text.fontMetrics
        return box.top + (box.height - (m.descent - m.ascent)) / 2f - m.ascent
    }

    private fun Rect.toRectF() = RectF(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat())
    private fun Rect.centerX() = left + width / 2f
    private fun Rect.centerY() = top + height / 2f

    private fun mix(a: Int, b: Int, t: Float): Int {
        fun ch(shift: Int) = (((a shr shift) and 0xFF) + (((b shr shift) and 0xFF) - ((a shr shift) and 0xFF)) * t).toInt()
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }
}
