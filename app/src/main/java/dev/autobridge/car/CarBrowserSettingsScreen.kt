package dev.autobridge.car

import android.webkit.WebSettings
import androidx.annotation.StringRes
import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.Header
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.SearchTemplate
import androidx.car.app.model.SectionedItemList
import androidx.car.app.model.Template
import androidx.car.app.model.Toggle
import dev.autobridge.R
import dev.autobridge.audio.AudioPlaybackStore
import dev.autobridge.browser.BrowserAdBlock
import dev.autobridge.browser.BrowserControlsStore
import dev.autobridge.browser.BrowserSplitLayout
import dev.autobridge.browser.BrowserSplitStore
import dev.autobridge.browser.BrowserUserAgentCodec
import dev.autobridge.browser.BrowserUserAgentMode
import dev.autobridge.browser.BrowserUserAgentStore
import dev.autobridge.browser.FloatingButtonAction
import dev.autobridge.youtube.YouTubeSettings

/**
 * Browser settings for the car surface.
 *
 * Split into "in-app controls" — how much chrome sits over the page and what the floating button
 * does — and the browser identity it already carried. The controls half exists because there is no
 * single right answer for a car: an auto-hiding toolbar gives the page every pixel but costs a tap
 * to get the address bar back, and pinning it costs 52dp for the whole drive. Both are defensible,
 * so both are offered rather than one being chosen for everyone.
 */
class CarBrowserSettingsScreen(carContext: CarContext) : Screen(carContext) {
    override fun onGetTemplate(): Template {
        val hideBar = BrowserControlsStore.hideUrlBar(carContext)
        val controls = ItemList.Builder()
            .addItem(
                Row.Builder()
                    .setTitle(carContext.getString(R.string.car_browser_hide_url_bar))
                    .addText(carContext.getString(R.string.car_browser_hide_url_bar_caption))
                    .setToggle(
                        Toggle.Builder { checked ->
                            BrowserControlsStore.setHideUrlBar(carContext, checked)
                            setResult(CHANGED)
                            invalidate()
                        }
                            .setChecked(hideBar)
                            .build()
                    )
                    .build()
            )
            .addItem(
                Row.Builder()
                    .setTitle(carContext.getString(R.string.car_browser_always_url_bar))
                    .addText(carContext.getString(R.string.car_browser_always_url_bar_caption))
                    // Hiding the bar wins over pinning it, so this row is disabled while the bar is
                    // hidden rather than offering a contradictory choice.
                    .setEnabled(!hideBar)
                    .setToggle(
                        Toggle.Builder { checked ->
                            BrowserControlsStore.setAlwaysShowUrlBar(carContext, checked)
                            setResult(CHANGED)
                            invalidate()
                        }
                            .setChecked(!hideBar && BrowserControlsStore.alwaysShowUrlBar(carContext))
                            .build()
                    )
                    .build()
            )
            .addItem(
                Row.Builder()
                    .setTitle(carContext.getString(R.string.car_browser_always_fab))
                    .addText(carContext.getString(R.string.car_browser_always_fab_caption))
                    .setToggle(
                        Toggle.Builder { checked ->
                            BrowserControlsStore.setAlwaysShowFloatingButton(carContext, checked)
                            setResult(CHANGED)
                            invalidate()
                        }
                            .setChecked(BrowserControlsStore.alwaysShowFloatingButton(carContext))
                            .build()
                    )
                    .build()
            )
            .addItem(
                Row.Builder()
                    .setTitle(carContext.getString(R.string.car_browser_fab_action))
                    .addText(BrowserControlsStore.floatingButtonAction(carContext).label(carContext))
                    .setBrowsable(true)
                    .setOnClickListener { openFloatingActionPicker() }
                    .build()
            )
            .addItem(
                Row.Builder()
                    .setTitle(carContext.getString(R.string.car_browser_fab_left))
                    .addText(carContext.getString(R.string.car_browser_fab_left_caption))
                    .setToggle(
                        Toggle.Builder { checked ->
                            BrowserControlsStore.setFloatingButtonOnLeft(carContext, checked)
                            setResult(CHANGED)
                            invalidate()
                        }
                            .setChecked(BrowserControlsStore.floatingButtonOnLeft(carContext))
                            .build()
                    )
                    .build()
            )
            .addItem(
                Row.Builder()
                    .setTitle(carContext.getString(R.string.car_browser_split))
                    .addText(BrowserSplitStore.layout(carContext).label(carContext))
                    .setBrowsable(true)
                    .setOnClickListener { openSplitLayoutPicker() }
                    .build()
            )
            .addItem(
                Row.Builder()
                    .setTitle(carContext.getString(R.string.car_browser_reverse_music))
                    .addText(carContext.getString(R.string.car_browser_reverse_music_caption))
                    .setToggle(
                        Toggle.Builder { checked ->
                            AudioPlaybackStore.setKeepPlayingThroughFocusLoss(carContext, checked)
                            setResult(CHANGED)
                            invalidate()
                        }
                            .setChecked(AudioPlaybackStore.keepPlayingThroughFocusLoss(carContext))
                            .build()
                    )
                    .build()
            )
            .build()

        val blocking = ItemList.Builder()
            .addItem(
                Row.Builder()
                    .setTitle(carContext.getString(R.string.car_browser_block_ads))
                    .addText(carContext.getString(R.string.car_browser_block_ads_caption))
                    .setToggle(
                        Toggle.Builder { checked ->
                            BrowserAdBlock.setEnabled(carContext, checked)
                            setResult(CONTENT_CHANGED)
                            invalidate()
                        }
                            .setChecked(BrowserAdBlock.enabled(carContext))
                            .build()
                    )
                    .build()
            )
            .addItem(
                Row.Builder()
                    .setTitle(carContext.getString(R.string.car_browser_skip_yt_ads))
                    .addText(carContext.getString(R.string.car_browser_skip_yt_ads_caption))
                    .setToggle(
                        Toggle.Builder { checked ->
                            YouTubeSettings.setAdSkipEnabled(carContext, checked)
                            // The skipper is armed from the page's own navigation callbacks, so it
                            // picks the new setting up on the next video without a reload.
                            setResult(CHANGED)
                            invalidate()
                        }
                            .setChecked(YouTubeSettings.adSkipEnabled(carContext))
                            .build()
                    )
                    .build()
            )
            .build()

        val identity = ItemList.Builder()
            .addItem(
                Row.Builder()
                    .setTitle(carContext.getString(R.string.car_browser_identity))
                    .addText(identitySummary())
                    .setBrowsable(true)
                    .setOnClickListener { openIdentityPicker() }
                    .build()
            )
            .build()

        return ListTemplate.Builder()
            .setHeader(
                Header.Builder()
                    .setTitle(carContext.getString(R.string.car_browser_settings_title))
                    .setStartHeaderAction(Action.BACK)
                    .build()
            )
            .addSectionedList(SectionedItemList.create(controls, carContext.getString(R.string.car_browser_section_controls)))
            .addSectionedList(SectionedItemList.create(blocking, carContext.getString(R.string.car_browser_section_blocking)))
            .addSectionedList(SectionedItemList.create(identity, carContext.getString(R.string.car_browser_section_identity)))
            .build()
    }

