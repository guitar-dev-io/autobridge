package dev.autobridge.car

import androidx.annotation.DrawableRes
import androidx.annotation.OptIn
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.annotations.ExperimentalCarApi
import androidx.car.app.annotations.RequiresCarApi
import androidx.car.app.constraints.ConstraintManager
import androidx.car.app.model.Action
import androidx.car.app.model.CarColor
import androidx.car.app.model.CarIcon
import androidx.car.app.model.GridItem
import androidx.car.app.model.GridTemplate
import androidx.car.app.model.ItemList
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Tab
import androidx.car.app.model.TabContents
import androidx.car.app.model.TabTemplate
import androidx.car.app.model.Template
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import dev.autobridge.R
import dev.autobridge.iptv.IptvChannelQueue
import dev.autobridge.iptv.IptvEntry
import dev.autobridge.iptv.IptvHistoryStore
import dev.autobridge.iptv.IptvKind
import dev.autobridge.iptv.IptvPlayback
import dev.autobridge.iptv.IptvSourceStore
import dev.autobridge.iptv.RadioBrowserBrowse
import dev.autobridge.iptv.RadioCategory
import dev.autobridge.iptv.RadioProvinceRepository
import dev.autobridge.media.MediaPlaybackClient
import dev.autobridge.radio.InternetRadioSource
import dev.autobridge.radio.RadioRepository

/**
 * Radio on the car, the way the phone has it: the five [RadioCategory]s as tabs along the top and
 * each one's stations as a grid of logos, a tap on a logo playing it. Needs a host at Car API 6
 * ([TabTemplate]); [open] sends an older one to the list landing ([CarIptvSourcesScreen]) instead.
 *
 * Each network tab fetches through [RadioBrowserBrowse] when it is first shown and drops a stale
 * answer by key, like [CarRadioCategoryScreen]. A grid holds as many cells as the host allows, so
 * a longer category ends in an "All stations" cell that opens that category's paged list. In
 * Country also carries the search, and the driver's own lists when there are any, so nothing the
 * old landing offered is out of reach.
 */
// TabTemplate and its parts were experimental before car-app 1.7; the opt-in keeps lint quiet
// whichever way this version marks them.
@OptIn(ExperimentalCarApi::class)
@RequiresCarApi(6)
class CarRadioHomeScreen(carContext: CarContext) : Screen(carContext) {
    companion object {
        /** Radio's landing for this host: the tabbed grid from Car API 6, the list before it. */
        fun open(carContext: CarContext): Screen =
            if (carContext.carAppApiLevel >= 6) CarRadioHomeScreen(carContext)
            else CarIptvSourcesScreen(carContext, IptvKind.RADIO)

        private const val FALLBACK_GRID_LIMIT = 6

        /**
         * The tabs: a host takes at most four ("There cannot be more than 4 tabs"), so Podcasts,
         * the least used, is a cell inside In Country instead of a tab of its own.
         */
        val TABS = listOf(RadioCategory.IN_COUNTRY, RadioCategory.LOCAL, RadioCategory.WORLD, RadioCategory.FAVORITES)
    }

    private sealed interface State {
        object Loading : State
        data class Ready(val entries: List<IptvEntry>) : State
        data class Failed(val message: String) : State
    }

    private val mediaPlayback = MediaPlaybackClient(carContext)
    private val logos = CarChannelLogos(carContext) { repaint() }
    private var selected = RadioCategory.IN_COUNTRY
    private val states = mutableMapOf<String, State>()

    init {
        mediaPlayback.connect(onConnected = { invalidate() }, onError = { invalidate() })
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) = load(selected)

