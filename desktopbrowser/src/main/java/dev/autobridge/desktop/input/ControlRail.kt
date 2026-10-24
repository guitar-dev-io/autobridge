package dev.autobridge.desktop.input

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import dev.autobridge.design.DesignTokens
import dev.autobridge.desktop.R
import dev.autobridge.desktop.display.FrameRenderer
import kotlin.math.min

/**
 * The desktop browser's controls, drawn over the page on both the car surface and the phone.
 *
 * Three states, so the page keeps the screen:
 *
 *  - **Collapsed**: a thin translucent handle on the left edge. It hardly covers a video, and it
 *    still shows where the controls are, which a rail that disappears completely does not.
 *  - **Open**: a panel slides out with the everyday controls, each a large labelled button:
 *    back, address, type, the touch ⇄ mouse switch (one button that shows the current mode) and
 *    more. In mouse mode a Drag button joins them: it holds the left button down so the next pan
 *    drags (select text, move a slider), and the next tap on the page drops it — the click-drag a
 *    car screen cannot otherwise do, because the host reports no touch-down or touch-up. It
 *    collapses after any action (Drag included, so its pan reaches the page), or [hideDelayMs]
 *    after the last touch; a scroll while it is open keeps it up instead of moving the page under it.
 *  - **More**: a second sheet beside the panel for what is used less: forward, reload, new tab,
 *    zoom, the Text row (Tab, Esc, select all, copy, paste), the keyboard choice (car only), how
 *    soon the panel collapses, and close (phone only).
 *    It never collapses by itself: it closes on a choice that leaves it or a tap outside.
 *
 * The host only reports taps, so every control is a tap target sized for a car, and the whole
 * thing scales down to fit a short screen. [onTap] decides what a tap means and returns the
 * [Action] for the owner to perform; the rail itself only changes its own state.
 *
 * Sizes follow the target's density, so the controls are the same physical size on a 720p car
 * screen, a 1080p one and a phone.
 */
class ControlRail(private val context: Context, @Volatile var density: Float) : FrameRenderer.Overlay {
    enum class State { COLLAPSED, OPEN, MORE }

    /** What a tap asked for. Mode switches and the collapse delay are handled inside the rail. */
    enum class Action {
        BACK, ADDRESS, KEYBOARD, FORWARD, RELOAD, NEW_TAB, ZOOM_OUT, ZOOM_IN, RESET_ZOOM,
        KEYBOARD_AUTOBRIDGE, KEYBOARD_CAR, KEYBOARD_PHONE, MODE_CHANGED, CLOSE,
        /** The More sheet's Text row: keys and clipboard shortcuts for the focused field. */
        TAB, ESCAPE, SELECT_ALL, COPY, PASTE,
        /** Mouse mode: hold the left button down ([dragHeld] is now true). */
        DRAG_START,
        /** Mouse mode: let it go ([dragHeld] is now false). */
        DRAG_END
    }

    sealed class Tap {
        /** Not the rail's: the page gets it. */
        object PassThrough : Tap()
        /** The rail used it (opened, closed, switched a setting); redraw. */
        object Consumed : Tap()
        data class Perform(val action: Action) : Tap()
    }

    @Volatile var mode: PointerMode = PointerMode.TOUCH

    /** Mouse mode with the left button held: a pan drags. Set by the Drag button, cleared by the owner. */
    @Volatile var dragHeld = false

    /** Panel buttons: back, address, type, mode, (drag in mouse mode), more. */
    private fun buttonCount() = if (mode == PointerMode.MOUSE) 6 else 5

    /** The owner's gate (parked, a session to control): nothing is drawn or hit while false. */
    @Volatile var visible = true
        set(value) {
            field = value
            if (!value) state = State.COLLAPSED
        }

    /** Car only: the keyboard row in More (AutoBridge's keyboard, the car's or the phone's). */
    @Volatile var showKeyboardChoice = false
    /** The selected keyboard segment: 0 AutoBridge, 1 the car's, 2 the phone's. */
    @Volatile var keyboardChoice = 0