    private fun openSplitLayoutPicker() {
        screenManager.pushForResult(CarSplitLayoutScreen(carContext)) { changed ->
            if (changed == true) {
                setResult(CHANGED)
                invalidate()
            }
        }
    }

    private fun openFloatingActionPicker() {
        screenManager.pushForResult(CarFloatingButtonActionScreen(carContext)) { changed ->
            if (changed == true) {
                setResult(CHANGED)
                invalidate()
            }
        }
    }

    /** "Custom" alone does not say which identity is active, so the string itself is shown too. */
    private fun identitySummary(): String {
        val label = BrowserUserAgentStore.label(carContext)
        if (BrowserUserAgentStore.mode(carContext) != BrowserUserAgentMode.CUSTOM) return label
        val ua = BrowserUserAgentStore.custom(carContext)
        if (ua.isBlank()) return label
        return carContext.getString(
            R.string.car_browser_identity_custom_summary,
            label,
            if (ua.length <= 48) ua else ua.take(45) + "…"
        )
    }

    private fun openIdentityPicker() {
        screenManager.pushForResult(CarBrowserIdentityScreen(carContext)) { changed ->
            if (changed == true) {
                // Only an identity change needs the page reloaded, which is why the result says
                // which half of the screen changed rather than just "something did".
                setResult(IDENTITY_CHANGED)
                invalidate()
            }
        }
    }

    companion object {
        /** A control preference changed; the renderer re-reads them, the page is left alone. */
        const val CHANGED = "controls"

        /** The User-Agent changed, so the page has to be reloaded to be served under it. */
        const val IDENTITY_CHANGED = "identity"

        /**
         * Request blocking was switched. Blocking is decided per request, so the page on screen
         * keeps whatever it already fetched until it is loaded again.
         */
        const val CONTENT_CHANGED = "content"
    }
}

