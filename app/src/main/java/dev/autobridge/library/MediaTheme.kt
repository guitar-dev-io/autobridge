package dev.autobridge.library

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import dev.autobridge.design.ThemeHolder
import dev.autobridge.design.ThemePalette
import dev.autobridge.design.ThemeResolver
import dev.autobridge.ui.AppFonts

/**
 * The redesign's component states for the phone media screens (Radio, Music, TV, the player
 * settings), read from the current [ThemeHolder.palette] so Light, AMOLED, presets and accents all
 * apply.
 *
 * One accent per screen: controls (chips, tabs, the play button, selection rings) take the user's
 * accent; a section hue (radio amber, TV blue) only colours its icon chip.
 */
internal object MediaTheme {
    val palette: ThemePalette get() = ThemeHolder.palette

    /** Weight 600, the redesign's selected-label weight. */
    val SEMIBOLD: Typeface? get() = AppFonts.weight(600)
    val REGULAR: Typeface? get() = AppFonts.weight(500)

    private fun Context.px(dp: Float): Int = (dp * resources.displayMetrics.density).toInt().coerceAtLeast(1)

    /**
     * A chip or segment: selected = accent outline + accentSoft fill; otherwise a hairline outline
     * on nothing. Ripple in the accent either way.
     */
    fun chipBackground(context: Context, selected: Boolean, radiusDp: Float): Drawable {
        val p = palette
        return RippleDrawable(
            ColorStateList.valueOf(ThemeResolver.withAlpha(p.accent, 0x38)),
            GradientDrawable().apply {
                cornerRadius = radiusDp * context.resources.displayMetrics.density
                setColor(if (selected) p.accentSoft else 0x00000000)
                setStroke(context.px(1f), if (selected) p.accent else p.hairline)
            },
            null
        )
    }

    /** Text colour and weight for a chip label in [selected] state. */
    fun styleChipLabel(label: TextView, selected: Boolean) {
        val p = palette
        label.setTextColor(if (selected) p.accent else p.textSecondary)
        label.typeface = if (selected) SEMIBOLD else REGULAR
    }

    /** A card that is the one playing: accentSoft fill and an accent ring; else the plain card. */
    fun cardBackground(context: Context, selected: Boolean, radiusDp: Float): Drawable {
        val p = palette
        return RippleDrawable(
            ColorStateList.valueOf(ThemeResolver.withAlpha(p.accent, 0x38)),
            GradientDrawable().apply {
                cornerRadius = radiusDp * context.resources.displayMetrics.density
                if (selected) {
                    setColor(p.accentSoft)
                    setStroke(context.px(2f), p.accent)
                } else {
                    setColor(p.surface)
                }
            },
            null
        )
    }

    /** A filled round button in the accent, for the screen's one primary control. */
    fun accentCircle(): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(palette.accent)
    }

    /**
     * One loading row as the design draws it: a skeleton square and two skeleton bars, 64dp tall,
     * in place of a spinner. Not focusable, and hidden from accessibility.
     */
    fun skeletonRow(context: Context): View = LinearLayout(context).apply {
        val p = palette
        gravity = android.view.Gravity.CENTER_VERTICAL
        minimumHeight = context.px(64f)
        setPadding(context.px(14f), context.px(8f), context.px(14f), context.px(8f))
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        fun block(radius: Float) = GradientDrawable().apply {
            setColor(p.skeleton)
            cornerRadius = radius * context.resources.displayMetrics.density
        }
        addView(View(context).apply { background = block(12f) },
            LinearLayout.LayoutParams(context.px(44f), context.px(44f)).apply { marginEnd = context.px(14f) })
        // The bars are 55% and 35% of the text column, as in the design.
        fun bar(fraction: Float, heightDp: Float, topDp: Float) = LinearLayout(context).apply {
            weightSum = 1f
            addView(View(context).apply { background = block(6f) }, LinearLayout.LayoutParams(0, -1, fraction))
        }.let { it to LinearLayout.LayoutParams(-1, context.px(heightDp)).apply { topMargin = context.px(topDp) } }
        val bars = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        bar(0.55f, 12f, 0f).let { (v, lp) -> bars.addView(v, lp) }
        bar(0.35f, 10f, 8f).let { (v, lp) -> bars.addView(v, lp) }
        addView(bars, LinearLayout.LayoutParams(0, -2, 1f))
    }

    /**
     * [count] skeleton rows in a card, the loading state for a list. Announced once to TalkBack as
     * [description] instead of row by row.
     */
    fun skeletonList(context: Context, count: Int, description: String): View = LinearLayout(context).apply {
        val p = palette
        orientation = LinearLayout.VERTICAL
        contentDescription = description
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        background = GradientDrawable().apply {
            setColor(p.cardFill)
            setStroke(context.px(1f), p.cardEdge)
            cornerRadius = 16f * context.resources.displayMetrics.density
        }
        repeat(count) { index ->
            if (index > 0) addView(View(context).apply { setBackgroundColor(p.hairline) }, LinearLayout.LayoutParams(-1, context.px(1f)))
            addView(skeletonRow(context), LinearLayout.LayoutParams(-1, -2))
        }
    }
}
