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
    fun build(context: Context, sizes: AutoUiSizes, onSubmit: (String) -> Unit): View {
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
                bottomMargin = sizes.dpInt(AutoUiSizes.CONTENT_GAP_DP * 2.5f)
            }
        )

        column.addView(
            searchField(context, sizes, onSubmit),
            LinearLayout.LayoutParams(-1, sizes.dpInt(SEARCH_HEIGHT_DP)).apply {
                bottomMargin = sizes.dpInt(AutoUiSizes.CONTENT_GAP_DP * 3)
            }
        )

        column.addView(
            TextView(context).apply {
                text = context.getString(R.string.browser_start_page_shortcuts)
                setTextColor(BrowserTheme.textSecondary)
                textSize = spFor(context, SECTION_TEXT_DP)
                letterSpacing = 0.06f
                setTypeface(typeface, Typeface.BOLD)
                setPadding(sizes.dpInt(AutoUiSizes.CONTENT_GAP_DP / 2f), 0, 0, 0)
            },
            LinearLayout.LayoutParams(-1, -2).apply {
                bottomMargin = sizes.dpInt(AutoUiSizes.CONTENT_GAP_DP)
            }
        )

        column.addView(grid, LinearLayout.LayoutParams(-1, -2))

        // Laid out once from the display so the first frame is already right, then re-flowed below
        // against the width the page is really given (split pane, rotation, a car surface).
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
        column.setPadding(0, sizes.dpInt(SIDE_PADDING_DP), 0, sizes.dpInt(SIDE_PADDING_DP))
        apply(context.resources.displayMetrics.widthPixels)

        val scroller = ScrollView(context).apply {
            isFillViewport = true
            addView(
                FrameLayout(context).apply { addView(column, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER)) },
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

    /** How many tiles fit across [width] px, bounded both ways so a tile is never a sliver. */
    private fun columnsFor(sizes: AutoUiSizes, width: Float): Int {
        if (width <= 0f) return COLUMNS_MIN
        val gap = sizes.dp(TILE_GAP_DP)
        val fit = ((width + gap) / (sizes.dp(TILE_MIN_WIDTH_DP) + gap)).toInt()
        return fit.coerceIn(COLUMNS_MIN, COLUMNS_MAX)
    }

    /**
     * Rebuilds the grid as rows of weighted tiles. A short last row is padded with empty views so
     * its tiles keep the width of the rows above instead of stretching to fill it.
     */
    private fun fillGrid(
        grid: LinearLayout,
        context: Context,
        sizes: AutoUiSizes,
        columns: Int,
        onSubmit: (String) -> Unit,
    ) {
        grid.removeAllViews()
        val gap = sizes.dpInt(TILE_GAP_DP)
        DEFAULT_START_PAGE_SHORTCUTS.chunked(columns).forEachIndexed { rowIndex, items ->
            val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            for (index in 0 until columns) {
                val shortcut = items.getOrNull(index)
                val cell = shortcut
                    ?.let { tile(context, sizes, it) { onSubmit(it.url) } }
                    ?: View(context)
                row.addView(
                    cell,
                    LinearLayout.LayoutParams(0, sizes.dpInt(TILE_HEIGHT_DP), 1f).apply {
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

    private fun searchField(context: Context, sizes: AutoUiSizes, onSubmit: (String) -> Unit): EditText =
        EditText(context).apply {
            hint = context.getString(R.string.browser_start_page_search_hint)
            setTextColor(BrowserTheme.textPrimary)
            setHintTextColor(BrowserTheme.textSecondary)
            textSize = spFor(context, SEARCH_TEXT_DP)
            setSingleLine()
            gravity = Gravity.CENTER_VERTICAL
            imeOptions = EditorInfo.IME_ACTION_GO
            setPadding(
                sizes.dpInt(AutoUiSizes.HORIZONTAL_PADDING_DP * 1.5f), 0,
                sizes.dpInt(AutoUiSizes.HORIZONTAL_PADDING_DP * 1.5f), 0
            )
            background = GradientDrawable().apply {
                setColor(BrowserTheme.addressPillBackground)
                cornerRadius = sizes.dp(SEARCH_HEIGHT_DP) / 2f
                setStroke(sizes.dpInt(1f).coerceAtLeast(1), BrowserTheme.outlineVariant)
            }
            setOnEditorActionListener { _, action, event ->
                val go = action == EditorInfo.IME_ACTION_GO ||
                    (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_UP)
                if (go && text.isNotBlank()) onSubmit(text.toString())
                go
            }
        }

    private fun tile(context: Context, sizes: AutoUiSizes, shortcut: StartPageShortcut, onTap: () -> Unit): View {
        val chipSize = sizes.dpInt(CHIP_DP)
        val chip = TextView(context).apply {
            text = shortcut.glyph
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            textSize = spFor(context, CHIP_TEXT_DP)
            setTypeface(typeface, Typeface.BOLD)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(shortcut.tint)
            }
        }
        val label = TextView(context).apply {
            text = shortcut.label
            gravity = Gravity.CENTER
            setTextColor(BrowserTheme.textPrimary)
            textSize = spFor(context, LABEL_TEXT_DP)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        val face = GradientDrawable().apply {
            setColor(BrowserTheme.sheetCardBackground)
            cornerRadius = sizes.dp(TILE_CORNER_DP)
            setStroke(sizes.dpInt(1f).coerceAtLeast(1), BrowserTheme.outlineVariant)
        }
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            contentDescription = shortcut.label
            setPadding(
                sizes.dpInt(AutoUiSizes.CONTENT_GAP_DP / 2f), 0,
                sizes.dpInt(AutoUiSizes.CONTENT_GAP_DP / 2f), 0
            )
            background = RippleDrawable(
                ColorStateList.valueOf(withAlpha(BrowserTheme.accent, 0.22f)), face, null
            )
            addView(chip, LinearLayout.LayoutParams(chipSize, chipSize).apply {
                bottomMargin = sizes.dpInt(AutoUiSizes.CONTENT_GAP_DP)
            })
            addView(label, LinearLayout.LayoutParams(-1, -2))
            setOnClickListener { onTap() }
        }
    }

    private fun withAlpha(color: Int, alpha: Float): Int =
        Color.argb((alpha * 255).toInt().coerceIn(0, 255), Color.red(color), Color.green(color), Color.blue(color))

    /**
     * A dp token as an sp value, with the accessibility font scale already capped the way
     * [BrowserSheetShell] caps it — a tile label has one line to fit and may not grow without bound.
     */
    private fun spFor(context: Context, dp: Float): Float {
        val actual = context.resources.configuration.fontScale.coerceAtLeast(0.01f)
        return dp * (AutoUiSizes.clampFontScale(actual) / actual)
    }

    /** Ceiling on the page's content width, so a wide pane centres it rather than stretching it. */
    private const val CONTENT_MAX_WIDTH_DP = 420f
    private const val SIDE_PADDING_DP = 20f
    private const val SEARCH_HEIGHT_DP = 52f
    private const val TILE_MIN_WIDTH_DP = 96f
    private const val TILE_HEIGHT_DP = 94f
    private const val TILE_GAP_DP = 10f
    private const val TILE_CORNER_DP = 18f
    private const val CHIP_DP = 42f
    private const val COLUMNS_MIN = 2
    private const val COLUMNS_MAX = 4
    private const val TITLE_TEXT_DP = 26f
    private const val SECTION_TEXT_DP = 12f
    private const val SEARCH_TEXT_DP = 15f
    private const val CHIP_TEXT_DP = 17f
    private const val LABEL_TEXT_DP = 13f
}
