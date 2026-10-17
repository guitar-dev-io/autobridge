package dev.autobridge.car

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.Header
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import dev.autobridge.R
import dev.autobridge.iptv.IptvChannelQueue
import dev.autobridge.iptv.IptvEntry
import dev.autobridge.iptv.IptvHistoryStore
import dev.autobridge.iptv.IptvKind
import dev.autobridge.iptv.IptvPlayback
import dev.autobridge.iptv.RadioBrowserBrowse
import dev.autobridge.iptv.RadioCategory
import dev.autobridge.iptv.RadioProvinceRepository
import dev.autobridge.radio.InternetRadioSource
import dev.autobridge.radio.RadioRepository
import dev.autobridge.media.MediaPlaybackClient

/**
 * One category's stations on the car, modelled on [CarIptvEntriesScreen]: the grid degrades to a
 * [CarListPaging]-paged list (a list carries the subtitle, the favorite marker and a logo that a
 * grid cell cannot), with logos through [CarChannelLogos] and a "Show more" row.
 *
 * A network category fetches once on start through [RadioBrowserBrowse], drops a stale answer by
 * key, and repaints on the main-thread callback. [RadioCategory.FAVORITES] reads
 * [IptvHistoryStore.favorites] instead (no network). [RadioCategory.LOCAL] with no chosen province
 * pushes a province picker ([CarRadioProvinceScreen]); with one set it shows that state's stations.
 *
 * A row tap records recents on the unified `radio-virtual` identity (via
 * [RadioRepository.recordPlayback] / [InternetRadioSource.toStation], so attribution matches the
 * phone) and then plays through [CarIptvPlayback]. Favoriting add/remove is phone-only — a car
 * [Row] has no per-row secondary action — so the favorite is a read-only text marker and the
 * primary tap always plays.
 */
