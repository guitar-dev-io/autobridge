package dev.autobridge.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * AutoBridge's own phone design language, used by every screen the app draws itself.
 *
 * The shape of it: an ink-dark base with raised surfaces and hairline borders instead of bright
 * outlines, a tinted rounded-square badge carrying each section's accent colour, left-aligned
 * titles with a quiet caption underneath, and a ripple on everything tappable. Sections keep their
 * accent colour all the way down — the Radio badge on the home grid is the same amber that marks
 * the Radio header and its rows — so the user can tell where they are without reading.
 *
 * Everything is built programmatically, matching the rest of this codebase, and every helper takes
 * a [Context] so the same builders serve an Activity or a dialog.
 */
object AutoBridgeDesign {

    // ----- Tokens -----

    const val INK = 0xFF0B0E14.toInt()
    const val SURFACE = 0xFF141924.toInt()
    const val SURFACE_RAISED = 0xFF1C2331.toInt()
    const val HAIRLINE = 0xFF26304A.toInt()
    const val TEXT = 0xFFEAF0FA.toInt()
    const val TEXT_MUTED = 0xFF8494B0.toInt()
    const val ACCENT = 0xFF4C7DF0.toInt()
    const val ACCENT_SOFT = 0xFF33C9D6.toInt()
    const val DANGER = 0xFFFF6B81.toInt()

    /** Per-section accents. Kept here so the phone and any future surface agree on them. */
    const val ACCENT_TV = 0xFF6EA8FF.toInt()
    const val ACCENT_RADIO = 0xFFFFB35C.toInt()
    const val ACCENT_WEB = 0xFF7DD3C0.toInt()
    const val ACCENT_VIDEO = 0xFFFF7A8A.toInt()
    const val ACCENT_FILES = 0xFF9BE08A.toInt()
    const val ACCENT_FAVORITE = 0xFFFF8FB1.toInt()
    const val ACCENT_SYSTEM = 0xFFB39DFF.toInt()

    fun Context.dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    /** Blends [color] onto the ink base at [alpha], for badge and chip fills. */
    fun tint(color: Int, alpha: Float): Int = Color.argb(
        (alpha * 255).toInt().coerceIn(0, 255),
        Color.red(color), Color.green(color), Color.blue(color)
    )

    fun surface(context: Context, fill: Int, radius: Int, stroke: Int = HAIRLINE): GradientDrawable =
        GradientDrawable().apply {
            setColor(fill)
            cornerRadius = context.dp(radius).toFloat()
            if (stroke != Color.TRANSPARENT) setStroke(context.dp(1).coerceAtLeast(1), stroke)
        }

    /** A tappable background: the surface plus a ripple in the section's accent. */
    fun tappable(context: Context, fill: Int, radius: Int, accent: Int = ACCENT, stroke: Int = HAIRLINE) =
        RippleDrawable(
            ColorStateList.valueOf(tint(accent, 0.22f)),
            surface(context, fill, radius, stroke),
            null
        )

    // ----- Building blocks -----

