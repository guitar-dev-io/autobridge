package dev.autobridge.browser

import android.app.AlertDialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.TextUtils
import android.text.format.DateUtils
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
import dev.autobridge.design.ScriptTracking
import dev.autobridge.entertainment.WebHistoryEntry
import dev.autobridge.entertainment.WebHistoryStore

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
 * What the start page's bottom toolbar drives. The phone keeps one page per activity, so the bar is
 * Back, Forward, Start page and Menu (no tab count). [canGoBack] / [canForward] are read each time
 * the page is shown, so the disabled state follows the WebView underneath.
 */
class StartPageNav(
    val canGoBack: () -> Boolean,
    val canForward: () -> Boolean,
    val onBack: () -> Unit,
    val onForward: () -> Unit,
    val onMenu: () -> Unit,
)

/**
 * The phone browser's start page ([BrowserStartupStore.START_PAGE]), as the UI redesign draws it:
 * an outlined search field, a speed-dial grid of popular sites, a "Continue reading" list of the
 * latest history, and a bottom toolbar. Built once from plain Views, the same way
 * [BrowserActivity]'s other overlays (`blocked`, `loadError`) are, so showing it costs nothing and
 * never touches the WebView or its navigation history.
 *
 * **The grid is sized from the surface, never authored.** The column count comes from the width the
 * page is actually given (four on a phone, up to six on a wide pane), so a narrow phone gets
 * narrower cells rather than clipped ones, and the content is capped at [CONTENT_MAX_WIDTH_DP] and
 * centred so a tablet gets the same page instead of tiles stretched across it.
 *
 * Every pressable thing follows the design-system states: a ripple for pressed, the disabled
 * grey for a toolbar button with nowhere to go, accent + soft accent fill for the selected one, and
 * at least [MIN_TOUCH_DP] to hit.
 */
object BrowserStartPage {
    /** The built page and the hook that brings its history and toolbar up to date on each show. */
    class Built(val view: View, val refresh: () -> Unit)

    private const val NAV_HEIGHT_DP = 56f
    private const val NAV_MARGIN_DP = 12f

    /** How much of the page's bottom the toolbar covers, for anything floating above it (the FAB). */
    const val NAV_BAR_RESERVE_DP = NAV_HEIGHT_DP + NAV_MARGIN_DP * 2

    /**
     * [onSupport], when given, adds the "support the developer" card under the search field; its ✕
     * hides it for [SUPPORT_SNOOZE_MS].
     */
    fun build(
        context: Context,
        sizes: AutoUiSizes,
        nav: StartPageNav,
        onSupport: (() -> Unit)? = null,
        onSubmit: (String) -> Unit,
    ): Built {
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }

        column.addView(
            searchField(context, sizes, onSubmit),
            LinearLayout.LayoutParams(-1, sizes.dpInt(SEARCH_HEIGHT_DP)).apply {
                bottomMargin = sizes.dpInt(SECTION_GAP_DP)
            }
        )

        if (onSupport != null && !supportSnoozed(context)) {
            lateinit var card: View
            card = supportCard(context, sizes, onSupport) {
                snoozeSupport(context)
                card.visibility = View.GONE
            }
            column.addView(card, LinearLayout.LayoutParams(-1, -2).apply {
                bottomMargin = sizes.dpInt(SECTION_GAP_DP)
            })
        }

        val dial = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        column.addView(dial, LinearLayout.LayoutParams(-1, -2).apply {
            bottomMargin = sizes.dpInt(SECTION_GAP_DP)
        })

