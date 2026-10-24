package dev.autobridge.browser

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import dev.autobridge.R

/**
 * The browser keyboard as the Android Auto browser draws it: straight onto the car surface, over
 * the bottom of the page, the same way [CarWebRenderer] draws its toolbar and menu. The host only
 * reports taps on that surface, so there is no view, no focus and no IME anywhere in this path,
 * and nothing the host can replace with its own full-screen keyboard.
 *
 * What it edits lives in [buffer]; the renderer decides what Go does with it ([target]).
 *
 * The host sends taps only (no press, no release), so a key cannot be held: backspace deletes one
 * character per tap, and the ✕ in the field clears it all.
 */
internal class CarSurfaceKeyboard(
    private val context: Context,
    val target: Target,
    seed: String,
    val mode: BrowserKeyboardMode,
    private val hint: String,
    /**
     * Each key goes to the page as it is tapped (the desktop browser), rather than the buffer being
     * mirrored or sent on Go (the built-in browser, false). The page may hold text this buffer never
     * saw, so in live mode every key reports what it did as [lastEdit], even at the buffer's ends.
     */
    private val live: Boolean = false,
) {
    enum class Target { ADDRESS, FIELD }

    /** What a live-mode key did, for the page to repeat. */
    sealed class Edit {
        data class Inserted(val text: String) : Edit()
        object DeletedBack : Edit()
        /** One character left (-1) or right (+1). */
        data class Cursor(val dir: Int) : Edit()
        /** The ✕ in the field: everything goes. */
        object Cleared : Edit()
    }

    private var pendingEdit: Edit? = null

    /** What the last [onTap] did in live mode; read once (reading clears it). Always null when not live. */
    val lastEdit: Edit?
        get() = pendingEdit.also { pendingEdit = null }

    sealed class Result {
        /** Swallowed by the tray; nothing changed. */
        object None : Result()

        /**
         * The tray changed and must repaint. [pageMutated] is true only when the page-relevant text
         * changed (a key inserted/deleted a character, or the field was cleared), so the renderer
         * mirrors the buffer into the focused page field. It is false for layer and cursor changes
         * (Shift, Language, cursor keys, a cursor-only field tap), which repaint the tray but must
         * not write to the page or fire a spurious `input` event.
         */
        data class Changed(val pageMutated: Boolean) : Result()
        data class Commit(val text: String) : Result()
        /** The hide button, or a tap outside the tray. */
        object Close : Result()
    }

    /** The address bar starts with the whole URL selected, so typing replaces it. */
    val buffer = BrowserTextBuffer(seed, selectAll = target == Target.ADDRESS && seed.isNotEmpty())
    private var shift = ShiftState.OFF
    private var language = CarKeyboardStore.language(context)

    // Geometry for the current area/density/language, one slot per shift state so the one-shot
    // shift flipping back after a letter costs no layout. Compared field by field: no key object is
    // allocated per lookup, and this runs several times per keystroke.
    private var cachedArea: Box? = null
    private var cachedDensity = 0f
    private var cachedLanguage: CarKeyboardLanguage? = null
    private var cachedPlain: BrowserKeyboardGeometry? = null
    private var cachedShifted: BrowserKeyboardGeometry? = null

    /** How far the field's text is scrolled left so the cursor stays in view. */
    private var textScroll = 0f

    /** The key the last tap hit, lit until [pressedUntilMs] so a tap shows on the very frame it is drawn in. */
    private var pressed: BrowserKey? = null
    private var pressedUntilMs = 0L

    // Labels that never change while this keyboard is up, resolved once instead of every frame.
    // Through resources (same strings) so a JVM test can supply them: Context.getString is final.
    private val spaceLabel = context.resources.getString(R.string.car_keyboard_space)
    private val goLabel = BrowserKeyboardStyle.goLabel(
        context.resources.getString(if (mode == BrowserKeyboardMode.URL) R.string.car_keyboard_go else R.string.car_keyboard_search)
    )
    private val hintPaint = android.text.TextPaint(Paint.ANTI_ALIAS_FLAG)
    private var fittedHint: String? = null
    private var fittedHintWidth = -1f
    private var fittedHintSize = -1f

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    // Roboto Medium for Latin, the system Thai fallback for ไทย; created once.
    private val keyFace = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = keyFace
    }
    private val fieldText = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = keyFace }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val rect = RectF()

    fun geometry(area: Box, density: Float): BrowserKeyboardGeometry {
        if (cachedArea != area || cachedDensity != density || cachedLanguage != language) {
            cachedArea = area
            cachedDensity = density
            cachedLanguage = language
            cachedPlain = null
            cachedShifted = null
        }
        val shifted = shift.active
        (if (shifted) cachedShifted else cachedPlain)?.let { return it }
        return BrowserKeyboardGeometry.create(area, density, BrowserKeyboardLayouts.rows(mode, language, shifted)).also {
            if (shifted) cachedShifted = it else cachedPlain = it
        }
    }

    fun onTap(x: Float, y: Float, area: Box, density: Float, nowMs: Long): Result {
        val g = geometry(area, density)
        pressed = null
        pendingEdit = null
        if (!CarKeyboardGestures.inKeyboardArea(x, y, g.tray)) return Result.Close
        if (g.hide.contains(x, y)) return Result.Close
        if (buffer.text.isNotEmpty() && g.clear.contains(x, y)) {
            buffer.clear()
            if (live) pendingEdit = Edit.Cleared
            return Result.Changed(pageMutated = true)
        }
        // Live: the page's caret cannot follow a tap in this echo field, so the tap does nothing.
        if (live && g.field.contains(x, y)) return Result.None
        if (g.field.contains(x, y)) {
            fieldText.textSize = g.keyHeight * FIELD_TEXT
            val start = textLeft(g)
            val advance = (x - start + textScroll).coerceAtLeast(0f)
            val text = buffer.text
            buffer.setCursor(fieldText.getOffsetForAdvance(text, 0, text.length, 0, text.length, false, advance))
            // A field tap only repositions the cursor: the text did not change, so no page mirror.
            return Result.Changed(pageMutated = false)
        }
        val key = g.keyAt(x, y) ?: return Result.None
        pressed = key
        pressedUntilMs = nowMs + PRESSED_MS
        if (live) liveKey(key)?.let { return it }
        // pageMutated is true only when the text changed (Text/Space/Backspace); layer and cursor
        // keys repaint the tray but must not mirror into the page (see CarKeyboardMirror).
        return when (key) {
            is BrowserKey.Text -> {
                buffer.insert(BrowserKeyboardLayouts.typed(key, shift))
                shift = shift.afterType()
                Result.Changed(pageMutated = true)
            }
            is BrowserKey.Space -> {
                buffer.insert(" ")
                Result.Changed(pageMutated = true)
            }
            is BrowserKey.Backspace -> {
                buffer.backspace()
                Result.Changed(pageMutated = true)
            }
            is BrowserKey.CursorLeft -> {
                buffer.moveCursor(-1)
                Result.Changed(pageMutated = false)
            }
            is BrowserKey.CursorRight -> {
                buffer.moveCursor(1)
                Result.Changed(pageMutated = false)
            }
            is BrowserKey.Shift -> {
                shift = shift.next()
                Result.Changed(pageMutated = false)
            }
            is BrowserKey.Language -> {
                language = if (language == CarKeyboardLanguage.THAI) CarKeyboardLanguage.LATIN else CarKeyboardLanguage.THAI
                CarKeyboardStore.setLanguage(context, language)
                shift = ShiftState.OFF
                Result.Changed(pageMutated = false)
            }
            is BrowserKey.Go -> Result.Commit(buffer.text.trim())
        }
    }

    /**
     * A live-mode key that edits: [lastEdit] comes from the key itself, not from what the buffer
     * did (Backspace at the start, or a cursor key at an end, still acts on the page), and the
     * buffer follows as far as it can as feedback. Null for the keys that work as in non-live mode.
     */
    private fun liveKey(key: BrowserKey): Result? {
        pendingEdit = when (key) {
            is BrowserKey.Text -> BrowserKeyboardLayouts.typed(key, shift).let { typed ->
                buffer.insert(typed)
                shift = shift.afterType()
                Edit.Inserted(typed)
            }
            is BrowserKey.Space -> {
                buffer.insert(" ")
                Edit.Inserted(" ")
            }
            is BrowserKey.Backspace -> {
                buffer.backspace()
                Edit.DeletedBack
            }
            is BrowserKey.CursorLeft -> {
                buffer.moveCursorByGrapheme(-1)
                Edit.Cursor(-1)
            }
            is BrowserKey.CursorRight -> {
                buffer.moveCursorByGrapheme(1)
                Edit.Cursor(1)
            }
            else -> return null
        }
        return Result.Changed(pageMutated = true)
    }

    /** Whether a key is still drawn pressed at [nowMs]; the renderer keeps redrawing until it is not. */
    fun pressedAt(nowMs: Long): Boolean = pressed != null && nowMs < pressedUntilMs

    fun draw(
        canvas: Canvas,
        area: Box,
        density: Float,
        nowMs: Long,
        drawIcon: (Canvas, BrowserIcon, Float, Float, Float, Int) -> Unit,
    ) {
        val g = geometry(area, density)
        val lit = if (pressedAt(nowMs)) pressed else null
        val style = BrowserKeyboardStyle
        val radius = style.KEY_RADIUS_DP * density

        fill.color = style.TRAY
        canvas.drawRect(g.tray.left, g.tray.top, g.tray.right, g.tray.bottom, fill)
        fill.color = style.TRAY_EDGE
        canvas.drawRect(g.tray.left, g.tray.top, g.tray.right, g.tray.top + density, fill)

        drawField(canvas, g, density, drawIcon)

        drawCap(canvas, g.hide, style.FUNCTION_CAP, radius, density)
        drawIcon(canvas, BrowserIcon.KBD_HIDE, g.hide.centerX, g.hide.centerY, g.keyHeight * HIDE_ICON, style.ON_FUNCTION_CAP)
        g.keys.forEach { (key, box) ->
            val (cap, ink) = style.colours(key, shift)
            drawCap(canvas, box, if (key == lit) style.pressedCap(key, shift) else cap, radius, density)
            val icon = keyIcon(key)
            if (icon != null) {
                drawIcon(canvas, icon, box.centerX, box.centerY, g.keyHeight * KEY_ICON, ink)
            } else {
                val text = keyLabel(key)
                label.color = ink
                label.textSize = g.keyHeight * (if (key is BrowserKey.Text && text.length <= 2) LETTER_TEXT else WORD_TEXT)
                val baseline = box.centerY - (label.descent() + label.ascent()) / 2f
                canvas.drawText(text, box.centerX, baseline, label)
            }
            if (key is BrowserKey.Shift && shift == ShiftState.LOCKED) {
                fill.color = ink
                val w = box.width * 0.3f
                canvas.drawRect(box.centerX - w / 2f, box.bottom - 6f * density, box.centerX + w / 2f, box.bottom - 4f * density, fill)
            }
        }
    }

    /** A flat Material key: one rounded cap. */
    private fun drawCap(canvas: Canvas, box: Box, cap: Int, radius: Float, density: Float) {
        fill.color = cap
        rect.set(box.left, box.top, box.right, box.bottom)
        canvas.drawRoundRect(rect, radius, radius, fill)
    }

    private fun textLeft(g: BrowserKeyboardGeometry): Float = g.field.left + g.keyHeight * 0.9f

    private fun drawField(
        canvas: Canvas,
        g: BrowserKeyboardGeometry,
        density: Float,
        drawIcon: (Canvas, BrowserIcon, Float, Float, Float, Int) -> Unit,
    ) {
        val style = BrowserKeyboardStyle
        val field = g.field
        val corner = style.KEY_RADIUS_DP * density
        fill.color = style.FIELD
        rect.set(field.left, field.top, field.right, field.bottom)
        canvas.drawRoundRect(rect, corner, corner, fill)
        // The field is always the focused input: a 2dp primary ring, as Material focuses a text field.
        stroke.color = style.CURSOR
        stroke.strokeWidth = 2f * density
        canvas.drawRoundRect(rect, corner, corner, stroke)
        drawIcon(canvas, BrowserIcon.SEARCH, field.left + g.keyHeight * 0.45f, field.centerY, g.keyHeight * 0.42f, style.INK_SECONDARY)

        val text = buffer.text
        if (text.isNotEmpty()) {
            drawIcon(canvas, BrowserIcon.CLOSE, g.clear.centerX, g.clear.centerY, g.keyHeight * 0.38f, style.INK_SECONDARY)
        }
        fieldText.textSize = g.keyHeight * FIELD_TEXT
        val start = textLeft(g)
        val end = (if (text.isNotEmpty()) g.clear.left else field.right - g.keyHeight * 0.4f)
        val width = (end - start).coerceAtLeast(1f)
        val baseline = field.centerY - (fieldText.descent() + fieldText.ascent()) / 2f

        if (text.isEmpty()) {
            fieldText.color = style.INK_SECONDARY
            canvas.drawText(fitHint(hint, width), start, baseline, fieldText)
            drawCursor(canvas, start, field, density)
            return
        }
        val cursorX = fieldText.measureText(text, 0, buffer.cursor)
        textScroll = when {
            cursorX - textScroll > width -> cursorX - width
            cursorX < textScroll -> cursorX
            else -> textScroll
        }.coerceIn(0f, (fieldText.measureText(text) - width).coerceAtLeast(0f))
        canvas.save()
        canvas.clipRect(start, field.top, start + width, field.bottom)
        if (buffer.hasSelection) {
            val a = fieldText.measureText(text, 0, minOf(buffer.selectionStart, buffer.selectionEnd))
            val b = fieldText.measureText(text, 0, maxOf(buffer.selectionStart, buffer.selectionEnd))
            fill.color = style.SELECTION
            canvas.drawRect(start + a - textScroll, field.top + field.height * 0.18f, start + b - textScroll, field.bottom - field.height * 0.18f, fill)
        }
        fieldText.color = style.INK
        canvas.drawText(text, start - textScroll, baseline, fieldText)
        if (!buffer.hasSelection) drawCursor(canvas, start + cursorX - textScroll, field, density)
        canvas.restore()
    }

    private fun drawCursor(canvas: Canvas, x: Float, field: Box, density: Float) {
        fill.color = BrowserKeyboardStyle.CURSOR
        canvas.drawRect(x, field.top + field.height * 0.2f, x + 2f * density, field.bottom - field.height * 0.2f, fill)
    }

    /** The hint cut to [width], ellipsized once per width/size rather than every frame. */
    private fun fitHint(text: String, width: Float): String {
        val size = fieldText.textSize
        fittedHint?.let { if (width == fittedHintWidth && size == fittedHintSize) return it }
        hintPaint.set(fieldText)
        return android.text.TextUtils.ellipsize(text, hintPaint, width, android.text.TextUtils.TruncateAt.END).toString().also {
            fittedHint = it
            fittedHintWidth = width
            fittedHintSize = size
        }
    }

    private fun keyLabel(key: BrowserKey): String = when (key) {
        is BrowserKey.Text -> BrowserKeyboardLayouts.typed(key, shift)
        is BrowserKey.Shift, is BrowserKey.Backspace, is BrowserKey.CursorLeft, is BrowserKey.CursorRight -> ""
        is BrowserKey.Space -> spaceLabel
        is BrowserKey.Go -> goLabel
        is BrowserKey.Language -> if (language == CarKeyboardLanguage.THAI) "EN" else "ไทย"
    }

    private fun keyIcon(key: BrowserKey): BrowserIcon? = when (key) {
        is BrowserKey.Shift -> BrowserIcon.KBD_SHIFT
        is BrowserKey.Backspace -> BrowserIcon.KBD_BACKSPACE
        is BrowserKey.CursorLeft -> BrowserIcon.KBD_CURSOR_LEFT
        is BrowserKey.CursorRight -> BrowserIcon.KBD_CURSOR_RIGHT
        else -> null
    }

    private companion object {
        const val LETTER_TEXT = 0.44f
        const val WORD_TEXT = 0.32f
        const val FIELD_TEXT = 0.42f
        const val KEY_ICON = 0.5f
        const val HIDE_ICON = 0.55f

        /** How long a tapped key stays lit: long enough to see on a head unit, short enough not to lag the next tap. */
        const val PRESSED_MS = 120L
    }
}
