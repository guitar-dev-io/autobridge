package dev.autobridge.car

import androidx.annotation.StringRes
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.Header
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.SectionedItemList
import androidx.car.app.model.Template
import dev.autobridge.R
import dev.autobridge.core.state.UiModeStore
import dev.autobridge.library.HomeSection

/**
 * Secondary destinations kept one tap away from the compact home menu.
 *
 * The home menu itself carries only the six primary cards. Everything else is reached from here,
 * and because those cards are drawn on the car surface — which a rotary controller cannot focus —
 * this host-drawn list is also what keeps every feature reachable without touch. That is why the
 * six primary sections are repeated here rather than left out.
 *
 * ## Why this is grouped, and in this order
 *
 * It used to be one flat list in [HomeSection] order: the library sections, then the tools, then
 * the primary six. Head units cap a list at a handful of rows (commonly six — see [CarListPaging]),
 * so the first page was Folders, Favorites, Playlists, Gallery, Weather, and Mirror — the feature
 * this app exists for — sat on page 2 behind "Show more", with Quick Launch and Recent behind that.
 * The things a driver reaches for were the hardest to reach.
 *
 * The order is now by how often someone actually wants a thing in a car, not by where it happens to
 * sit in the enum:
 *
 *  - **[frequentGroup]** — what is already playing, the user's own shortcuts, Mirror, Recent and the
 *    Agent. Putting the phone on the screen and picking up where you left off are the two things
 *    done at almost every stop, and the Agent is the only input that is safe while moving.
 *  - **[contentGroup]** — the sections that start something new.
 *  - **[deviceGroup]** — on-device libraries; browsing files is a parked activity.
 *  - **[otherGroup]** — looked at once and then left alone.
 *
 * Re-ranking is a one-line move of an entry between those four functions.
 */
class CarHomeMoreScreen(
    carContext: CarContext,
    private val requestSafety: () -> Unit,
    private val page: Int = 0
) : Screen(carContext) {

    private data class Entry(val title: String, val action: () -> Unit)

    /** One titled run of rows; [titleRes] is the section header the host draws above them. */
    private data class Group(@StringRes val titleRes: Int, val entries: List<Entry>)

    private fun section(s: HomeSection) =
        Entry(s.title(carContext)) { CarHomeNavigator.open(carContext, screenManager, s, requestSafety) }

    private fun entry(@StringRes titleRes: Int, action: () -> Unit) =
        Entry(carContext.getString(titleRes), action)

    /**
     * The bridge player, listed only while it has something to show.
     *
     * An always-present row that opens an empty player is the kind of dead entry this screen is
     * meant to avoid, and the player pushes itself to the front anyway whenever content is sent.
     */
    private fun nowPlayingEntry(): Entry? =
        dev.autobridge.bridge.AutoBridgeSessionManager.current
            .takeIf { it.source != null }
            ?.let { state ->
                Entry(
                    carContext.getString(
                        R.string.car_more_now_playing,
                        state.source?.displayTitle.orEmpty()
                    )
                ) {
                    CarNavigation.open(screenManager, "CarBridgePlayerScreen") {
                        dev.autobridge.bridge.CarBridgePlayerScreen(carContext)
                    }
                }
            }

    private fun frequentGroup() = Group(
        R.string.car_more_group_frequent,
        listOfNotNull(
            nowPlayingEntry(),
            // On the car, HomeSection.APPS opens CarQuickLaunchScreen — the user's own shortcuts —
            // and not the phone's installed-app list, so it is labelled for what it does here.
            entry(R.string.car_more_quick_launch) {
                CarHomeNavigator.open(carContext, screenManager, HomeSection.APPS, requestSafety)
            },
            section(HomeSection.MIRROR),
            entry(R.string.car_more_recent) {
                CarNavigation.open(screenManager, "CarRecentScreen") { CarRecentScreen(carContext) }
            },
            entry(R.string.car_more_agent) {
                CarNavigation.open(screenManager, "CarAgentScreen") { CarAgentScreen(carContext) }
            }
        )
    )

    private fun contentGroup() = Group(
        R.string.car_more_group_content,
        // The primary six in the order the home cards show them, so the two surfaces read the same,
        // then the places that collect content rather than being one site.
        HomeMenuItem.primary.map { section(it.section) } + listOf(
            entry(R.string.car_more_media_center) {
                CarNavigation.open(screenManager, "CarMediaCenterScreen") { CarMediaCenterScreen(carContext) }
            },
            entry(R.string.car_more_bookmarks) {
                CarNavigation.open(screenManager, "CarBookmarksScreen") { CarBookmarksScreen(carContext) }
            }
        )
    )

    private fun deviceGroup() = Group(
        R.string.car_more_group_device,
        listOf(HomeSection.FOLDERS, HomeSection.PLAYLISTS, HomeSection.GALLERY, HomeSection.FAVORITES)
            .map(::section)
    )

    private fun otherGroup() = Group(
        R.string.car_more_group_other,
        listOf(
            section(HomeSection.WEATHER),
            entry(R.string.car_more_fuel) {
                CarNavigation.open(screenManager, "CarFuelScreen") { CarFuelScreen(carContext) }
            },
            entry(R.string.car_more_driving) {
                UiModeStore.setDriving(true)
                screenManager.push(CarDrivingModeScreen(carContext))
            },
            section(HomeSection.SETTINGS)
        )
    )

    private fun groups(): List<Group> =
        listOf(frequentGroup(), contentGroup(), deviceGroup(), otherGroup())
            .filter { it.entries.isNotEmpty() }

    override fun onGetTemplate(): Template {
        // Paged over the flattened order, so the host's row budget is respected exactly as before.
        // Each row carries the group it came from, so the page can be re-sectioned for display.
        val flat = groups().flatMap { group -> group.entries.map { group to it } }
        val paged = CarListPaging.page(carContext, flat, page)

        val template = ListTemplate.Builder()
        var sections = 0
        var current: Group? = null
        var builder = ItemList.Builder()
        fun flush() {
            val group = current ?: return
            template.addSectionedList(
                SectionedItemList.create(builder.build(), carContext.getString(group.titleRes))
            )
            sections++
        }
        paged.items.forEach { (group, entry) ->
            if (group !== current) {
                flush()
                current = group
                builder = ItemList.Builder()
            }
            builder.addItem(
                Row.Builder().setTitle(entry.title).setOnClickListener { entry.action() }.build()
            )
        }
        if (paged.hasMore) {
            // Appended to the section already open rather than given one of its own: a header over
            // a lone "Show more" row reads as a category that turns out not to be there.
            builder.addItem(
                Row.Builder().setTitle(carContext.getString(R.string.car_iptv_show_more))
                    .setOnClickListener {
                        screenManager.push(CarHomeMoreScreen(carContext, requestSafety, page + 1))
                    }
                    .build()
            )
        }
        flush()
        // A ListTemplate must carry a list. Nothing above produces zero sections today, but the
        // host rejects an empty template outright, so the fallback is cheaper than the crash.
        if (sections == 0) template.setSingleList(ItemList.Builder().build())

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
