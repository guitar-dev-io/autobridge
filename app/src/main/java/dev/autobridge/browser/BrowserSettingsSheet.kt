package dev.autobridge.browser

import android.app.Activity
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import androidx.core.content.ContextCompat
import dev.autobridge.R

/**
 * The browser's Settings sheet, built on the shared [BrowserSheetShell] so it reads as one surface
 * with the menu, "Send to car" and "More actions" sheets rather than the stack of `AlertDialog`s it
 * replaces.
 *
 * It gathers everything that was scattered across `showControlSettings` / `showAdvancedSettings` /
 * `showPrivacySettings` / the User-Agent dialogs into one scrollable column of labelled groups:
 * Appearance, Display scale, Start up, In-app control, Start page, User agent, Playback (DRM) and
 * Privacy & site data. Each control reads and writes a store directly ([BrowserAppearanceStore],
 * [BrowserDisplayScaleStore], [BrowserStartupStore], [BrowserControlsStore], [BrowserUserAgentStore],
 * [BrowserDrmStore], [BrowserAdBlock]) and then calls back so the activity can apply the change to the live WebView;
 * the sheet owns no browser behaviour of its own, exactly like the other sheets.
 *
 * The whole sheet re-renders in place after any change (the [render] clear-and-rebuild pattern from
 * [SendToCarSheet]) so a segmented control or switch shows its new state immediately.
 */