    /**
     * Screen header. [subtitle] is the quiet line under the title, [chip] an optional status pill
     * and [action] an optional round trailing button (glyph to handler).
     */
    fun header(
        context: Context,
        title: String,
        subtitle: String? = null,
        onBack: (() -> Unit)? = null,
        chip: String? = null,
        actions: List<HeaderAction> = emptyList()
    ): View {
        val row = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(
                context.dp(if (onBack == null) 20 else 8), context.dp(14),
                context.dp(16), context.dp(10)
            )
        }
        if (onBack != null) {
            row.addView(glyphButton(context, "‹", 30f) { onBack() },
                LinearLayout.LayoutParams(context.dp(44), context.dp(44)))
        }
        val text = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        text.addView(TextView(context).apply {
            this.text = title
            textSize = 26f
            setTextColor(TEXT)
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        })
        if (!subtitle.isNullOrBlank()) text.addView(TextView(context).apply {
            this.text = subtitle
            textSize = 13f
            setTextColor(TEXT_MUTED)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, context.dp(2), 0, 0)
        })
        row.addView(text, LinearLayout.LayoutParams(0, -2, 1f).apply {
            marginStart = context.dp(if (onBack == null) 0 else 4)
        })

        if (!chip.isNullOrBlank()) row.addView(statusChip(context, chip),
            LinearLayout.LayoutParams(-2, context.dp(32)).apply { marginEnd = context.dp(8) })
        // Only an action that asks to be primary gets the filled treatment; an overflow menu stays
        // a quiet circle so it does not read as the thing to press on the screen.
        actions.forEachIndexed { index, action ->
            row.addView(
                glyphButton(
                    context,
                    action.glyph,
                    if (action.filled) 18f else 20f,
                    filled = action.filled,
                    onClick = action.onClick
                ),
                LinearLayout.LayoutParams(context.dp(42), context.dp(42)).apply {
                    if (index != actions.lastIndex) marginEnd = context.dp(6)
                }
            )
        }
        return row
    }

    /** One trailing header button. [filled] marks the screen's primary action. */
    data class HeaderAction(
        val glyph: String,
        val onClick: () -> Unit,
        val filled: Boolean = false
    )

    /** A live-state pill: a small accent dot plus one short label. */
    fun statusChip(context: Context, label: String): View = LinearLayout(context).apply {
        gravity = Gravity.CENTER
        background = surface(context, tint(ACCENT_SOFT, 0.14f), 16, tint(ACCENT_SOFT, 0.35f))
        setPadding(context.dp(10), 0, context.dp(12), 0)
        addView(View(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(ACCENT_SOFT)
            }
        }, LinearLayout.LayoutParams(context.dp(7), context.dp(7)).apply {
            marginEnd = context.dp(7)
            gravity = Gravity.CENTER_VERTICAL
        })
        addView(TextView(context).apply {
            text = label
            textSize = 12f
            setTextColor(TEXT)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            letterSpacing = 0.06f
        })
    }

    fun glyphButton(
        context: Context,
        glyph: String,
        size: Float,
        filled: Boolean = false,
        onClick: () -> Unit
    ): TextView = TextView(context).apply {
        text = glyph
        textSize = size
        gravity = Gravity.CENTER
        setTextColor(if (filled) INK else TEXT)
        isClickable = true
        isFocusable = true
        contentDescription = glyph
        background = if (filled) {
            RippleDrawable(
                ColorStateList.valueOf(tint(Color.WHITE, 0.3f)),
                GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(ACCENT_SOFT) },
                null
            )
        } else {
            RippleDrawable(
                ColorStateList.valueOf(tint(Color.WHITE, 0.16f)),
                GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(SURFACE) },
                null
            )
        }
        setOnClickListener { onClick() }
    }

    /** Quiet uppercase divider label between groups of content. */
    fun sectionLabel(context: Context, text: String): TextView = TextView(context).apply {
        this.text = text.uppercase()
        textSize = 11f
        setTextColor(TEXT_MUTED)
        letterSpacing = 0.14f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setPadding(context.dp(6), context.dp(18), context.dp(6), context.dp(8))
    }

    /**
     * A home-grid card: tinted accent badge, title, and a caption line. Left-aligned rather than
     * centred, which is what separates this grid from the reference launcher's symmetric tiles.
     */
    fun sectionCard(
        context: Context,
        title: String,
        caption: String,
        icon: Int,
        accent: Int,
        onClick: () -> Unit
    ): View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = tappable(context, SURFACE, 20, accent)
        isClickable = true
        isFocusable = true
        contentDescription = title
        setPadding(context.dp(14), context.dp(14), context.dp(14), context.dp(14))
        setOnClickListener { onClick() }

        addView(FrameLayout(context).apply {
            background = surface(context, tint(accent, 0.16f), 14, tint(accent, 0.3f))
            addView(ImageView(context).apply {
                setImageResource(icon)
                setColorFilter(accent)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, FrameLayout.LayoutParams(context.dp(24), context.dp(24), Gravity.CENTER))
        }, LinearLayout.LayoutParams(context.dp(44), context.dp(44)))

        addView(View(context), LinearLayout.LayoutParams(0, 0, 1f))

        addView(TextView(context).apply {
            text = title
            textSize = 16f
            setTextColor(TEXT)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        })
        addView(TextView(context).apply {
            text = caption
            textSize = 12f
            setTextColor(TEXT_MUTED)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, context.dp(2), 0, 0)
        })
    }

    /**
     * A content row: optional square artwork (or an accent-tinted initial when there is no image),
     * title, subtitle, and an optional trailing glyph with its own tap target.
     */
    fun contentRow(
        context: Context,
        title: String,
        subtitle: String,
        accent: Int,
        artworkUrl: String? = null,
        badgeText: String? = null,
        badgeIcon: Int = 0,
        trailing: String? = null,
        onTrailing: (() -> Unit)? = null,
        onClick: () -> Unit
    ): View = LinearLayout(context).apply {
        gravity = Gravity.CENTER_VERTICAL
        background = tappable(context, SURFACE, 16, accent)
        isClickable = true
        isFocusable = true
        contentDescription = title
        setPadding(context.dp(10), context.dp(10), context.dp(8), context.dp(10))
        setOnClickListener { onClick() }

        val artwork = FrameLayout(context).apply {
            background = surface(context, tint(accent, 0.16f), 12, tint(accent, 0.28f))
            clipToOutline = true
        }
        val initial = TextView(context).apply {
            text = (badgeText ?: title.trim().take(1)).uppercase()
            textSize = 17f
            gravity = Gravity.CENTER
            setTextColor(accent)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        if (badgeIcon != 0) {
            artwork.addView(ImageView(context).apply {
                setImageResource(badgeIcon)
                setColorFilter(accent)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, FrameLayout.LayoutParams(context.dp(22), context.dp(22), Gravity.CENTER))
        } else {
            artwork.addView(initial, FrameLayout.LayoutParams(-1, -1))
        }
        if (!artworkUrl.isNullOrBlank()) {
            val image = ImageView(context).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                visibility = View.GONE
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            artwork.addView(image, FrameLayout.LayoutParams(-1, -1))
            // The letter stays until a bitmap actually arrives, so a failed logo is never a hole.
            ImageLoader.load(context, artworkUrl, image) {
                initial.visibility = View.GONE
                image.visibility = View.VISIBLE
            }
        }
        addView(artwork, LinearLayout.LayoutParams(context.dp(46), context.dp(46)))

        val text = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(context.dp(12), 0, context.dp(8), 0)
        }
        text.addView(TextView(context).apply {
            this.text = title
            textSize = 15f
            setTextColor(TEXT)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        })
        if (subtitle.isNotBlank()) text.addView(TextView(context).apply {
            this.text = subtitle
            textSize = 12f
            setTextColor(TEXT_MUTED)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, context.dp(3), 0, 0)
        })
        addView(text, LinearLayout.LayoutParams(0, -2, 1f))

        if (!trailing.isNullOrBlank()) addView(
            glyphButton(context, trailing, 17f) { (onTrailing ?: onClick)() },
            LinearLayout.LayoutParams(context.dp(40), context.dp(40))
        )
    }

    /** Pill button used for page-level actions (Add source, Refresh, Clear…). */
    fun pill(
        context: Context,
        label: String,
        primary: Boolean = false,
        accent: Int = ACCENT,
        onClick: () -> Unit
    ): View = TextView(context).apply {
        text = label
        textSize = 13f
        gravity = Gravity.CENTER
        setTextColor(if (primary) INK else TEXT)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        isClickable = true
        isFocusable = true
        setPadding(context.dp(14), context.dp(10), context.dp(14), context.dp(10))
        background = RippleDrawable(
            ColorStateList.valueOf(tint(if (primary) Color.WHITE else accent, 0.24f)),
            surface(
                context,
                if (primary) accent else SURFACE,
                18,
                if (primary) accent else HAIRLINE
            ),
            null
        )
        setOnClickListener { onClick() }
    }

    /** Rounded search box that reports every keystroke; used to filter long channel lists. */
    fun searchField(context: Context, hint: String, initial: String, onChange: (String) -> Unit): EditText =
        EditText(context).apply {
            this.hint = hint
            setText(initial)
            setSelection(initial.length)
            setSingleLine()
            textSize = 15f
            setTextColor(TEXT)
            setHintTextColor(TEXT_MUTED)
            background = surface(context, SURFACE, 16)
            setPadding(context.dp(14), context.dp(12), context.dp(14), context.dp(12))
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                override fun afterTextChanged(s: Editable?) = onChange(s?.toString().orEmpty())
            })
        }

    /** Centred empty state with an optional call to action. */
    fun emptyState(
        context: Context,
        title: String,
        message: String,
        action: Pair<String, () -> Unit>? = null,
        accent: Int = ACCENT
    ): View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(context.dp(24), context.dp(48), context.dp(24), context.dp(24))
        addView(TextView(context).apply {
            this.text = title
            textSize = 17f
            setTextColor(TEXT)
            gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        })
        addView(TextView(context).apply {
            this.text = message
            textSize = 14f
            setTextColor(TEXT_MUTED)
            gravity = Gravity.CENTER
            setPadding(0, context.dp(8), 0, 0)
        })
        if (action != null) addView(
            pill(context, action.first, primary = true, accent = accent, onClick = action.second),
            LinearLayout.LayoutParams(-2, -2).apply { topMargin = context.dp(20) }
        )
    }

    /**
     * The standard page: ink background, a header, optional pinned content (search, action pills),
     * a scrolling body, and an optional bottom bar the caller owns (the mini player).
     */
    fun page(
        context: Context,
        header: View,
        pinned: List<View> = emptyList(),
        body: View,
        bottomBar: View? = null,
        applyInsets: Boolean = true
    ): View {
        val root = FrameLayout(context).apply { setBackgroundColor(INK) }
        val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        // targetSdk 36 means edge-to-edge on Android 15+, so the page owns its own bar insets;
        // without this the header would sit under the status bar and the bottom bar under the
        // gesture handle. The IME inset is folded in so a search field is never covered.
        if (applyInsets) androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.ime())
            view.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
            insets
        }
        column.addView(header, LinearLayout.LayoutParams(-1, -2))
        pinned.forEachIndexed { index, view ->
            column.addView(view, LinearLayout.LayoutParams(-1, -2).apply {
                marginStart = context.dp(16)
                marginEnd = context.dp(16)
                topMargin = if (index == 0) context.dp(6) else 0
                bottomMargin = context.dp(10)
            })
        }
        val scroll = ScrollView(context).apply {
            isVerticalScrollBarEnabled = false
            clipToPadding = false
            addView(body, ViewGroup.LayoutParams(-1, -2))
        }
        column.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        if (bottomBar != null) column.addView(bottomBar, LinearLayout.LayoutParams(-1, -2))
        root.addView(column, FrameLayout.LayoutParams(-1, -1))
        return root
    }

    /** Vertical container for page body content, with the standard side gutter. */
    fun body(context: Context): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(context.dp(16), 0, context.dp(16), context.dp(20))
    }

    /** Spacing helper so callers do not repeat LayoutParams for stacked rows. */
    fun LinearLayout.stack(view: View, gap: Int = 8) {
        addView(view, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = context.dp(gap) })
    }
}
