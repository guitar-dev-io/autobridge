package dev.autobridge.browser

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import dev.autobridge.R

/**
 * One shortcut tile on the start page: a popular site, shown as a glyph chip plus a label.
 *
 * [tint] is the site's own brand colour and is deliberately *not* a theme role: a row of
 * identically-grey chips has to be read letter by letter, while a red chip is YouTube before the
 * label is read at all. The glyph on it is always white, so the pair carries its own contrast in
 * either scheme.
 */
data class StartPageShortcut(val label: String, val url: String, val glyph: String, val tint: Int)

/** Default shortcuts shown on the start page; fixed rather than configurable, as item 10 only asked for "popular sites". */
val DEFAULT_START_PAGE_SHORTCUTS = listOf(
    StartPageShortcut("Google", "https://www.google.com/", "G", Color.rgb(0x42, 0x85, 0xF4)),
    StartPageShortcut("YouTube", "https://m.youtube.com/", "▶", Color.rgb(0xE6, 0x21, 0x17)),
    StartPageShortcut("Wikipedia", "https://www.wikipedia.org/", "W", Color.rgb(0x6B, 0x72, 0x80)),
    StartPageShortcut("Facebook", "https://m.facebook.com/", "f", Color.rgb(0x18, 0x77, 0xF2)),
    StartPageShortcut("X", "https://x.com/", "X", Color.rgb(0x33, 0x37, 0x3C)),
    StartPageShortcut("Instagram", "https://www.instagram.com/", "IG", Color.rgb(0xE1, 0x30, 0x6C)),
)

/**
 * The phone browser's start page ([BrowserStartupStore.START_PAGE]): a search box and a grid of
 * shortcuts to popular sites, built once from plain Views the same way [BrowserActivity]'s other
 * overlays (`blocked`, `loadError`) are, so showing it costs nothing and never touches the WebView
 * or its navigation history.
 *
 * **The grid is sized from the surface, never authored.** It previously drew three columns of a
 * fixed [AutoUiSizes.MENU_TILE_WIDTH_DP] tile, which comes to ~372dp of tiles and margins — wider
 * than a 360dp phone, so the third column was clipped off the right edge with no way to scroll to
 * it. Now the column count is derived from the width the page is actually given (the same rule
 * [AutoUiSizes.menuColumns] uses for the car menu) and the tiles share that width evenly, so a
 * narrow phone gets smaller tiles rather than invisible ones. The content is also capped at
 * [CONTENT_MAX_WIDTH_DP] and centred, so a wide split pane or tablet gets the same page instead of
 * six tiles stretched across it.
 */
object BrowserStartPage {
    /**
     * [onSupport], when given, adds the "support the developer" card under the search bar, the
     * way AABrowser's start page carries its own card; its ✕ hides it for [SUPPORT_SNOOZE_MS].
     */
    fun build(context: Context, sizes: AutoUiSizes, onSupport: (() -> Unit)? = null, onSubmit: (String) -> Unit): View {
        val grid = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }

        column.addView(
            TextView(context).apply {
                text = context.getString(R.string.browser_start_page_title)
                setTextColor(BrowserTheme.textPrimary)
                textSize = spFor(context, TITLE_TEXT_DP)
                setTypeface(typeface, Typeface.BOLD)
                gravity = Gravity.CENTER
            },
            LinearLayout.LayoutParams(-1, -2).apply {
                bottomMargin = sizes.dpInt(AutoUiSizes.CONTENT_GAP_DP / 2f)
            }
        )

        column.addView(
            TextView(context).apply {
                text = context.getString(R.string.browser_start_page_subtitle)
                setTextColor(BrowserTheme.textSecondary)
                textSize = spFor(context, SUBTITLE_TEXT_DP)
                gravity = Gravity.CENTER
            },
            LinearLayout.LayoutParams(-1, -2).apply {
                bottomMargin = sizes.dpInt(AutoUiSizes.CONTENT_GAP_DP * 2f)
            }
        )

