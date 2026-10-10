package dev.autobridge.browser

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import dev.autobridge.R

/**
 * The browser keyboard as views, typing straight into a real [EditText] (the Duo Screen pane's
 * address bar on the car display). The field keeps focus and its cursor, so a tap in it moves the
 * cursor and a drag selects, exactly as with the system keyboard; every key edits the field's
 * text at its selection. The field must have `showSoftInputOnFocus = false`, or the system
 * keyboard comes up as well.
 *
 * Unlike the car surface ([CarSurfaceKeyboard]) this is a real touch screen, so backspace repeats
 * while held.
 *
 * [areaWidth] and [areaHeight] are the window's size in px; keys are sized from them with the same
 * rule the car surface uses ([BrowserKeyboardGeometry]).
 */
class BrowserKeyboardView(
    private val context: Context,
    private val field: EditText,
    private val mode: BrowserKeyboardMode,
    areaWidth: Int,
    areaHeight: Int,
    private val onGo: (String) -> Unit,
    private val onHide: () -> Unit,
) {
    private val density = context.resources.displayMetrics.density
    private var shift = ShiftState.OFF
    private var language = CarKeyboardStore.language(context)
    private val area = Box(0f, 0f, areaWidth.toFloat(), areaHeight.toFloat())
    private val rows = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val handler = Handler(Looper.getMainLooper())

    private val repeatBackspace = object : Runnable {
        override fun run() {
            backspace()
            handler.postDelayed(this, REPEAT_MS)
        }
    }

    val view: View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(BrowserTheme.surfaceContainer)
        val pad = dp(8f).toInt()
        setPadding(pad, dp(4f).toInt(), pad, pad)
        // Taps between keys stay here instead of reaching the page underneath.
        isClickable = true
        // No key takes focus: the field keeps it, and with it the cursor.
        descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        addView(TextView(context).apply {
            text = "⌄"
            contentDescription = context.getString(R.string.car_keyboard_hide)
            textSize = 22f
            gravity = Gravity.CENTER
            setTextColor(BrowserTheme.textPrimary)
            background = keyCap(BrowserTheme.surfaceContainerHigh)
            isFocusable = false
            setOnClickListener { onHide() }
        }, LinearLayout.LayoutParams(dp(64f).toInt(), dp(36f).toInt()).apply { gravity = Gravity.END })
        addView(rows, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = Unit
            override fun onViewDetachedFromWindow(v: View) = handler.removeCallbacks(repeatBackspace)
        })
    }

    init {
        rebuild()
    }

    private fun dp(value: Float) = value * density

    private fun rebuild() {
        rows.removeAllViews()
        val layout = BrowserKeyboardLayouts.rows(mode, language, shift.active)
        val geometry = BrowserKeyboardGeometry.create(area, density, layout)
        val gap = dp(5f).toInt()
        layout.forEachIndexed { index, row ->
            val line = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_HORIZONTAL
            }
            row.forEach { key ->
                line.addView(button(key), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, BrowserKeyboardLayouts.weight(key)).apply {
                    marginStart = gap / 2
                    marginEnd = gap / 2
                })
            }
            // Letter rows keep one key width, like the car surface: pad short rows with weight.
            if (index < layout.lastIndex) {
                val widest = layout.dropLast(1).maxOf { r -> r.sumOf { BrowserKeyboardLayouts.weight(it).toDouble() } }
                line.weightSum = widest.toFloat()
            }
            rows.addView(line, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, geometry.keyHeight.toInt()).apply {
                topMargin = gap
            })
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun button(key: BrowserKey): TextView = TextView(context).apply {
        text = label(key)
        contentDescription = description(key)
        gravity = Gravity.CENTER
        isFocusable = false
        val (cap, ink) = colours(key)
        setTextColor(ink)
        background = keyCap(cap)
        textSize = if (key is BrowserKey.Text && text.length <= 2) 20f else 15f
        if (key is BrowserKey.Go) setTypeface(typeface, Typeface.BOLD)
        if (key is BrowserKey.Backspace) {
            // Held: delete once now, then keep deleting until released.
            setOnTouchListener { v, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        v.isPressed = true
                        backspace()
                        handler.postDelayed(repeatBackspace, REPEAT_DELAY_MS)
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        v.isPressed = false
                        handler.removeCallbacks(repeatBackspace)
                    }
                }
                true
            }
        } else {
            setOnClickListener { onKey(key) }
        }
    }

    private fun keyCap(fill: Int) = RippleDrawable(
        ColorStateList.valueOf(BrowserTheme.textSecondary),
        GradientDrawable().apply {
            cornerRadius = dp(8f)
            setColor(fill)
        },
        null,
    )

    private fun colours(key: BrowserKey): Pair<Int, Int> = when {
        key is BrowserKey.Go -> BrowserTheme.primary to BrowserTheme.onPrimary
        key is BrowserKey.Shift && shift.active -> BrowserTheme.primaryContainer to BrowserTheme.onPrimaryContainer
        key is BrowserKey.Text && key.lower.length <= 1 -> BrowserTheme.surfaceContainerHighest to BrowserTheme.textPrimary
        key is BrowserKey.Space -> BrowserTheme.surfaceContainerHighest to BrowserTheme.textSecondary
        else -> BrowserTheme.surfaceContainerHigh to BrowserTheme.textPrimary
    }

    private fun label(key: BrowserKey): String = when (key) {
        is BrowserKey.Text -> BrowserKeyboardLayouts.typed(key, shift)
        is BrowserKey.Shift -> if (shift == ShiftState.LOCKED) "⇪" else "⇧"
        is BrowserKey.Backspace -> "⌫"
        is BrowserKey.Space -> context.getString(R.string.car_keyboard_space)
        is BrowserKey.Go -> context.getString(if (mode == BrowserKeyboardMode.URL) R.string.car_keyboard_go else R.string.car_keyboard_search)
        is BrowserKey.Language -> if (language == CarKeyboardLanguage.THAI) "EN" else "ไทย"
        is BrowserKey.CursorLeft -> "◀"
        is BrowserKey.CursorRight -> "▶"
    }

    private fun description(key: BrowserKey): String = when (key) {
        is BrowserKey.Text -> label(key)
        is BrowserKey.Shift -> "Shift"
        is BrowserKey.Backspace -> "Backspace"
        is BrowserKey.Space -> "Space"
        is BrowserKey.Go -> label(key)
        is BrowserKey.Language -> "Switch language"
        is BrowserKey.CursorLeft -> "Cursor left"
        is BrowserKey.CursorRight -> "Cursor right"
    }

    /**
     * Applies [change] to the field's text and selection through a [BrowserTextBuffer], so the
     * editing rules are the ones the tests cover, then writes both back to the field.
     */
    private fun edit(change: (BrowserTextBuffer) -> Unit) {
        val text = field.text?.toString().orEmpty()
        val buffer = BrowserTextBuffer.withSelection(text, field.selectionStart, field.selectionEnd)
        change(buffer)
        field.setText(buffer.text)
        field.setSelection(buffer.selectionStart, buffer.selectionEnd)
    }

    private fun backspace() = edit { it.backspace() }

    private fun onKey(key: BrowserKey) {
        when (key) {
            is BrowserKey.Text -> {
                val typed = BrowserKeyboardLayouts.typed(key, shift)
                edit { it.insert(typed) }
                val before = shift
                shift = shift.afterType()
                if (before != shift) rebuild()
            }
            is BrowserKey.Space -> edit { it.insert(" ") }
            is BrowserKey.Backspace -> backspace()
            is BrowserKey.CursorLeft -> edit { it.moveCursor(-1) }
            is BrowserKey.CursorRight -> edit { it.moveCursor(1) }
            is BrowserKey.Shift -> {
                shift = shift.next()
                rebuild()
            }
            is BrowserKey.Language -> {
                language = if (language == CarKeyboardLanguage.THAI) CarKeyboardLanguage.LATIN else CarKeyboardLanguage.THAI
                CarKeyboardStore.setLanguage(context, language)
                shift = ShiftState.OFF
                rebuild()
            }
            is BrowserKey.Go -> onGo(field.text?.toString().orEmpty().trim())
        }
    }

    private companion object {
        const val REPEAT_DELAY_MS = 400L
        const val REPEAT_MS = 60L
    }
}
