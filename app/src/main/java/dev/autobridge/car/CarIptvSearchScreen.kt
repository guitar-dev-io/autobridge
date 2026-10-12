package dev.autobridge.car

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ItemList
import androidx.car.app.model.Row
import androidx.car.app.model.SearchTemplate
import androidx.car.app.model.Template
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import dev.autobridge.R
import dev.autobridge.iptv.IptvCatalogData
import dev.autobridge.iptv.IptvChannelQueue
import dev.autobridge.iptv.IptvEntry
import dev.autobridge.iptv.IptvGuide
import dev.autobridge.iptv.IptvGuideText
import dev.autobridge.iptv.IptvHistoryStore
import dev.autobridge.iptv.IptvSource
import dev.autobridge.media.MediaPlaybackClient

/**
 * Finds a channel in one source by its name, or by what it is showing: "football" lists the
 * channels whose guide says a match is on now, after the ones with "football" in their name.
 *
 * Built on [SearchTemplate], the one car template with a text field. The host decides whether the
 * keyboard is offered while moving (most offer voice input instead), so the screen asks for
 * nothing the host would refuse. Results are capped at the host's list limit.
 */
class CarIptvSearchScreen(
    carContext: CarContext,
    private val source: IptvSource,
    private val data: IptvCatalogData
) : Screen(carContext) {
    private val mediaPlayback = MediaPlaybackClient(carContext)
    private var query = ""

    init {
        mediaPlayback.connect(onConnected = {}, onError = {})
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) = mediaPlayback.disconnect()
        })
    }

    override fun onGetTemplate(): Template {
        val results = results()
        val list = ItemList.Builder()
        if (query.isBlank()) {
            list.addItem(Row.Builder().setTitle(carContext.getString(R.string.car_iptv_search_prompt)).build())
        } else if (results.isEmpty()) {
            list.addItem(Row.Builder().setTitle(carContext.getString(R.string.car_find_no_matches)).build())
        }
        results.forEach { entry ->
            val guide = IptvGuide.nowNext(source, entry)
            val row = Row.Builder()
                .setTitle(entry.title)
                .setOnClickListener { play(entry, results) }
            listOfNotNull(IptvGuideText.now(carContext, guide), entry.subtitle.takeIf { it.isNotBlank() })
                .joinToString(" • ")
                .takeIf { it.isNotBlank() }
                ?.let { row.addText(it) }
            CarIptvCheck.secondLine(carContext, entry.url, next = null)?.let { row.addText(it) }
            list.addItem(row.build())
        }
        return SearchTemplate.Builder(
            object : SearchTemplate.SearchCallback {
                override fun onSearchTextChanged(searchText: String) {
                    query = searchText
                    invalidate()
                }

                override fun onSearchSubmitted(searchText: String) {
                    query = searchText
                    invalidate()
                }
            }
        )
            .setHeaderAction(Action.BACK)
            .setInitialSearchText(query)
            .setSearchHint(carContext.getString(R.string.car_iptv_search_hint))
            .setShowKeyboardByDefault(true)
            .setItemList(list.build())
            .build()
    }

    /** Name matches first, then channels whose programme on now matches, without duplicates. */
    private fun results(): List<IptvEntry> {
        val needle = query.trim()
        if (needle.isEmpty()) return emptyList()
        val limit = (CarListPaging.limit(carContext) - 1).coerceAtLeast(1)
        val byName = data.entries.asSequence()
            .filter { !it.isSeriesFolder && it.title.contains(needle, ignoreCase = true) }
            .take(limit)
            .toList()
        if (byName.size >= limit) return byName
        val onNow = IptvGuide.onNowMatching(source, data.entries, needle, limit)
        return (byName + onNow).distinctBy { it.id }.take(limit)
    }

    private fun play(entry: IptvEntry, results: List<IptvEntry>) {
        IptvHistoryStore.recordPlayback(carContext, source, entry)
        CarIptvPlayback.play(
            this, carContext, mediaPlayback, entry.title, entry.url, source.kind, entry.playback,
            IptvChannelQueue.around(results, entry, CarIptvCheck::isDead)
        )
    }
}
