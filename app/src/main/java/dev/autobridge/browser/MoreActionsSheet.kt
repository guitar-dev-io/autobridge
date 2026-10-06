package dev.autobridge.browser

import android.graphics.Typeface
import android.app.Activity
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import dev.autobridge.R

/**
 * "All actions": every action the phone browser offers, grouped so the catalog is scannable
 * instead of one list of thirty rows. Reached from the quick menu's "All actions ›" and from the
 * floating button when it is bound to it.
 *
 * Section order, membership and *shape* follow the mockup (`docs/design/10_PhoneBrowserMenu.png`).
 * Each section is drawn in the form that suits what it holds, which is why this sheet is built
 * section by section rather than from one uniform row list:
 *
 * - **Car** — the primary [DrawerAction.SEND_TO_CAR] button, then Add to queue / Get from car.
 * - **Navigate**, **This page**, **AutoBridge** — grids of equal tiles: short labels, no captions,
 *   several visible at a glance.
 * - **Saved & open elsewhere**, **Settings & about** — grouped list cards: longer labels, a
 *   secondary hint on the right, and a chevron or ↗ saying whether the row opens a screen or leaves
 *   the app.
 *
 * Car-only concepts this surface has no version of — multiple tabs, split layout, the car's own
 * Media Center and diagnostics screens — are left off rather than drawn as dead tiles, even though
 * the mockup shows Tabs / New tab / Split: the phone keeps one page per Activity (see
 * [BrowserActivity]), so those tiles would have nothing to switch between.
 * [BrowserActivity.runMenuAction] already treats them as no-ops on [MenuSurface.PHONE].
 *
 * Every control dispatches a [DrawerAction] straight back to [BrowserActivity.runMenuAction]
 * through [onAction]; this sheet implements no browser behaviour of its own, exactly as
 * [BrowserMenuSheet] does, so moving an action here changes where it is reached and nothing about
 * what it does.
 */
