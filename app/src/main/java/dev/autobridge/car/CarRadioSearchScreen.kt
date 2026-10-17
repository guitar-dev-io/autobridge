package dev.autobridge.car

import android.os.Handler
import android.os.Looper
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ItemList
import androidx.car.app.model.Row
import androidx.car.app.model.SearchTemplate
import androidx.car.app.model.Template
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import dev.autobridge.R
import dev.autobridge.iptv.IptvCatalog
import dev.autobridge.iptv.IptvChannelQueue
import dev.autobridge.iptv.IptvEntry
import dev.autobridge.iptv.IptvHistoryStore
import dev.autobridge.iptv.IptvKind
import dev.autobridge.iptv.IptvSource
import dev.autobridge.iptv.IptvSourceStore
import dev.autobridge.iptv.RadioBrowserSearch
import dev.autobridge.iptv.RadioStationMatcher
import dev.autobridge.media.MediaPlaybackClient

/**
 * Finds a radio station by name or frequency ("Cool 93", "FM 95.5", "คลื่น 95") across every
 * radio source the user has, then online through radio-browser.
 *
 * Local matches come first and appear as the user types; the online search runs on submit or
 * after a short pause in typing. The list never exceeds the host's limit: one row is kept for a
 * status line (searching / failed / no matches), and when online results exist the local ones
 * give up half the rest. Search is meant to be refined, so there is no "Show more".
 */
class CarRadioSearchScreen(carContext: CarContext) : Screen(carContext) {
    private sealed interface Online {
        object Idle : Online
        data class Searching(val key: String) : Online
        data class Ready(val key: String, val entries: List<IptvEntry>) : Online
        data class Failed(val key: String, val message: String) : Online
    }

    private val mediaPlayback = MediaPlaybackClient(carContext)
    private val handler = Handler(Looper.getMainLooper())
    private val debounce = Runnable { startOnline() }
    private var query = ""
    private var online: Online = Online.Idle

    init {
        mediaPlayback.connect(onConnected = {}, onError = {})
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) = loadSources()

