package dev.autobridge.duoscreen.chrome

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import dev.autobridge.duoscreen.layout.DuoScreenLayout.Rect
import dev.autobridge.duoscreen.layout.DuoScreenPreset

/** What a pane is running, as its Arrange card shows it. */
data class PaneApp(val label: String, val icon: Bitmap?)

/**
 * What to tell the driver while neither Shizuku nor root is ready: the panes cannot run their apps
 * yet, so one card over them says what to do on the phone, with the icons of the apps that will
 * open once it is done.
 */
data class SetupNotice(val title: String, val body: String, val apps: List<PaneApp>)

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
    /** The pane a dragged pane would swap with if let go now: highlighted, so the drop is seen. */
    val swapTargetId: Int? = null,
    val gripGrabbed: Boolean,
    val pressed: ChromeTarget?,
    val currentPreset: DuoScreenPreset,
    /** Pane id to its card title ("Pane 1 · Maps") and share line ("56% of the height"). */
    val titles: Map<Int, String>,
    val shares: Map<Int, String>,
    val apps: Map<Int, PaneApp>,
    val labels: ChromeLabels,
    /** Shown over the panes while the privileges they need are missing; null once they are there. */
    val setup: SetupNotice? = null
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
        const val SWAP_TARGET = 0xB82D4470.toInt()
        const val TEXT = 0xFFF1F3F5.toInt()
        const val TEXT_MUTED = 0xFFC7CCD2.toInt()
        const val CARD_BUTTON = 0xFFF1F3F5.toInt()
        const val CARD_BUTTON_TEXT = 0xFF0F1114.toInt()
        const val TILE = 0xFF1C2330.toInt()
        /** The floating toolbar over the seam: nearly opaque, with a soft drop below it. */
        const val TOOLBAR = 0xF21C1F25.toInt()
        const val TOOLBAR_SHADOW = 0x66000000
        /** The setup card's hairline edge and the soft accent disc behind its lock. */
        const val CARD_EDGE = 0x1FFFFFFF
        const val ACCENT_SOFT = 0x2EB3C7F5
    }

    private enum class Glyph { LAYOUT_STACKED, LAYOUT_COLUMNS, SWAP, RELOAD, ARRANGE, DONE, KEYBOARD }

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

    // A StaticLayout keeps the paint it was built with, so the setup card's text gets paints of
    // its own rather than [text], whose size every other element changes.
    private val setupTitle = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        color = TEXT
    }
    private val setupBody = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = TEXT_MUTED }
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

        // Under the bar and the rail, which stay usable: Exit and the layout still work.
        if (!c.editing) frame.setup?.let { drawSetup(canvas, frame, it) }

        drawBar(canvas, frame)
        if (c.editing) drawChips(canvas, frame) else drawRail(canvas, frame)
    }

    // --------------------------------------------------------------------------------- rail

    /**
     * The quick rail: closed, a slim tab on the edge with a chevron pointing into the screen;
     * open, a floating panel of each pane's app icon (the focused pane's ringed) above the quick
     * actions.
     */
    private fun drawRail(canvas: Canvas, frame: ChromeFrame) {
        val rail = frame.chrome.rail ?: return
        val d = frame.density
        val s = frame.chrome.spec
        rail.tab?.let { tab ->
            rect.set(tab.toRectF())
            val r = tab.width.toFloat()
            fill.color = TOOLBAR
            // Rounded on the inner side only: the outer corners run off the edge.
            if (rail.side == RailSide.RIGHT) rect.right += r else rect.left -= r
            canvas.drawRoundRect(rect, r, r, fill)
            stroke.color = ICON
            stroke.strokeWidth = 2.5f * d
            val cx = tab.centerX()
            val cy = tab.centerY()
            val arm = tab.width * 0.22f
            val dir = if (rail.side == RailSide.RIGHT) 1f else -1f
            path.reset()
            path.moveTo(cx + dir * arm / 2, cy - arm)
            path.lineTo(cx - dir * arm / 2, cy)
            path.lineTo(cx + dir * arm / 2, cy + arm)
            canvas.drawPath(path, stroke)
            return
        }
        val panel = rail.panel ?: return
        val pr = panel.width / 2f
        rect.set(panel.toRectF())
        rect.offset(0f, 2f * d)
        fill.color = TOOLBAR_SHADOW
        canvas.drawRoundRect(rect, pr, pr, fill)
        rect.set(panel.toRectF())
        fill.color = TOOLBAR
        canvas.drawRoundRect(rect, pr, pr, fill)

        val corner = s.BUTTON_RADIUS * d
        rail.apps.forEach { (paneId, box) ->
            val pressed = frame.pressed == ChromeTarget.RailApp(paneId)
            fill.color = if (pressed) BUTTON_PRESSED else BUTTON
            rect.set(box.toRectF())
            canvas.drawRoundRect(rect, corner, corner, fill)
            val icon = frame.apps[paneId]?.icon
            if (icon != null) {
                val inset = box.width * 0.14f
                rect.set(box.left + inset, box.top + inset, box.right - inset, box.bottom - inset)
                canvas.drawBitmap(icon, null, rect, image)
            } else {
                text.color = ICON
                text.textSize = box.height * 0.42f
                drawCentred(canvas, (paneId + 1).toString(), box, box.width.toFloat())
            }
            if (paneId == frame.focusedPaneId) {
                stroke.color = ACCENT
                stroke.strokeWidth = 2.5f * d
                rect.set(box.toRectF())
                rect.inset(-2f * d, -2f * d)
                canvas.drawRoundRect(rect, corner + 2f * d, corner + 2f * d, stroke)
            }
        }
        fill.color = GRIP
        val inset = panel.width * 0.25f
        canvas.drawRect(panel.left + inset, rail.separatorAt - d / 2, panel.right - inset, rail.separatorAt + d / 2, fill)
        rail.buttons.forEach { (kind, box) ->
            val pressed = frame.pressed == ChromeTarget.Control(kind)
            fill.color = if (pressed) BUTTON_PRESSED else BUTTON
            rect.set(box.toRectF())
            canvas.drawRoundRect(rect, corner, corner, fill)
            when (kind) {
                ChromeTarget.Kind.RAIL_MOVE -> {
                    // Two chevrons pointing at the other edge: "move me there".
                    stroke.color = ICON
                    stroke.strokeWidth = 2.5f * d
                    val cx = box.centerX()
                    val cy = box.centerY()
                    val arm = box.width * 0.13f
                    val dir = if (rail.side == RailSide.RIGHT) -1f else 1f
                    for (offset in listOf(-arm * 0.9f, arm * 0.9f)) {
                        path.reset()
                        path.moveTo(cx + offset - dir * arm / 2, cy - arm)
                        path.lineTo(cx + offset + dir * arm / 2, cy)
                        path.lineTo(cx + offset - dir * arm / 2, cy + arm)
                        canvas.drawPath(path, stroke)
                    }
                }
                else -> {
                    val glyph = when (kind) {
                        ChromeTarget.Kind.LAYOUT ->
                            if (frame.currentPreset == DuoScreenPreset.EVEN_COLUMNS ||
                                frame.currentPreset == DuoScreenPreset.WIDE_LEFT ||
                                frame.currentPreset == DuoScreenPreset.WIDE_RIGHT
                            ) Glyph.LAYOUT_COLUMNS else Glyph.LAYOUT_STACKED
                        ChromeTarget.Kind.SWAP -> Glyph.SWAP
                        ChromeTarget.Kind.RELOAD -> Glyph.RELOAD
                        ChromeTarget.Kind.KEYBOARD -> Glyph.KEYBOARD
                        else -> Glyph.ARRANGE
                    }
                    // The rail's buttons are larger than the toolbar's; the icon grows with them.
                    val iconSize = s.BUTTON_ICON * d * box.width / (s.BUTTON * d)
                    glyph(canvas, glyph, box.centerX(), box.centerY(), iconSize, ICON)
                }
            }
        }
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
            // Closed: only the handle in the middle of the seam. Open: the toolbar over the seam.
            val toolbar = c.toolbar
            if (toolbar == null) {
                c.handle?.let { handle ->
                    fill.color = if (frame.gripGrabbed) ACCENT else GRIP
                    rect.set(handle.toRectF())
                    val r = minOf(handle.width, handle.height) / 2f
                    canvas.drawRoundRect(rect, r, r, fill)
                }
                return
            }
            rect.set(toolbar.toRectF())
            val tr = minOf(toolbar.width, toolbar.height) / 2f
            fill.color = TOOLBAR_SHADOW
            rect.offset(0f, 2f * d)
            canvas.drawRoundRect(rect, tr, tr, fill)
            rect.set(toolbar.toRectF())
            fill.color = TOOLBAR
            canvas.drawRoundRect(rect, tr, tr, fill)
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
            // The swap target loses its dimming and takes the Swap colour, so it reads as the
            // place the dragged pane is about to land.
            fill.color = if (card.paneId == frame.swapTargetId) SWAP_TARGET else DIM
            canvas.drawRoundRect(rect, radius, radius, fill)
            if (card.paneId == frame.selectedPaneId || card.paneId == frame.swapTargetId) {
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

    // ------------------------------------------------------------------------- setup card

    /**
     * The panes dimmed, and over their middle a floating card: a lock in a soft accent disc, the
     * title, what to do on the phone, and the apps that will open here once that is done.
     */
    private fun drawSetup(canvas: Canvas, frame: ChromeFrame, setup: SetupNotice) {
        val c = frame.chrome
        val d = frame.density
        val s = c.spec
        val compact = s == DuoScreenChromeSpec.COMPACT
        val radius = s.PANE_RADIUS * d
        fill.color = DIM
        panesOf(c).forEach { pane ->
            rect.set(pane.toRectF())
            canvas.drawRoundRect(rect, radius, radius, fill)
        }

        val pad = (if (compact) 20f else 32f) * d
        val gap = s.CARD_GAP * d
        val badge = (if (compact) 48f else 68f) * d
        val tile = s.CARD_TILE * 0.62f * d
        val tileGap = (if (compact) 10f else 14f) * d
        val width = minOf(c.bounds.width * 0.72f, (if (compact) 380f else 560f) * d)
        val inner = (width - 2 * pad).toInt().coerceAtLeast(1)

        setupTitle.textSize = s.CARD_TITLE * d
        setupBody.textSize = s.CARD_SHARE * d
        val title = centredLayout(setup.title, setupTitle, inner, maxLines = 2)
        val body = centredLayout(setup.body, setupBody, inner, maxLines = 4)
        val apps = setup.apps.filter { it.icon != null }.take(3)
        val iconsHeight = if (apps.isEmpty()) 0f else gap * 1.4f + tile
        val height = pad + badge + gap + title.height + gap * 0.5f + body.height + iconsHeight + pad

        val left = (c.bounds.width - width) / 2f
        val top = ((c.bounds.height - height) / 2f).coerceAtLeast(0f)
        val cardRadius = s.CARD_TILE_RADIUS * d
        val shadow = 6f * d
        rect.set(left, top + shadow, left + width, top + height + shadow)
        fill.color = TOOLBAR_SHADOW
        canvas.drawRoundRect(rect, cardRadius, cardRadius, fill)
        rect.set(left, top, left + width, top + height)
        fill.color = TOOLBAR
        canvas.drawRoundRect(rect, cardRadius, cardRadius, fill)
        stroke.color = CARD_EDGE
        stroke.strokeWidth = 1f * d
        canvas.drawRoundRect(rect, cardRadius, cardRadius, stroke)

        val cx = left + width / 2f
        var y = top + pad
        fill.color = ACCENT_SOFT
        canvas.drawCircle(cx, y + badge / 2f, badge / 2f, fill)
        drawLock(canvas, cx, y + badge / 2f, badge * 0.5f)
        y += badge + gap

        canvas.save()
        canvas.translate(left + pad, y)
        title.draw(canvas)
        canvas.translate(0f, title.height + gap * 0.5f)
        body.draw(canvas)
        canvas.restore()
        y += title.height + gap * 0.5f + body.height

        if (apps.isNotEmpty()) {
            y += gap * 1.4f
            val row = apps.size * tile + (apps.size - 1) * tileGap
            var x = cx - row / 2f
            val tr = tile * 0.3f
            apps.forEach { app ->
                rect.set(x, y, x + tile, y + tile)
                fill.color = TILE
                canvas.drawRoundRect(rect, tr, tr, fill)
                rect.inset(tile * 0.18f, tile * 0.18f)
                canvas.drawBitmap(app.icon!!, null, rect, image)
                x += tile + tileGap
            }
        }
    }

    private fun centredLayout(value: String, paint: TextPaint, width: Int, maxLines: Int): StaticLayout =
        StaticLayout.Builder.obtain(value, 0, value.length, paint, width)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setMaxLines(maxLines)
            .setEllipsize(TextUtils.TruncateAt.END)
            .setLineSpacing(0f, 1.1f)
            .build()

    /** A padlock about [size] tall, centred on ([cx], [cy]), in the accent's line weight. */
    private fun drawLock(canvas: Canvas, cx: Float, cy: Float, size: Float) {
        val bodyTop = cy - size * 0.05f
        val shackle = size * 0.2f
        stroke.color = ACCENT
        stroke.strokeWidth = size * 0.1f
        path.reset()
        path.moveTo(cx - shackle, bodyTop)
        path.lineTo(cx - shackle, cy - size * 0.22f)
        path.arcTo(RectF(cx - shackle, cy - size * 0.5f + stroke.strokeWidth / 2f, cx + shackle, cy + size * 0.06f), 180f, 180f, false)
        path.lineTo(cx + shackle, bodyTop)
        canvas.drawPath(path, stroke)
        fill.color = ACCENT
        rect.set(cx - size * 0.32f, bodyTop, cx + size * 0.32f, cy + size * 0.45f)
        canvas.drawRoundRect(rect, size * 0.1f, size * 0.1f, fill)
        fill.color = TOOLBAR
        canvas.drawCircle(cx, cy + size * 0.17f, size * 0.06f, fill)
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
            Glyph.KEYBOARD -> {
                rect.set(x(2.5f), y(5.5f), x(21.5f), y(18.5f)); canvas.drawRoundRect(rect, 2f * k, 2f * k, stroke)
                fill.color = color
                for (row in 0..1) for (col in 0..4) {
                    val kx = x(5.5f + col * 3.25f)
                    val ky = y(8.8f + row * 3f)
                    canvas.drawCircle(kx, ky, 0.95f * k, fill)
                }
                path.moveTo(x(8f), y(15.3f)); path.lineTo(x(16f), y(15.3f))
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
