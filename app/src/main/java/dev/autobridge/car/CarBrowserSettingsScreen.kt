package dev.autobridge.car

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
import dev.autobridge.browser.BrowserControlsStore
import dev.autobridge.browser.BrowserUserAgentMode
import dev.autobridge.browser.BrowserUserAgentStore
import dev.autobridge.browser.FloatingButtonAction

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
                    .setTitle("Hide URL bar")
                    .addText("Remove the address bar completely; use the floating button for the menu")
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
                    .setTitle("Always show URL bar")
                    .addText("Keep the address bar over the page instead of letting it fade away")
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
                    .setTitle("Always show floating button")
                    .addText("Keep the button on screen instead of fading it out with the toolbar")
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
                    .setTitle("Floating button action")
                    .addText(BrowserControlsStore.floatingButtonAction(carContext).label)
                    .setBrowsable(true)
                    .setOnClickListener { openFloatingActionPicker() }
                    .build()
            )
            .build()

        val identity = ItemList.Builder()
            .addItem(
                Row.Builder()
                    .setTitle("Browser identity")
                    .addText(BrowserUserAgentStore.label(carContext))
                    .setBrowsable(true)
                    .setOnClickListener { openIdentityPicker() }
                    .build()
            )
            .build()

        return ListTemplate.Builder()
            .setHeader(
                Header.Builder()
                    .setTitle("Browser settings")
                    .setStartHeaderAction(Action.BACK)
                    .build()
            )
            .addSectionedList(SectionedItemList.create(controls, "In-app controls"))
            .addSectionedList(SectionedItemList.create(identity, "Identity"))
            .build()
    }

    private fun openFloatingActionPicker() {
        screenManager.pushForResult(CarFloatingButtonActionScreen(carContext)) { changed ->
            if (changed == true) {
                setResult(CHANGED)
                invalidate()
            }
        }
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
                    .setTitle(if (action == current) "${action.label}  •  Selected" else action.label)
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
                    .setTitle("Floating button action")
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
            .addItem(modeRow("Mobile", "Use the phone browser identity", BrowserUserAgentMode.MOBILE, current))
            .addItem(modeRow("Desktop", "Request desktop versions of websites", BrowserUserAgentMode.DESKTOP, current))
            .addItem(
                Row.Builder()
                    .setTitle(if (current == BrowserUserAgentMode.CUSTOM) "Custom  •  Selected" else "Custom")
                    .addText(customSummary())
                    .setBrowsable(true)
                    .setOnClickListener { openCustomEditor() }
                    .build()
            )
            .build()

        return ListTemplate.Builder()
            .setHeader(
                Header.Builder()
                    .setTitle("Browser identity")
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
        .setTitle(if (mode == current) "$title  •  Selected" else title)
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
        ?: "Enter a custom User-Agent string"

    private fun openCustomEditor() {
        screenManager.pushForResult(
            CarUserAgentInputScreen(carContext, BrowserUserAgentStore.custom(carContext))
        ) { result ->
            val value = result as? String ?: return@pushForResult
            if (BrowserUserAgentStore.saveCustom(carContext, value)) {
                setResult(true)
                screenManager.pop()
            } else {
                CarToast.makeText(carContext, "Enter a valid User-Agent", CarToast.LENGTH_SHORT).show()
            }
        }
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
        .setSearchHint("Custom User-Agent")
        .setShowKeyboardByDefault(true)
        .setActionStrip(
            androidx.car.app.model.ActionStrip.Builder()
                .addAction(Action.Builder().setTitle("Save").setOnClickListener { submit(pendingValue) }.build())
                .build()
        )
        .build()

    private fun submit(value: String) {
        setResult(value)
        screenManager.pop()
    }
}