/** How the car surface is divided between the main page and the side page. */
private class CarSplitLayoutScreen(carContext: CarContext) : Screen(carContext) {
    override fun onGetTemplate(): Template {
        val current = BrowserSplitStore.layout(carContext)
        val list = ItemList.Builder()
        BrowserSplitLayout.entries.forEach { layout ->
            list.addItem(
                Row.Builder()
                    .setTitle(
                        layout.label(carContext).let {
                            if (layout == current) selectedLabel(it) else it
                        }
                    )
                    .addText(carContext.getString(description(layout)))
                    .setOnClickListener {
                        BrowserSplitStore.setLayout(carContext, layout)
                        setResult(true)
                        screenManager.pop()
                    }
                    .build()
            )
        }
        // Kept on this screen rather than the settings list, which is already near the row limit
        // some hosts enforce.
        list.addItem(
            Row.Builder()
                .setTitle(carContext.getString(R.string.car_browser_side_right))
                .addText(carContext.getString(R.string.car_browser_side_right_caption))
                .setToggle(
                    Toggle.Builder { checked ->
                        BrowserSplitStore.setSideOnRight(carContext, checked)
                        setResult(true)
                        invalidate()
                    }
                        .setChecked(BrowserSplitStore.sideOnRight(carContext))
                        .build()
                )
                .build()
        )
        return ListTemplate.Builder()
            .setHeader(
                Header.Builder()
                    .setTitle(carContext.getString(R.string.car_browser_split))
                    .setStartHeaderAction(Action.BACK)
                    .build()
            )
            .setSingleList(list.build())
            .build()
    }

    @StringRes
    private fun description(layout: BrowserSplitLayout): Int = when (layout) {
        BrowserSplitLayout.SINGLE -> R.string.car_split_desc_single
        BrowserSplitLayout.HALF -> R.string.car_split_desc_half
        BrowserSplitLayout.FORTY_SIXTY -> R.string.car_split_desc_forty_sixty
        BrowserSplitLayout.SIXTY_FIVE_THIRTY_FIVE -> R.string.car_split_desc_sixty_five
        BrowserSplitLayout.PORTRAIT_LANDSCAPE -> R.string.car_split_desc_portrait_landscape
    }
}

/** Which action the floating button carries. */
private class CarFloatingButtonActionScreen(carContext: CarContext) : Screen(carContext) {
    override fun onGetTemplate(): Template {
        val current = BrowserControlsStore.floatingButtonAction(carContext)
        val list = ItemList.Builder()
        FloatingButtonAction.values().forEach { action ->
            list.addItem(
                Row.Builder()
                    .setTitle(
                        action.label(carContext).let {
                            if (action == current) selectedLabel(it) else it
                        }
                    )
                    .setOnClickListener {
                        BrowserControlsStore.setFloatingButtonAction(carContext, action)
                        setResult(true)
                        screenManager.pop()
                    }
                    .build()
            )
        }
        return ListTemplate.Builder()
            .setHeader(
                Header.Builder()
                    .setTitle(carContext.getString(R.string.car_browser_fab_action))
                    .setStartHeaderAction(Action.BACK)
                    .build()
            )
            .setSingleList(list.build())
            .build()
    }
}

/** The User-Agent the browser presents; unchanged behaviour, moved behind its own row. */
private class CarBrowserIdentityScreen(carContext: CarContext) : Screen(carContext) {
    override fun onGetTemplate(): Template {
        val current = BrowserUserAgentStore.mode(carContext)
        val list = ItemList.Builder()
            .addItem(
                modeRow(
                    carContext.getString(R.string.car_ua_mobile),
                    carContext.getString(R.string.car_ua_mobile_caption),
                    BrowserUserAgentMode.MOBILE,
                    current
                )
            )
            .addItem(
                modeRow(
                    carContext.getString(R.string.car_ua_desktop),
                    carContext.getString(R.string.car_ua_desktop_caption),
                    BrowserUserAgentMode.DESKTOP,
                    current
                )
            )
            .addItem(
                Row.Builder()
                    .setTitle(
                        carContext.getString(R.string.car_ua_custom).let {
                            if (current == BrowserUserAgentMode.CUSTOM) selectedLabel(it) else it
                        }
                    )
                    .addText(customSummary())
                    .setBrowsable(true)
                    .setOnClickListener { openCustomEditor() }
                    .build()
            )
            .addItem(
                Row.Builder()
                    .setTitle(carContext.getString(R.string.car_ua_presets))
                    .addText(carContext.getString(R.string.car_ua_presets_caption))
                    .setBrowsable(true)
                    .setOnClickListener { openPresets() }
                    .build()
            )
            .build()

        return ListTemplate.Builder()
            .setHeader(
                Header.Builder()
                    .setTitle(carContext.getString(R.string.car_browser_identity))
                    .setStartHeaderAction(Action.BACK)
                    .build()
            )
            .setSingleList(list)
            .build()
    }

