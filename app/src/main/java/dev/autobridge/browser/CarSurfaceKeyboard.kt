package dev.autobridge.browser

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
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
) {
    enum class Target { ADDRESS, FIELD }

    sealed class Result {
        /** Swallowed by the tray; nothing changed. */
        object None : Result()
        object Changed : Result()
        data class Commit(val text: String) : Result()
        /** The hide button, or a tap outside the tray. */
        object Close : Result()
    }

    /** The address bar starts with the whole URL selected, so typing replaces it. */
    val buffer = BrowserTextBuffer(seed, selectAll = target == Target.ADDRESS && seed.isNotEmpty())
    private var shift = ShiftState.OFF
    private var language = CarKeyboardStore.language(context)

    private var cachedKey: Any? = null
    private var cached: BrowserKeyboardGeometry? = null

    /** How far the field's text is scrolled left so the cursor stays in view. */
    private var textScroll = 0f

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val fieldText = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val rect = RectF()
    private val path = Path()

    fun geometry(area: Box, density: Float): BrowserKeyboardGeometry {
        val key = listOf(area, density, language, shift.active, mode)
        cached?.let { if (cachedKey == key) return it }
        return BrowserKeyboardGeometry.create(area, density, BrowserKeyboardLayouts.rows(mode, language, shift.active)).also {
            cached = it
            cachedKey = key
        }
    }

    fun onTap(x: Float, y: Float, area: Box, density: Float): Result {
        val g = geometry(area, density)
        if (!g.tray.contains(x, y)) return Result.Close
        if (g.hide.contains(x, y)) return Result.Close
        if (buffer.text.isNotEmpty() && g.clear.contains(x, y)) {
            buffer.clear()
            return Result.Changed
        }
        if (g.field.contains(x, y)) {
            fieldText.textSize = g.keyHeight * FIELD_TEXT
            val start = textLeft(g)
            val advance = (x - start + textScroll).coerceAtLeast(0f)
            val text = buffer.text
            buffer.setCursor(fieldText.getOffsetForAdvance(text, 0, text.length, 0, text.length, false, advance))
            return Result.Changed
        }
        val key = g.keyAt(x, y) ?: return Result.None
        when (key) {
            is BrowserKey.Text -> {
                buffer.insert(BrowserKeyboardLayouts.typed(key, shift))
                shift = shift.afterType()
            }
            is BrowserKey.Space -> buffer.insert(" ")
            is BrowserKey.Backspace -> buffer.backspace()
            is BrowserKey.CursorLeft -> buffer.moveCursor(-1)
            is BrowserKey.CursorRight -> buffer.moveCursor(1)
            is BrowserKey.Shift -> shift = shift.next()
            is BrowserKey.Language -> {
                language = if (language == CarKeyboardLanguage.THAI) CarKeyboardLanguage.LATIN else CarKeyboardLanguage.THAI
                CarKeyboardStore.setLanguage(context, language)
                shift = ShiftState.OFF
            }
            is BrowserKey.Go -> return Result.Commit(buffer.text.trim())
        }
        return Result.Changed
    }

    fun draw(canvas: Canvas, area: Box, density: Float, drawIcon: (Canvas, BrowserIcon, Float, Float, Float, Int) -> Unit) {
        val g = geometry(area, density)
        val theme = BrowserTheme.dark
        val radius = 8f * density

        fill.color = theme.surfaceContainer
        canvas.drawRect(g.tray.left, g.tray.top, g.tray.right, g.tray.bottom, fill)
        fill.color = theme.outlineVariant
        canvas.drawRect(g.tray.left, g.tray.top, g.tray.right, g.tray.top + density, fill)

        drawField(canvas, g, density, drawIcon)

        // Hide: a key-shaped button with a chevron pointing down.
        fill.color = theme.surfaceContainerHigh
        rect.set(g.hide.left, g.hide.top, g.hide.right, g.hide.bottom)
        canvas.drawRoundRect(rect, radius, radius, fill)
        stroke.color = theme.textPrimary
        stroke.strokeWidth = 2.5f * density
        val half = g.hide.width * 0.16f
        path.rewind()
        path.moveTo(g.hide.centerX - half, g.hide.centerY - half / 2f)
        path.lineTo(g.hide.centerX, g.hide.centerY + half / 2f)
        path.lineTo(g.hide.centerX + half, g.hide.centerY - half / 2f)
        canvas.drawPath(path, stroke)

        g.keys.forEach { (key, box) ->
            val (cap, ink) = colours(key)
            fill.color = cap
            rect.set(box.left, box.top, box.right, box.bottom)
            canvas.drawRoundRect(rect, radius, radius, fill)
            val text = keyLabel(key)
            label.color = ink
            label.textSize = g.keyHeight * (if (key is BrowserKey.Text && text.length <= 2) LETTER_TEXT else WORD_TEXT)
            label.isFakeBoldText = key is BrowserKey.Go
            val baseline = box.centerY - (label.descent() + label.ascent()) / 2f
            canvas.drawText(text, box.centerX, baseline, label)
            if (key is BrowserKey.Shift && shift == ShiftState.LOCKED) {
                fill.color = ink
                val w = box.width * 0.3f
                canvas.drawRect(box.centerX - w / 2f, box.bottom - 6f * density, box.centerX + w / 2f, box.bottom - 4f * density, fill)
            }
        }
    }

    private fun textLeft(g: BrowserKeyboardGeometry): Float = g.field.left + g.keyHeight * 0.9f

    private fun drawField(
        canvas: Canvas,
        g: BrowserKeyboardGeometry,
        density: Float,
        drawIcon: (Canvas, BrowserIcon, Float, Float, Float, Int) -> Unit,
    ) {
        val theme = BrowserTheme.dark
        val field = g.field
        fill.color = theme.addressPillBackground
        rect.set(field.left, field.top, field.right, field.bottom)
        canvas.drawRoundRect(rect, field.height / 2f, field.height / 2f, fill)
        drawIcon(canvas, BrowserIcon.SEARCH, field.left + g.keyHeight * 0.45f, field.centerY, g.keyHeight * 0.42f, theme.textSecondary)

        val text = buffer.text
        if (text.isNotEmpty()) {
            drawIcon(canvas, BrowserIcon.CLOSE, g.clear.centerX, g.clear.centerY, g.keyHeight * 0.38f, theme.iconEnabled)
        }
        fieldText.textSize = g.keyHeight * FIELD_TEXT
        val start = textLeft(g)
        val end = (if (text.isNotEmpty()) g.clear.left else field.right - g.keyHeight * 0.4f)
        val width = (end - start).coerceAtLeast(1f)
        val baseline = field.centerY - (fieldText.descent() + fieldText.ascent()) / 2f

        if (text.isEmpty()) {
            fieldText.color = theme.textSecondary
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
            fill.color = theme.accent
            fill.alpha = 90
            canvas.drawRect(start + a - textScroll, field.top + field.height * 0.18f, start + b - textScroll, field.bottom - field.height * 0.18f, fill)
            fill.alpha = 255
        }
        fieldText.color = theme.textPrimary
        canvas.drawText(text, start - textScroll, baseline, fieldText)
        if (!buffer.hasSelection) drawCursor(canvas, start + cursorX - textScroll, field, density)
        canvas.restore()
    }

    private fun drawCursor(canvas: Canvas, x: Float, field: Box, density: Float) {
        fill.color = BrowserTheme.dark.accent
        canvas.drawRect(x, field.top + field.height * 0.2f, x + 2f * density, field.bottom - field.height * 0.2f, fill)
    }

    private fun fitHint(text: String, width: Float): String =
        android.text.TextUtils.ellipsize(text, android.text.TextPaint(fieldText), width, android.text.TextUtils.TruncateAt.END).toString()

    private fun colours(key: BrowserKey): Pair<Int, Int> {
        val theme = BrowserTheme.dark
        return when {
            key is BrowserKey.Go -> theme.primary to theme.onPrimary
            key is BrowserKey.Shift && shift.active -> theme.primaryContainer to theme.onPrimaryContainer
            key is BrowserKey.Text && key.lower.length <= 1 -> theme.surfaceContainerHighest to theme.textPrimary
            key is BrowserKey.Space -> theme.surfaceContainerHighest to theme.textSecondary
            else -> theme.surfaceContainerHigh to theme.textPrimary
        }
    }

    private fun keyLabel(key: BrowserKey): String = when (key) {
        is BrowserKey.Text -> BrowserKeyboardLayouts.typed(key, shift)
        is BrowserKey.Shift -> "⇧"
        is BrowserKey.Backspace -> "⌫"
        is BrowserKey.Space -> context.getString(R.string.car_keyboard_space)
        is BrowserKey.Go -> context.getString(if (mode == BrowserKeyboardMode.URL) R.string.car_keyboard_go else R.string.car_keyboard_search)
        is BrowserKey.Language -> if (language == CarKeyboardLanguage.THAI) "EN" else "ไทย"
        is BrowserKey.CursorLeft -> "◀"
        is BrowserKey.CursorRight -> "▶"
    }

    private companion object {
        const val LETTER_TEXT = 0.44f
        const val WORD_TEXT = 0.32f
        const val FIELD_TEXT = 0.42f
    }
}