        column.addView(
            searchBar(context, sizes, onSubmit),
            LinearLayout.LayoutParams(-1, sizes.dpInt(SEARCH_HEIGHT_DP)).apply {
                bottomMargin = sizes.dpInt(AutoUiSizes.CONTENT_GAP_DP * 2.5f)
            }
        )

        if (onSupport != null && !supportSnoozed(context)) {
            lateinit var card: View
            card = supportCard(context, sizes, onSupport) {
                snoozeSupport(context)
                card.visibility = View.GONE
            }
            column.addView(card, LinearLayout.LayoutParams(-1, -2).apply {
                bottomMargin = sizes.dpInt(AutoUiSizes.CONTENT_GAP_DP * 2.5f)
            })
        }

        column.addView(
            TextView(context).apply {
                text = context.getString(R.string.browser_start_page_shortcuts)
                setTextColor(BrowserTheme.textPrimary)
                textSize = spFor(context, SECTION_TEXT_DP)
                setTypeface(typeface, Typeface.BOLD)
                setPadding(sizes.dpInt(AutoUiSizes.CONTENT_GAP_DP / 2f), 0, 0, 0)
            },
            LinearLayout.LayoutParams(-1, -2).apply {
                bottomMargin = sizes.dpInt(AutoUiSizes.CONTENT_GAP_DP)
            }
        )

        column.addView(grid, LinearLayout.LayoutParams(-1, -2))

        // Laid out once from the display so the first frame is already right, then re-flowed below
        // against the width the page is really given (split pane, rotation, a car display).
        var columns = -1
        var sidePadding = -1
        var lastWidth = -1
        fun apply(width: Int) {
            if (width <= 0) return
            val side = sizes.dpInt(SIDE_PADDING_DP) +
                ((width - sizes.dpInt(CONTENT_MAX_WIDTH_DP)) / 2).coerceAtLeast(0)
            val fit = columnsFor(sizes, (width - side * 2).toFloat())
            if (side == sidePadding && fit == columns) return
            sidePadding = side
            columns = fit
            column.setPadding(side, column.paddingTop, side, column.paddingBottom)
            fillGrid(grid, context, sizes, fit, onSubmit)
        }
        column.setPadding(0, sizes.dpInt(TOP_PADDING_DP), 0, sizes.dpInt(SIDE_PADDING_DP))
        apply(context.resources.displayMetrics.widthPixels)

