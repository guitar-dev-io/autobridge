package dev.autobridge.browser

import android.app.Activity
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import dev.autobridge.R

/**
 * The phone's main browser sheet, redesigned around the one action the phone browser exists for:
 * handing a page or a search to the car.
 *
 * **Hierarchy, top to bottom** (`docs/design/09_PhoneBrowser.png`). A pair of primary buttons —
 * accent-filled **Send to car** beside the quieter tonal **Add to queue**; a row of four page
 * actions (Bookmark / History / Downloads / Find); and one grouped card of switches ending in
 * **All actions ›**. Everything else lives in [MoreActionsSheet] behind that row.
 *
 * The sheet draws no header and no address field: the toolbar owns the address, and in fullscreen
 * the ⌄ handle brings the toolbar back, so a second omnibox here would be a duplicate rather than
 * the only way in.
 *
 * **What this is and is not.** This is phone-only. The car surface draws its own menu on a Canvas
 * through [CarWebRenderer] from the shared [BrowserDrawerModel] data; this sheet renders real Views
 * and now lays itself out directly rather than mirroring that model's two-card grid, because the
 * phone's hierarchy (a primary pair, one tile row, one grouped card) is deliberately different from
 * the car's. It still owns no browser behaviour: every control dispatches a [DrawerAction] back
 * through [onAction], or opens one of the two secondary sheets through [onSendToCar] / [onMore].
 */