class BrowserSettingsSheet(
    private val activity: Activity,
    private val sizes: AutoUiSizes,
    /** Reopens the main menu sheet when the header back arrow is tapped. Null just closes. */
    private val onBack: (() -> Unit)? = null,
    /** Appearance changed: push algorithmic darkening onto the live WebView. */
    private val onAppearanceChanged: () -> Unit,
    /** Display scale changed: push textZoom onto the live WebView. */
    private val onDisplayScaleChanged: () -> Unit,
    /** Floating-button preference changed: re-apply visibility/glyph. */
    private val onFloatingButtonChanged: () -> Unit,
    /** DRM preference changed: re-apply the Widevine level and reload. */
    private val onDrmChanged: () -> Unit,
    /** Ad blocking was switched: reload so the current page is fetched under the new rule. */
    private val onAdBlockChanged: () -> Unit,
    /** Start-page background preference changed: re-apply the window backdrop. */
    private val onStartPageBackgroundChanged: () -> Unit,
    /** Opens the Home-page editor (a text field dialog owned by the activity). */
    private val onEditHomePage: () -> Unit,
    /** Opens the full User-Agent chooser (presets + custom) owned by the activity. */
    private val onEditUserAgent: () -> Unit,
    /** Privacy: reset saved per-site permissions. */
    private val onResetPermissions: () -> Unit,
    /** Privacy: delete cookies and site data. */
    private val onDeleteSiteData: () -> Unit,
    /** Privacy: clear all browsing data (cache, cookies, storage, history). */
    private val onClearBrowsingData: () -> Unit,
) {
    private val shell = BrowserSheetShell(activity, sizes)
    private lateinit var container: LinearLayout

    fun show() {
        container = shell.contentColumn()
        render()
        shell.show(container)
    }

    private fun render() {
        container.removeAllViews()
        container.addView(shell.grip())
        container.addView(header())

        container.addView(sectionLabel("Appearance"))
        container.addView(appearanceRow())

        container.addView(sectionLabel("Display scale"))
        container.addView(displayScaleRow())

        container.addView(sectionLabel("Start up"))
        container.addView(homePageRow())
        container.addView(launchBehaviorRow())

        container.addView(sectionLabel("In-app control"))
        container.addView(floatingActionRow())
        container.addView(
            toggleRow(
                "Always show floating button",
                "Keep the in-app button on-screen instead of auto-hiding after touching the page",
                BrowserControlsStore.alwaysShowFloatingButton(activity),
            ) {
                BrowserControlsStore.setAlwaysShowFloatingButton(activity, it)
                onFloatingButtonChanged()
            }
        )
        container.addView(floatingPositionRow())

        container.addView(sectionLabel("Start page"))
        container.addView(
            toggleRow(
                "Default gradient background",
                "Draw the start page over the app's gradient",
                BrowserStartupStore.gradientBackground(activity),
            ) {
                BrowserStartupStore.setGradientBackground(activity, it)
                onStartPageBackgroundChanged()
            }
        )

        container.addView(sectionLabel("User agent"))
        container.addView(userAgentRow())

        container.addView(sectionLabel("Playback"))
        container.addView(
            toggleRow(
                "DRM Widevine L3 enforcer",
                "Enforces Widevine L3 compatibility to resolve black screen or playback issues",
                BrowserDrmStore.enforceL3(activity),
            ) {
                BrowserDrmStore.setEnforceL3(activity, it)
                onDrmChanged()
            }
        )

        container.addView(sectionLabel("Content blocking"))
        container.addView(
            toggleRow(
                "Block ads and trackers",
                "Drops requests to known ad and tracking hosts. YouTube's pre-roll and mid-roll " +
                    "video ads are not among them — they share a host with the video; use the " +
                    "YouTube add-ons for those",
                BrowserAdBlock.enabled(activity),
            ) {
                BrowserAdBlock.setEnabled(activity, it)
                onAdBlockChanged()
            }
        )

        container.addView(sectionLabel("Privacy and site data"))
        container.addView(
            navRow("Reset saved site permissions", "Clear camera, mic and other per-site grants") {
                onResetPermissions()
            }
        )
        container.addView(
            navRow("Delete cookies and site data", "Remove cookies and local site data") {
                onDeleteSiteData()
            }
        )
        container.addView(
            navRow("Clear browsing data", "Cache, cookies, site data and history (bookmarks are kept)") {
                onClearBrowsingData()
            }
        )
    }

    // ------------------------------------------------------------------------------------ header

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
            text = "Settings"
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

    // -------------------------------------------------------------------------------- Appearance

    private fun appearanceRow(): View {
        val current = BrowserAppearanceStore.mode(activity)
        return segmented(BrowserAppearance.entries.map { it to it.label }, current) { picked ->
            if (picked != current) {
                BrowserAppearanceStore.select(activity, picked)
                onAppearanceChanged()
                render()
            }
        }
    }

    // ----------------------------------------------------------------------------- Display scale

    /**
     * A slider rather than discrete chips: text zoom is a continuous quantity
     * ([BrowserDisplayScaleStore.MIN_PERCENT]..[BrowserDisplayScaleStore.MAX_PERCENT] in
     * [BrowserDisplayScaleStore.STEP_PERCENT] steps), and a slider shows where the current value
     * sits in that range at a glance instead of making the user scan a row of numbers for the
     * tick. The value label updates on every drag frame; the store is only written and the live
     * page only re-zoomed on release ([SeekBar.OnSeekBarChangeListener.onStopTrackingTouch]), so
     * dragging never hammers the WebView's `textZoom` on every pixel of finger movement. Unlike
     * every other control in this sheet it does not call [render] on change — rebuilding the row
     * mid-drag would tear down the very [SeekBar] the finger is on.
     */
    private fun displayScaleRow(): View {
        val current = BrowserDisplayScaleStore.percent(activity)
        val steps = (BrowserDisplayScaleStore.MAX_PERCENT - BrowserDisplayScaleStore.MIN_PERCENT) /
            BrowserDisplayScaleStore.STEP_PERCENT
        fun percentAt(progress: Int) =
            BrowserDisplayScaleStore.MIN_PERCENT + progress * BrowserDisplayScaleStore.STEP_PERCENT

        val valueLabel = TextView(activity).apply {
            text = "$current%"
            textSize = shell.sp(AutoUiSizes.ICON_SMALL_DP * 0.85f)
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(BrowserTheme.textPrimary)
            gravity = Gravity.END
            minWidth = sizes.dpInt(44f)
        }
        val slider = SeekBar(activity).apply {
            max = steps
            progress = (current - BrowserDisplayScaleStore.MIN_PERCENT) / BrowserDisplayScaleStore.STEP_PERCENT
            progressTintList = android.content.res.ColorStateList.valueOf(BrowserTheme.accent)
            thumbTintList = android.content.res.ColorStateList.valueOf(BrowserTheme.accent)
            contentDescription = "Display scale"
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                    valueLabel.text = "${percentAt(progress)}%"
                }

                override fun onStartTrackingTouch(bar: SeekBar) = Unit

                override fun onStopTrackingTouch(bar: SeekBar) {
                    BrowserDisplayScaleStore.setPercent(activity, percentAt(bar.progress))
                    onDisplayScaleChanged()
                }
            })
        }
        return LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = shell.rounded(BrowserTheme.sheetCardBackground, shell.cornerRadius())
            setPadding(shell.pad(), 0, shell.pad(), 0)
            addView(slider, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(valueLabel, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = shell.gap() })
            layoutParams = rowParams().apply { height = sizes.dpInt(AutoUiSizes.TOUCH_TARGET_DP) }
        }
    }

    // ---------------------------------------------------------------------------------- Start up

    private fun homePageRow(): View {
        val home = BrowserStartupStore.homePage(activity)
        val subtitle = if (home == BrowserStartupStore.START_PAGE) {
            activity.getString(R.string.browser_start_page_label)
        } else {
            BrowserDisplayUrl.compact(home, max = 42)
        }
        return navRow("Home page", subtitle) { onEditHomePage() }
    }

    private fun launchBehaviorRow(): View {
        val current = BrowserStartupStore.launchBehavior(activity)
        return segmented(BrowserLaunchBehavior.entries.map { it to it.label }, current) { picked ->
            if (picked != current) {
                BrowserStartupStore.setLaunchBehavior(activity, picked)
                render()
            }
        }
    }

    // --------------------------------------------------------------------------- In-app control

    private fun floatingActionRow(): View {
        val action = BrowserControlsStore.floatingButtonAction(activity)
        return navRow("Floating button action", action.label(activity)) {
            cycleFloatingAction(action)
        }
    }

    /** The sheet has no sub-dialog of its own, so tapping cycles to the next action and re-renders. */
    private fun cycleFloatingAction(current: FloatingButtonAction) {
        val values = FloatingButtonAction.entries
        val next = values[(values.indexOf(current) + 1) % values.size]
        BrowserControlsStore.setFloatingButtonAction(activity, next)
        onFloatingButtonChanged()
        render()
    }

    private fun floatingPositionRow(): View {
        val onLeft = BrowserControlsStore.floatingButtonOnLeft(activity)
        val control = segmented(
            listOf(false to "Right", true to "Left"),
            onLeft,
        ) { picked ->
            if (picked != onLeft) {
                BrowserControlsStore.setFloatingButtonOnLeft(activity, picked)
                onFloatingButtonChanged()
                render()
            }
        }
        // "Right / Left" alone does not say what side of what, so a leading label names it.
        return LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(activity).apply {
                text = "Floating button position"
                textSize = shell.sp(AutoUiSizes.ICON_SMALL_DP * 0.95f)
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(BrowserTheme.textPrimary)
                setPadding(0, 0, 0, shell.gap() / 2)
            })
            addView(control)
            layoutParams = rowParams()
        }
    }

    // -------------------------------------------------------------------------------- User agent

    private fun userAgentRow(): View =
        navRow("User agent", BrowserUserAgentStore.label(activity)) { onEditUserAgent() }

    // --------------------------------------------------------------------------------- primitives

    /**
     * A segmented control over [options] (value to label), the selected one filled with the accent.
     * Reused for Appearance, launch behaviour and floating-button side.
     */
    private fun <T> segmented(options: List<Pair<T, String>>, selected: T, onPick: (T) -> Unit): View {
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            background = shell.rounded(BrowserTheme.sheetCardBackground, sizes.dp(AutoUiSizes.TOUCH_TARGET_DP) / 2f)
            setPadding(sizes.dpInt(4f), sizes.dpInt(4f), sizes.dpInt(4f), sizes.dpInt(4f))
            layoutParams = rowParams()
        }
        options.forEachIndexed { index, (value, label) ->
            val active = value == selected
            val chip = TextView(activity).apply {
                // Same tick the display-scale row uses, so "which one is on" is answered the same
                // way everywhere in this sheet rather than by fill and weight alone.
                text = if (active) "✓ $label" else label
                contentDescription = if (active) "$label, selected" else label
                gravity = Gravity.CENTER
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                textSize = shell.sp(AutoUiSizes.ICON_SMALL_DP * 0.85f)
                setTextColor(if (active) BrowserTheme.onPrimary else BrowserTheme.textSecondary)
                if (active) setTypeface(typeface, Typeface.BOLD)
                background = shell.rounded(
                    if (active) BrowserTheme.accent else android.graphics.Color.TRANSPARENT,
                    sizes.dp(AutoUiSizes.TOUCH_TARGET_DP) / 2f
                )
                minHeight = sizes.dpInt(AutoUiSizes.TOUCH_TARGET_DP * 0.85f)
                setOnClickListener { onPick(value) }
            }
            row.addView(chip, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                if (index > 0) marginStart = sizes.dpInt(4f)
            })
        }
        return row
    }

    /** A full-width card with a title, subtitle and a trailing switch. */
    private fun toggleRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit): View {
        val texts = labelColumn(title, subtitle)
        return LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = shell.rounded(BrowserTheme.sheetCardBackground, shell.cornerRadius())
            setPadding(shell.pad(), shell.gap(), shell.pad(), shell.gap())
            addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(Switch(activity).apply {
                isChecked = checked
                setOnCheckedChangeListener { _, value -> onChange(value) }
            })
            layoutParams = rowParams()
        }
    }

    /** A full-width card with a title, a value/subtitle and a trailing chevron; opens another step. */
    private fun navRow(title: String, value: String, onClick: () -> Unit): View {
        val texts = labelColumn(title, value)
        return LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = shell.rounded(BrowserTheme.sheetCardBackground, shell.cornerRadius())
            setPadding(shell.pad(), shell.gap(), shell.pad(), shell.gap())
            contentDescription = title
            addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(ImageView(activity).apply {
                setImageDrawable(iconDrawable(BrowserIcon.CHEVRON_RIGHT, BrowserTheme.textSecondary))
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                val size = sizes.dpInt(AutoUiSizes.ICON_MEDIUM_DP)
                layoutParams = LinearLayout.LayoutParams(size, size)
            })
            setOnClickListener { onClick() }
            layoutParams = rowParams()
        }
    }

    /** The shared [BrowserIcon] vector tinted for use as an in-sheet affordance; see [BrowserIcon]. */
    private fun iconDrawable(icon: BrowserIcon, color: Int) =
        ContextCompat.getDrawable(activity, icon.resId)!!.mutate().apply { setTint(color) }

    private fun labelColumn(title: String, subtitle: String): View = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        addView(TextView(activity).apply {
            text = title
            textSize = shell.sp(AutoUiSizes.ICON_SMALL_DP * 0.95f)
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(BrowserTheme.textPrimary)
        })
        if (subtitle.isNotBlank()) {
            addView(TextView(activity).apply {
                text = subtitle
                textSize = shell.sp(AutoUiSizes.ICON_SMALL_DP * 0.72f)
                setTextColor(BrowserTheme.textSecondary)
            })
        }
    }

    private fun sectionLabel(label: String): View = TextView(activity).apply {
        text = label
        textSize = shell.sp(AutoUiSizes.ICON_SMALL_DP * 0.8f)
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(BrowserTheme.textSecondary)
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = shell.gap(); bottomMargin = shell.gap() / 2 }
    }

    private fun rowParams() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { bottomMargin = shell.gap() }
}
