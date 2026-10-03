package dev.autobridge.car

import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.Header
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.SectionedItemList
import androidx.car.app.model.Template
import dev.autobridge.R
import dev.autobridge.apps.QuickLaunchStore
import dev.autobridge.core.state.RecentActivityStore

/**
 * Manage + launch Quick Launch shortcuts — the car's own pinned list, which is what
 * [dev.autobridge.library.HomeSection.APPS] opens here (the phone's Apps tile is the installed-app
 * list instead).
 *
 * Tapping a shortcut runs it: a URL opens in the car browser, an INTERNAL one runs an
 * [dev.autobridge.agent.AgentCommandRouter.AgentAction], and an APP one launches a phone app.
 * "Manage" exposes remove / reorder. "Add" offers a feature to pin or a web address to type; it
 * used to offer only the address, which left the INTERNAL kind unreachable and meant Mirror or
 * "resume what was playing" could not be pinned at all. All changes persist via [QuickLaunchStore].
 */
class CarQuickLaunchScreen(carContext: CarContext, private val manage: Boolean = false) : Screen(carContext) {

    override fun onGetTemplate(): Template {
        val shortcuts = QuickLaunchStore.list(carContext)
        val list = ItemList.Builder()
        if (shortcuts.isEmpty()) {
            list.setNoItemsMessage(carContext.getString(R.string.car_quicklaunch_empty))
        } else {
            shortcuts.forEachIndexed { index, shortcut ->
                val subtitle = when (shortcut.kind) {
                    QuickLaunchStore.Kind.URL -> shortcut.payload
                    QuickLaunchStore.Kind.INTERNAL -> carContext.getString(R.string.car_quicklaunch_internal)
                    QuickLaunchStore.Kind.APP ->
                        carContext.getString(R.string.car_quicklaunch_app_subtitle, shortcut.payload)
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
                    .setTitle(carContext.getString(
                        if (manage) R.string.car_quicklaunch_manage_title else R.string.car_quicklaunch_title
                    ))
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
                // The payload is an AgentAction name. It used to be free text run back through
                // AgentCommandParser, which is right for speech — where the words are whatever the
                // driver said — but wrong for a row stored on disk: a parser tweak could silently
                // stop a pinned shortcut from resolving. Text payloads still work, so anything
                // written by an older build keeps launching.
                val action = runCatching {
                    dev.autobridge.agent.AgentCommandRouter.AgentAction.valueOf(shortcut.payload)
                }.getOrNull()
                val command = action?.let { dev.autobridge.agent.AgentCommandRouter.Command(it) }
                    ?: dev.autobridge.agent.AgentCommandRouter.parse(shortcut.payload)
                if (command != null) {
                    dev.autobridge.agent.AgentCommandRouter.execute(this, carContext, command)
                }
            }
            QuickLaunchStore.Kind.APP -> {
                if (!dev.autobridge.apps.QuickAppLauncher.launch(carContext, shortcut.payload)) {
                    CarToast.makeText(
                        carContext,
                        carContext.getString(R.string.car_could_not_launch, shortcut.label),
                        CarToast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    private fun showManageOptions(shortcut: QuickLaunchStore.Shortcut, index: Int, count: Int) {
        val list = ItemList.Builder()
        if (index > 0) {
            list.addItem(
                Row.Builder().setTitle(carContext.getString(R.string.car_quicklaunch_move_up)).setOnClickListener {
                    QuickLaunchStore.move(carContext, shortcut.id, up = true)
                    screenManager.pop()
                    invalidate()
                }.build()
            )
        }
        if (index < count - 1) {
            list.addItem(
                Row.Builder().setTitle(carContext.getString(R.string.car_quicklaunch_move_down)).setOnClickListener {
                    QuickLaunchStore.move(carContext, shortcut.id, up = false)
                    screenManager.pop()
                    invalidate()
                }.build()
            )
        }
        list.addItem(
            Row.Builder().setTitle(carContext.getString(R.string.car_quicklaunch_remove)).setOnClickListener {
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

    /**
     * What can be pinned besides a web address.
     *
     * These are the destinations worth one tap in a car: putting the phone on the screen, picking
     * playback back up, and the three places content is reached from. Each label is the one its
     * destination already uses, so a pinned row and the screen it opens read the same.
     */
    private fun pinnableFeatures(): List<Pair<Int, dev.autobridge.agent.AgentCommandRouter.AgentAction>> =
        listOf(
            R.string.section_mirror to
                dev.autobridge.agent.AgentCommandRouter.AgentAction.OPEN_MIRROR,
            R.string.car_media_resume to
                dev.autobridge.agent.AgentCommandRouter.AgentAction.RESUME_MEDIA,
            R.string.car_mirror_media to
                dev.autobridge.agent.AgentCommandRouter.AgentAction.OPEN_MEDIA,
            R.string.car_more_recent to
                dev.autobridge.agent.AgentCommandRouter.AgentAction.OPEN_RECENT,
            R.string.car_driving_browser to
                dev.autobridge.agent.AgentCommandRouter.AgentAction.OPEN_BROWSER
        )

    /** Chooser for the "+" action: a feature, or the free-text web entry the screen always had. */
    private fun addShortcut() {
        val features = ItemList.Builder()
        pinnableFeatures().forEach { (labelRes, action) ->
            val label = carContext.getString(labelRes)
            features.addItem(
                Row.Builder()
                    .setTitle(label)
                    .setOnClickListener {
                        QuickLaunchStore.add(
                            carContext, label, QuickLaunchStore.Kind.INTERNAL, action.name
                        )
                        CarToast.makeText(
                            carContext,
                            carContext.getString(R.string.car_quicklaunch_added, label),
                            CarToast.LENGTH_SHORT
                        ).show()
                        screenManager.pop()
                        invalidate()
                    }
                    .build()
            )
        }
        val web = ItemList.Builder().addItem(
            Row.Builder()
                .setTitle(carContext.getString(R.string.car_quicklaunch_add_website))
                .addText(carContext.getString(R.string.car_quicklaunch_add_website_caption))
                .setBrowsable(true)
                .setOnClickListener {
                    screenManager.pop()
                    addWebShortcut()
                }
                .build()
        ).build()

        screenManager.push(object : Screen(carContext) {
            override fun onGetTemplate(): Template =
                ListTemplate.Builder()
                    .setHeader(
                        Header.Builder()
                            .setTitle(carContext.getString(R.string.car_quicklaunch_add_title))
                            .setStartHeaderAction(Action.BACK)
                            .build()
                    )
                    .addSectionedList(
                        SectionedItemList.create(
                            features.build(),
                            carContext.getString(R.string.car_quicklaunch_add_group_feature)
                        )
                    )
                    .addSectionedList(
                        SectionedItemList.create(
                            web,
                            carContext.getString(R.string.car_quicklaunch_add_group_web)
                        )
                    )
                    .build()
        })
    }

    private fun addWebShortcut() {
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