            override fun onDestroy(owner: LifecycleOwner) {
                logos.stop()
                mediaPlayback.disconnect()
            }
        })
    }

    private fun repaint() {
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) invalidate()
    }

    private fun province(): String? = RadioProvinceRepository.selected(carContext).takeIf { it.isNotBlank() }

    /** Fetches [category]'s stations unless they are already here or on the way. */
    private fun load(category: RadioCategory) {
        if (category == RadioCategory.FAVORITES) return
        if (category == RadioCategory.LOCAL && province() == null) return
        val key = category.cacheKey(province()) ?: return
        if (states[key] is State.Ready || states[key] == State.Loading) return
        states[key] = State.Loading
        RadioBrowserBrowse.load(category, province()) { result ->
            states[result.key] = when (result) {
                is RadioBrowserBrowse.Result.Ready -> State.Ready(result.entries)
                is RadioBrowserBrowse.Result.Failed -> State.Failed(result.message)
            }
            repaint()
        }
    }

    override fun onGetTemplate(): Template {
        logos.beginTemplate()
        val builder = TabTemplate.Builder(object : TabTemplate.TabCallback {
            override fun onTabSelected(tabContentId: String) {
                val category = TABS.firstOrNull { it.name == tabContentId } ?: return
                selected = category
                load(category)
                invalidate()
            }
        }).setHeaderAction(Action.APP_ICON)
        TABS.forEach { category ->
            builder.addTab(
                Tab.Builder()
                    .setTitle(carContext.getString(category.labelRes))
                    .setIcon(icon(tabIcon(category)))
                    .setContentId(category.name)
                    .build()
            )
        }
        return builder
            .setTabContents(TabContents.Builder(content(selected)).build())
            .setActiveTabContentId(selected.name)
            .build()
    }

    /** The selected tab's body: a station grid, or the one thing standing in its way. */
    private fun content(category: RadioCategory): Template {
        if (category == RadioCategory.FAVORITES) {
            val favorites = IptvHistoryStore.favorites(carContext, IptvKind.RADIO).filter { it.url.isNotBlank() }
            if (favorites.isEmpty()) return message(carContext.getString(R.string.radio_browse_empty))
            val items = ItemList.Builder()
            val logoByUrl = favorites.associate { it.url to it.logo }
            favorites.take(gridLimit()).forEach { item ->
                items.addItem(stationCell(item.title, item.logo, item.url) {
                    RadioRepository.recordPlayback(carContext, RadioRepository.RadioHistory.toRadioStation(item))
                    CarIptvPlayback.play(
                        this, carContext, mediaPlayback, item.title, item.url, IptvKind.RADIO, item.playback,
                        IptvChannelQueue.aroundItems(favorites, item, CarIptvCheck::isDead),
                        logoFor = { logoByUrl[it] }
                    )
                })
            }
            return GridTemplate.Builder().setSingleList(items.build()).build()
        }
        if (category == RadioCategory.LOCAL && province() == null) {
            return MessageTemplate.Builder(carContext.getString(R.string.radio_local_pick_title))
                .addAction(
                    Action.Builder()
                        .setTitle(carContext.getString(R.string.radio_local_pick_cta))
                        .setOnClickListener {
                            screenManager.push(CarRadioProvinceScreen(carContext) {
                                load(RadioCategory.LOCAL)
                                repaint()
                            })
                        }
                        .build()
                )
                .build()
        }
        val key = category.cacheKey(province())
        return when (val state = key?.let { states[it] } ?: State.Loading) {
            State.Loading -> GridTemplate.Builder().setLoading(true).build()
            is State.Failed -> message(state.message)
            is State.Ready -> stationGrid(category, state.entries)
        }
    }

    private fun stationGrid(category: RadioCategory, entries: List<IptvEntry>): Template {
        // The directory lists some stations twice (one per stream); the grid shows each name once.
        val stations = entries.filterNot { CarIptvCheck.isDead(it.url) || it.url.isBlank() }
            .distinctBy { it.title.trim().lowercase() }
        val extras = mutableListOf<GridItem>()
        if (category == RadioCategory.IN_COUNTRY) {
            extras += actionCell(carContext.getString(R.string.radio_search), CarIcons.SEARCH) {
                screenManager.push(CarRadioSearchScreen(carContext))
            }
            extras += actionCell(carContext.getString(RadioCategory.PODCASTS.labelRes), R.drawable.ic_car_mic) {
                screenManager.push(CarRadioCategoryScreen(carContext, RadioCategory.PODCASTS))
            }
        }
        val limit = gridLimit()
        // Room for the extras, and for "All stations" when the category does not fit.
        var room = (limit - extras.size).coerceAtLeast(1)
        val overflow = stations.size > room
        if (overflow) room = (room - 1).coerceAtLeast(1)
        val items = ItemList.Builder()
        extras.forEach(items::addItem)
        val logoByUrl = stations.associate { it.url to it.logo }
        stations.take(room).forEach { entry ->
            items.addItem(stationCell(entry.title, entry.logo, entry.url) { play(entry, stations, logoByUrl) })
        }
        if (overflow) {
            items.addItem(actionCell(carContext.getString(R.string.radio_all_stations), R.drawable.ic_tile_radio) {
                screenManager.push(CarRadioCategoryScreen(carContext, category))
            })
        } else if (category == RadioCategory.IN_COUNTRY && IptvSourceStore.list(carContext, IptvKind.RADIO).isNotEmpty()) {
            // The driver's own radio lists (M3U, Xtream) still live on the list landing.
            items.addItem(actionCell(carContext.getString(R.string.radio_browse_my_stations), CarIcons.LIBRARY) {
                screenManager.push(CarIptvSourcesScreen(carContext, IptvKind.RADIO))
            })
        }
        if (stations.isEmpty() && extras.isEmpty()) return message(carContext.getString(R.string.radio_browse_empty))
        return GridTemplate.Builder().setSingleList(items.build()).build()
    }

    private fun play(entry: IptvEntry, stations: List<IptvEntry>, logoByUrl: Map<String, String>) {
        RadioRepository.recordPlayback(carContext, InternetRadioSource.toStation(entry))
        CarIptvPlayback.play(
            this, carContext, mediaPlayback, entry.title, entry.url, IptvKind.RADIO,
            IptvPlayback.STREAM, IptvChannelQueue.around(stations, entry, CarIptvCheck::isDead),
            logoFor = { logoByUrl[it] }
        )
    }

    /**
     * A station: its logo on a card, or its coloured name card ([CarStationTile]); the radio mark
     * only past the image budget. The station on the air says so under its name.
     */
    private fun stationCell(title: String, logo: String?, url: String, onClick: () -> Unit): GridItem {
        val onAir = url == mediaPlayback.currentUri
        val cell = GridItem.Builder()
            .setTitle(title)
            .setImage(logos.tile(logo, title, onAir) ?: radioMark(), GridItem.IMAGE_TYPE_LARGE)
            .setOnClickListener(onClick)
        if (onAir) {
            cell.setText(carContext.getString(if (mediaPlayback.isPlaying) R.string.car_lib_playing else R.string.car_lib_paused))
        }
        return cell.build()
    }

    /** Search, podcasts, all stations: a dark card like the stations', not a bare icon. */
    private fun actionCell(title: String, @DrawableRes iconRes: Int, onClick: () -> Unit): GridItem {
        val card = actionCards.getOrPut(iconRes) {
            androidx.core.content.ContextCompat.getDrawable(carContext, iconRes)?.let { glyph ->
                CarIcon.Builder(IconCompat.createWithBitmap(CarStationTile.action(glyph))).build()
            } ?: icon(iconRes)
        }
        return GridItem.Builder()
            .setTitle(title)
            .setImage(card, GridItem.IMAGE_TYPE_LARGE)
            .setOnClickListener(onClick)
            .build()
    }

    private val actionCards = mutableMapOf<Int, CarIcon>()

    private fun message(text: String): Template = MessageTemplate.Builder(text).build()

    private fun gridLimit(): Int = runCatching {
        carContext.getCarService(ConstraintManager::class.java)
            .getContentLimit(ConstraintManager.CONTENT_LIMIT_TYPE_GRID)
    }.getOrDefault(FALLBACK_GRID_LIMIT).coerceIn(FALLBACK_GRID_LIMIT, 100)

    private fun icon(@DrawableRes resourceId: Int): CarIcon = CarIcons.of(carContext, resourceId)

    private fun radioMark(): CarIcon =
        CarIcon.Builder(IconCompat.createWithResource(carContext, R.drawable.ic_tile_radio))
            .setTint(CarColor.createCustom(HomeDashboardTheme.ACCENT_RADIO, HomeDashboardTheme.ACCENT_RADIO))
            .build()

    @DrawableRes
    private fun tabIcon(category: RadioCategory): Int = when (category) {
        RadioCategory.IN_COUNTRY -> R.drawable.ic_car_home
        RadioCategory.LOCAL -> R.drawable.ic_radio_local
        RadioCategory.WORLD -> R.drawable.ic_tile_language
        RadioCategory.PODCASTS -> R.drawable.ic_car_mic
        RadioCategory.FAVORITES -> R.drawable.ic_browser_star
    }
}
