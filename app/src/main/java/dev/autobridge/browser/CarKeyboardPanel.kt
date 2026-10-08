package dev.autobridge.browser

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import dev.autobridge.R

/**
 * The app's own on-screen keyboard, as a view, for a car browser that is a real view tree but gets
 * no system keyboard on the car display (the browser in a Duo Screen pane). Bridge Web draws the
 * same keys ([CarKeyboardLayouts]) with the same behaviour.
 *
 * What is typed accumulates in the panel: [onChange] gets the whole buffer after every key that
 * changes it (so the page's field can mirror it live), [onCommit] gets it once on Go, and
 * [onClose] runs when the ✕ is tapped. The caller adds [view] at the bottom of its window and
 * removes it on commit or close.
 */
class CarKeyboardPanel(
    private val context: Context,
    private val colors: Colors,
    seed: String,
    private val onChange: (String) -> Unit,
    private val onCommit: (String) -> Unit,
    private val onClose: () -> Unit,
) {
    /** The tray and cap colours; Go uses the accent pair, letters the light cap, the rest the dark one. */
    data class Colors(
        val tray: Int,
        val letterCap: Int,
        val modifierCap: Int,
        val goCap: Int,
        val onGo: Int,
        val text: Int,
        val textSecondary: Int,
    )

    private val typed = StringBuilder(seed)
    private var shift = false
    private var symbols = false
    private var language = CarKeyboardStore.language(context)
    private val rows = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val preview = TextView(context).apply {
        textSize = 20f
        isSingleLine = true
        ellipsize = TextUtils.TruncateAt.START
        gravity = Gravity.CENTER_VERTICAL
        background = rounded(colors.modifierCap, 12f)
        setPadding(dp(16), 0, dp(16), 0)
    }

    val view: View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(colors.tray)
        setPadding(dp(8), dp(6), dp(8), dp(8))
        // Taps between keys stay here rather than reaching the page underneath.
        isClickable = true
        // No key takes focus: the page keeps its focused field, which is where the text goes.
        descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(preview, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
                marginEnd = dp(KEY_GAP)
            })
            addView(TextView(context).apply {
                text = "✕"
                contentDescription = context.getString(R.string.browser_close)
                setTextColor(colors.textSecondary)
                textSize = 20f
                gravity = Gravity.CENTER
                background = ripple(colors.modifierCap)
                isClickable = true
                setOnClickListener { onClose() }
            }, LinearLayout.LayoutParams(dp(TOUCH), dp(TOUCH)))
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(TOUCH)).apply {
            bottomMargin = dp(6)
        })
        addView(rows, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    init {
        rebuildKeys()
        syncPreview()
    }

    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).toInt()

    private fun rounded(fill: Int, radiusDp: Float) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = radiusDp * context.resources.displayMetrics.density
        setColor(fill)
    }

    private fun ripple(fill: Int) = RippleDrawable(ColorStateList.valueOf(colors.textSecondary), rounded(fill, 12f), null)

    private fun rebuildKeys() {
        rows.removeAllViews()
        CarKeyboardLayouts.rows(language, shift, symbols).forEach { row ->
            val line = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            row.forEach { key ->
                line.addView(
                    keyButton(key),
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, CarKeyboardLayouts.weight(key)).apply {
                        marginStart = dp(KEY_GAP)
                        marginEnd = dp(KEY_GAP)
                    }
                )
            }
            rows.addView(line, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(KEY_HEIGHT)).apply {
                topMargin = dp(KEY_GAP)
            })
        }
    }

    private fun keyButton(key: CarKey): TextView = TextView(context).apply {
        text = label(key)
        contentDescription = description(key)
        setTextColor(if (key is CarKey.Go) colors.onGo else colors.text)
        textSize = if (key is CarKey.Text) 22f else 17f
        setTypeface(typeface, Typeface.BOLD)
        gravity = Gravity.CENTER
        background = ripple(
            when (key) {
                is CarKey.Go -> colors.goCap
                is CarKey.Text, is CarKey.Space -> colors.letterCap
                else -> colors.modifierCap
            }
        )
        isClickable = true
        isFocusable = false
        setOnClickListener { onKey(key) }
    }

    /** Each layer key names what it switches TO, not what is showing. */
    private fun label(key: CarKey): String = when (key) {
        is CarKey.Text -> if (shift) key.upper else key.lower
        is CarKey.Shift -> "⇧"
        is CarKey.Backspace -> "⌫"
        is CarKey.Space -> "space"
        is CarKey.Go -> "Go"
        is CarKey.Language -> if (language == CarKeyboardLanguage.THAI) "EN" else "ไทย"
        is CarKey.Symbols -> when {
            !symbols -> "?123"
            language == CarKeyboardLanguage.THAI -> "กขค"
            else -> "ABC"
        }
    }

    private fun description(key: CarKey): String = when (key) {
        is CarKey.Text -> label(key)
        is CarKey.Shift -> "Shift"
        is CarKey.Backspace -> "Backspace"
        is CarKey.Space -> "Space"
        is CarKey.Go -> "Search"
        is CarKey.Language -> "Switch language"
        is CarKey.Symbols -> "Switch symbols"
    }

    private fun onKey(key: CarKey) {
        var mutated = false
        when (key) {
            is CarKey.Text -> {
                typed.append(if (shift) key.upper else key.lower)
                mutated = true
                // One-shot, as on a phone: shift applies to the next key only.
                if (shift) {
                    shift = false
                    rebuildKeys()
                }
            }
            is CarKey.Space -> {
                typed.append(' ')
                mutated = true
            }
            is CarKey.Backspace -> {
                if (typed.isNotEmpty()) typed.setLength(typed.length - 1)
                mutated = true
            }
            is CarKey.Shift -> {
                shift = !shift
                rebuildKeys()
            }
            is CarKey.Symbols -> {
                symbols = !symbols
                shift = false
                rebuildKeys()
            }
            is CarKey.Language -> {
                language = if (language == CarKeyboardLanguage.THAI) CarKeyboardLanguage.LATIN else CarKeyboardLanguage.THAI
                CarKeyboardStore.setLanguage(context, language)
                symbols = false
                shift = false
                rebuildKeys()
            }
            is CarKey.Go -> return onCommit(typed.toString().trim())
        }
        syncPreview()
        if (mutated) onChange(typed.toString())
    }

    private fun syncPreview() {
        val value = typed.toString()
        if (value.isEmpty()) {
            preview.text = context.getString(R.string.car_keyboard_hint)
            preview.setTextColor(colors.textSecondary)
        } else {
            preview.text = value
            preview.setTextColor(colors.text)
        }
    }

    companion object {
        private const val KEY_HEIGHT = 56
        private const val KEY_GAP = 4
        private const val TOUCH = 52

        /**
         * Reads the page's focused text field: its current text, or null when nothing a driver
         * types into has focus. Password fields are left out on purpose: the panel echoes what is
         * typed in its preview, on a screen passengers can read.
         */
        const val FOCUSED_FIELD_SCRIPT = """
            (function(){
              var el = document.activeElement;
              if (!el) return null;
              if (el.isContentEditable) return el.textContent || '';
              if (el.readOnly || el.disabled) return null;
              if (el.tagName === 'TEXTAREA') return el.value || '';
              if (el.tagName !== 'INPUT') return null;
              var t = (el.type || 'text').toLowerCase();
              if (['text', 'search', 'url', 'email', 'tel', 'number'].indexOf(t) < 0) return null;
              return el.value || '';
            })();
        """

        /**
         * Like [FOCUSED_FIELD_SCRIPT], but a JSON object `{value, label, type}`, or null when no
         * typeable field has focus. [label] is what the page calls the field (placeholder,
         * aria-label, its <label>, or its name), so the car's input screen can say which field it
         * fills instead of looking like the address bar.
         */
        const val FOCUSED_FIELD_INFO_SCRIPT = """
            (function(){
              var el = document.activeElement;
              if (!el) return null;
              function labelOf(e){
                var t = e.getAttribute('placeholder') || e.getAttribute('aria-label') || '';
                if (!t && e.id) { var l = document.querySelector('label[for="' + e.id + '"]'); if (l) t = l.textContent; }
                if (!t && e.closest) { var p = e.closest('label'); if (p) t = p.textContent; }
                if (!t) t = e.getAttribute('title') || e.getAttribute('name') || '';
                return (t || '').replace(/\s+/g, ' ').trim().slice(0, 80);
              }
              if (el.isContentEditable) return JSON.stringify({value: el.textContent || '', label: labelOf(el), type: 'text'});
              if (el.readOnly || el.disabled) return null;
              if (el.tagName === 'TEXTAREA') return JSON.stringify({value: el.value || '', label: labelOf(el), type: 'text'});
              if (el.tagName !== 'INPUT') return null;
              var t = (el.type || 'text').toLowerCase();
              if (['text', 'search', 'url', 'email', 'tel', 'number'].indexOf(t) < 0) return null;
              return JSON.stringify({value: el.value || '', label: labelOf(el), type: t});
            })();
        """

        /**
         * The script that puts [value] (already a quoted JS string literal) into the page's
         * focused field, and with [submit] submits its form or presses Enter. Returns 'OK', or
         * 'NO_FOCUS' when nothing editable has focus.
         */
        fun typeScript(value: String, submit: Boolean): String = """
            (function(){
              var el = document.activeElement;
              var editable = el && (el.tagName === 'INPUT' || el.tagName === 'TEXTAREA' || el.isContentEditable);
              if (!editable) return 'NO_FOCUS';
              if (el.isContentEditable) { el.textContent = $value; }
              else { el.value = $value; el.dispatchEvent(new Event('input', {bubbles:true})); }
              ${if (submit) "if (el.form) { el.form.submit(); } else { el.dispatchEvent(new KeyboardEvent('keydown', {key:'Enter', keyCode:13, which:13, bubbles:true})); }" else ""}
              return 'OK';
            })();
        """.trimIndent()
    }
}
