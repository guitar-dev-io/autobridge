package dev.autobridge.car

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.Header
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import dev.autobridge.R
import dev.autobridge.core.state.UiModeStore
import dev.autobridge.library.HomeSection

/**
 * Secondary destinations kept one tap away from the compact home menu.
 *
 * The home menu itself carries only the six primary cards. Every other section is listed here
 * first, followed by the tools that were always on this screen, and finally the six primary
 * sections again: the home cards are drawn on the car surface, which a rotary controller cannot
 * focus, so this host-drawn list is what keeps every feature reachable without touch.
 */
class CarHomeMoreScreen(
    carContext: CarContext,
    private val requestSafety: () -> Unit,
    private val page: Int = 0
) : Screen(carContext) {

    private data class Entry(val title: String, val action: () -> Unit)

    private fun entries(): List<Entry> {
        val primary = HomeMenuItem.primary.map { it.section }.toSet()
        val section = { s: HomeSection ->
            Entry(s.title(carContext)) { CarHomeNavigator.open(carContext, screenManager, s, requestSafety) }
        }
        val secondary = HomeSection.carSections.filterNot { it in primary || it == HomeSection.SETTINGS }
        val tools = listOf(
            // Media Center is reached from here rather than the home grid, which leads with the
            // content sections shared with the phone launcher.
            Entry(carContext.getString(R.string.car_more_media_center)) { CarNavigation.open(screenManager, "CarMediaCenterScreen") { CarMediaCenterScreen(carContext) } },
            Entry(carContext.getString(R.string.car_more_bookmarks)) { CarNavigation.open(screenManager, "CarBookmarksScreen") { CarBookmarksScreen(carContext) } },
            Entry(carContext.getString(R.string.car_more_agent)) { CarNavigation.open(screenManager, "CarAgentScreen") { CarAgentScreen(carContext) } },
            Entry(carContext.getString(R.string.car_more_recent)) { CarNavigation.open(screenManager, "CarRecentScreen") { CarRecentScreen(carContext) } },
            Entry(carContext.getString(R.string.car_more_driving)) {
                UiModeStore.setDriving(true)
                screenManager.push(CarDrivingModeScreen(carContext))
            },
            section(HomeSection.SETTINGS)
        )
        return secondary.map(section) + tools + HomeMenuItem.primary.map { section(it.section) }
    }

    override fun onGetTemplate(): Template {
        val paged = CarListPaging.page(carContext, entries(), page)
        val items = ItemList.Builder()
        paged.items.forEach { entry ->
            items.addItem(Row.Builder().setTitle(entry.title).setOnClickListener { entry.action() }.build())
        }
        if (paged.hasMore) {
            items.addItem(
                Row.Builder().setTitle(carContext.getString(R.string.car_iptv_show_more))
                    .setOnClickListener { screenManager.push(CarHomeMoreScreen(carContext, requestSafety, page + 1)) }
                    .build()
            )
        }
        val template = ListTemplate.Builder().setSingleList(items.build())
        val title =
            if (page == 0) carContext.getString(R.string.car_more_title)
            else carContext.getString(R.string.car_more_title_paged, page + 1)
        if (carContext.carAppApiLevel >= 7) {
            template.setHeader(Header.Builder().setTitle(title).setStartHeaderAction(Action.BACK).build())
        } else {
            @Suppress("DEPRECATION")
            template.setTitle(title).setHeaderAction(Action.BACK)
        }
        return template.build()
    }
}