        // "Continue reading": a header with Clear, then one card of rows. Hidden while history is empty.
        val recentHeader = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(sectionLabel(context, context.getString(R.string.browser_v2_continue_reading)), LinearLayout.LayoutParams(0, -2, 1f))
        }
        val recentCard = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = cardFace(sizes)
            clipToOutline = true
        }
        lateinit var refillRecent: () -> Unit
        recentHeader.addView(
            TextView(context).apply {
                text = context.getString(R.string.browser_start_page_recent_clear)
                setTextColor(BrowserTheme.accent)
                textSize = spFor(context, SMALL_TEXT_DP + 1f)
                setTypeface(typeface, Typeface.BOLD)
                gravity = Gravity.CENTER
                minimumHeight = sizes.dpInt(MIN_TOUCH_DP)
                minimumWidth = sizes.dpInt(MIN_TOUCH_DP)
                val pad = sizes.dpInt(8f)
                setPadding(pad, 0, pad, 0)
                isClickable = true
                isFocusable = true
                background = ripple(null)
                setOnClickListener {
                    AlertDialog.Builder(context)
                        .setTitle(R.string.browser_history_clear_all)
                        .setMessage(R.string.browser_history_clear_confirm)
                        .setPositiveButton(R.string.action_clear) { _, _ ->
                            WebHistoryStore.clear(context)
                            refillRecent()
                        }
                        .setNegativeButton(R.string.action_cancel, null)
                        .show()
                }
            },
            LinearLayout.LayoutParams(-2, -2)
        )
        column.addView(recentHeader, LinearLayout.LayoutParams(-1, -2).apply {
            bottomMargin = sizes.dpInt(6f)
        })
        column.addView(recentCard, LinearLayout.LayoutParams(-1, -2))

        refillRecent = {
            val entries = WebHistoryStore.list(context).take(RECENT_COUNT)
            recentCard.removeAllViews()
            val visible = if (entries.isEmpty()) View.GONE else View.VISIBLE
            recentHeader.visibility = visible
            recentCard.visibility = visible
            entries.forEachIndexed { index, entry ->
                if (index > 0) recentCard.addView(
                    View(context).apply { setBackgroundColor(BrowserTheme.hairline) },
                    LinearLayout.LayoutParams(-1, sizes.dpInt(1f).coerceAtLeast(1))
                )
                recentCard.addView(
                    recentRow(context, sizes, entry, onTap = { onSubmit(entry.url) }) {
                        WebHistoryStore.remove(context, entry.url)
                        refillRecent()
                    },
                    LinearLayout.LayoutParams(-1, -2)
                )
            }
        }
        refillRecent()

        // Laid out once from the display so the first frame is already right, then re-flowed below
        // against the width the page is really given (split pane, rotation, a car display).
        var sidePadding = -1
        var columns = -1
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
            fillDial(dial, context, sizes, fit, onSubmit)
        }
        column.setPadding(
            0, sizes.dpInt(TOP_PADDING_DP), 0,
            sizes.dpInt(SIDE_PADDING_DP + NAV_BAR_RESERVE_DP)
        )
        apply(context.resources.displayMetrics.widthPixels)

        val bar = navBar(context, sizes, nav)

        val scroller = ScrollView(context).apply {
            isFillViewport = true
            addView(
                FrameLayout(context).apply { addView(column, FrameLayout.LayoutParams(-1, -2, Gravity.TOP)) },
                ViewGroup.LayoutParams(-1, -1)
            )
        }
        val root = FrameLayout(context).apply {
            setBackgroundColor(BrowserTheme.background)
            addView(scroller, FrameLayout.LayoutParams(-1, -1))
            addView(bar.view, FrameLayout.LayoutParams(-1, sizes.dpInt(NAV_HEIGHT_DP), Gravity.BOTTOM).apply {
                val margin = sizes.dpInt(NAV_MARGIN_DP)
                setMargins(margin, margin, margin, margin)
            })
            addOnLayoutChangeListener { view, left, _, right, _, _, _, _, _ ->
                // Re-flowing mid-layout would be a requestLayout() inside a layout pass; post it.
                val width = right - left
                if (width == lastWidth) return@addOnLayoutChangeListener
                lastWidth = width
                view.post {
                    apply(width)
                    // The toolbar keeps the content's width too, centred on a wide pane.
                    (bar.view.layoutParams as FrameLayout.LayoutParams).let { lp ->
                        val margin = sizes.dpInt(NAV_MARGIN_DP)
                        lp.leftMargin = maxOf(margin, sidePadding)
                        lp.rightMargin = maxOf(margin, sidePadding)
                        bar.view.layoutParams = lp
                    }
                }
            }
        }
        return Built(root) {
            refillRecent()
            bar.refresh()
        }
    }

    /** Four speed-dial columns on a phone, up to six once each keeps [DIAL_CELL_MIN_DP]. */
    private fun columnsFor(sizes: AutoUiSizes, width: Float): Int =
        (width / sizes.dp(DIAL_CELL_MIN_DP)).toInt().coerceIn(3, 6)

    /** Rebuilds the speed dial as rows of equal cells; a short last row is padded with spacers. */
    private fun fillDial(grid: LinearLayout, context: Context, sizes: AutoUiSizes, columns: Int, onSubmit: (String) -> Unit) {
        grid.removeAllViews()
        DEFAULT_START_PAGE_SHORTCUTS.chunked(columns).forEachIndexed { rowIndex, items ->
            val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            for (index in 0 until columns) {
                val cell = items.getOrNull(index)?.let { dialTile(context, sizes, it) { onSubmit(it.url) } } ?: View(context)
                row.addView(cell, LinearLayout.LayoutParams(0, -2, 1f))
            }
            grid.addView(row, LinearLayout.LayoutParams(-1, -2).apply {
                if (rowIndex > 0) topMargin = sizes.dpInt(DIAL_ROW_GAP_DP)
            })
        }
    }

    /** One speed-dial tile: a bordered square holding the site's brand dot, its name underneath. */
    private fun dialTile(context: Context, sizes: AutoUiSizes, shortcut: StartPageShortcut, onTap: () -> Unit): View {
        val tile = sizes.dpInt(DIAL_TILE_DP)
        val dot = sizes.dpInt(DIAL_DOT_DP)
        val square = FrameLayout(context).apply {
            background = GradientDrawable().apply {
                setColor(BrowserTheme.surfaceContainer)
                cornerRadius = sizes.dp(TILE_CORNER_DP)
                setStroke(sizes.dpInt(1f).coerceAtLeast(1), BrowserTheme.hairline)
            }
            addView(TextView(context).apply {
                text = shortcut.glyph
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                textSize = spFor(context, 13f)
                setTypeface(typeface, Typeface.BOLD)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(shortcut.tint)
                }
            }, FrameLayout.LayoutParams(dot, dot, Gravity.CENTER))
        }
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            isClickable = true
            isFocusable = true
            contentDescription = shortcut.label
            val pad = sizes.dpInt(4f)
            setPadding(pad, pad, pad, pad)
            background = ripple(sizes.dp(TILE_CORNER_DP))
            addView(square, LinearLayout.LayoutParams(tile, tile))
            addView(TextView(context).apply {
                text = shortcut.label
                setTextColor(BrowserTheme.textSecondary)
                textSize = spFor(context, SMALL_TEXT_DP)
                gravity = Gravity.CENTER
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = sizes.dpInt(6f) })
            setOnClickListener { onTap() }
        }
    }

    /**
     * The search field: outlined, a magnifier, the field, and a round accent Go button at the end
     * (44dp, the minimum touch target).
     */
    private fun searchField(context: Context, sizes: AutoUiSizes, onSubmit: (String) -> Unit): View {
        val field = EditText(context).apply {
            hint = context.getString(R.string.browser_start_page_search_hint)
            setTextColor(BrowserTheme.textPrimary)
            setHintTextColor(BrowserTheme.textSecondary)
            textSize = spFor(context, BODY_TEXT_DP)
            setSingleLine()
            gravity = Gravity.CENTER_VERTICAL
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = EditorInfo.IME_ACTION_GO
            setPadding(sizes.dpInt(10f), 0, sizes.dpInt(10f), 0)
            background = null
            setOnEditorActionListener { _, action, event ->
                val go = action == EditorInfo.IME_ACTION_GO ||
                    (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_UP)
                if (go && text.isNotBlank()) onSubmit(text.toString())
                go
            }
        }
        val goSize = sizes.dpInt(MIN_TOUCH_DP)
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(sizes.dpInt(14f), 0, sizes.dpInt(4f), 0)
            background = GradientDrawable().apply {
                setColor(BrowserTheme.surfaceContainer)
                cornerRadius = sizes.dp(FIELD_CORNER_DP)
                setStroke(sizes.dpInt(1f).coerceAtLeast(1), BrowserTheme.hairline)
            }
            addView(
                ImageView(context).apply {
                    setImageResource(R.drawable.ic_browser_search)
                    setColorFilter(BrowserTheme.textSecondary)
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                },
                LinearLayout.LayoutParams(sizes.dpInt(ICON_DP), sizes.dpInt(ICON_DP))
            )
            addView(field, LinearLayout.LayoutParams(0, -1, 1f))
            addView(
                ImageView(context).apply {
                    setImageResource(R.drawable.ic_browser_forward)
                    setColorFilter(BrowserTheme.onPrimary)
                    scaleType = ImageView.ScaleType.CENTER_INSIDE
                    val pad = goSize / 4
                    setPadding(pad, pad, pad, pad)
                    contentDescription = context.getString(R.string.browser_start_page_go)
                    isClickable = true
                    isFocusable = true
                    background = RippleDrawable(
                        ColorStateList.valueOf(withAlpha(BrowserTheme.onPrimary, 0.24f)),
                        GradientDrawable().apply {
                            shape = GradientDrawable.OVAL
                            setColor(BrowserTheme.accent)
                        },
                        null
                    )
                    setOnClickListener { if (field.text.isNotBlank()) onSubmit(field.text.toString()) }
                },
                LinearLayout.LayoutParams(goSize, goSize)
            )
        }
    }

    /** One "Continue reading" row: title over "site · when", and a ✕ that removes it from history. */
    private fun recentRow(
        context: Context,
        sizes: AutoUiSizes,
        entry: WebHistoryEntry,
        onTap: () -> Unit,
        onDelete: () -> Unit,
    ): View {
        val host = hostOf(entry.url)
        val title = entry.title.ifBlank { host }
        val texts = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(context).apply {
                text = title
                setTextColor(BrowserTheme.textPrimary)
                textSize = spFor(context, BODY_TEXT_DP)
                setTypeface(typeface, Typeface.BOLD)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            })
            addView(TextView(context).apply {
                val ago = DateUtils.getRelativeTimeSpanString(
                    entry.timestampMs, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS,
                    DateUtils.FORMAT_ABBREV_RELATIVE
                )
                text = if (entry.timestampMs > 0) context.getString(R.string.browser_v2_visited, host, ago) else host
                setTextColor(BrowserTheme.textSecondary)
                textSize = spFor(context, SMALL_TEXT_DP + 1f)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setPadding(0, sizes.dpInt(2f), 0, 0)
            })
        }
        val remove = ImageView(context).apply {
            setImageResource(R.drawable.ic_browser_close)
            setColorFilter(BrowserTheme.textSecondary)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            val pad = sizes.dpInt(12f)
            setPadding(pad, pad, pad, pad)
            contentDescription = context.getString(R.string.browser_v2_remove_recent, title)
            isClickable = true
            isFocusable = true
            background = ripple(sizes.dp(MIN_TOUCH_DP) / 2f)
            setOnClickListener { onDelete() }
        }
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = sizes.dpInt(ROW_MIN_HEIGHT_DP)
            setPadding(sizes.dpInt(14f), sizes.dpInt(8f), sizes.dpInt(4f), sizes.dpInt(8f))
            isClickable = true
            isFocusable = true
            contentDescription = title
            background = ripple(null)
            addView(texts, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = sizes.dpInt(8f) })
            addView(remove, LinearLayout.LayoutParams(sizes.dpInt(MIN_TOUCH_DP), sizes.dpInt(MIN_TOUCH_DP)))
            setOnClickListener { onTap() }
        }
    }

    private class NavBar(val view: View, val refresh: () -> Unit)

    /**
     * The bottom toolbar: Back, Forward, Start page (selected: accent glyph on the soft accent fill)
     * and Menu, each 48dp. Back and Forward take the disabled grey when there is nowhere to go.
     */
    private fun navBar(context: Context, sizes: AutoUiSizes, nav: StartPageNav): NavBar {
        val size = sizes.dpInt(NAV_BUTTON_DP)
        val bar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val pad = sizes.dpInt(4f)
            setPadding(pad, pad, pad, pad)
            background = GradientDrawable().apply {
                setColor(BrowserTheme.surfaceContainerHigh)
                cornerRadius = sizes.dp(NAV_HEIGHT_DP) / 2f
            }
            elevation = sizes.dp(3f)
        }
        fun button(icon: BrowserIcon, description: String, selected: Boolean = false, onTap: () -> Unit): ImageView {
            val view = ImageView(context).apply {
                setImageResource(icon.resId)
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                val pad = (size - sizes.dpInt(ICON_DP)) / 2
                setPadding(pad, pad, pad, pad)
                contentDescription = description
                isSelected = selected
                setColorFilter(if (selected) BrowserTheme.accent else BrowserTheme.iconEnabled)
                background = if (selected) {
                    RippleDrawable(
                        ColorStateList.valueOf(withAlpha(BrowserTheme.accent, 0.22f)),
                        GradientDrawable().apply {
                            shape = GradientDrawable.OVAL
                            setColor(BrowserTheme.selectedFill)
                        },
                        null
                    )
                } else ripple(size / 2f)
                setOnClickListener { onTap() }
            }
            bar.addView(FrameLayout(context).apply {
                addView(view, FrameLayout.LayoutParams(size, size, Gravity.CENTER))
            }, LinearLayout.LayoutParams(0, -1, 1f))
            return view
        }
        val back = button(BrowserIcon.BACK, context.getString(R.string.browser_back)) { nav.onBack() }
        val forward = button(BrowserIcon.FORWARD, context.getString(R.string.browser_v2_forward)) { nav.onForward() }
        button(BrowserIcon.HOME_PAGE, context.getString(R.string.browser_v2_start_page), selected = true) { }
        button(BrowserIcon.MENU, context.getString(R.string.browser_v2_menu)) { nav.onMenu() }
        fun setEnabled(view: ImageView, enabled: Boolean) {
            view.isEnabled = enabled
            view.setColorFilter(if (enabled) BrowserTheme.iconEnabled else BrowserTheme.iconDisabled)
        }
        val refresh = {
            setEnabled(back, nav.canGoBack())
            setEnabled(forward, nav.canForward())
        }
        refresh()
        return NavBar(bar, refresh)
    }

    private fun sectionLabel(context: Context, label: String): TextView = TextView(context).apply {
        text = label.uppercase()
        letterSpacing = ScriptTracking.letterSpacing(text, 0.06f)
        setTextColor(BrowserTheme.textSecondary)
        textSize = spFor(context, SMALL_TEXT_DP)
        setTypeface(typeface, Typeface.BOLD)
        setPadding(sizes4(context), 0, 0, 0)
    }

    private fun sizes4(context: Context) = (4 * context.resources.displayMetrics.density).toInt()

    private fun cardFace(sizes: AutoUiSizes) = GradientDrawable().apply {
        setColor(BrowserTheme.surfaceContainer)
        cornerRadius = sizes.dp(CARD_CORNER_DP)
        setStroke(sizes.dpInt(1f).coerceAtLeast(1), BrowserTheme.hairline)
    }

    /** The pressed state: an accent ripple, bounded to a rounded mask when [radius] is given. */
    private fun ripple(radius: Float?): RippleDrawable = RippleDrawable(
        ColorStateList.valueOf(withAlpha(BrowserTheme.accent, 0.22f)),
        null,
        radius?.let { r -> GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = r } }
            ?: ColorDrawable(Color.WHITE)
    )

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

    /** How many most-recent pages "Continue reading" shows. */
    private const val RECENT_COUNT = 4
    /** The design system's minimum touch target. */
    private const val MIN_TOUCH_DP = 44f

    /** Ceiling on the page's content width, so a wide display centres it rather than stretching it. */
    private const val CONTENT_MAX_WIDTH_DP = 720f
    private const val TOP_PADDING_DP = 16f
    private const val SIDE_PADDING_DP = 16f
    private const val SECTION_GAP_DP = 20f
    private const val SEARCH_HEIGHT_DP = 52f
    private const val FIELD_CORNER_DP = 14f
    private const val ICON_DP = 20f
    private const val DIAL_CELL_MIN_DP = 80f
    private const val DIAL_TILE_DP = 52f
    private const val DIAL_DOT_DP = 28f
    private const val DIAL_ROW_GAP_DP = 10f
    private const val TILE_CORNER_DP = 16f
    private const val CARD_CORNER_DP = 20f
    private const val ROW_MIN_HEIGHT_DP = 60f
    private const val NAV_BUTTON_DP = 48f
    private const val BODY_TEXT_DP = 15f
    private const val SMALL_TEXT_DP = 12f
    // The support card's own sizes, unchanged by the redesign.
    private const val CHIP_DP = 46f
    private const val LABEL_TEXT_DP = 16f
    private const val HOST_TEXT_DP = 12f
}
