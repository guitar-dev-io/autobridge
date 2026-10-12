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

    /** Up to three figures side by side, each a small label over its value. */
    fun stats(context: Context, cells: List<Pair<String, String>>): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        cells.forEach { (label, value) ->
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(text(context, label, 12f, AutoBridgeDesign.TEXT_MUTED))
                addView(text(context, value, 20f, bold = true, top = 2).apply { maxLines = 1 })
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

    private fun rounded(context: Context, color: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = context.dp(3).toFloat()
    }
}