class BrowserMenuSheet(
    private val activity: Activity,
    private val sizes: AutoUiSizes,
    /** Re-read on every render, so the sheet redraws the switch it just moved. */
    private val state: () -> BrowserMenuState,
    private val onAction: (DrawerAction) -> Unit,
    /** Opens the dedicated "Send to car" sheet; the primary button's whole job. */
    private val onSendToCar: () -> Unit,
    /** Opens the "More actions" sheet. */
    private val onMore: () -> Unit,
) {
    private val shell = BrowserSheetShell(activity, sizes)
    private lateinit var container: LinearLayout

    /** Opens the sheet. */
    fun show() {
        container = shell.contentColumn()
        render()
        shell.show(container)
    }

    private fun perform(action: DrawerAction) {
        shell.dismiss()
        onAction(action)
    }

    /** A [BrowserIcon] as a plain, untinted-background `ImageView` at a given size/tint. */
    private fun icon(icon: BrowserIcon, sizeDp: Float, tint: Int): ImageView = ImageView(activity).apply {
        setImageResource(icon.resId)
        scaleType = ImageView.ScaleType.CENTER_INSIDE
        setColorFilter(tint)
        val side = sizes.dpInt(sizeDp)
        layoutParams = LinearLayout.LayoutParams(side, side)
    }

    private fun render() {
        val current = state()
        container.removeAllViews()
        container.addView(shell.grip())
        container.addView(primaryRow())
        container.addView(quickActionsRow())
        container.addView(settingsCard(current))
    }

    /** The faint full-width rule the mockup draws between the rows inside [settingsCard]. */
    private fun divider(): View = View(activity).apply {
        setBackgroundColor(BrowserTheme.hairline)
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, sizes.dpInt(1f)
        )
    }

    // ------------------------------------------------------------------------------ primary pair

    /**
     * The two car actions, side by side: accent-filled **Send to car** (wider, the thing this
     * browser exists for) and tonal **Add to queue** — "put it on screen now" next to "play it
     * after this one". Send opens the focused [SendToCarSheet] rather than firing straight away,
     * so a different URL or a search can still be chosen; Add to queue is one tap and done.
     */
    private fun primaryRow(): View = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        addView(
            pillButton(
                glyph = BrowserIcon.CAR,
                label = activity.getString(R.string.drawer_send_to_car),
                fill = BrowserTheme.accent,
                foreground = BrowserTheme.onPrimary,
            ) { shell.dismiss(); onSendToCar() },
            LinearLayout.LayoutParams(0, sizes.dpInt(AutoUiSizes.SHEET_PRIMARY_CTA_HEIGHT_DP), 1.25f)
        )
        addView(
            pillButton(
                glyph = BrowserIcon.ADD,
                label = activity.getString(R.string.send_queue_title),
                fill = BrowserTheme.tileBackground,
                foreground = BrowserTheme.textPrimary,
            ) { perform(DrawerAction.ADD_TO_QUEUE) },
            LinearLayout.LayoutParams(0, sizes.dpInt(AutoUiSizes.SHEET_PRIMARY_CTA_HEIGHT_DP), 1f)
                .apply { marginStart = shell.gap() }
        )
        layoutParams = rowParams()
    }

    /** One of [primaryRow]'s two pills: a centred icon and bold label on a filled rounded shape. */
    private fun pillButton(
        glyph: BrowserIcon,
        label: String,
        fill: Int,
        foreground: Int,
        onClick: () -> Unit,
    ): View = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        background = shell.rounded(fill, shell.cornerRadius())
        contentDescription = label
        addView(icon(glyph, AutoUiSizes.SHEET_ICON_DP * 0.95f, foreground).apply {
            (layoutParams as LinearLayout.LayoutParams).marginEnd = shell.gap()
        })
        addView(TextView(activity).apply {
            text = label
            maxLines = 1
            textSize = shell.sp(AutoUiSizes.ICON_SMALL_DP * 0.95f)
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(foreground)
        })
        setOnClickListener { onClick() }
    }

    // ------------------------------------------------------------------------------------- rows

    /** Bookmark / History / Downloads / Find: the page actions reached often enough to be one tap. */
    private fun quickActionsRow(): View = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        addView(
            actionButton(BrowserIcon.BOOKMARK_ADD, activity.getString(R.string.drawer_bookmark), enabled = true) {
                perform(DrawerAction.BOOKMARK_PAGE)
            },
            equalParams(0)
        )
        addView(
            actionButton(BrowserIcon.HISTORY, activity.getString(R.string.drawer_history), enabled = true) {
                perform(DrawerAction.HISTORY)
            },
            equalParams(1)
        )
        addView(
            actionButton(BrowserIcon.DOWNLOAD, activity.getString(R.string.drawer_downloads), enabled = true) {
                perform(DrawerAction.DOWNLOADS)
            },
            equalParams(2)
        )
        addView(
            actionButton(BrowserIcon.SEARCH, activity.getString(R.string.drawer_find), enabled = true) {
                perform(DrawerAction.FIND_IN_PAGE)
            },
            equalParams(3)
        )
        layoutParams = rowParams()
    }

    /**
     * The mockup's single grouped card: the page-level switches, then **All actions ›**, divided by
     * hairlines rather than separated into four floating cards. One background, so the three
     * switches and the way out read as one block of settings instead of four unrelated controls.
     */
    private fun settingsCard(current: BrowserMenuState): View = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        background = shell.rounded(BrowserTheme.sheetCardBackground, shell.cornerRadius())
        addView(switchRow(activity.getString(R.string.drawer_request_desktop), current.isDesktop) {
            onAction(DrawerAction.TOGGLE_DESKTOP)
        })
        addView(divider())
        // Same switch as Settings ▸ Content blocking; [BrowserActivity] reloads the page either way.
        addView(switchRow(activity.getString(R.string.car_browser_block_ads), current.adBlockEnabled) {
            onAction(DrawerAction.TOGGLE_AD_BLOCK)
        })
        addView(divider())
        // Not in the mockup's two-switch card, but fullscreen lost its toolbar button in phase 1
        // and this is its only one-tap home; All actions ▸ This page carries it as well.
        addView(switchRow(activity.getString(R.string.drawer_fullscreen), current.fullscreen) {
            onAction(DrawerAction.TOGGLE_FULLSCREEN)
        })
        addView(divider())
        addView(allActionsRow())
        layoutParams = rowParams()
    }

    /** Opens [MoreActionsSheet] — every action, grouped, including the ones not quick enough for here. */
    private fun allActionsRow(): View {
        val label = TextView(activity).apply {
            text = activity.getString(R.string.more_title)
            textSize = shell.sp(AutoUiSizes.ICON_SMALL_DP * 0.95f)
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(BrowserTheme.primary)
        }
        val chevron = icon(BrowserIcon.CHEVRON_RIGHT, AutoUiSizes.ICON_MEDIUM_DP, BrowserTheme.primary)
        return LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(shell.pad(), 0, shell.pad(), 0)
            contentDescription = activity.getString(R.string.more_title)
            addView(label, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(chevron)
            setOnClickListener { shell.dismiss(); onMore() }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, sizes.dpInt(AutoUiSizes.SHEET_ROW_HEIGHT_DP)
            )
        }
    }

    /**
     * One button in a navigation/secondary row: an icon chip over a label, on a tonal background.
     * Disabled entries keep their place (so the row never reflows) but take no tap and read dimmed.
     */
    private fun actionButton(icon: BrowserIcon, label: String, enabled: Boolean, onClick: () -> Unit): View =
        LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = shell.rounded(
                if (enabled) BrowserTheme.tileBackground else BrowserTheme.tileDisabledBackground,
                shell.cornerRadius()
            )
            setPadding(shell.gap(), shell.gap(), shell.gap(), shell.gap())
            contentDescription = label
            isClickable = enabled
            isFocusable = enabled
            if (enabled) setOnClickListener { onClick() }
            val tint = if (enabled) BrowserTheme.iconEnabled else BrowserTheme.iconDisabled
            addView(ImageView(activity).apply {
                setImageResource(icon.resId)
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                setColorFilter(tint)
                val side = sizes.dpInt(AutoUiSizes.SHEET_ICON_DP)
                layoutParams = LinearLayout.LayoutParams(side, side).apply { bottomMargin = shell.gap() }
            })
            addView(TextView(activity).apply {
                text = label
                gravity = Gravity.CENTER
                maxLines = 1
                textSize = shell.sp(AutoUiSizes.ICON_SMALL_DP * 0.78f)
                setTextColor(if (enabled) BrowserTheme.textPrimary else BrowserTheme.iconDisabled)
            })
        }

    /** One row inside [settingsCard]: a label and a switch, no leading icon, no card of its own. */
    private fun switchRow(label: String, checked: Boolean, onToggle: () -> Unit): View =
        LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(shell.pad(), 0, shell.pad(), 0)
            addView(
                TextView(activity).apply {
                    text = label
                    textSize = shell.sp(AutoUiSizes.ICON_SMALL_DP * 0.9f)
                    setTextColor(BrowserTheme.textPrimary)
                },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            )
            addView(Switch(activity).apply {
                isChecked = checked
                // The sheet stays open: whatever the setting changes happens underneath it.
                setOnCheckedChangeListener { _, _ -> onToggle() }
            })
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, sizes.dpInt(AutoUiSizes.SHEET_ROW_HEIGHT_DP)
            )
        }

    private fun rowParams() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { bottomMargin = shell.gap() }

    /** Equal-width tiles in a nav/secondary row, each a fixed [AutoUiSizes.SHEET_TILE_HEIGHT_DP] tall. */
    private fun equalParams(index: Int) = LinearLayout.LayoutParams(
        0, sizes.dpInt(AutoUiSizes.SHEET_TILE_HEIGHT_DP), 1f
    ).apply { if (index > 0) marginStart = shell.gap() }
}
