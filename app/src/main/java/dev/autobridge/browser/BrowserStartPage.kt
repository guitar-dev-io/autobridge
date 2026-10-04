package dev.autobridge.browser

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import dev.autobridge.R

/** One shortcut tile on the start page: a popular site, shown as a glyph chip plus a label. */
data class StartPageShortcut(val label: String, val url: String, val glyph: String)

/** Default shortcuts shown on the start page; fixed rather than configurable, as item 10 only asked for "popular sites". */
val DEFAULT_START_PAGE_SHORTCUTS = listOf(
    StartPageShortcut("Google", "https://www.google.com/", "G"),
    StartPageShortcut("YouTube", "https://m.youtube.com/", "▶"),
    StartPageShortcut("Wikipedia", "https://www.wikipedia.org/", "W"),
    StartPageShortcut("Facebook", "https://m.facebook.com/", "f"),
    StartPageShortcut("X", "https://x.com/", "X"),
    StartPageShortcut("Instagram", "https://www.instagram.com/", "IG"),
)

/**
 * The phone browser's start page ([BrowserStartupStore.START_PAGE]): a search box and a grid of
 * shortcuts to popular sites, built once from plain Views the same way [BrowserActivity]'s other
 * overlays (`blocked`, `loadError`) are, so showing it costs nothing and never touches the WebView
 * or its navigation history.
 */
object BrowserStartPage {
    fun build(context: Context, sizes: AutoUiSizes, onSubmit: (String) -> Unit): View {
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }

        column.addView(
            TextView(context).apply {
                text = context.getString(R.string.browser_start_page_title)
                setTextColor(BrowserTheme.textPrimary)
                textSize = 22f
                gravity = Gravity.CENTER
            },
            LinearLayout.LayoutParams(-2, -2).apply {
                bottomMargin = sizes.dpInt(AutoUiSizes.CONTENT_GAP_DP * 3)
            }
        )

        column.addView(
            searchField(context, sizes, onSubmit),
            LinearLayout.LayoutParams(sizes.dpInt(SEARCH_WIDTH_DP), sizes.dpInt(AutoUiSizes.TOUCH_TARGET_DP)).apply {
                bottomMargin = sizes.dpInt(AutoUiSizes.CONTENT_GAP_DP * 3)
            }
        )

        column.addView(shortcutGrid(context, sizes, onSubmit))

        val scroller = ScrollView(context).apply {
            isFillViewport = true
            addView(
                FrameLayout(context).apply { addView(column, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER)) },
                ViewGroup.LayoutParams(-1, -1)
            )
        }
        return FrameLayout(context).apply {
            setBackgroundColor(BrowserTheme.background)
            addView(scroller, FrameLayout.LayoutParams(-1, -1))
        }
    }

    private fun searchField(context: Context, sizes: AutoUiSizes, onSubmit: (String) -> Unit): EditText =
        EditText(context).apply {
            hint = context.getString(R.string.browser_start_page_search_hint)
            setTextColor(BrowserTheme.textPrimary)
            setHintTextColor(BrowserTheme.textSecondary)
            setSingleLine()
            gravity = Gravity.CENTER_VERTICAL
            imeOptions = EditorInfo.IME_ACTION_GO
            setPadding(
                sizes.dpInt(AutoUiSizes.HORIZONTAL_PADDING_DP), 0,
                sizes.dpInt(AutoUiSizes.HORIZONTAL_PADDING_DP), 0
            )
            background = GradientDrawable().apply {
                setColor(BrowserTheme.addressPillBackground)
                cornerRadius = sizes.dp(AutoUiSizes.TOUCH_TARGET_DP) / 2f
            }
            setOnEditorActionListener { _, action, event ->
                val go = action == EditorInfo.IME_ACTION_GO ||
                    (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_UP)
                if (go && text.isNotBlank()) onSubmit(text.toString())
                go
            }
        }

    private fun shortcutGrid(context: Context, sizes: AutoUiSizes, onSubmit: (String) -> Unit): GridLayout =
        GridLayout(context).apply {
            columnCount = 3
            DEFAULT_START_PAGE_SHORTCUTS.forEach { shortcut ->
                addView(
                    tile(context, sizes, shortcut) { onSubmit(shortcut.url) },
                    GridLayout.LayoutParams().apply {
                        width = sizes.dpInt(AutoUiSizes.MENU_TILE_WIDTH_DP)
                        height = sizes.dpInt(AutoUiSizes.MENU_TILE_HEIGHT_DP)
                        val gap = sizes.dpInt(AutoUiSizes.MENU_TILE_GAP_DP)
                        setMargins(gap, gap, gap, gap)
                    }
                )
            }
        }

    private fun tile(context: Context, sizes: AutoUiSizes, shortcut: StartPageShortcut, onTap: () -> Unit): View {
        val chipSize = sizes.dpInt(AutoUiSizes.ICON_LARGE_DP + 16f)
        val chip = TextView(context).apply {
            text = shortcut.glyph
            gravity = Gravity.CENTER
            setTextColor(BrowserTheme.textPrimary)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(BrowserTheme.tileIconChip)
            }
        }
        val label = TextView(context).apply {
            text = shortcut.label
            gravity = Gravity.CENTER
            setTextColor(BrowserTheme.textSecondary)
            maxLines = 1
        }
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            background = GradientDrawable().apply {
                setColor(BrowserTheme.tileBackground)
                cornerRadius = sizes.cornerRadius
            }
            addView(chip, LinearLayout.LayoutParams(chipSize, chipSize).apply {
                bottomMargin = sizes.dpInt(AutoUiSizes.CONTENT_GAP_DP)
            })
            addView(label, LinearLayout.LayoutParams(-2, -2))
            setOnClickListener { onTap() }
        }
    }

    private const val SEARCH_WIDTH_DP = 280f
}
