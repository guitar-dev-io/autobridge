package dev.autobridge.browser

import android.app.Activity
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import dev.autobridge.R

/**
 * "All actions": every action the phone browser offers, grouped so the catalog is scannable
 * instead of one list of thirty rows. Reached from the quick menu's "All actions ›" and from the
 * floating button when it is bound to it.
 *
 * Section order and membership follow the mockup (`docs/design/10_PhoneBrowserMenu.png`): **Car**
 * (Send to car, Add to queue, Get from car), **Navigate**, **This page**, **Saved & open
 * elsewhere**, **AutoBridge** (Now playing, Library, Agent — this surface's phone-side equivalents
 * of the car's Media Center / Agent screens), and **Settings & about**. Car-only concepts this
 * surface has no version of — multiple tabs, split layout, the car's own Media Center/diagnostics
 * screens — are left off rather than listed as dead rows; [BrowserActivity.runMenuAction] already
 * treats them as no-ops on [MenuSurface.PHONE] for the same reason.
 *
 * Every row dispatches a [DrawerAction] straight back to [BrowserActivity.runMenuAction] through
 * [onAction]; this sheet implements no browser behaviour of its own, exactly as [BrowserMenuSheet]
 * does, so moving an action here changes where it is reached and nothing about what it does.
 *
 * Zoom is a single row with its own "−" and "+" controls instead of two separate entries, so the
 * two opposite adjustments of one setting sit together and can be repeated without the sheet
 * closing between taps.
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

    /**
     * One list entry. [chevron] marks rows that open another screen rather than acting in place.
     * [enabled] reads live state (only Back/Forward use it); a disabled row stays in the list,
     * dimmed and inert, so the catalog's shape never shifts under the user.
     *
     * The two texts are string *ids*: [sections] is a property of this sheet, built once, so
     * holding the resolved text would pin whatever language was in force when the sheet was
     * constructed.
     */
    private data class Row(
        val action: DrawerAction,
        val icon: BrowserIcon,
        @StringRes val titleRes: Int,
        @StringRes val subtitleRes: Int,
        val chevron: Boolean = false,
        val enabled: (BrowserMenuState) -> Boolean = { true },
    )

    private data class Section(@StringRes val titleRes: Int, val rows: List<Row>)

    // Icons mirror [BrowserDrawerModel]'s car-surface items where the same action exists there, so
    // the same action resolves to the same artwork on both surfaces.
    private val sections = listOf(
        Section(
            R.string.more_section_car,
            listOf(
                Row(DrawerAction.SEND_TO_CAR, BrowserIcon.CAR, R.string.send_now_title, R.string.send_now_caption),
                Row(DrawerAction.ADD_TO_QUEUE, BrowserIcon.ADD, R.string.send_queue_title, R.string.send_queue_caption),
                Row(DrawerAction.RECEIVE_FROM_CAR, BrowserIcon.RECEIVE, R.string.more_get_from_car, R.string.more_get_from_car_caption),
            )
        ),
        Section(
            R.string.more_section_navigate,
            listOf(
                Row(DrawerAction.NAV_BACK, BrowserIcon.BACK, R.string.drawer_back, R.string.more_back_caption, enabled = { it.canGoBack }),
                Row(DrawerAction.NAV_FORWARD, BrowserIcon.FORWARD, R.string.drawer_forward, R.string.more_forward_caption, enabled = { it.canGoForward }),
                Row(DrawerAction.RELOAD, BrowserIcon.RELOAD, R.string.drawer_reload, R.string.more_reload_caption),
                Row(DrawerAction.HOME, BrowserIcon.HOME_PAGE, R.string.more_start_page, R.string.more_start_page_caption),
            )
        ),
        Section(
            R.string.more_section_this_page,
            listOf(
                Row(DrawerAction.BOOKMARK_PAGE, BrowserIcon.BOOKMARK_ADD, R.string.drawer_bookmark, R.string.more_bookmark_caption),
                Row(DrawerAction.FIND_IN_PAGE, BrowserIcon.SEARCH, R.string.more_find_in_page, R.string.more_find_in_page_caption, chevron = true),
                Row(DrawerAction.TOGGLE_FULLSCREEN, BrowserIcon.FULLSCREEN_ENTER, R.string.drawer_fullscreen, R.string.more_fullscreen_caption),
                Row(DrawerAction.COPY_URL, BrowserIcon.COPY, R.string.more_copy_url, R.string.more_copy_url_caption),
                Row(DrawerAction.PASTE_AND_GO, BrowserIcon.PASTE, R.string.more_paste_and_go, R.string.more_paste_and_go_caption),
                Row(DrawerAction.TOGGLE_DESKTOP, BrowserIcon.DESKTOP_MODE, R.string.drawer_request_desktop, R.string.more_desktop_caption),
            )
        ),
        Section(
            R.string.more_section_saved,
            listOf(
                Row(DrawerAction.BOOKMARKS, BrowserIcon.BOOKMARK_LIST, R.string.drawer_bookmarks, R.string.more_bookmarks_list_caption, chevron = true),
                Row(DrawerAction.HISTORY, BrowserIcon.HISTORY, R.string.more_history, R.string.more_history_caption, chevron = true),
                Row(DrawerAction.DOWNLOADS, BrowserIcon.DOWNLOAD, R.string.more_downloads, R.string.more_downloads_caption, chevron = true),
                Row(DrawerAction.OPEN_EXTERNAL, BrowserIcon.OPEN_EXTERNAL, R.string.more_open_external, R.string.more_open_external_caption, chevron = true),
            )
        ),
        Section(
            R.string.more_section_autobridge,
            listOf(
                Row(DrawerAction.NOW_PLAYING, BrowserIcon.PLAY, R.string.drawer_now_playing, R.string.more_now_playing_caption, chevron = true),
                Row(DrawerAction.MEDIA_LIBRARY, BrowserIcon.LIBRARY, R.string.drawer_library, R.string.more_library_caption, chevron = true),
                Row(DrawerAction.AGENT, BrowserIcon.AGENT, R.string.drawer_agent, R.string.more_agent_caption, chevron = true),
            )
        ),
        Section(
            R.string.more_section_settings,
            listOf(
                Row(DrawerAction.SETTINGS, BrowserIcon.SETTINGS, R.string.more_browser_settings, R.string.more_browser_settings_caption, chevron = true),
                Row(DrawerAction.CLEAR_DATA, BrowserIcon.DELETE, R.string.more_clear_browsing_data, R.string.more_clear_browsing_data_caption, chevron = true),
                Row(DrawerAction.SUPPORT, BrowserIcon.SUPPORT, R.string.more_support, R.string.more_support_caption, chevron = true),
                Row(DrawerAction.LICENSES, BrowserIcon.LICENSES, R.string.more_licenses, R.string.more_licenses_caption, chevron = true),
                Row(DrawerAction.GITHUB, BrowserIcon.CODE, R.string.more_github, R.string.more_github_caption, chevron = true),
            )
        ),
    )

    fun show() {
        val current = state()
        val container = shell.contentColumn()
        container.addView(shell.grip())
        container.addView(header())
        sections.forEach { section ->
            container.addView(sectionLabel(section.titleRes))
            section.rows.forEach { container.addView(listRow(it, current)) }
        }
        container.addView(zoomRow())
        container.addView(exitRow())
        shell.show(container)
    }

    /**
     * Header: a circular back arrow, then "All actions", matching the mockup's `‹  All actions`.
     * The back arrow dismisses this sheet and reopens the main one through [onBack] when supplied;
     * with no handler it simply closes, so the sheet is never a dead end.
     */
    private fun header(): View {
        val back = ImageView(activity).apply {
            setImageDrawable(iconDrawable(BrowserIcon.BACK, BrowserTheme.textPrimary))
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            contentDescription = activity.getString(R.string.browser_back)
            val side = sizes.dpInt(AutoUiSizes.SHEET_CLOSE_BUTTON_DP)
            val inset = (side - sizes.dpInt(AutoUiSizes.ICON_LARGE_DP)) / 2
            setPadding(inset, inset, inset, inset)
            background = shell.rounded(BrowserTheme.sheetCardBackground, side / 2f)
            layoutParams = LinearLayout.LayoutParams(side, side).apply { marginEnd = shell.pad() }
            setOnClickListener { shell.dismiss(); onBack?.invoke() }
        }
        val title = TextView(activity).apply {
            text = activity.getString(R.string.more_title)
            textSize = shell.sp(AutoUiSizes.ICON_LARGE_DP * 0.9f)
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(BrowserTheme.textPrimary)
        }
        return LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(back)
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
        ).apply { topMargin = shell.gap() / 2; bottomMargin = shell.gap() / 2 }
    }

    private fun listRow(row: Row, current: BrowserMenuState): View {
        val enabled = row.enabled(current)
        val icon = circularIcon(row.icon, enabled)
        val title = activity.getString(row.titleRes)
        val texts = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(activity).apply {
                text = title
                textSize = shell.sp(AutoUiSizes.ICON_SMALL_DP * 0.95f)
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(if (enabled) BrowserTheme.textPrimary else BrowserTheme.iconDisabled)
            })
            activity.getString(row.subtitleRes).takeIf { it.isNotBlank() }?.let { subtitle ->
                addView(TextView(activity).apply {
                    text = subtitle
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    textSize = shell.sp(AutoUiSizes.ICON_SMALL_DP * 0.72f)
                    setTextColor(BrowserTheme.textSecondary)
                })
            }
        }
        return LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = shell.rounded(BrowserTheme.sheetCardBackground, shell.cornerRadius())
            setPadding(shell.pad(), 0, shell.pad(), 0)
            contentDescription = title
            isClickable = enabled
            isFocusable = enabled
            addView(icon)
            addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            if (row.chevron) {
                addView(ImageView(activity).apply {
                    setImageDrawable(iconDrawable(BrowserIcon.CHEVRON_RIGHT, BrowserTheme.textSecondary))
                    scaleType = ImageView.ScaleType.CENTER_INSIDE
                    val size = sizes.dpInt(AutoUiSizes.ICON_MEDIUM_DP)
                    layoutParams = LinearLayout.LayoutParams(size, size)
                })
            }
            if (enabled) setOnClickListener { shell.dismiss(); onAction(row.action) }
            layoutParams = rowParams().apply { height = sizes.dpInt(AutoUiSizes.SHEET_ROW_HEIGHT_DP) }
        }
    }

    /** A leading [BrowserIcon] vector inside a circular tonal area, the mockup's list-row icon treatment. */
    private fun circularIcon(icon: BrowserIcon, enabled: Boolean = true): View = ImageView(activity).apply {
        setImageDrawable(iconDrawable(icon, if (enabled) BrowserTheme.iconEnabled else BrowserTheme.iconDisabled))
        scaleType = ImageView.ScaleType.CENTER_INSIDE
        val side = sizes.dpInt(AutoUiSizes.SHEET_LIST_ICON_DP)
        val inset = (side - sizes.dpInt(AutoUiSizes.ICON_MEDIUM_DP)) / 2
        setPadding(inset, inset, inset, inset)
        background = shell.rounded(BrowserTheme.tileIconChip, side / 2f)
        layoutParams = LinearLayout.LayoutParams(side, side).apply { marginEnd = shell.pad() }
    }

    /** The shared [BrowserIcon] vector tinted for use in this sheet; see [BrowserIcon]. */
    private fun iconDrawable(icon: BrowserIcon, color: Int) =
        ContextCompat.getDrawable(activity, icon.resId)!!.mutate().apply { setTint(color) }

    /** Zoom in one row: a label on the left, "−" and "+" that repeat without closing the sheet. */
    private fun zoomRow(): View {
        val texts = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(activity).apply {
                text = activity.getString(R.string.more_zoom)
                textSize = shell.sp(AutoUiSizes.ICON_SMALL_DP * 0.95f)
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(BrowserTheme.textPrimary)
            })
            addView(TextView(activity).apply {
                text = activity.getString(R.string.more_zoom_caption)
                textSize = shell.sp(AutoUiSizes.ICON_SMALL_DP * 0.72f)
                setTextColor(BrowserTheme.textSecondary)
            })
        }
        val icon = circularIcon(BrowserIcon.SEARCH)
        return LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = shell.rounded(BrowserTheme.sheetCardBackground, shell.cornerRadius())
            setPadding(shell.pad(), 0, shell.pad(), 0)
            addView(icon)
            addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            // These do not dismiss the sheet: zoom is adjusted by repeated taps, and closing between
            // each would make "+ + +" three separate sheet openings.
            addView(zoomButton(BrowserIcon.REMOVE, "Zoom out") { onAction(DrawerAction.ZOOM_OUT) })
            addView(zoomButton(BrowserIcon.ADD, "Zoom in") { onAction(DrawerAction.ZOOM_IN) }.also {
                (it.layoutParams as LinearLayout.LayoutParams).marginStart = shell.gap()
            })
            layoutParams = rowParams().apply { height = sizes.dpInt(AutoUiSizes.SHEET_ROW_HEIGHT_DP) }
        }
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
            val outline = shell.rounded(android.graphics.Color.TRANSPARENT, shell.cornerRadius()).apply {
                setStroke(sizes.dpInt(1.5f), BrowserTheme.error)
            }
            background = outline
            contentDescription = activity.getString(R.string.more_exit_browser_caption)
            isClickable = true
            isFocusable = true
            addView(label)
            setOnClickListener { shell.dismiss(); onAction(DrawerAction.APP_HOME) }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, sizes.dpInt(AutoUiSizes.SHEET_ROW_HEIGHT_DP)
            ).apply { topMargin = shell.gap() / 2 }
        }
    }

    private fun rowParams() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { bottomMargin = shell.gap() }
}