            override fun onDestroy(owner: LifecycleOwner) {
                handler.removeCallbacks(debounce)
                mediaPlayback.disconnect()
            }
        })
    }

    /** Radio sources not loaded yet are loaded now, so their stations join the results as they land. */
    private fun loadSources() {
        IptvSourceStore.list(carContext, IptvKind.RADIO)
            .filter { IptvCatalog.cached(it.id) == null }
            .forEach { source -> IptvCatalog.load(carContext, source) { repaint() } }
    }

    private fun repaint() {
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) invalidate()
    }

    private fun key(text: String = query) = RadioStationMatcher.apiQuery(text)

    private fun startOnline() {
        handler.removeCallbacks(debounce)
        val key = key()
        if (key.isBlank()) return
        val current = online
        if (current is Online.Ready && current.key == key) return
        online = Online.Searching(key)
        repaint()
        RadioBrowserSearch.search(query) { result ->
            val resultKey = key(result.query)
            // An answer for a query the field no longer shows is dropped.
            if (resultKey != key()) return@search
            online = when (result) {
                is RadioBrowserSearch.Result.Ready -> Online.Ready(resultKey, result.entries)
                is RadioBrowserSearch.Result.Failed -> Online.Failed(resultKey, result.message)
            }
            repaint()
        }
    }

    private fun onQuery(text: String, submitted: Boolean) {
        query = text
        handler.removeCallbacks(debounce)
        val key = key()
        val stateKey = when (val current = online) {
            Online.Idle -> null
            is Online.Searching -> current.key
            is Online.Ready -> current.key
            is Online.Failed -> current.key
        }
        if (stateKey != key) online = Online.Idle
        when {
            submitted -> startOnline()
            key.length >= MIN_ONLINE_CHARS -> handler.postDelayed(debounce, DEBOUNCE_MS)
        }
        invalidate()
    }

    private fun localMatches(): List<Pair<IptvSource, IptvEntry>> {
        val all = IptvSourceStore.list(carContext, IptvKind.RADIO).flatMap { source ->
            IptvCatalog.cached(source.id)?.entries.orEmpty()
                .filter { !it.isSeriesFolder && it.url.isNotBlank() }
                .map { source to it }
        }
        return RadioStationMatcher.rank(all, query) { it.second.title }.distinctBy { it.second.url }
    }

    private fun onlineMatches(localUrls: Set<String>): List<IptvEntry> {
        val ready = online as? Online.Ready ?: return emptyList()
        // The API's name search did the words; a frequency still needs judging ("95" is not "1095").
        val checkNumbers = RadioStationMatcher.hasNumber(query)
        return ready.entries
            .filter { it.url !in localUrls }
            .filter { !checkNumbers || RadioStationMatcher.matches(it.title, query) }
    }

    override fun onGetTemplate(): Template {
        val list = ItemList.Builder()
        if (query.isBlank()) {
            list.addItem(Row.Builder().setTitle(carContext.getString(R.string.radio_search_prompt)).build())
        } else {
            addResults(list)
        }
        return SearchTemplate.Builder(
            object : SearchTemplate.SearchCallback {
                override fun onSearchTextChanged(searchText: String) = onQuery(searchText, submitted = false)

                override fun onSearchSubmitted(searchText: String) = onQuery(searchText, submitted = true)
            }
        )
            .setHeaderAction(Action.BACK)
            .setInitialSearchText(query)
            .setSearchHint(carContext.getString(R.string.radio_search_hint))
            .setShowKeyboardByDefault(true)
            .setItemList(list.build())
            .build()
    }

    private fun addResults(list: ItemList.Builder) {
        val budget = (CarListPaging.limit(carContext) - 1).coerceAtLeast(1)
        val local = localMatches()
        val remote = onlineMatches(local.map { it.second.url }.toSet())
        val localCap = if (remote.isNotEmpty()) (budget + 1) / 2 else budget
        val localShown = local.take(localCap)
        val remoteShown = remote.take(budget - localShown.size)
        val queue = localShown.map { it.second } + remoteShown

        localShown.forEach { (source, entry) ->
            val row = Row.Builder()
                .setTitle(entry.title)
                .addText(listOf(source.name, entry.subtitle).filter { it.isNotBlank() }.joinToString(" • "))
                .setOnClickListener { play(source, entry, queue) }
            CarIptvCheck.secondLine(carContext, entry.url, next = null)?.let { row.addText(it) }
            list.addItem(row.build())
        }
        remoteShown.forEach { entry ->
            val row = Row.Builder()
                .setTitle(entry.title)
                .addText(
                    listOf(carContext.getString(R.string.radio_search_online), entry.subtitle)
                        .filter { it.isNotBlank() }
                        .joinToString(" • ")
                )
                .setOnClickListener { play(RadioBrowserSearch.SOURCE, entry, queue) }
            CarIptvCheck.secondLine(carContext, entry.url, next = null)?.let { row.addText(it) }
            list.addItem(row.build())
        }

        // A typed query waiting out the pause reads as searching, not as "no matches".
        val pending = online == Online.Idle && key().length >= MIN_ONLINE_CHARS
        val status = when (val current = online) {
            is Online.Searching -> carContext.getString(R.string.radio_search_searching)
            is Online.Failed -> carContext.getString(R.string.radio_search_failed, current.message)
            else -> when {
                pending -> carContext.getString(R.string.radio_search_searching)
                queue.isEmpty() -> carContext.getString(R.string.car_find_no_matches)
                else -> null
            }
        }
        status?.let { list.addItem(Row.Builder().setTitle(it).build()) }
    }

    private fun play(source: IptvSource, entry: IptvEntry, queue: List<IptvEntry>) {
        IptvHistoryStore.recordPlayback(carContext, source, entry)
        CarIptvPlayback.play(
            this, carContext, mediaPlayback, entry.title, entry.url, IptvKind.RADIO, entry.playback,
            IptvChannelQueue.around(queue, entry, CarIptvCheck::isDead)
        )
    }

    private companion object {
        const val DEBOUNCE_MS = 600L
        const val MIN_ONLINE_CHARS = 2
    }
}
