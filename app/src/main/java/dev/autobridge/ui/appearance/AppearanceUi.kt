package dev.autobridge.ui.appearance

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import dev.autobridge.design.ThemeHolder
import dev.autobridge.design.ThemePalette
import dev.autobridge.design.ThemeResolver
import dev.autobridge.ui.AutoBridgeDesign.dp
import dev.autobridge.ui.AppFonts

/**
 * The building blocks of the Appearance pages, drawn to the redesign's component states: the
 * card in the chosen card style, option boxes with an accent edge when selected, the pill toggle,
 * the segmented control on a raised track, radio dots and swatches.
 *
 * Every colour comes from the current [ThemeHolder.palette], so the page is itself a preview of
 * what it sets; the activity recreates on every change and these are rebuilt.
 */
internal object AppearanceUi {

    val p: ThemePalette get() = ThemeHolder.palette

    private val medium: Typeface? get() = AppFonts.medium()

    fun text(
        context: Context,
        value: CharSequence,
        sizeSp: Float,
        color: Int = p.text,
        bold: Boolean = false,
        lines: Int = 0
    ): TextView = TextView(context).apply {
        text = value
        textSize = sizeSp
        setTextColor(color)
        if (bold) typeface = medium
        if (lines > 0) {
            maxLines = lines
            ellipsize = TextUtils.TruncateAt.END
        }
        includeFontPadding = true
    }

    /** The quiet uppercase label over a section. [first] is the first one on a page (less top room). */
    fun label(context: Context, value: String, first: Boolean = false): TextView =
        text(context, value.uppercase(), 11f, p.textTertiary, bold = true).apply {
            letterSpacing = 0.06f
            setPadding(context.dp(4), context.dp(if (first) 16 else 18), context.dp(4), context.dp(8))
        }

    fun shape(context: Context, fill: Int, radiusDp: Int, stroke: Int = Color.TRANSPARENT, strokeDp: Int = 1): GradientDrawable =
        GradientDrawable().apply {
            setColor(fill)
            cornerRadius = context.dp(radiusDp).toFloat()
            if (stroke != Color.TRANSPARENT) setStroke(context.dp(strokeDp).coerceAtLeast(1), stroke)
        }