    private fun modeRow(
        title: String,
        subtitle: String,
        mode: BrowserUserAgentMode,
        current: BrowserUserAgentMode
    ): Row = Row.Builder()
        .setTitle(if (mode == current) selectedLabel(title) else title)
        .addText(subtitle)
        .setOnClickListener {
            BrowserUserAgentStore.select(carContext, mode)
            setResult(true)
            screenManager.pop()
        }
        .build()

    private fun customSummary(): String = BrowserUserAgentStore.custom(carContext)
        .takeIf { it.isNotBlank() }
        ?.let { if (it.length <= 54) it else it.take(51) + "…" }
        ?: carContext.getString(R.string.car_ua_custom_empty)

    private fun openPresets() {
        screenManager.pushForResult(CarUserAgentPresetScreen(carContext)) { changed ->
            if (changed == true) {
                setResult(true)
                screenManager.pop()
            }
        }
    }

    private fun openCustomEditor() {
        screenManager.pushForResult(
            CarUserAgentInputScreen(carContext, BrowserUserAgentStore.custom(carContext))
        ) { result ->
            val value = result as? String ?: return@pushForResult
            if (BrowserUserAgentStore.saveCustom(carContext, value)) {
                setResult(true)
                screenManager.pop()
            } else {
                CarToast.makeText(
                    carContext,
                    carContext.getString(R.string.car_ua_invalid),
                    CarToast.LENGTH_SHORT
                ).show()
            }
        }
    }
}

/**
 * Ready-made Custom identities. Typing a 120-character UA on a head-unit keyboard is the hard part
 * of a custom User-Agent, so the common ones are one tap; picking one saves it as the Custom string,
 * which the Custom editor can then tweak.
 */
private class CarUserAgentPresetScreen(carContext: CarContext) : Screen(carContext) {
    override fun onGetTemplate(): Template {
        val presets = BrowserUserAgentCodec.presets(
            runCatching { WebSettings.getDefaultUserAgent(carContext) }.getOrDefault("")
        )
        val current = BrowserUserAgentStore.custom(carContext)
        val isCustom = BrowserUserAgentStore.mode(carContext) == BrowserUserAgentMode.CUSTOM
        val list = ItemList.Builder()
        presets.forEach { preset ->
            val selected = isCustom && preset.userAgent == current
            list.addItem(
                Row.Builder()
                    .setTitle(if (selected) selectedLabel(preset.label) else preset.label)
                    .addText(preset.userAgent.let { if (it.length <= 54) it else it.take(51) + "…" })
                    .setOnClickListener {
                        if (BrowserUserAgentStore.saveCustom(carContext, preset.userAgent)) {
                            setResult(true)
                            screenManager.pop()
                        }
                    }
                    .build()
            )
        }
        return ListTemplate.Builder()
            .setHeader(
                Header.Builder()
                    .setTitle(carContext.getString(R.string.car_ua_presets_title))
                    .setStartHeaderAction(Action.BACK)
                    .build()
            )
            .setSingleList(list.build())
            .build()
    }
}

private class CarUserAgentInputScreen(
    carContext: CarContext,
    private val initialValue: String
) : Screen(carContext) {
    private var pendingValue = initialValue

    override fun onGetTemplate(): Template = SearchTemplate.Builder(
        object : SearchTemplate.SearchCallback {
            override fun onSearchTextChanged(searchText: String) {
                pendingValue = searchText
            }

            override fun onSearchSubmitted(searchText: String) {
                submit(searchText)
            }
        }
    )
        .setHeaderAction(Action.BACK)
        .setInitialSearchText(initialValue)
        .setSearchHint(carContext.getString(R.string.car_ua_input_hint))
        .setShowKeyboardByDefault(true)
        .setActionStrip(
            androidx.car.app.model.ActionStrip.Builder()
                .addAction(
                    Action.Builder()
                        .setTitle(carContext.getString(R.string.action_save))
                        .setOnClickListener { submit(pendingValue) }
                        .build()
                )
                .build()
        )
        .build()

    private fun submit(value: String) {
        setResult(value)
        screenManager.pop()
    }
}

/**
 * Marks the active entry in a car picker. Shared across the four pickers in this file so the
 * decoration, and the one string behind it, are defined once rather than per picker.
 */
private fun Screen.selectedLabel(title: String): String =
    carContext.getString(R.string.car_selected, title)
