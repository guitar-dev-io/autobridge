package dev.autobridge.ui

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import dev.autobridge.ui.AutoBridgeDesign.dp

/**
 * The shared shape of the My Car tool pages (parking, maintenance, fuel, costs, emergency): one
 * summary card at the top, one primary action, the list, and the long explanation as a footnote
 * at the end instead of a paragraph above everything.
 */
object CarToolViews {

    /** The summary card: a raised surface the caller fills top to bottom. */
    fun card(context: Context, build: LinearLayout.() -> Unit): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = AutoBridgeDesign.surface(context, AutoBridgeDesign.SURFACE, 16, android.graphics.Color.TRANSPARENT)
        setPadding(context.dp(16), context.dp(14), context.dp(16), context.dp(16))
        build()
    }

    fun text(
        context: Context,
        value: CharSequence,
        size: Float,
        color: Int = AutoBridgeDesign.TEXT,
        bold: Boolean = false,
        top: Int = 0
    ): TextView = TextView(context).apply {
        text = value
        textSize = size
        setTextColor(color)
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        fontFeatureSettings = "tnum"
        setPadding(0, context.dp(top), 0, 0)
    }

    /** A quiet line after the list: what the page keeps and where, said once. */
    fun footnote(context: Context, value: String): TextView =
        text(context, value, 12f, AutoBridgeDesign.TEXT_MUTED, top = 4)

    /** One figure: a small label over its value, the unit after it in small type ("14.2 km/L"). */
    data class Stat(val label: String, val value: String, val unit: String? = null)

    /** Up to three figures side by side. */
    fun stats(context: Context, cells: List<Stat>): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        cells.forEach { cell ->
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(text(context, cell.label, 12f, AutoBridgeDesign.TEXT_MUTED))
                val shown = android.text.SpannableStringBuilder(cell.value)
                if (cell.unit != null) {
                    val start = shown.length
                    shown.append(" ").append(cell.unit)
                    shown.setSpan(android.text.style.RelativeSizeSpan(0.6f), start, shown.length, 0)
                    shown.setSpan(android.text.style.ForegroundColorSpan(AutoBridgeDesign.TEXT_MUTED), start, shown.length, 0)
                }
                addView(text(context, shown, 22f, bold = true, top = 2).apply { maxLines = 1 })
            }, LinearLayout.LayoutParams(0, -2, 1f))
        }
    }

    /** A thin bar [fraction] full, in [color]; used for "how much of the interval is used". */
    fun bar(context: Context, fraction: Float, color: Int, top: Int = 8): View = FrameLayout(context).apply {
        val height = context.dp(6)
        background = rounded(context, AutoBridgeDesign.HAIRLINE)
        val fill = View(context).apply { background = rounded(context, color) }
        addView(fill, FrameLayout.LayoutParams(0, height))
        layoutParams = LinearLayout.LayoutParams(-1, height).apply { topMargin = context.dp(top) }
        addOnLayoutChangeListener { _, left, _, right, _, _, _, _, _ ->
            val width = ((right - left) * fraction.coerceIn(0f, 1f)).toInt()
            if (fill.layoutParams.width != width) post { fill.layoutParams = FrameLayout.LayoutParams(width, height) }
        }
    }

    /** One bar split in two by weight, [first] then [second]; for "fuel against everything else". */
    fun splitBar(context: Context, first: Double, second: Double, firstColor: Int, secondColor: Int, top: Int = 10): View =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            val height = context.dp(8)
            layoutParams = LinearLayout.LayoutParams(-1, height).apply { topMargin = context.dp(top) }
            val total = first + second
            if (total <= 0.0) {
                background = rounded(context, AutoBridgeDesign.HAIRLINE)
                return@apply
            }
            if (first > 0) addView(View(context).apply { background = rounded(context, firstColor) },
                LinearLayout.LayoutParams(0, height, (first / total).toFloat()).apply { if (second > 0) marginEnd = context.dp(2) })
            if (second > 0) addView(View(context).apply { background = rounded(context, secondColor) },
                LinearLayout.LayoutParams(0, height, (second / total).toFloat()))
        }

    /** Two or three pills sharing a row equally. */
    fun pillRow(context: Context, pills: List<View>): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        pills.forEachIndexed { index, pill ->
            addView(pill, LinearLayout.LayoutParams(0, -2, 1f).apply {
                if (index < pills.lastIndex) marginEnd = context.dp(8)
            })
        }
    }

    /**
     * One list line as in the design: a title, a quiet second line, and an amount on the right
     * (baht, a due date) when there is one. No emoji badge: the page title already says what it is.
     */
    fun row(
        context: Context,
        title: String,
        subtitle: String?,
        amount: String? = null,
        accent: Int = AutoBridgeDesign.ACCENT,
        onClick: (() -> Unit)? = null
    ): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = context.dp(64)
        setPadding(context.dp(16), context.dp(10), context.dp(16), context.dp(10))
        background = if (onClick != null) {
            AutoBridgeDesign.tappable(context, AutoBridgeDesign.SURFACE, 16, accent, android.graphics.Color.TRANSPARENT)
        } else {
            AutoBridgeDesign.surface(context, AutoBridgeDesign.SURFACE, 16, android.graphics.Color.TRANSPARENT)
        }
        if (onClick != null) setOnClickListener { onClick() }
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(text(context, title, 15f).apply { maxLines = 2 })
            if (!subtitle.isNullOrBlank()) addView(text(context, subtitle, 12f, AutoBridgeDesign.TEXT_MUTED, top = 2))
        }, LinearLayout.LayoutParams(0, -2, 1f))
        if (amount != null) addView(text(context, amount, 15f, bold = true).apply { setPadding(context.dp(12), 0, 0, 0) })
    }

    /** "● Fuel ฿2,480   ● Other ฿970": what each colour of a [splitBar] stands for. */
    fun legend(context: Context, items: List<Pair<Int, String>>): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, context.dp(8), 0, 0)
        items.forEach { (color, label) ->
            addView(View(context).apply { background = rounded(context, color) },
                LinearLayout.LayoutParams(context.dp(8), context.dp(8)).apply { marginEnd = context.dp(6) })
            addView(text(context, label, 13f, AutoBridgeDesign.TEXT_MUTED), LinearLayout.LayoutParams(-2, -2).apply { marginEnd = context.dp(16) })
        }
    }

    /** The coloured square behind a tool's icon, as on the My Car tiles. */
    fun iconChip(context: Context, icon: Int, color: Int, size: Int = 44): View = FrameLayout(context).apply {
        background = AutoBridgeDesign.surface(context, AutoBridgeDesign.tint(color, 0.16f), 12, android.graphics.Color.TRANSPARENT)
        addView(android.widget.ImageView(context).apply {
            setImageResource(icon)
            imageTintList = android.content.res.ColorStateList.valueOf(color)
        }, FrameLayout.LayoutParams(context.dp(size / 2), context.dp(size / 2), Gravity.CENTER))
        layoutParams = LinearLayout.LayoutParams(context.dp(size), context.dp(size))
    }

    /** A round icon button, 56 dp by default: the call button of an emergency line. */
    fun roundButton(context: Context, icon: Int, color: Int, description: String, size: Int = 56, onClick: () -> Unit): View =
        FrameLayout(context).apply {
            background = android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(AutoBridgeDesign.tint(color, 0.3f)),
                GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(AutoBridgeDesign.tint(color, 0.16f))
                },
                null
            )
            contentDescription = description
            isClickable = true
            setOnClickListener { onClick() }
            addView(android.widget.ImageView(context).apply {
                setImageResource(icon)
                imageTintList = android.content.res.ColorStateList.valueOf(color)
            }, FrameLayout.LayoutParams(context.dp(24), context.dp(24), Gravity.CENTER))
            layoutParams = LinearLayout.LayoutParams(context.dp(size), context.dp(size))
        }

    /**
     * A small pill added to the right of a page header, for a setting changed rarely (fuel or EV).
     * [header] is what [AutoBridgeDesign.header] returned.
     */
    fun headerChip(context: Context, header: View, label: String, accent: Int, onClick: () -> Unit): View {
        (header as? LinearLayout)?.addView(
            AutoBridgeDesign.pill(context, label, accent = accent, onClick = onClick),
            LinearLayout.LayoutParams(-2, -2)
        )
        return header
    }

    private fun rounded(context: Context, color: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = context.dp(3).toFloat()
    }
}