        val scroller = ScrollView(context).apply {
            isFillViewport = true
            addView(
                FrameLayout(context).apply { addView(column, FrameLayout.LayoutParams(-1, -2, Gravity.TOP)) },
                ViewGroup.LayoutParams(-1, -1)
            )
        }
        return FrameLayout(context).apply {
            setBackgroundColor(BrowserTheme.background)
            addView(scroller, FrameLayout.LayoutParams(-1, -1))
            addOnLayoutChangeListener { view, left, _, right, _, _, _, _, _ ->
                // Re-flowing mid-layout would be a requestLayout() inside a layout pass; post it.
                val width = right - left
                if (width == lastWidth) return@addOnLayoutChangeListener
                lastWidth = width
                view.post { apply(width) }
            }
        }
    }

    /**
     * One column of link cards on a phone, two side by side once a card can keep
     * [CARD_MIN_WIDTH_DP] (a car display, a tablet, a landscape phone).
     */
    private fun columnsFor(sizes: AutoUiSizes, width: Float): Int {
        val gap = sizes.dp(CARD_GAP_DP)
        return if (width >= sizes.dp(CARD_MIN_WIDTH_DP) * 2 + gap) 2 else 1
    }

    /**
     * Rebuilds the list as rows of weighted cards. A short last row is padded with an empty view
     * so its card keeps the width of the rows above instead of stretching to fill it.
     */
    private fun fillGrid(
        grid: LinearLayout,
        context: Context,
        sizes: AutoUiSizes,
        columns: Int,
        onSubmit: (String) -> Unit,
    ) {
        grid.removeAllViews()
        val gap = sizes.dpInt(CARD_GAP_DP)
        DEFAULT_START_PAGE_SHORTCUTS.chunked(columns).forEachIndexed { rowIndex, items ->
            val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            for (index in 0 until columns) {
                val slot = rowIndex * columns + index + 1
                val cell = items.getOrNull(index)
                    ?.let { shortcut -> linkCard(context, sizes, shortcut, slot) { onSubmit(shortcut.url) } }
                    ?: View(context)
                row.addView(
                    cell,
                    LinearLayout.LayoutParams(0, sizes.dpInt(CARD_HEIGHT_DP), 1f).apply {
                        if (index > 0) marginStart = gap
                    }
                )
            }
            grid.addView(
                row,
                LinearLayout.LayoutParams(-1, -2).apply { if (rowIndex > 0) topMargin = gap }
            )
        }
    }

    /** The search pill: a magnifier, the field, and a round Go button at the end. */
    private fun searchBar(context: Context, sizes: AutoUiSizes, onSubmit: (String) -> Unit): View {
        val height = sizes.dpInt(SEARCH_HEIGHT_DP)
        val field = searchField(context, sizes, onSubmit)
        val inset = sizes.dpInt(SEARCH_INSET_DP)
        val goSize = height - inset * 2
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(sizes.dpInt(AutoUiSizes.HORIZONTAL_PADDING_DP * 1.25f), 0, inset, 0)
            background = GradientDrawable().apply {
                setColor(BrowserTheme.addressPillBackground)
                cornerRadius = height / 2f
                setStroke(sizes.dpInt(1f).coerceAtLeast(1), BrowserTheme.outlineVariant)
            }
            addView(
                ImageView(context).apply {
                    setImageResource(R.drawable.ic_browser_search)
                    setColorFilter(BrowserTheme.accent)
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                },
                LinearLayout.LayoutParams(sizes.dpInt(SEARCH_ICON_DP), sizes.dpInt(SEARCH_ICON_DP))
            )
            addView(field, LinearLayout.LayoutParams(0, -1, 1f))
            addView(
                ImageView(context).apply {
                    setImageResource(R.drawable.ic_browser_forward)
                    setColorFilter(BrowserTheme.onFabContainer)
                    scaleType = ImageView.ScaleType.CENTER_INSIDE
                    val pad = goSize / 4
                    setPadding(pad, pad, pad, pad)
                    contentDescription = context.getString(R.string.browser_start_page_go)
                    isClickable = true
                    isFocusable = true
                    background = RippleDrawable(
                        ColorStateList.valueOf(withAlpha(BrowserTheme.accent, 0.22f)),
                        GradientDrawable().apply {
                            shape = GradientDrawable.OVAL
                            setColor(BrowserTheme.fabContainer)
                        },
                        null
                    )
                    setOnClickListener { if (field.text.isNotBlank()) onSubmit(field.text.toString()) }
                },
                LinearLayout.LayoutParams(goSize, goSize)
            )
        }
    }

    private fun searchField(context: Context, sizes: AutoUiSizes, onSubmit: (String) -> Unit): EditText =
        EditText(context).apply {
            hint = context.getString(R.string.browser_start_page_search_hint)
            setTextColor(BrowserTheme.textPrimary)
            setHintTextColor(BrowserTheme.textSecondary)
            textSize = spFor(context, SEARCH_TEXT_DP)
            setSingleLine()
            gravity = Gravity.CENTER_VERTICAL
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = EditorInfo.IME_ACTION_GO
            setPadding(sizes.dpInt(AutoUiSizes.HORIZONTAL_PADDING_DP), 0, sizes.dpInt(AutoUiSizes.HORIZONTAL_PADDING_DP), 0)
            background = null
            setOnEditorActionListener { _, action, event ->
                val go = action == EditorInfo.IME_ACTION_GO ||
                    (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_UP)
                if (go && text.isNotBlank()) onSubmit(text.toString())
                go
            }
        }

    /** A quick link: brand chip, name over address, and its slot number at the end. */
    private fun linkCard(
        context: Context,
        sizes: AutoUiSizes,
        shortcut: StartPageShortcut,
        slot: Int,
        onTap: () -> Unit,
    ): View {
        val chipSize = sizes.dpInt(CHIP_DP)
        val chip = TextView(context).apply {
            text = shortcut.glyph
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            textSize = spFor(context, CHIP_TEXT_DP)
            setTypeface(typeface, Typeface.BOLD)
            background = GradientDrawable().apply {
                cornerRadius = chipSize * 0.3f
                setColor(shortcut.tint)
            }
        }
        val texts = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(context).apply {
                text = shortcut.label
                setTextColor(BrowserTheme.textPrimary)
                textSize = spFor(context, LABEL_TEXT_DP)
                setTypeface(typeface, Typeface.BOLD)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            })
            addView(TextView(context).apply {
                text = hostOf(shortcut.url)
                setTextColor(BrowserTheme.textSecondary)
                textSize = spFor(context, HOST_TEXT_DP)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setPadding(0, sizes.dpInt(AutoUiSizes.CONTENT_GAP_DP * 0.25f), 0, 0)
            })
        }
        val badge = TextView(context).apply {
            text = context.getString(R.string.browser_start_page_slot, slot)
            setTextColor(BrowserTheme.textSecondary)
            textSize = spFor(context, BADGE_TEXT_DP)
            val padH = sizes.dpInt(AutoUiSizes.CONTENT_GAP_DP * 0.6f)
            val padV = sizes.dpInt(AutoUiSizes.CONTENT_GAP_DP * 0.25f)
            setPadding(padH, padV, padH, padV)
            background = GradientDrawable().apply {
                setColor(BrowserTheme.addressPillBackground)
                cornerRadius = sizes.dp(BADGE_CORNER_DP)
            }
        }
        val face = GradientDrawable().apply {
            setColor(BrowserTheme.sheetCardBackground)
            cornerRadius = sizes.dp(CARD_CORNER_DP)
            setStroke(sizes.dpInt(1f).coerceAtLeast(1), BrowserTheme.outlineVariant)
        }
        val gap = sizes.dpInt(AutoUiSizes.CONTENT_GAP_DP)
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            isFocusable = true
            contentDescription = shortcut.label
            setPadding(gap * 3 / 2, 0, gap * 3 / 2, 0)
            background = RippleDrawable(
                ColorStateList.valueOf(withAlpha(BrowserTheme.accent, 0.22f)), face, null
            )
            addView(chip, LinearLayout.LayoutParams(chipSize, chipSize).apply { marginEnd = gap * 3 / 2 })
            addView(texts, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = gap })
            addView(badge, LinearLayout.LayoutParams(-2, -2).apply { gravity = Gravity.TOP })
            setOnClickListener { onTap() }
        }
    }

    /** The support card: heart chip, title and line, a "Support" button, and ✕ to snooze it. */
    private fun supportCard(context: Context, sizes: AutoUiSizes, onSupport: () -> Unit, onClose: () -> Unit): View {
        val gap = sizes.dpInt(AutoUiSizes.CONTENT_GAP_DP)
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(gap * 2, gap * 2, gap, gap * 2)
            background = GradientDrawable().apply {
                setColor(BrowserTheme.sheetCardBackground)
                cornerRadius = sizes.dp(CARD_CORNER_DP)
                setStroke(sizes.dpInt(1f).coerceAtLeast(1), BrowserTheme.outlineVariant)
            }
        }
        val head = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        head.addView(ImageView(context).apply {
            setImageResource(R.drawable.ic_browser_support)
            setColorFilter(BrowserTheme.onFabContainer)
            val pad = sizes.dpInt(10f)
            setPadding(pad, pad, pad, pad)
            background = GradientDrawable().apply {
                setColor(BrowserTheme.fabContainer)
                cornerRadius = sizes.dp(14f)
            }
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(sizes.dpInt(CHIP_DP), sizes.dpInt(CHIP_DP)).apply { marginEnd = gap * 3 / 2 })
        head.addView(TextView(context).apply {
            text = context.getString(R.string.support_card_title)
            setTextColor(BrowserTheme.textPrimary)
            textSize = spFor(context, LABEL_TEXT_DP + 1f)
            setTypeface(typeface, Typeface.BOLD)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        head.addView(TextView(context).apply {
            text = "✕"
            textSize = spFor(context, LABEL_TEXT_DP)
            gravity = Gravity.CENTER
            setTextColor(BrowserTheme.textSecondary)
            contentDescription = context.getString(R.string.player_close)
            isClickable = true
            isFocusable = true
            setOnClickListener { onClose() }
        }, LinearLayout.LayoutParams(sizes.dpInt(44f), sizes.dpInt(44f)))
        card.addView(head)
        card.addView(TextView(context).apply {
            text = context.getString(R.string.support_card_message)
            setTextColor(BrowserTheme.textSecondary)
            textSize = spFor(context, HOST_TEXT_DP + 1f)
            setPadding(0, gap, gap, gap * 3 / 2)
        })
        card.addView(TextView(context).apply {
            text = context.getString(R.string.home_donate)
            setTextColor(BrowserTheme.onFabContainer)
            textSize = spFor(context, LABEL_TEXT_DP - 1f)
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(gap * 2, 0, gap * 2, 0)
            isClickable = true
            isFocusable = true
            background = RippleDrawable(
                ColorStateList.valueOf(withAlpha(BrowserTheme.accent, 0.22f)),
                GradientDrawable().apply {
                    setColor(BrowserTheme.fabContainer)
                    cornerRadius = sizes.dp(24f)
                },
                null
            )
            setOnClickListener { onSupport() }
        }, LinearLayout.LayoutParams(-2, sizes.dpInt(48f)))
        return card
    }

    private fun supportSnoozed(context: Context): Boolean {
        val until = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_SUPPORT_SNOOZED, 0L)
        return System.currentTimeMillis() < until
    }

    private fun snoozeSupport(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong(KEY_SUPPORT_SNOOZED, System.currentTimeMillis() + SUPPORT_SNOOZE_MS).apply()
    }

    /** "www.google.com" for "https://www.google.com/": the address a link card shows under its name. */
    internal fun hostOf(url: String): String =
        url.substringAfter("://").substringBefore('/').substringBefore('?').ifEmpty { url }

    private fun withAlpha(color: Int, alpha: Float): Int =
        Color.argb((alpha * 255).toInt().coerceIn(0, 255), Color.red(color), Color.green(color), Color.blue(color))

    /**
     * A dp token as an sp value, with the accessibility font scale already capped the way
     * [BrowserSheetShell] caps it — a card label has one line to fit and may not grow without bound.
     */
    private fun spFor(context: Context, dp: Float): Float {
        val actual = context.resources.configuration.fontScale.coerceAtLeast(0.01f)
        return dp * (AutoUiSizes.clampFontScale(actual) / actual)
    }

    private const val PREFS = "autobridge_browser"
    private const val KEY_SUPPORT_SNOOZED = "start_support_snoozed_until"
    /** A ✕ on the support card hides it for a week. */
    private const val SUPPORT_SNOOZE_MS = 7L * 24 * 60 * 60 * 1000

    /** Ceiling on the page's content width, so a wide display centres it rather than stretching it. */
    private const val CONTENT_MAX_WIDTH_DP = 900f
    private const val TOP_PADDING_DP = 28f
    private const val SIDE_PADDING_DP = 20f
    private const val SEARCH_HEIGHT_DP = 56f
    private const val SEARCH_INSET_DP = 8f
    private const val SEARCH_ICON_DP = 24f
    private const val CARD_MIN_WIDTH_DP = 300f
    private const val CARD_HEIGHT_DP = 76f
    private const val CARD_GAP_DP = 10f
    private const val CARD_CORNER_DP = 22f
    private const val CHIP_DP = 46f
    private const val BADGE_CORNER_DP = 8f
    private const val TITLE_TEXT_DP = 30f
    private const val SUBTITLE_TEXT_DP = 14f
    private const val SECTION_TEXT_DP = 18f
    private const val SEARCH_TEXT_DP = 16f
    private const val CHIP_TEXT_DP = 18f
    private const val LABEL_TEXT_DP = 16f
    private const val HOST_TEXT_DP = 12f
    private const val BADGE_TEXT_DP = 12f
}