    /** Phone only: a Close button in More, as the old menu had. */
    @Volatile var showClose = false

    /** Called on the main thread when the panel collapses by itself, to redraw without it. */
    @Volatile var onAutoHide: (() -> Unit)? = null

    @Volatile var state = State.COLLAPSED
        private set

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** How long an open panel stays without a touch; 0 = until closed by hand. */
    var hideDelayMs: Long = prefs.getLong(KEY_HIDE_MS, DEFAULT_HIDE_MS)
        private set(value) {
            field = value
            prefs.edit { putLong(KEY_HIDE_MS, value) }
        }

    private val handler = Handler(Looper.getMainLooper())
    private val collapse = Runnable {
        if (state != State.COLLAPSED) {
            state = State.COLLAPSED
            onAutoHide?.invoke()
        }
    }

    // --- Drawing resources ---
    private fun icon(id: Int): Drawable = ContextCompat.getDrawable(context, id)!!.mutate()
    private val icons = HashMap<Int, Drawable>()
    private fun iconFor(id: Int) = icons.getOrPut(id) { icon(id) }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val leftText = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.LEFT }

    // Labels of the Text row and the keyboard choice, resolved once (not per draw).
    private val textHeaderLabel = context.getString(R.string.desktop_rail_text)
    private val textCells = arrayOf(
        context.getString(R.string.desktop_rail_tab) to Action.TAB,
        context.getString(R.string.desktop_rail_esc) to Action.ESCAPE,
        context.getString(R.string.desktop_rail_select_all) to Action.SELECT_ALL,
        context.getString(R.string.desktop_rail_copy) to Action.COPY,
        context.getString(R.string.desktop_rail_paste) to Action.PASTE,
    )
    private val keyboardAutoBridgeLabel = context.getString(R.string.desktop_rail_keyboard_autobridge)
    private val keyboardCarLabel = context.getString(R.string.desktop_rail_keyboard_car)
    private val keyboardPhoneLabel = context.getString(R.string.desktop_rail_keyboard_phone)

    /** The last area drawn in; taps are laid out against the same geometry. */
    @Volatile private var lastWidth = 0
    @Volatile private var lastHeight = 0
    @Volatile private var originX = 0f
    @Volatile private var originY = 0f

    /**
     * The part of the target the page is shown in (the renderer's mapping), so the rail sits on
     * the page: above the phone keyboard when it is open, and beside — not under — Android Auto's
     * widgets on its split home screen. Null uses the whole target.
     */
    @Volatile var area: () -> RectF? = { null }

    // --- Geometry ---

    private class Item(val rect: RectF, val onTap: () -> Tap)

    private class Geometry(val handle: RectF, val panel: RectF?, val sheet: RectF?, val items: List<Item>, val draw: List<(Canvas) -> Unit>)

    private fun s(dp: Float, scale: Float) = dp * density * scale

    /** Scale that fits the open panel (the tallest part) into the target's height. */
    private fun scaleFor(height: Int): Float {
        val panelDp = PANEL_PAD * 2 + CHEVRON + GAP + buttonCount() * (CONTROL_H + GAP) - GAP
        val sheetDp = sheetHeightDp()
        val needed = maxOf(panelDp, sheetDp) * density
        return min(1f, height * 0.92f / needed).coerceAtLeast(0.45f)
    }

    private fun sheetHeightDp(): Float {
        var h = SHEET_PAD * 2 + HEADER + GAP + CONTROL_H + GAP + CONTROL_H
        h += GAP + HEADER + GAP + CONTROL_H // Text row
        if (showKeyboardChoice) h += GAP + HEADER + GAP + CONTROL_H
        h += GAP + HEADER + GAP + CONTROL_H
        if (showClose) h += GAP + CONTROL_H
        return h
    }

    private fun geometry(width: Int, height: Int): Geometry {
        val sc = scaleFor(height)
        val items = ArrayList<Item>()
        val draws = ArrayList<(Canvas) -> Unit>()

        val handleH = s(HANDLE_H, sc)
        val handle = RectF(0f, (height - handleH) / 2f, s(HANDLE_W, sc), (height + handleH) / 2f)
        if (state == State.COLLAPSED) {
            draws += { c -> drawHandle(c, handle) }
            return Geometry(handle, null, null, items, draws)
        }

        // --- Panel ---
        val panelW = s(PANEL_W, sc)
        val panelH = s(PANEL_PAD * 2 + CHEVRON + GAP + buttonCount() * (CONTROL_H + GAP) - GAP, sc)
        val left = s(PANEL_LEFT, sc)
        val top = ((height - panelH) / 2f).coerceAtLeast(0f)
        val panel = RectF(left, top, left + panelW, top + panelH)
        draws += { c -> panelBackground(c, panel, s(RADIUS_CONTAINER, sc)) }
        val inner = s(PANEL_PAD, sc)
        var y = top + inner
        val chevron = RectF(panel.left + inner, y, panel.right - inner, y + s(CHEVRON, sc))
        items += Item(chevron) { state = State.COLLAPSED; Tap.Consumed }
        draws += { c -> drawIcon(c, R.drawable.ic_rail_collapse, chevron, s(ICON_CHEVRON, sc), MUTED) }
        y = chevron.bottom + s(GAP, sc)

        fun panelButton(iconId: Int, label: String, selected: Boolean, outlined: Boolean, onTap: () -> Tap) {
            val r = RectF(panel.left + inner, y, panel.right - inner, y + s(CONTROL_H, sc))
            items += Item(r, onTap)
            draws += { c -> drawButton(c, r, iconId, label, selected, outlined, s(RADIUS_CONTROL, sc), s(ICON, sc), s(TEXT_LABEL, sc)) }
            y = r.bottom + s(GAP, sc)
        }
        panelButton(R.drawable.ic_rail_back, context.getString(R.string.desktop_rail_back), false, false) { done(Action.BACK) }
        panelButton(R.drawable.ic_rail_search, context.getString(R.string.desktop_rail_address), false, false) { done(Action.ADDRESS) }
        panelButton(R.drawable.ic_rail_keyboard, context.getString(R.string.desktop_rail_type), false, false) { done(Action.KEYBOARD) }
        val touch = mode == PointerMode.TOUCH
        panelButton(
            if (touch) R.drawable.ic_rail_touch else R.drawable.ic_rail_mouse,
            context.getString(if (touch) R.string.desktop_rail_touch else R.string.desktop_rail_mouse),
            true, false
        ) {
            mode = if (mode == PointerMode.TOUCH) PointerMode.MOUSE else PointerMode.TOUCH
            touched()
            Tap.Perform(Action.MODE_CHANGED)
        }
        if (mode == PointerMode.MOUSE) {
            panelButton(R.drawable.ic_rail_drag, context.getString(R.string.desktop_rail_drag), dragHeld, false) {
                dragHeld = !dragHeld
                done(if (dragHeld) Action.DRAG_START else Action.DRAG_END)
            }
        }
        panelButton(R.drawable.ic_rail_more, context.getString(R.string.desktop_rail_more), false, state == State.MORE) {
            state = if (state == State.MORE) State.OPEN else State.MORE
            touched()
            Tap.Consumed
        }

        if (state != State.MORE) return Geometry(handle, panel, null, items, draws)

        // --- More sheet ---
        val sheetW = s(SHEET_W, sc).coerceAtMost(width - panel.right - s(16f, sc) - s(8f, sc))
        val sheetH = s(sheetHeightDp(), sc)
        val sheetLeft = panel.right + s(16f, sc)
        val sheetTop = (panel.centerY() - sheetH / 2f).coerceIn(0f, (height - sheetH).coerceAtLeast(0f))
        val sheet = RectF(sheetLeft, sheetTop, sheetLeft + sheetW, sheetTop + sheetH)
        draws += { c -> panelBackground(c, sheet, s(RADIUS_CONTAINER, sc), SHEET_FILL) }
        val pad = s(SHEET_PAD, sc)
        val gap = s(GAP, sc)
        y = sheet.top + pad
        val x0 = sheet.left + pad
        val x1 = sheet.right - pad

        fun header(label: String) {
            val baseline = y + s(HEADER, sc) * 0.72f
            draws += { c -> drawLeftText(c, label, x0 + s(6f, sc), baseline, s(TEXT_HEADER, sc), MUTED) }
            y += s(HEADER, sc) + gap
        }

        header(context.getString(R.string.desktop_rail_this_page))
        val cellW = (x1 - x0 - gap * 2) / 3f
        listOf(
            Triple(R.drawable.ic_rail_forward, R.string.desktop_rail_forward, Action.FORWARD),
            Triple(R.drawable.ic_rail_reload, R.string.desktop_rail_reload, Action.RELOAD),
            Triple(R.drawable.ic_rail_new_tab, R.string.desktop_rail_new_tab, Action.NEW_TAB),
        ).forEachIndexed { i, (iconId, labelId, action) ->
            val l = x0 + i * (cellW + gap)
            val r = RectF(l, y, l + cellW, y + s(CONTROL_H, sc))
            val label = context.getString(labelId)
            items += Item(r) { done(action) }
            draws += { c -> drawButton(c, r, iconId, label, false, false, s(RADIUS_CONTROL, sc), s(ICON, sc), s(TEXT_LABEL, sc)) }
        }
        y += s(CONTROL_H, sc) + gap

        // Zoom: label on the left, − and + on the right; the sheet stays open between steps.
        val zoomRow = RectF(x0, y, x1, y + s(CONTROL_H, sc))
        val step = s(STEP_BUTTON, sc)
        val plus = RectF(zoomRow.right - gap - step, zoomRow.centerY() - step / 2, zoomRow.right - gap, zoomRow.centerY() + step / 2)
        val minus = RectF(plus.left - gap - step, plus.top, plus.left - gap, plus.bottom)
        // Reset (Ctrl+0) sits left of −, so the familiar −/+ pair keeps its place on the right.
        val reset = RectF(minus.left - gap - step, minus.top, minus.left - gap, minus.bottom)
        val zoomLabelLeft = zoomRow.left + s(16f, sc)
        val zoomLabel = context.getString(R.string.desktop_rail_zoom)
        val resetLabel = context.getString(R.string.desktop_rail_zoom_reset)
        draws += { c ->
            roundRect(c, zoomRow, s(RADIUS_CONTROL, sc), BUTTON_FILL)
            drawFittedLeftText(c, zoomLabel, zoomLabelLeft, zoomRow.centerY(), reset.left - gap - zoomLabelLeft, s(TEXT_LABEL, sc), INK)
            roundRect(c, reset, s(RADIUS_INNER, sc), STEP_FILL)
            roundRect(c, minus, s(RADIUS_INNER, sc), STEP_FILL)
            roundRect(c, plus, s(RADIUS_INNER, sc), STEP_FILL)
            drawCenteredText(c, resetLabel, reset, s(TEXT_LABEL, sc), INK)
            drawCenteredText(c, "−", minus, s(28f, sc), INK)
            drawCenteredText(c, "+", plus, s(28f, sc), INK)
        }
        items += Item(reset) { touched(); Tap.Perform(Action.RESET_ZOOM) }
        items += Item(minus) { touched(); Tap.Perform(Action.ZOOM_OUT) }
        items += Item(plus) { touched(); Tap.Perform(Action.ZOOM_IN) }
        y = zoomRow.bottom + gap

        // Text: keys a car or phone keyboard lacks, and the clipboard, for the focused field.
        header(textHeaderLabel)
        val textW = (x1 - x0 - gap * (textCells.size - 1)) / textCells.size
        textCells.forEachIndexed { i, (label, action) ->
            val l = x0 + i * (textW + gap)
            val r = RectF(l, y, l + textW, y + s(CONTROL_H, sc))
            items += Item(r) { done(action) }
            draws += { c ->
                roundRect(c, r, s(RADIUS_CONTROL, sc), BUTTON_FILL)
                drawCenteredText(c, label, r, s(TEXT_LABEL, sc), INK)
            }
        }
        y += s(CONTROL_H, sc) + gap

        fun segments(options: List<Pair<String, Boolean>>, onPick: (Int) -> Tap) {
            val row = RectF(x0, y, x1, y + s(CONTROL_H, sc))
            draws += { c -> roundRect(c, row, s(RADIUS_CONTROL, sc), BUTTON_FILL) }
            val inset = s(SEGMENT_INSET, sc)
            val segW = (row.width() - inset * (options.size + 1)) / options.size
            options.forEachIndexed { i, (label, selected) ->
                val l = row.left + inset + i * (segW + inset)
                val r = RectF(l, row.top + inset, l + segW, row.bottom - inset)
                items += Item(r) { onPick(i) }
                draws += { c ->
                    if (selected) roundRect(c, r, s(RADIUS_INNER, sc), SELECTED)
                    drawCenteredText(c, label, r, s(TEXT_LABEL, sc), if (selected) Color.WHITE else INK_SECONDARY)
                }
            }
            y = row.bottom + gap
        }

        if (showKeyboardChoice) {
            header(context.getString(R.string.desktop_rail_keyboard))
            val choice = keyboardChoice
            segments(
                listOf(
                    keyboardAutoBridgeLabel to (choice == 0),
                    keyboardCarLabel to (choice == 1),
                    keyboardPhoneLabel to (choice == 2),
                )
            ) { i ->
                keyboardChoice = i
                touched()
                Tap.Perform(
                    when (i) {
                        1 -> Action.KEYBOARD_CAR
                        2 -> Action.KEYBOARD_PHONE
                        else -> Action.KEYBOARD_AUTOBRIDGE
                    }
                )
            }
        }

        header(context.getString(R.string.desktop_rail_menu))
        val delays = listOf(2_000L, 4_000L, 0L)
        segments(
            listOf(
                context.getString(R.string.desktop_rail_hide_2s) to (hideDelayMs == 2_000L),
                context.getString(R.string.desktop_rail_hide_4s) to (hideDelayMs == 4_000L),
                context.getString(R.string.desktop_rail_hide_never) to (hideDelayMs == 0L),
            )
        ) { i ->
            hideDelayMs = delays[i]
            touched()
            Tap.Consumed
        }

        if (showClose) {
            val r = RectF(x0, y, x1, y + s(CONTROL_H, sc))
            val label = context.getString(R.string.desktop_rail_close)
            items += Item(r) { done(Action.CLOSE) }
            draws += { c ->
                roundRect(c, r, s(RADIUS_CONTROL, sc), BUTTON_FILL)
                drawCenteredText(c, label, r, s(TEXT_LABEL, sc), INK)
            }
        }
        return Geometry(handle, panel, sheet, items, draws)
    }

    // --- Taps ---

    /**
     * What a tap at ([x], [y]) in target pixels means. Collapsed, only the handle's strip is the
     * rail's; open, a tap outside the panel and sheet closes them without reaching the page.
     */
    fun onTap(targetX: Float, targetY: Float): Tap {
        if (!visible || lastWidth <= 0 || lastHeight <= 0) return Tap.PassThrough
        val x = targetX - originX
        val y = targetY - originY
        val g = geometry(lastWidth, lastHeight)
        if (state == State.COLLAPSED) {
            val slop = HANDLE_SLOP * density
            val h = g.handle
            if (x <= h.right + slop && y >= h.top - slop && y <= h.bottom + slop) {
                state = State.OPEN
                touched()
                return Tap.Consumed
            }
            return Tap.PassThrough
        }
        g.items.firstOrNull { it.rect.contains(x, y) }?.let { return it.onTap() }
        val inside = g.panel?.contains(x, y) == true || g.sheet?.contains(x, y) == true
        if (inside) {
            touched()
            return Tap.Consumed
        }
        state = State.COLLAPSED
        return Tap.Consumed
    }

    /** An action that leaves the panel: collapse it, then the owner performs [action]. */
    private fun done(action: Action): Tap {
        state = State.COLLAPSED
        handler.removeCallbacks(collapse)
        return Tap.Perform(action)
    }

    /**
     * Restarts the collapse countdown. Only the bare panel counts down: with the More sheet open
     * the driver is reading or choosing, so it stays until a choice or a tap outside closes it.
     */
    private fun touched() {
        handler.removeCallbacks(collapse)
        if (hideDelayMs > 0 && state == State.OPEN) handler.postDelayed(collapse, hideDelayMs)
    }

    /**
     * A scroll or fling while the panel is open: the host gives no position for it, and the finger
     * is almost always on the panel, so it belongs to the rail (it keeps the panel up) rather than
     * scrolling the page underneath. Returns true when the rail took it.
     */
    fun consumesScroll(): Boolean {
        if (!visible || state == State.COLLAPSED) return false
        touched()
        return true
    }

    // --- Drawing ---

    override fun draw(canvas: Canvas, width: Int, height: Int) {
        val a = area() ?: RectF(0f, 0f, width.toFloat(), height.toFloat())
        originX = a.left
        originY = a.top
        lastWidth = a.width().toInt()
        lastHeight = a.height().toInt()
        if (!visible) return
        canvas.save()
        canvas.translate(a.left, a.top)
        geometry(lastWidth, lastHeight).draw.forEach { it(canvas) }
        canvas.restore()
    }

    private fun drawHandle(c: Canvas, r: RectF) {
        val radius = r.width() * 0.64f
        fill.color = HANDLE
        c.drawRoundRect(RectF(r.left - radius, r.top, r.right, r.bottom), radius, radius, fill)
        fill.color = HANDLE_DOT
        val dot = r.width() * 0.18f
        for (i in -1..1) c.drawCircle(r.centerX() - r.width() * 0.05f, r.centerY() + i * dot * 3.2f, dot, fill)
    }

    private fun panelBackground(c: Canvas, r: RectF, radius: Float, color: Int = PANEL_FILL) {
        roundRect(c, r, radius, color)
        stroke.color = PANEL_EDGE
        stroke.strokeWidth = density
        c.drawRoundRect(r, radius, radius, stroke)
    }

    private fun roundRect(c: Canvas, r: RectF, radius: Float, color: Int) {
        fill.color = color
        c.drawRoundRect(r, radius, radius, fill)
    }

    private fun drawButton(c: Canvas, r: RectF, iconId: Int, label: String, selected: Boolean, outlined: Boolean, radius: Float, iconSize: Float, textSize: Float) {
        roundRect(c, r, radius, if (selected) SELECTED else if (outlined) OUTLINED_FILL else BUTTON_FILL)
        if (outlined) {
            stroke.color = ACCENT
            stroke.strokeWidth = 2 * density
            c.drawRoundRect(r, radius, radius, stroke)
        }
        val gap = textSize * 0.4f
        val block = iconSize + gap + textSize
        val iconTop = r.centerY() - block / 2f
        drawIcon(c, iconId, RectF(r.left, iconTop, r.right, iconTop + iconSize), iconSize, if (selected) Color.WHITE else INK)
        text.color = if (selected) Color.WHITE else INK
        val shown = fitText(text, label, r.width() - textSize, textSize)
        c.drawText(shown, r.centerX(), iconTop + iconSize + gap + textSize * 0.85f, text)
    }

    private fun drawIcon(c: Canvas, iconId: Int, box: RectF, size: Float, color: Int) {
        val d = iconFor(iconId)
        val l = box.centerX() - size / 2f
        val t = box.centerY() - size / 2f
        d.setBounds(l.toInt(), t.toInt(), (l + size).toInt(), (t + size).toInt())
        d.setTint(color)
        d.draw(c)
    }

    private fun drawCenteredText(c: Canvas, value: String, r: RectF, size: Float, color: Int) {
        text.color = color
        val shown = if (value.length > 1) fitText(text, value, r.width() - size, size) else value.also { text.textSize = size }
        c.drawText(shown, r.centerX(), r.centerY() - (text.descent() + text.ascent()) / 2f, text)
    }

    /** Sets [paint]'s size to fit [value] in [maxWidth] (down to 80%), then ellipsizes; returns what to draw. */
    private fun fitText(paint: Paint, value: String, maxWidth: Float, size: Float): String {
        paint.textSize = size
        val fitted = fittedTextSize(paint.measureText(value), maxWidth, size)
        paint.textSize = fitted
        if (paint.measureText(value) <= maxWidth) return value
        val room = (maxWidth - paint.measureText("…")).coerceAtLeast(0f)
        val n = paint.breakText(value, true, room, null)
        return value.substring(0, n).trimEnd() + "…"
    }

    private fun drawFittedLeftText(c: Canvas, value: String, x: Float, centerY: Float, maxWidth: Float, size: Float, color: Int) {
        leftText.color = color
        val shown = fitText(leftText, value, maxWidth, size)
        c.drawText(shown, x, centerY - (leftText.descent() + leftText.ascent()) / 2f, leftText)
    }

    private fun drawLeftText(c: Canvas, value: String, x: Float, baseline: Float, size: Float, color: Int) {
        leftText.textSize = size
        leftText.color = color
        c.drawText(value, x, baseline, leftText)
    }

    companion object {
        private const val PREFS = "desktop_rail"
        private const val KEY_HIDE_MS = "hide_ms"
        const val DEFAULT_HIDE_MS = 4_000L

        // Geometry in dp, from the approved design (Desktop Browser Car Rail).
        private const val HANDLE_W = 22f
        private const val HANDLE_H = 132f
        private const val HANDLE_SLOP = 18f
        private const val PANEL_LEFT = 16f
        private const val PANEL_W = 116f
        private const val PANEL_PAD = 10f
        private const val CHEVRON = 40f
        private const val GAP = 8f
        private const val SHEET_W = 440f
        private const val SHEET_PAD = 16f
        private const val HEADER = 24f

        // One scale shared by the rail and the sheet: every control is the same height.
        private const val CONTROL_H = 80f
        private const val RADIUS_CONTROL = 20f
        private const val RADIUS_INNER = 16f
        private const val RADIUS_CONTAINER = 28f
        private const val ICON = 30f
        private const val ICON_CHEVRON = 26f
        private const val TEXT_LABEL = 16f
        private const val TEXT_HEADER = 14f
        private const val STEP_BUTTON = 64f
        private const val SEGMENT_INSET = 8f

        private val HANDLE = DesignTokens.DesktopRail.HANDLE
        private val HANDLE_DOT = DesignTokens.DesktopRail.HANDLE_DOT
        private val PANEL_FILL = DesignTokens.DesktopRail.PANEL_FILL
        private val SHEET_FILL = DesignTokens.DesktopRail.SHEET_FILL
        private val PANEL_EDGE = DesignTokens.DesktopRail.PANEL_EDGE
        private val BUTTON_FILL = DesignTokens.DesktopRail.BUTTON_FILL
        private val STEP_FILL = DesignTokens.DesktopRail.PANEL_EDGE
        private val OUTLINED_FILL = DesignTokens.DesktopRail.OUTLINED_FILL
        private val SELECTED = DesignTokens.DesktopRail.SELECTED
        private val ACCENT = DesignTokens.Phone.ACCENT
        private val INK = DesignTokens.Phone.TEXT
        private val INK_SECONDARY = DesignTokens.DesktopRail.INK_SECONDARY
        private val MUTED = DesignTokens.DesktopRail.MUTED
    }
}

/** How touches on the page are turned into mouse input. */
enum class PointerMode {
    /** The finger is the pointer: tap clicks there, drag scrolls the page. */
    TOUCH,
    /** Trackpad: drag moves the cursor, tap clicks where the cursor is. For small targets. */
    MOUSE,
}

/** Text size that fits [maxWidth]: [size] if it already does, else scaled down but not below 80%. */
internal fun fittedTextSize(measured: Float, maxWidth: Float, size: Float): Float =
    if (measured <= maxWidth || measured <= 0f) size else maxOf(size * maxWidth / measured, size * 0.8f)