    fun oval(fill: Int, stroke: Int = Color.TRANSPARENT, strokePx: Int = 0): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(fill)
            if (stroke != Color.TRANSPARENT && strokePx > 0) setStroke(strokePx, stroke)
        }

    fun ripple(content: android.graphics.drawable.Drawable): RippleDrawable =
        RippleDrawable(ColorStateList.valueOf(ThemeResolver.withAlpha(p.accent, 0x38)), content, null)

    /** A card in the chosen card style (Elevated, Outlined, Glass) and corner size. */
    fun cardBackground(context: Context): GradientDrawable =
        shape(context, p.cardFill, p.corners.card, p.cardEdge)

    /** A column of [rows] in one card, a hairline between each pair. */
    fun card(context: Context, rows: List<View>): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = cardBackground(context)
        clipToOutline = true
        rows.forEachIndexed { index, row ->
            if (index > 0) addView(hairline(context))
            addView(row, LinearLayout.LayoutParams(-1, -2))
        }
    }

    /** A padded card holding free-form content. */
    fun paddedCard(context: Context, paddingDp: Int = 14): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = cardBackground(context)
        setPadding(context.dp(paddingDp), context.dp(paddingDp), context.dp(paddingDp), context.dp(paddingDp))
    }

    fun hairline(context: Context): View = View(context).apply {
        setBackgroundColor(p.hairline)
        layoutParams = LinearLayout.LayoutParams(-1, maxOf(1, context.dp(1)))
    }

    /** Fill and edge of a selectable box: accent edge on an accent-soft fill when [selected]. */
    fun optionBackground(context: Context, selected: Boolean, radiusDp: Int = p.corners.button) =
        ripple(shape(context, if (selected) p.accentSoft else Color.TRANSPARENT, radiusDp, if (selected) p.accent else p.hairline))

    fun makeTappable(view: View, description: String, selected: Boolean? = null, onClick: () -> Unit) {
        view.isClickable = true
        view.isFocusable = true
        view.contentDescription = description
        if (selected != null) view.isSelected = selected
        view.setOnClickListener { onClick() }
    }

    fun glyph(context: Context, path: String, color: Int, sizeDp: Int, stroke: Float = 1.8f): View =
        GlyphView(context, path, color, stroke).apply {
            layoutParams = LinearLayout.LayoutParams(context.dp(sizeDp), context.dp(sizeDp))
        }

    /** A rounded square holding a glyph: the icon badge at the start of a list row. */
    fun glyphChip(context: Context, path: String, fill: Int, color: Int, sizeDp: Int = 36, glyphDp: Int = 18, radiusDp: Int): View =
        FrameLayout(context).apply {
            background = shape(context, fill, radiusDp)
            addView(GlyphView(context, path, color), FrameLayout.LayoutParams(context.dp(glyphDp), context.dp(glyphDp), Gravity.CENTER))
            layoutParams = LinearLayout.LayoutParams(context.dp(sizeDp), context.dp(sizeDp))
        }

    /** Title over a caption, the middle of a list row. */
    fun titles(context: Context, title: String, caption: String?): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        addView(text(context, title, 15f, p.text, bold = true))
        if (!caption.isNullOrBlank()) addView(text(context, caption, 13f, p.textSecondary).apply {
            setPadding(0, context.dp(2), 0, 0)
        })
    }

    /** The radio indicator at the end of a choice row. */
    fun radioDot(context: Context, selected: Boolean): View = FrameLayout(context).apply {
        background = oval(Color.TRANSPARENT, if (selected) p.accent else p.hairline, context.dp(2))
        addView(View(context).apply { background = oval(if (selected) p.accent else Color.TRANSPARENT) },
            FrameLayout.LayoutParams(context.dp(10), context.dp(10), Gravity.CENTER))
        layoutParams = LinearLayout.LayoutParams(context.dp(22), context.dp(22))
    }

    /**
     * One list row: a leading view, title and caption, and a trailing view. The whole row is the
     * tap target.
     */
    fun listRow(
        context: Context,
        leading: View?,
        title: String,
        caption: String?,
        trailing: View?,
        minHeightDp: Int = 64,
        selected: Boolean? = null,
        onClick: (() -> Unit)?
    ): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = context.dp(minHeightDp)
        setPadding(context.dp(14), context.dp(8), context.dp(14), context.dp(8))
        if (leading != null) addView(leading)
        addView(titles(context, title, caption), LinearLayout.LayoutParams(0, -2, 1f).apply {
            marginStart = if (leading != null) context.dp(14) else 0
            marginEnd = context.dp(10)
        })
        if (trailing != null) addView(trailing)
        if (onClick != null) {
            background = ripple(GradientDrawable().apply { setColor(Color.TRANSPARENT) })
            makeTappable(this, title, selected, onClick)
        }
    }

    fun chevron(context: Context): View = glyph(context, Glyphs.CHEVRON, p.textTertiary, 18, 2f)

    /** The pill switch from the design: accent track and on-accent knob when on. */
    fun toggle(context: Context, on: Boolean): View = FrameLayout(context).apply {
        background = shape(context, if (on) p.accent else p.hairline, 14)
        addView(View(context).apply { background = oval(if (on) p.onAccent else p.textSecondary) },
            FrameLayout.LayoutParams(context.dp(24), context.dp(24), Gravity.CENTER_VERTICAL or if (on) Gravity.END else Gravity.START).apply {
                marginStart = context.dp(2)
                marginEnd = context.dp(2)
            })
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        layoutParams = LinearLayout.LayoutParams(context.dp(48), context.dp(28)).apply { marginStart = context.dp(4) }
    }

    /** A title, caption and [toggle]; the whole row flips it. */
    fun toggleRow(context: Context, title: String, caption: String?, on: Boolean, onToggle: () -> Unit): LinearLayout =
        listRow(context, null, title, caption, toggle(context, on), minHeightDp = 60, onClick = onToggle).apply {
            setPadding(context.dp(14), context.dp(8), context.dp(10), context.dp(8))
            accessibilityDelegate = SwitchAccessibility(on)
        }

    /** Reads the row out as a switch with its state rather than a plain button. */
    private class SwitchAccessibility(private val on: Boolean) : View.AccessibilityDelegate() {
        override fun onInitializeAccessibilityNodeInfo(host: View, info: android.view.accessibility.AccessibilityNodeInfo) {
            super.onInitializeAccessibilityNodeInfo(host, info)
            info.className = android.widget.Switch::class.java.name
            info.isCheckable = true
            info.isChecked = on
        }
    }

    /** A segmented control on a raised track: the selected segment sits on the surface colour. */
    fun <T> segmented(context: Context, options: List<Pair<T, String>>, selected: T, onPick: (T) -> Unit): View =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            background = shape(context, p.raised, p.corners.button)
            val inset = context.dp(4)
            setPadding(inset, inset, inset, inset)
            val small = AppearanceModel.smallRadii(p.corners)
            options.forEachIndexed { index, (value, label) ->
                val active = value == selected
                addView(text(context, label, 13f, if (active) p.text else p.textSecondary, bold = active, lines = 1).apply {
                    gravity = Gravity.CENTER
                    background = ripple(shape(context, if (active) p.surface else Color.TRANSPARENT, small.chipSmall))
                    makeTappable(this, label, active) { if (!active) onPick(value) }
                }, LinearLayout.LayoutParams(0, context.dp(40), 1f).apply {
                    if (index > 0) marginStart = context.dp(4)
                })
            }
        }

    /**
     * An accent swatch: a [color] disc inside a ring that shows only when [selected], with a check
     * on the selected one when [check] is set.
     */
    fun swatch(context: Context, color: Int, selected: Boolean, outerDp: Int, innerDp: Int, check: Boolean): FrameLayout =
        FrameLayout(context).apply {
            background = oval(Color.TRANSPARENT, if (selected) p.text else Color.TRANSPARENT, context.dp(2))
            val disc = FrameLayout(context).apply { background = oval(color) }
            if (selected && check) disc.addView(GlyphView(context, Glyphs.CHECK, p.onAccent, 2.6f),
                FrameLayout.LayoutParams(context.dp(18), context.dp(18), Gravity.CENTER))
            addView(disc, FrameLayout.LayoutParams(context.dp(innerDp), context.dp(innerDp), Gravity.CENTER))
            layoutParams = LinearLayout.LayoutParams(context.dp(outerDp), context.dp(outerDp))
        }

    /** A SeekBar in the accent colour, thumb included (a white thumb disappears on light cards). */
    fun seekBar(context: Context, max: Int, progress: Int, description: String, onMove: (Int) -> Unit, onRelease: (Int) -> Unit): SeekBar =
        SeekBar(context).apply {
            this.max = max
            this.progress = progress
            contentDescription = description
            progressTintList = ColorStateList.valueOf(p.accent)
            thumbTintList = ColorStateList.valueOf(p.accent)
            progressBackgroundTintList = ColorStateList.valueOf(p.hairline)
            minimumHeight = context.dp(44)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar, at: Int, fromUser: Boolean) = onMove(at)
                override fun onStartTrackingTouch(bar: SeekBar) = Unit
                override fun onStopTrackingTouch(bar: SeekBar) = onRelease(bar.progress)
            })
        }

    /** Lays [cells] out [columns] to a row with [gapDp] between them, each row as tall as its tallest cell. */
    fun grid(context: Context, cells: List<View>, columns: Int, gapDp: Int): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        cells.chunked(columns).forEachIndexed { rowIndex, rowCells ->
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                for (i in 0 until columns) {
                    val cell = rowCells.getOrNull(i) ?: View(context)
                    addView(cell, LinearLayout.LayoutParams(0, -1, 1f).apply {
                        if (i > 0) marginStart = context.dp(gapDp)
                    })
                }
            }, LinearLayout.LayoutParams(-1, -2).apply { if (rowIndex > 0) topMargin = context.dp(gapDp) })
        }
    }
}