class CarRadioCategoryScreen(
    carContext: CarContext,
    private val category: RadioCategory,
    private val page: Int = 0
) : Screen(carContext) {

    private sealed interface State {
        object Loading : State
        data class Ready(val key: String, val entries: List<IptvEntry>) : State
        data class Failed(val key: String, val message: String) : State
    }

    private val mediaPlayback = MediaPlaybackClient(carContext)
    private val logos = CarChannelLogos(carContext) { repaint() }
    private val isNetwork = category != RadioCategory.FAVORITES
    private var state: State = State.Loading
    private var fetchedKey: String? = null

    init {
        mediaPlayback.connect(onConnected = { invalidate() }, onError = { invalidate() })
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                if (isNetwork) startLoad()
            }

            override fun onDestroy(owner: LifecycleOwner) {
                logos.stop()
                mediaPlayback.disconnect()
            }
        })
    }

    /** Fetch this category once; a repeat (e.g. a return to a cached key) is a no-op. */
    private fun startLoad() {
        if (category == RadioCategory.LOCAL && RadioProvinceRepository.selected(carContext).isBlank()) {
            // No province yet: the body is the picker prompt, no network.
            state = State.Ready(RadioCategory.LOCAL.cacheKey("").orEmpty(), emptyList())
            fetchedKey = null
            return
        }
        val province = RadioProvinceRepository.selected(carContext).takeIf { it.isNotBlank() }
        val key = category.cacheKey(province) ?: return
        if (fetchedKey == key && state is State.Ready) return
        fetchedKey = key
        state = State.Loading
        RadioBrowserBrowse.load(category, province) { result ->
            // Drop an answer for a key we are no longer asking for.
            if (result.key != fetchedKey) return@load
            state = when (result) {
                is RadioBrowserBrowse.Result.Ready -> State.Ready(result.key, result.entries)
                is RadioBrowserBrowse.Result.Failed -> State.Failed(result.key, result.message)
            }
            repaint()
        }
    }

    private fun repaint() {
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) invalidate()
    }

    private fun title(): String = carContext.getString(category.labelRes)

    override fun onGetTemplate(): Template {
        val list = ItemList.Builder()
        logos.beginTemplate()
        when (category) {
            RadioCategory.FAVORITES -> buildFavorites(list)
            RadioCategory.LOCAL -> buildLocal(list)
            else -> buildNetwork(list)
        }
        return ListTemplate.Builder()
            .setHeader(
                Header.Builder()
                    .setTitle(title())
                    .setStartHeaderAction(Action.BACK)
                    .build()
            )
            .setSingleList(list.build())
            .build()
    }

    /** Local with no province: the pick-a-province prompt; otherwise the state's station list. */
    private fun buildLocal(list: ItemList.Builder) {
        if (RadioProvinceRepository.selected(carContext).isBlank()) {
            list.addItem(
                Row.Builder()
                    .setTitle(carContext.getString(R.string.radio_local_pick_title))
                    .addText(carContext.getString(R.string.radio_local_pick_cta))
                    .setBrowsable(true)
                    .setOnClickListener {
                        screenManager.push(CarRadioProvinceScreen(carContext) {
                            // A province was chosen: re-fetch and repaint on return.
                            startLoad()
                            repaint()
                        })
                    }
                    .build()
            )
            return
        }
        buildNetwork(list)
    }

    /** A network category: loading / failed / empty status, else the paged station rows. */
    private fun buildNetwork(list: ItemList.Builder) {
        if (category == RadioCategory.PODCASTS) {
            list.addItem(
                Row.Builder()
                    .setTitle(carContext.getString(R.string.radio_podcasts_note))
                    .build()
            )
        }
        when (val s = state) {
            State.Loading -> list.addItem(
                Row.Builder().setTitle(carContext.getString(R.string.radio_browse_loading)).build()
            )
            is State.Failed -> list.addItem(
                Row.Builder().setTitle(s.message).build()
            )
            is State.Ready -> {
                if (s.entries.isEmpty()) {
                    list.addItem(
                        Row.Builder()
                            .setTitle(carContext.getString(R.string.radio_browse_empty))
                            .build()
                    )
                    return
                }
                addStationRows(list, s.entries)
            }
        }
    }

    /** The paged station rows for a network category, with logos and a "Show more" row. */
    private fun addStationRows(list: ItemList.Builder, entries: List<IptvEntry>) {
        val paged = CarListPaging.page(carContext, entries, page)
        paged.items.forEach { entry ->
            val favorite = entry.url.isNotBlank() && IptvHistoryStore.isFavorite(carContext, entry.url)
            val row = Row.Builder()
                .setTitle(entry.title)
                .addText(
                    listOfNotNull(
                        entry.subtitle.takeIf { it.isNotBlank() },
                        carContext.getString(R.string.car_iptv_favourite).takeIf { favorite }
                    ).joinToString(" • ")
                        .ifBlank { carContext.getString(R.string.car_iptv_tap_to_play) }
                )
                .setOnClickListener { play(entry, entries) }
            logos.icon(entry.logo)?.let { row.setImage(it, Row.IMAGE_TYPE_SMALL) }
            list.addItem(row.build())
        }
        if (paged.hasMore) {
            list.addItem(
                Row.Builder()
                    .setTitle(carContext.getString(R.string.car_iptv_show_more))
                    .setBrowsable(true)
                    .setOnClickListener {
                        screenManager.push(CarRadioCategoryScreen(carContext, category, page + 1))
                    }
                    .build()
            )
        }
    }

    /** Favorites: read-only favorite marker on every row; the primary tap plays, never toggles. */
    private fun buildFavorites(list: ItemList.Builder) {
        val favorites = IptvHistoryStore.favorites(carContext, IptvKind.RADIO)
        if (favorites.isEmpty()) {
            list.addItem(
                Row.Builder().setTitle(carContext.getString(R.string.radio_browse_empty)).build()
            )
            return
        }
        val paged = CarListPaging.page(carContext, favorites, page)
        paged.items.forEach { item ->
            val row = Row.Builder()
                .setTitle(item.title)
                .addText(carContext.getString(R.string.car_iptv_favourite))
                .setOnClickListener { playFavorite(item, favorites) }
            logos.icon(item.logo)?.let { row.setImage(it, Row.IMAGE_TYPE_SMALL) }
            list.addItem(row.build())
        }
        if (paged.hasMore) {
            list.addItem(
                Row.Builder()
                    .setTitle(carContext.getString(R.string.car_iptv_show_more))
                    .setBrowsable(true)
                    .setOnClickListener {
                        screenManager.push(CarRadioCategoryScreen(carContext, category, page + 1))
                    }
                    .build()
            )
        }
    }

    /**
     * Records recents on the `radio-virtual` identity (so attribution matches the phone) FIRST,
     * then plays with the category entries as the queue.
     */
    private fun play(entry: IptvEntry, entries: List<IptvEntry>) {
        if (entry.url.isBlank()) return
        RadioRepository.recordPlayback(carContext, InternetRadioSource.toStation(entry))
        val logos = entries.associate { it.url to it.logo }
        CarIptvPlayback.play(
            this, carContext, mediaPlayback, entry.title, entry.url, IptvKind.RADIO,
            IptvPlayback.STREAM, IptvChannelQueue.around(entries, entry, CarIptvCheck::isDead),
            logoFor = { logos[it] }
        )
    }

    /** A Favorites tap plays with the favorites list as the queue (never toggles on car). */
    private fun playFavorite(item: IptvHistoryStore.Item, favorites: List<IptvHistoryStore.Item>) {
        if (item.url.isBlank()) return
        RadioRepository.recordPlayback(carContext, RadioRepository.RadioHistory.toRadioStation(item))
        val logos = favorites.associate { it.url to it.logo }
        CarIptvPlayback.play(
            this, carContext, mediaPlayback, item.title, item.url, IptvKind.RADIO,
            item.playback, IptvChannelQueue.aroundItems(favorites, item, CarIptvCheck::isDead),
            logoFor = { logos[it] }
        )
    }
}