class MoreActionsSheet(
    private val activity: Activity,
    private val sizes: AutoUiSizes,
    /** Re-read before every render, so Back/Forward grey out against the truth. */
    private val state: () -> BrowserMenuState,
    private val onAction: (DrawerAction) -> Unit,
    /** Reopens the main sheet when the header back arrow is tapped. Null closes without reopening. */
    private val onBack: (() -> Unit)? = null,
) {
    private val shell = BrowserSheetShell(activity, sizes)

    /** Local zoom-percent approximation: updated on each +/− tap, displayed between the buttons. */
    private var zoomPercent = 100
    private var zoomReadout: TextView? = null

    /**
     * One tile in a grid section. [enabled] reads live state (only Back/Forward use it); a disabled
     * tile keeps its place, dimmed and inert, so the grid's shape never shifts under the user.
     *
     * [titleRes] is a string *id*: the grids are properties of this sheet, built once, so holding
     * resolved text would pin whatever language was in force when the sheet was constructed.
     */
    private data class Tile(
        val action: DrawerAction,
        val icon: BrowserIcon?,
        @StringRes val titleRes: Int,
        val enabled: (BrowserMenuState) -> Boolean = { true },
    )

    /**
     * One row of a grouped list card: a label, an optional right-hand hint ([hintRes] of 0 for the
     * rows the mockup leaves bare), and a trailing glyph.
     */
    private data class CardRow(
        val action: DrawerAction,
        @StringRes val titleRes: Int,
        @StringRes val hintRes: Int = 0,
        /** ↗ for rows that leave the app, › for rows that open another screen inside it. */
        val leavesApp: Boolean = false,
    )

    private val navigateTiles = listOf(
        Tile(DrawerAction.NAV_BACK, BrowserIcon.BACK, R.string.drawer_back) { it.canGoBack },
        Tile(DrawerAction.NAV_FORWARD, BrowserIcon.FORWARD, R.string.drawer_forward) { it.canGoForward },
        Tile(DrawerAction.RELOAD, BrowserIcon.RELOAD, R.string.drawer_reload),
        Tile(DrawerAction.HOME, BrowserIcon.HOME_PAGE, R.string.more_start_page),
    )

    private val thisPageTiles = listOf(
        Tile(DrawerAction.BOOKMARK_PAGE, BrowserIcon.BOOKMARK_ADD, R.string.drawer_bookmark),
        Tile(DrawerAction.FIND_IN_PAGE, BrowserIcon.SEARCH, R.string.more_find_in_page),
        Tile(DrawerAction.TOGGLE_FULLSCREEN, BrowserIcon.FULLSCREEN_ENTER, R.string.drawer_fullscreen),
        Tile(DrawerAction.COPY_URL, BrowserIcon.COPY, R.string.more_copy_url),
        Tile(DrawerAction.PASTE_AND_GO, BrowserIcon.PASTE, R.string.more_paste_and_go),
    )

    // The phone's stand-ins for the car's Media Center and Agent screens: each is a normal Activity
    // away, so they are listed as plain labelled tiles rather than browser controls.
    private val autoBridgeTiles = listOf(
        Tile(DrawerAction.NOW_PLAYING, null, R.string.drawer_now_playing),
        Tile(DrawerAction.MEDIA_LIBRARY, null, R.string.drawer_library),
        Tile(DrawerAction.AGENT, null, R.string.drawer_agent),
    )

    private val savedRows = listOf(
        CardRow(DrawerAction.BOOKMARKS, R.string.drawer_bookmarks),
        CardRow(DrawerAction.HISTORY, R.string.more_history, R.string.more_history_hint),
        CardRow(DrawerAction.DOWNLOADS, R.string.more_downloads, R.string.more_downloads_hint),
        CardRow(DrawerAction.OPEN_EXTERNAL, R.string.more_open_external, leavesApp = true),
    )

    private val settingsRows = listOf(
        CardRow(DrawerAction.SETTINGS, R.string.more_browser_settings),
        CardRow(DrawerAction.CLEAR_DATA, R.string.more_clear_browsing_data),
        CardRow(DrawerAction.SUPPORT, R.string.more_support, R.string.more_support_hint, leavesApp = true),
        CardRow(DrawerAction.LICENSES, R.string.more_licenses),
        CardRow(DrawerAction.GITHUB, R.string.more_github, R.string.more_github_hint, leavesApp = true),
    )

    fun show() {
        val current = state()
        val container = shell.contentColumn()
        container.addView(shell.grip())
        container.addView(header())

        container.addView(sectionLabel(R.string.more_section_car))
        container.addView(sendToCarButton())
        container.addView(carPairRow())

        container.addView(sectionLabel(R.string.more_section_navigate))
        tileRows(navigateTiles, current).forEach(container::addView)

        container.addView(sectionLabel(R.string.more_section_this_page))
        tileRows(thisPageTiles, current).forEach(container::addView)
        container.addView(standaloneSettingsRow(zoomRow()))
        container.addView(standaloneSettingsRow(desktopSwitchRow(current)))

        container.addView(sectionLabel(R.string.more_section_saved))
        container.addView(listCard(savedRows))

        container.addView(sectionLabel(R.string.more_section_autobridge))
        tileRows(autoBridgeTiles, current).forEach(container::addView)

        container.addView(sectionLabel(R.string.more_section_settings))
        container.addView(listCard(settingsRows))

        container.addView(exitRow())
        shell.show(container)
    }

    /**
     * Header: "All actions" title and a round ✕. The [onBack] constructor param is kept for callers
     * but the back arrow is no longer rendered — the header is just the title and the close button.
     */
    private fun header(): View {
        val title = TextView(activity).apply {
            text = activity.getString(R.string.more_title)
            textSize = shell.sp(AutoUiSizes.ICON_LARGE_DP * 0.9f)
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(BrowserTheme.textPrimary)
        }
        return LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(shell.closeButton { shell.dismiss() })
            layoutParams = rowParams()
        }
    }

    private fun sectionLabel(@StringRes titleRes: Int): View = TextView(activity).apply {
        text = activity.getString(titleRes).uppercase()
        textSize = shell.sp(AutoUiSizes.ICON_SMALL_DP * 0.68f)
        letterSpacing = 0.08f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(BrowserTheme.textSecondary)
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = shell.gap(); bottomMargin = shell.gap() / 2 }
    }

    // --------------------------------------------------------------------------------------- car

    /** The one full-width, accent-filled button on the sheet: the thing this browser exists for. */
    private fun sendToCarButton(): View = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        background = shell.rounded(BrowserTheme.accent, shell.cornerRadius())
        contentDescription = activity.getString(R.string.drawer_send_to_car)
        addView(tintedIcon(BrowserIcon.CAR, BrowserTheme.onPrimary).apply {
            (layoutParams as LinearLayout.LayoutParams).marginEnd = shell.gap()
        })
        addView(TextView(activity).apply {
            text = activity.getString(R.string.drawer_send_to_car)
            textSize = shell.sp(AutoUiSizes.ICON_SMALL_DP * 0.95f)
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(BrowserTheme.onPrimary)
        })
        setOnClickListener { shell.dismiss(); onAction(DrawerAction.SEND_TO_CAR) }
        layoutParams = rowParams().apply { height = sizes.dpInt(AutoUiSizes.SHEET_PRIMARY_CTA_HEIGHT_DP) }
    }

    /** Add to queue and Get from car, side by side under the primary button. */
    private fun carPairRow(): View = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        addView(
            inlineTile(BrowserIcon.ADD, R.string.send_queue_title) { onAction(DrawerAction.ADD_TO_QUEUE) },
            LinearLayout.LayoutParams(0, sizes.dpInt(AutoUiSizes.SHEET_ROW_HEIGHT_DP), 1f)
        )
        addView(
            inlineTile(BrowserIcon.RECEIVE, R.string.more_get_from_car) { onAction(DrawerAction.RECEIVE_FROM_CAR) },
            LinearLayout.LayoutParams(0, sizes.dpInt(AutoUiSizes.SHEET_ROW_HEIGHT_DP), 1f)
                .apply { marginStart = shell.gap() }
        )
        layoutParams = rowParams()
    }

    /** A tonal pill with its icon and label on one line — the car pair's shape. */
    private fun inlineTile(icon: BrowserIcon, @StringRes titleRes: Int, onClick: () -> Unit): View {
        val label = activity.getString(titleRes)
        return LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = shell.rounded(BrowserTheme.tileBackground, shell.cornerRadius())
            contentDescription = label
            addView(tintedIcon(icon, BrowserTheme.iconEnabled).apply {
                (layoutParams as LinearLayout.LayoutParams).marginEnd = shell.gap() / 2
            })
            addView(TextView(activity).apply {
                text = label
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                textSize = shell.sp(AutoUiSizes.ICON_SMALL_DP * 0.82f)
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(BrowserTheme.textPrimary)
            })
            setOnClickListener { shell.dismiss(); onClick() }
        }
    }

    // ------------------------------------------------------------------------------------- grids

    /** Lays [tiles] out three to a row, padding the last row so its tiles keep the same width. */
    private fun tileRows(tiles: List<Tile>, current: BrowserMenuState): List<View> =
        tiles.chunked(TILES_PER_ROW).map { chunk ->
            LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                repeat(TILES_PER_ROW) { column ->
                    val params = LinearLayout.LayoutParams(
                        0, sizes.dpInt(AutoUiSizes.SHEET_TILE_HEIGHT_DP), 1f
                    ).apply { if (column > 0) marginStart = shell.gap() }
                    // An empty spacer rather than a shorter row: a trailing gap keeps every tile the
                    // same width whether its row holds one, two or three of them.
                    addView(chunk.getOrNull(column)?.let { tile(it, current) } ?: View(activity), params)
                }
                layoutParams = rowParams()
            }
        }

    /** One grid tile: an optional icon over a label, on a tonal rounded square. */
    private fun tile(tile: Tile, current: BrowserMenuState): View {
        val enabled = tile.enabled(current)
        val label = activity.getString(tile.titleRes)
        val tint = if (enabled) BrowserTheme.iconEnabled else BrowserTheme.iconDisabled
        return LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = shell.rounded(
                if (enabled) BrowserTheme.tileBackground else BrowserTheme.tileDisabledBackground,
                shell.cornerRadius()
            )
            setPadding(shell.gap() / 2, shell.gap(), shell.gap() / 2, shell.gap())
            contentDescription = label
            isClickable = enabled
            isFocusable = enabled
            if (enabled) setOnClickListener { shell.dismiss(); onAction(tile.action) }
            tile.icon?.let {
                addView(tintedIcon(it, tint).apply {
                    (layoutParams as LinearLayout.LayoutParams).bottomMargin = shell.gap()
                })
            }
            val labelSp = shell.sp(AutoUiSizes.ICON_SMALL_DP * 0.78f)
            addView(
                TextView(activity).apply {
                    text = label
                    gravity = Gravity.CENTER
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setTextColor(if (enabled) BrowserTheme.textPrimary else BrowserTheme.iconDisabled)
                    // Thai labels run longer than the mockup's English ones and were being cut
                    // mid-word inside the fixed-width tile. Shrink to fit instead.
                    setAutoSizeTextTypeUniformWithConfiguration(
                        (labelSp * 0.72f).toInt().coerceAtLeast(1),
                        labelSp.toInt().coerceAtLeast(2),
                        1,
                        android.util.TypedValue.COMPLEX_UNIT_SP,
                    )
                },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }
    }

    // ------------------------------------------------------------------------------- list cards

    /** A grouped card of label rows divided by hairlines, the mockup's settings-list treatment. */
    private fun listCard(rows: List<CardRow>): View = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        background = shell.rounded(BrowserTheme.sheetCardBackground, shell.cornerRadius())
        rows.forEachIndexed { index, row ->
            if (index > 0) addView(divider())
            addView(cardRow(row))
        }
        layoutParams = rowParams()
    }

    private fun cardRow(row: CardRow): View {
        val label = activity.getString(row.titleRes)
        val trailing = if (row.leavesApp) BrowserIcon.OPEN_EXTERNAL else BrowserIcon.CHEVRON_RIGHT
        return LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(shell.pad(), 0, shell.pad(), 0)
            contentDescription = label
            isClickable = true
            isFocusable = true
            // The title takes its natural width and the hint absorbs what is left: the other way
            // round, a long translated hint measures first and squeezes the title to nothing.
            addView(
                TextView(activity).apply {
                    text = label
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    textSize = shell.sp(AutoUiSizes.ICON_SMALL_DP * 0.9f)
                    setTextColor(BrowserTheme.textPrimary)
                },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
            val hint = if (row.hintRes != 0) activity.getString(row.hintRes) else ""
            addView(
                TextView(activity).apply {
                    text = hint
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    gravity = Gravity.END
                    textSize = shell.sp(AutoUiSizes.ICON_SMALL_DP * 0.72f)
                    setTextColor(BrowserTheme.textSecondary)
                },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    .apply { marginStart = shell.gap(); marginEnd = shell.gap() / 2 }
            )
            addView(ImageView(activity).apply {
                setImageDrawable(iconDrawable(trailing, BrowserTheme.textSecondary))
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                val side = sizes.dpInt(AutoUiSizes.ICON_MEDIUM_DP)
                layoutParams = LinearLayout.LayoutParams(side, side)
            })
            setOnClickListener { shell.dismiss(); onAction(row.action) }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, sizes.dpInt(AutoUiSizes.SHEET_ROW_HEIGHT_DP)
            )
        }
    }

    /**
     * Wraps a single page-setting row (zoom or the desktop-site switch) in its own rounded card.
     * The two settings used to share one grouped card; the mockup draws them as separate standalone
     * rows, so each gets its own [BrowserTheme.sheetCardBackground] background instead.
     */
    private fun standaloneSettingsRow(row: View): View = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        background = shell.rounded(BrowserTheme.sheetCardBackground, shell.cornerRadius())
        addView(row)
        layoutParams = rowParams()
    }

    /** Zoom in one row: a label on the left, "−" and "+" that repeat without closing the sheet. */
    private fun zoomRow(): View = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(shell.pad(), 0, shell.pad(), 0)
        addView(
            TextView(activity).apply {
                text = activity.getString(R.string.more_zoom)
                textSize = shell.sp(AutoUiSizes.ICON_SMALL_DP * 0.9f)
                setTextColor(BrowserTheme.textPrimary)
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        // These do not dismiss the sheet: zoom is adjusted by repeated taps, and closing between
        // each would make "+ + +" three separate sheet openings. [BrowserActivity] zooms the
        // WebView by a relative factor and never reads a level back, so the readout between the
        // buttons tracks a local approximation rather than a real level.
        val readout = TextView(activity).apply {
            text = activity.getString(R.string.browser_zoom_percent, zoomPercent)
            gravity = Gravity.CENTER
            minWidth = sizes.dpInt(48f)
            textSize = shell.sp(AutoUiSizes.ICON_SMALL_DP * 0.9f)
            setTextColor(BrowserTheme.textPrimary)
        }
        zoomReadout = readout
        addView(zoomButton(BrowserIcon.REMOVE, "Zoom out") {
            if (zoomPercent > ZOOM_MIN_PERCENT) zoomPercent -= ZOOM_STEP_PERCENT
            readout.text = activity.getString(R.string.browser_zoom_percent, zoomPercent)
            onAction(DrawerAction.ZOOM_OUT)
        })
        addView(readout, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { marginStart = shell.gap(); marginEnd = shell.gap() })
        addView(zoomButton(BrowserIcon.ADD, "Zoom in") {
            if (zoomPercent < ZOOM_MAX_PERCENT) zoomPercent += ZOOM_STEP_PERCENT
            readout.text = activity.getString(R.string.browser_zoom_percent, zoomPercent)
            onAction(DrawerAction.ZOOM_IN)
        })
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, sizes.dpInt(AutoUiSizes.SHEET_ROW_HEIGHT_DP)
        )
    }

    private fun desktopSwitchRow(current: BrowserMenuState): View = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(shell.pad(), 0, shell.pad(), 0)
        addView(
            TextView(activity).apply {
                text = activity.getString(R.string.drawer_request_desktop)
                textSize = shell.sp(AutoUiSizes.ICON_SMALL_DP * 0.9f)
                setTextColor(BrowserTheme.textPrimary)
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        addView(Switch(activity).apply {
            isChecked = current.isDesktop
            // The sheet stays open: the page reloads underneath it, as it does from the quick menu.
            setOnCheckedChangeListener { _, _ -> onAction(DrawerAction.TOGGLE_DESKTOP) }
        })
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, sizes.dpInt(AutoUiSizes.SHEET_ROW_HEIGHT_DP)
        )
    }

    private fun zoomButton(icon: BrowserIcon, description: String, onClick: () -> Unit): View =
        ImageView(activity).apply {
            setImageDrawable(iconDrawable(icon, BrowserTheme.textPrimary))
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            contentDescription = description
            val side = sizes.dpInt(AutoUiSizes.TOUCH_TARGET_DP)
            val inset = (side - sizes.dpInt(AutoUiSizes.ICON_MEDIUM_DP)) / 2
            setPadding(inset, inset, inset, inset)
            background = shell.rounded(BrowserTheme.tileBackground, side / 2f)
            layoutParams = LinearLayout.LayoutParams(side, side)
            setOnClickListener { onClick() }
        }

    // ------------------------------------------------------------------------------------ pieces

    /** A [BrowserIcon] vector at the sheet's standard icon size, tinted. */
    private fun tintedIcon(icon: BrowserIcon, tint: Int): ImageView = ImageView(activity).apply {
        setImageDrawable(iconDrawable(icon, tint))
        scaleType = ImageView.ScaleType.CENTER_INSIDE
        val side = sizes.dpInt(AutoUiSizes.SHEET_ICON_DP)
        layoutParams = LinearLayout.LayoutParams(side, side)
    }

    /** The shared [BrowserIcon] vector tinted for use in this sheet; see [BrowserIcon]. */
    private fun iconDrawable(icon: BrowserIcon, color: Int) =
        ContextCompat.getDrawable(activity, icon.resId)!!.mutate().apply { setTint(color) }

    /** The faint rule between rows inside a grouped card. */
    private fun divider(): View = View(activity).apply {
        setBackgroundColor(BrowserTheme.hairline)
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, sizes.dpInt(1f)
        )
    }

    /**
     * "Exit browser", on its own outside every section: an outlined pill in the error colour, the
     * one row on this sheet that leaves the app rather than acting inside it. [DrawerAction.APP_HOME]
     * is what every other exit on this surface already sends (the toolbar's Home control, the car's
     * own exit) — reusing it here is what keeps "leave the browser" meaning one thing.
     */
    private fun exitRow(): View {
        val label = TextView(activity).apply {
            text = activity.getString(R.string.more_exit_browser)
            textSize = shell.sp(AutoUiSizes.ICON_SMALL_DP * 0.95f)
            gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(BrowserTheme.error)
        }
        return LinearLayout(activity).apply {
            gravity = Gravity.CENTER
            background = shell.rounded(android.graphics.Color.TRANSPARENT, shell.cornerRadius()).apply {
                setStroke(sizes.dpInt(1.5f), BrowserTheme.error)
            }
            contentDescription = activity.getString(R.string.more_exit_browser_caption)
            isClickable = true
            isFocusable = true
            addView(label)
            setOnClickListener { shell.dismiss(); onAction(DrawerAction.APP_HOME) }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, sizes.dpInt(AutoUiSizes.SHEET_ROW_HEIGHT_DP)
            ).apply { topMargin = shell.gap() }
        }
    }

    private fun rowParams() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { bottomMargin = shell.gap() }

    private companion object {
        /** Matches the mockup's three-up grids; the last row is padded rather than stretched. */
        const val TILES_PER_ROW = 3
        const val ZOOM_MIN_PERCENT = 50
        const val ZOOM_MAX_PERCENT = 200
        const val ZOOM_STEP_PERCENT = 10
    }
}
