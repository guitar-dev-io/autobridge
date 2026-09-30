package dev.autobridge.car

import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.Header
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import dev.autobridge.apps.QuickLaunchStore
import dev.autobridge.core.state.RecentActivityStore

/**
 * Manage + launch Quick Launch shortcuts. Tapping a shortcut runs it (open URL in the car browser,
 * trigger an internal feature, or launch a phone app). The "Manage" mode exposes remove / reorder;
 * "Add" pushes a text field to create a custom URL shortcut. All changes persist via
 * [QuickLaunchStore].
 */
class CarQuickLaunchScreen(carContext: CarContext, private val manage: Boolean = false) : Screen(carContext) {

    override fun onGetTemplate(): Template {
        val shortcuts = QuickLaunchStore.list(carContext)
        val list = ItemList.Builder()
        if (shortcuts.isEmpty()) {
            list.setNoItemsMessage("No shortcuts yet — tap Add")
        } else {
            shortcuts.forEachIndexed { index, shortcut ->
                val subtitle = when (shortcut.kind) {
                    QuickLaunchStore.Kind.URL -> shortcut.payload
                    QuickLaunchStore.Kind.INTERNAL -> "AutoBridge action"
                    QuickLaunchStore.Kind.APP -> "App • ${shortcut.payload}"
                }
                val row = Row.Builder().setTitle(shortcut.label).addText(subtitle)
                if (manage) {
                    row.setOnClickListener { showManageOptions(shortcut, index, shortcuts.size) }
                } else {
                    row.setOnClickListener { launch(shortcut) }
                }
                list.addItem(row.build())
            }
        }

        return ListTemplate.Builder()
            .setHeader(
                Header.Builder()
                    .setTitle(if (manage) "Manage Quick Launch" else "Quick Launch")
                    .setStartHeaderAction(Action.BACK)
                    .addEndHeaderAction(
                        Action.Builder()
                            .setIcon(CarIcons.of(carContext, CarIcons.ADD))
                            .setOnClickListener { addShortcut() }
                            .build()
                    )
                    .apply {
                        if (!manage) {
                            addEndHeaderAction(
                                Action.Builder()
                                    .setIcon(CarIcons.of(carContext, CarIcons.EDIT))
                                    .setOnClickListener { CarNavigation.open(screenManager, "CarQuickLaunchScreen") { CarQuickLaunchScreen(carContext, manage = true) } }
                                    .build()
                            )
                        }
                    }
                    .build()
            )
            .setSingleList(list.build())
            .build()
    }

    private fun launch(shortcut: QuickLaunchStore.Shortcut) {
        when (shortcut.kind) {
            QuickLaunchStore.Kind.URL -> {
                val browser = CarBrowserScreen(carContext)
                screenManager.push(browser)
                browser.openUrl(shortcut.payload)
                RecentActivityStore.record(
                    carContext,
                    RecentActivityStore.Entry(
                        RecentActivityStore.Kind.BROWSER, shortcut.label, data = shortcut.payload
                    )
                )
            }
            QuickLaunchStore.Kind.INTERNAL -> {
                val command = dev.autobridge.agent.AgentCommandRouter.parse(shortcut.payload)
                if (command != null) {
                    dev.autobridge.agent.AgentCommandRouter.execute(this, carContext, command)
                }
            }
            QuickLaunchStore.Kind.APP -> {
                if (!dev.autobridge.apps.QuickAppLauncher.launch(carContext, shortcut.payload)) {
                    CarToast.makeText(carContext, "Could not launch ${shortcut.label}", CarToast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun showManageOptions(shortcut: QuickLaunchStore.Shortcut, index: Int, count: Int) {
        val list = ItemList.Builder()
        if (index > 0) {
            list.addItem(
                Row.Builder().setTitle("Move up").setOnClickListener {
                    QuickLaunchStore.move(carContext, shortcut.id, up = true)
                    screenManager.pop()
                    invalidate()
                }.build()
            )
        }
        if (index < count - 1) {
            list.addItem(
                Row.Builder().setTitle("Move down").setOnClickListener {
                    QuickLaunchStore.move(carContext, shortcut.id, up = false)
                    screenManager.pop()
                    invalidate()
                }.build()
            )
        }
        list.addItem(
            Row.Builder().setTitle("Remove").setOnClickListener {
                QuickLaunchStore.remove(carContext, shortcut.id)
                screenManager.pop()
                invalidate()
            }.build()
        )

        screenManager.push(object : Screen(carContext) {
            override fun onGetTemplate(): Template =
                ListTemplate.Builder()
                    .setHeader(
                        Header.Builder()
                            .setTitle(shortcut.label)
                            .setStartHeaderAction(Action.BACK)
                            .build()
                    )
                    .setSingleList(list.build())
                    .build()
        })
    }

    private fun addShortcut() {
        screenManager.pushForResult(CarBrowserSearchScreen(carContext, "")) { result ->
            val text = (result as? String)?.trim().orEmpty()
            if (text.isEmpty()) return@pushForResult
            val url = dev.autobridge.entertainment.ContentAddress.https(text)
            if (url != null) {
                val label = android.net.Uri.parse(url).host ?: text
                QuickLaunchStore.add(carContext, label, QuickLaunchStore.Kind.URL, url)
            } else {
                // Treat as a search shortcut.
                QuickLaunchStore.add(
                    carContext,
                    text,
                    QuickLaunchStore.Kind.URL,
                    "https://www.google.com/search?q=" + android.net.Uri.encode(text)
                )
            }
            invalidate()
        }
    }
}
