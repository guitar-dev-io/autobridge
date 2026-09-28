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
import androidx.car.app.model.Template
import dev.autobridge.browser.BrowserUserAgentMode
import dev.autobridge.browser.BrowserUserAgentStore

class CarBrowserSettingsScreen(carContext: CarContext) : Screen(carContext) {
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
