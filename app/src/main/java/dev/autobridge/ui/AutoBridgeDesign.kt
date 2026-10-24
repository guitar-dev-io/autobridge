package dev.autobridge.ui

import android.app.Activity
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
import android.view.ViewTreeObserver
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import dev.autobridge.R
import dev.autobridge.design.DesignTokens
import dev.autobridge.design.ScriptTracking
import dev.autobridge.design.ThemeHolder

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

    // The surface, text and accent roles follow Settings > Appearance: they read the palette
    // ThemeHolder resolved from the user's choices (dev.autobridge.design.ThemeResolver), so they
    // are getters, not constants. Section accents below stay fixed identity colours.
    val INK: Int get() = ThemeHolder.palette.background
    val SURFACE: Int get() = ThemeHolder.palette.surface
    val SURFACE_RAISED: Int get() = ThemeHolder.palette.raised
    val HAIRLINE: Int get() = ThemeHolder.palette.hairline
    val TEXT: Int get() = ThemeHolder.palette.text
    val TEXT_MUTED: Int get() = ThemeHolder.palette.textSecondary
    val ACCENT: Int get() = ThemeHolder.palette.accent
    const val ACCENT_SOFT = DesignTokens.Phone.ACCENT_SOFT
    val DANGER: Int get() = ThemeHolder.palette.danger

    /** "Parked" / "connected" status colour, shared by Home, Control, Car & Connection and Duo Screen. */
    const val ACCENT_ONLINE = DesignTokens.Phone.ACCENT_ONLINE

    /**
     * Signal colours for a checked channel: green answers, amber answers slowly, and a dead one
     * takes [DANGER]. Green reading as "go" is the one colour convention a driver does not have to
     * learn, and the head unit shows the same three through `CarColor` (see `CarIptvCheck`).
     */
    const val SIGNAL_GOOD = DesignTokens.Phone.SIGNAL_GOOD
    const val SIGNAL_SLOW = DesignTokens.Phone.SIGNAL_SLOW

    /** Per-section accents. Kept here so the phone and any future surface agree on them. */
    const val ACCENT_TV = DesignTokens.Phone.ACCENT_TV
    const val ACCENT_RADIO = DesignTokens.Phone.ACCENT_RADIO
    const val ACCENT_WEB = DesignTokens.Phone.ACCENT_WEB
    const val ACCENT_VIDEO = DesignTokens.Phone.ACCENT_VIDEO
    const val ACCENT_FILES = DesignTokens.Phone.ACCENT_FILES
    const val ACCENT_FAVORITE = DesignTokens.Phone.ACCENT_FAVORITE
    const val ACCENT_SYSTEM = DesignTokens.Phone.ACCENT_SYSTEM

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
        actions: List<HeaderAction> = emptyList(),
        logo: Int? = null
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
        if (logo != null) {
            row.addView(ImageView(context).apply {
                setImageResource(logo)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(context.dp(40), context.dp(40)).apply {
                marginEnd = context.dp(12)
            })
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
                    tint = action.tint,
                    onClick = action.onClick
                ).also { it.tag = action.tag },
                LinearLayout.LayoutParams(context.dp(42), context.dp(42)).apply {
                    if (index != actions.lastIndex) marginEnd = context.dp(6)
                }
            )
        }
        return row
    }

    /**
     * One trailing header button. [filled] marks the screen's primary action. [tint] overrides the
     * glyph's colour, for a button whose own state is worth reading at a glance (a signal icon,
     * say) rather than always looking like plain chrome. [tag] lets a caller find that button again
     * after the header is built, to update [tint] live without re-rendering the whole page.
     */
    data class HeaderAction(
        val glyph: String,
        val onClick: () -> Unit,
        val filled: Boolean = false,
        val tint: Int? = null,
        val tag: String? = null
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
            letterSpacing = ScriptTracking.letterSpacing(label, 0.06f)
        })
    }

    fun glyphButton(
        context: Context,
        glyph: String,
        size: Float,
        filled: Boolean = false,
        tint: Int? = null,
        onClick: () -> Unit
    ): TextView = TextView(context).apply {
        text = glyph
        textSize = size
        gravity = Gravity.CENTER
        setTextColor(tint ?: if (filled) INK else TEXT)
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

    /**
     * Quiet uppercase divider label between groups of content. Tracked out in Latin only: Thai
     * gets no letter spacing, which would split its vowels from their consonants ([ScriptTracking]).
     */
    fun sectionLabel(context: Context, text: String): TextView = TextView(context).apply {
        this.text = text.uppercase()
        textSize = 11f
        setTextColor(TEXT_MUTED)
        letterSpacing = ScriptTracking.letterSpacing(text, 0.14f)
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
     *
     * [trailing] is a glyph in a fixed 40dp square. For a word, use [actionLabel] instead: a pill as
     * wide as its text, so a label like "Grant in Shizuku" or a Thai one is never broken mid-word.
     * [subtitleMaxLines] lets a row whose subtitle is the point (a reason, an instruction) wrap.
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
        guide: String? = null,
        subtitleMaxLines: Int = 1,
        actionLabel: String? = null,
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
            maxLines = subtitleMaxLines
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, context.dp(3), 0, 0)
        })
        text.addView(guideLabel(context, guide))
        addView(text, LinearLayout.LayoutParams(0, -2, 1f))

        if (!actionLabel.isNullOrBlank()) addView(
            pill(context, actionLabel, accent = accent) { (onTrailing ?: onClick)() }.apply {
                (this as TextView).maxLines = 1
            },
            LinearLayout.LayoutParams(-2, -2)
        )

        if (!trailing.isNullOrBlank()) addView(
            glyphButton(context, trailing, 17f) { (onTrailing ?: onClick)() },
            LinearLayout.LayoutParams(context.dp(40), context.dp(40))
        )
    }

    /**
     * A grid cell for one entry: the playlist's own logo across the top, the title under it, and a
     * coloured status line when there is something to say about the address.
     *
     * The row ([contentRow]) and the tile are the same content at two densities. A tile is for
     * things that carry artwork worth seeing — channels, stations, films — because the logo is what
     * a user recognises before they have read anything; a row is for everything whose identity is
     * its text. The logo is fitted, never cropped: a channel wordmark cut in half reads as a
     * broken image, and most of them arrive as wide transparent PNGs.
     *
     * [status] is text plus its colour, and the view holding it is tagged [R.id.autobridge_status_label]
     * so a caller can write into a page that is already on screen — see [statusLabel].
     */
    fun contentTile(
        context: Context,
        title: String,
        subtitle: String = "",
        accent: Int,
        artworkUrl: String? = null,
        badgeText: String? = null,
        status: Pair<String, Int>? = null,
        corner: Pair<String, () -> Unit>? = null,
        onLongClick: (() -> Unit)? = null,
        guide: String? = null,
        onClick: () -> Unit
    ): View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = tappable(context, SURFACE, 18, accent)
        isClickable = true
        isFocusable = true
        contentDescription = title
        setPadding(context.dp(10), context.dp(10), context.dp(10), context.dp(10))
        setOnClickListener { onClick() }
        if (onLongClick != null) setOnLongClickListener { onLongClick(); true }

        val artwork = FrameLayout(context).apply {
            background = surface(context, tint(accent, 0.14f), 12, tint(accent, 0.26f))
            clipToOutline = true
        }
        val initial = TextView(context).apply {
            text = (badgeText ?: title.trim().take(1)).uppercase()
            textSize = 26f
            gravity = Gravity.CENTER
            setTextColor(accent)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        artwork.addView(initial, FrameLayout.LayoutParams(-1, -1))
        if (!artworkUrl.isNullOrBlank()) {
            val image = ImageView(context).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                visibility = View.GONE
                setPadding(context.dp(10), context.dp(10), context.dp(10), context.dp(10))
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            artwork.addView(image, FrameLayout.LayoutParams(-1, -1))
            // The letter stays until a bitmap actually arrives, so a failed logo is never a hole.
            ImageLoader.load(context, artworkUrl, image) {
                initial.visibility = View.GONE
                image.visibility = View.VISIBLE
            }
        }
        addView(artwork, LinearLayout.LayoutParams(-1, context.dp(104)))

        // The star (or ›) sits beside the title rather than over the artwork, so it never covers
        // the logo the user is looking for.
        val titleRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, context.dp(10), 0, 0)
        }
        titleRow.addView(TextView(context).apply {
            this.text = title
            textSize = 15f
            setTextColor(TEXT)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, -2, 1f))
        if (corner != null) titleRow.addView(
            glyphButton(context, corner.first, 16f, tint = if (corner.first == "★") ACCENT_FAVORITE else null, onClick = corner.second),
            LinearLayout.LayoutParams(context.dp(36), context.dp(36)).apply {
                marginStart = context.dp(4)
                topMargin = -context.dp(6)
            }
        )
        addView(titleRow, LinearLayout.LayoutParams(-1, -2))

        if (subtitle.isNotBlank()) addView(TextView(context).apply {
            this.text = subtitle
            textSize = 12f
            setTextColor(TEXT_MUTED)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, context.dp(2), 0, 0)
        }, LinearLayout.LayoutParams(-1, -2))

        addView(guideLabel(context, guide), LinearLayout.LayoutParams(-1, -2))

        // The check result in words, led by its coloured dot. Built even when there is nothing to
        // show yet: a result that lands later is written into this view, and a page that is
        // already scrolled must not be rebuilt under the user.
        addView(TextView(context).apply {
            id = R.id.autobridge_status_label
            textSize = 12f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, context.dp(4), 0, 0)
            status?.let { (text, color) ->
                this.text = statusText(text)
                setTextColor(color)
            }
            visibility = if (status == null || status.first.isBlank()) View.GONE else View.VISIBLE
        }, LinearLayout.LayoutParams(-1, -2))
    }

    private fun statusText(text: String) = "●  $text"

    /**
     * The programme-guide line of a [contentRow] or [contentTile]: what is on now, in the accent
     * text colour so it reads apart from the muted subtitle. Built even while empty, for the same
     * reason as the status line — a guide that lands later is written in with [setGuide].
     */
    private fun guideLabel(context: Context, guide: String?): TextView = TextView(context).apply {
        id = R.id.autobridge_guide_label
        textSize = 12f
        setTextColor(TEXT)
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        setPadding(0, context.dp(3), 0, 0)
        text = guide.orEmpty()
        visibility = if (guide.isNullOrBlank()) View.GONE else View.VISIBLE
    }

    /** Writes [text] into a row's or tile's guide line, or hides it when [text] is blank. */
    fun setGuide(view: View, text: String?) {
        val label = view.findViewById<TextView>(R.id.autobridge_guide_label) ?: return
        if (label.text.toString() == text.orEmpty()) return
        label.text = text.orEmpty()
        label.visibility = if (text.isNullOrBlank()) View.GONE else View.VISIBLE
    }

    /** The status line inside a [contentTile], for writing a result into a page already drawn. */
    fun statusLabel(tile: View): TextView? = tile.findViewById(R.id.autobridge_status_label)

    /** Writes [text] in [color] into a tile's status line, or hides it when [text] is blank. */
    fun setStatus(tile: View, text: String, color: Int) {
        val label = statusLabel(tile) ?: return
        label.text = statusText(text)
        label.setTextColor(color)
        label.visibility = if (text.isBlank()) View.GONE else View.VISIBLE
    }

    /**
     * [tiles] laid out in rows of [columns], returned as views a page body stacks like any other
     * row — so a grid page needs no new container and keeps the one scroll view.
     *
     * A short last row holds its cell width with empty space instead of stretching its tile across
     * the page, which is what makes an odd channel count look like a grid rather than a mistake.
     */
    fun grid(context: Context, tiles: List<View>, columns: Int = 2, gap: Int = 8): List<View> =
        tiles.chunked(columns.coerceAtLeast(1)).map { cells ->
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                cells.forEachIndexed { index, tile ->
                    // MATCH_PARENT in a wrap_content row is the equal-height trick: the row
                    // measures to its tallest cell and the others stretch to it.
                    addView(tile, LinearLayout.LayoutParams(0, -1, 1f).apply {
                        marginStart = if (index == 0) 0 else context.dp(gap)
                    })
                }
                repeat(columns - cells.size) {
                    addView(View(context), LinearLayout.LayoutParams(0, -1, 1f).apply {
                        marginStart = context.dp(gap)
                    })
                }
            }
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
     *
     * [overlay], when given, floats at the end edge of the scroll area instead of scrolling with
     * it — a right-edge letter index, say. The scroll view itself carries [R.id.autobridge_page_scroll]
     * so a caller that built [overlay] can find it afterwards and scroll to a position; see
     * [pageScroll].
     */
    fun page(
        context: Context,
        header: View,
        pinned: List<View> = emptyList(),
        body: View,
        bottomBar: View? = null,
        applyInsets: Boolean = true,
        overlay: View? = null
    ): View {
        val root = FrameLayout(context).apply { setBackgroundColor(INK); setTag(R.id.autobridge_page_root, applyInsets) }
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
            id = R.id.autobridge_page_scroll
            isVerticalScrollBarEnabled = false
            clipToPadding = false
            addView(body, ViewGroup.LayoutParams(-1, -2))
        }
        if (overlay == null) {
            column.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        } else {
            val scrollArea = FrameLayout(context)
            scrollArea.addView(scroll, FrameLayout.LayoutParams(-1, -1))
            scrollArea.addView(overlay, FrameLayout.LayoutParams(-2, -1, Gravity.END))
            column.addView(scrollArea, LinearLayout.LayoutParams(-1, 0, 1f))
        }
        if (bottomBar != null) column.addView(bottomBar, LinearLayout.LayoutParams(-1, -2))
        root.addView(column, FrameLayout.LayoutParams(-1, -1))
        return root
    }

    /**
     * Shows [page] (built by [page]) as [activity]'s content in place of the page already there.
     * Pages are rebuilt whole when their state changes, and a bare setContentView drew the new one
     * scrolled to the top (restoring the position in a posted callback came a frame late) and
     * without the system-bar padding until its insets arrived, so every rebuild flashed. This
     * carries the padding over and, with [keepScroll], restores the scroll position before the
     * first frame is drawn. Pass keepScroll = false when the rebuild shows different content.
     */
    fun showPage(activity: Activity, page: View, keepScroll: Boolean = true) {
        val previous = activity.findViewById<ViewGroup>(android.R.id.content)?.getChildAt(0)
        val replacing = previous != null && previous !== page && previous.getTag(R.id.autobridge_page_root) != null
        if (replacing && previous!!.getTag(R.id.autobridge_page_root) == true && page.getTag(R.id.autobridge_page_root) == true) {
            page.setPadding(previous.paddingLeft, previous.paddingTop, previous.paddingRight, previous.paddingBottom)
        }
        val y = if (replacing && keepScroll) pageScroll(previous!!)?.scrollY ?: 0 else 0
        activity.setContentView(page)
        val scroll = pageScroll(page) ?: return
        if (y <= 0) return
        scroll.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                scroll.viewTreeObserver.removeOnPreDrawListener(this)
                scroll.scrollTo(0, y)
                return true
            }
        })
    }

    /** The scroll view [page] built around its body, so a caller can scroll to a position in it. */
    fun pageScroll(page: View): ScrollView? = page.findViewById(R.id.autobridge_page_scroll)

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
