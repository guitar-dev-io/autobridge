package dev.autobridge.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import dev.autobridge.ui.AutoBridgeDesign.dp

/**
 * The grouped settings list: a quiet section label over one card that holds several rows, divided by
 * hairlines, instead of a column of separately floating cards.
 *
 * [AutoBridgeDesign.contentRow] is the right shape for a list of *content* — a channel, a recent
 * send — where every row is an independent thing and the gaps between them say so. A settings list
 * is the opposite: the rows under one label belong together, and drawing each with its own border
 * and shadow gap makes a twelve-row page read as twelve unrelated decisions. So the card is the
 * group and the rows live inside it, which is also what makes a section of five rows scan as one
 * block at arm's length.
 *
 * Everything here is a flat row meant to be handed to [group]; none of them draw their own card.
 */
object SettingsUi {

    /** Corner radius of a group card — the 20–24dp band the design tokens give cards. */
    private const val CARD_RADIUS = 20

    /** Side of the tinted icon badge that opens a [row]. */
    private const val BADGE = 40

    /**
     * One titled section: the label, then [rows] stacked inside a single card with a hairline
     * between each pair. Pass an empty [label] for a card with no heading.
     */
    fun group(context: Context, label: String, rows: List<View>): View =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            if (label.isNotBlank()) addView(AutoBridgeDesign.sectionLabel(context, label))
            addView(card(context, rows))
        }

    /** The card itself, without a heading — for a lone group that needs no label. */
    fun card(context: Context, rows: List<View>): View =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = AutoBridgeDesign.surface(context, AutoBridgeDesign.SURFACE, CARD_RADIUS)
            // The rows have square corners; clipping them to the card's keeps a pressed first or
            // last row from painting its ripple outside the rounded edge.
            clipToOutline = true
            rows.forEachIndexed { index, row ->
                if (index > 0) addView(divider(context))
                addView(row, LinearLayout.LayoutParams(-1, -2))
            }
        }

    /** A full-bleed hairline between two rows of the same card. */
    fun divider(context: Context): View = View(context).apply {
        setBackgroundColor(AutoBridgeDesign.HAIRLINE)
        layoutParams = LinearLayout.LayoutParams(-1, maxOf(1, context.dp(1) / 2))
    }

    /**
     * The standard settings row: tinted icon badge, title, caption, and a trailing chevron.
     *
     * [caption] is allowed two lines rather than one — several of these name four things the screen
     * covers ("Player, aspect ratio, picture, subtitle translation") and that sentence is the whole
     * reason the row can be found without opening it.
     */
    fun row(
        context: Context,
        title: String,
        caption: String,
        icon: Int,
        accent: Int,
        onClick: () -> Unit,
    ): View = base(context).apply {
        contentDescription = title
        isClickable = true
        isFocusable = true
        setOnClickListener { onClick() }
        addView(badge(context, icon, accent))
        addView(labels(context, title, caption), LinearLayout.LayoutParams(0, -2, 1f))
        addView(chevron(context))
    }

    /**
     * A row whose answer fits on the row: title on the left, the value it is set to on the right.
     * For settings that are a single choice — the UI language, a named action — where showing the
     * current value is more use than a sentence describing the screen behind it.
     */
    fun valueRow(
        context: Context,
        title: String,
        value: String,
        onClick: () -> Unit,
    ): View = base(context).apply {
        contentDescription = title
        isClickable = true
        isFocusable = true
        setOnClickListener { onClick() }
        addView(
            TextView(context).apply {
                text = title
                textSize = 15f
                setTextColor(AutoBridgeDesign.TEXT)
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            },
            LinearLayout.LayoutParams(-2, -2)
        )
        addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
        addView(
            TextView(context).apply {
                text = value
                textSize = 14f
                setTextColor(AutoBridgeDesign.TEXT_MUTED)
                gravity = Gravity.END
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            },
            LinearLayout.LayoutParams(-2, -2).apply { marginStart = context.dp(8) }
        )
        addView(chevron(context))
    }

    /**
     * A [row] that carries its own switch instead of opening anything. The whole row toggles, so the
     * target is the row and not just the thumb; the switch is not independently clickable for the
     * same reason (two tap targets doing one thing is how a double-toggle happens).
     */
    fun switchRow(
        context: Context,
        title: String,
        caption: String,
        icon: Int,
        accent: Int,
        checked: Boolean,
        onToggle: () -> Unit,
    ): View = base(context).apply {
        contentDescription = title
        isClickable = true
        isFocusable = true
        setOnClickListener { onToggle() }
        addView(badge(context, icon, accent))
        addView(labels(context, title, caption), LinearLayout.LayoutParams(0, -2, 1f))
        addView(
            Switch(context).apply {
                isChecked = checked
                // The row owns the gesture; the switch is the indicator on it.
                isClickable = false
                isFocusable = false
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                thumbTintList = ColorStateList.valueOf(
                    if (checked) AutoBridgeDesign.ACCENT else AutoBridgeDesign.TEXT_MUTED
                )
                trackTintList = ColorStateList.valueOf(
                    if (checked) AutoBridgeDesign.tint(AutoBridgeDesign.ACCENT, 0.5f)
                    else AutoBridgeDesign.HAIRLINE
                )
            },
            LinearLayout.LayoutParams(-2, -2).apply { marginStart = context.dp(8) }
        )
    }

    /**
     * A labelled segmented control: the label on the left, the options as one pill on the right when
     * they fit beside it, which is the shape the design uses for two- and three-way choices
     * (pane count, floating-button side).
     */
    fun <T> segmentRow(
        context: Context,
        title: String,
        options: List<Pair<T, String>>,
        selected: T,
        onPick: (T) -> Unit,
    ): View = base(context).apply {
        addView(
            TextView(context).apply {
                text = title
                textSize = 15f
                setTextColor(AutoBridgeDesign.TEXT)
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            },
            LinearLayout.LayoutParams(0, -2, 1f)
        )
        addView(segments(context, options, selected, onPick), LinearLayout.LayoutParams(-2, -2))
    }

    /**
     * The segmented control on its own, for a caller that needs it under a label rather than beside
     * one — a choice with long option names has no room to sit on the same line as its title.
     */
    fun <T> segments(
        context: Context,
        options: List<Pair<T, String>>,
        selected: T,
        onPick: (T) -> Unit,
    ): View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        background = AutoBridgeDesign.surface(context, AutoBridgeDesign.INK, 14)
        val inset = context.dp(3)
        setPadding(inset, inset, inset, inset)
        options.forEach { (value, label) ->
            val active = value == selected
            addView(
                TextView(context).apply {
                    text = label
                    textSize = 14f
                    gravity = Gravity.CENTER
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    contentDescription = if (active) "$label, selected" else label
                    setTextColor(if (active) Color.WHITE else AutoBridgeDesign.TEXT_MUTED)
                    if (active) typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                    background =
                        if (active) AutoBridgeDesign.surface(
                            context, AutoBridgeDesign.ACCENT, 11, AutoBridgeDesign.ACCENT
                        ) else null
                    minWidth = context.dp(52)
                    setPadding(context.dp(12), context.dp(8), context.dp(12), context.dp(8))
                    setOnClickListener { onPick(value) }
                },
                LinearLayout.LayoutParams(-2, -2)
            )
        }
    }

    /**
     * A slider row: the title with its current value in accent on the right, the track under both.
     *
     * [onRelease] fires on finger-up only — the label follows every drag frame, but whatever the
     * value drives (a live WebView's zoom, a pane's density) is written once, at the end.
     */
    fun sliderRow(
        context: Context,
        title: String,
        steps: Int,
        progress: Int,
        valueText: (Int) -> String,
        onRelease: (Int) -> Unit,
    ): View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(context.dp(14), context.dp(12), context.dp(14), context.dp(12))
        val value = TextView(context).apply {
            text = valueText(progress)
            textSize = 15f
            setTextColor(AutoBridgeDesign.ACCENT)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        }
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(context).apply {
                text = title
                textSize = 15f
                setTextColor(AutoBridgeDesign.TEXT)
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(value, LinearLayout.LayoutParams(-2, -2))
        }, LinearLayout.LayoutParams(-1, -2))
        addView(SeekBar(context).apply {
            max = steps
            this.progress = progress
            contentDescription = title
            progressTintList = ColorStateList.valueOf(AutoBridgeDesign.ACCENT)
            thumbTintList = ColorStateList.valueOf(Color.WHITE)
            progressBackgroundTintList = ColorStateList.valueOf(AutoBridgeDesign.HAIRLINE)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar, at: Int, fromUser: Boolean) {
                    value.text = valueText(at)
                }

                override fun onStartTrackingTouch(bar: SeekBar) = Unit

                override fun onStopTrackingTouch(bar: SeekBar) = onRelease(bar.progress)
            })
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = context.dp(4) })
    }

    /**
     * A row that is only text: a heading and an explanation, for a card whose controls follow it.
     * No badge and no chevron, because it opens nothing.
     */
    fun captionRow(context: Context, title: String, caption: String): View = base(context).apply {
        addView(labels(context, title, caption), LinearLayout.LayoutParams(0, -2, 1f))
    }

    /**
     * A standalone card that reads as a warning or a way out: the text in [accent] over a card
     * tinted the same, outside any group. Used for "End duo screen now" and the like, which are not
     * settings and should not sit in a list of them.
     */
    fun dangerCard(
        context: Context,
        title: String,
        caption: String,
        accent: Int = AutoBridgeDesign.DANGER,
        onClick: () -> Unit,
    ): View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = AutoBridgeDesign.tappable(
            context, AutoBridgeDesign.tint(accent, 0.08f), CARD_RADIUS, accent,
            stroke = AutoBridgeDesign.tint(accent, 0.35f)
        )
        isClickable = true
        isFocusable = true
        contentDescription = title
        setPadding(context.dp(14), context.dp(14), context.dp(14), context.dp(14))
        setOnClickListener { onClick() }
        addView(TextView(context).apply {
            text = title
            textSize = 16f
            setTextColor(accent)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        })
        addView(TextView(context).apply {
            text = caption
            textSize = 13f
            setTextColor(AutoBridgeDesign.tint(accent, 0.75f))
            setPadding(0, context.dp(3), 0, 0)
        })
    }

    // --------------------------------------------------------------------------------- primitives

    /** The row frame every variant above shares: one horizontal line, centred, evenly padded. */
    private fun base(context: Context): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        // Tall enough to clear the 44dp phone touch target with a one-line title.
        minimumHeight = context.dp(56)
        setPadding(context.dp(14), context.dp(12), context.dp(12), context.dp(12))
        background = AutoBridgeDesign.tappable(context, Color.TRANSPARENT, 0, stroke = Color.TRANSPARENT)
    }

    private fun badge(context: Context, icon: Int, accent: Int): View = FrameLayout(context).apply {
        background = AutoBridgeDesign.surface(
            context, AutoBridgeDesign.tint(accent, 0.16f), 12, AutoBridgeDesign.tint(accent, 0.3f)
        )
        addView(ImageView(context).apply {
            setImageResource(icon)
            setColorFilter(accent)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, FrameLayout.LayoutParams(context.dp(21), context.dp(21), Gravity.CENTER))
        layoutParams = LinearLayout.LayoutParams(context.dp(BADGE), context.dp(BADGE))
    }

    private fun labels(context: Context, title: String, caption: String): View =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(context.dp(12), 0, context.dp(8), 0)
            addView(TextView(context).apply {
                text = title
                textSize = 15f
                setTextColor(AutoBridgeDesign.TEXT)
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            })
            if (caption.isNotBlank()) addView(TextView(context).apply {
                text = caption
                textSize = 12.5f
                setTextColor(AutoBridgeDesign.TEXT_MUTED)
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
                setPadding(0, context.dp(2), 0, 0)
            })
        }

    private fun chevron(context: Context): View = TextView(context).apply {
        text = "›"
        textSize = 20f
        gravity = Gravity.CENTER
        setTextColor(AutoBridgeDesign.TEXT_MUTED)
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        layoutParams = LinearLayout.LayoutParams(context.dp(24), -2)
    }
}
