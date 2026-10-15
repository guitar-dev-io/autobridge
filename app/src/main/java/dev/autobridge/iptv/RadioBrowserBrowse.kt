package dev.autobridge.iptv

import android.os.Handler
import android.os.Looper
import androidx.annotation.VisibleForTesting
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Category browsing through radio-browser, off the main thread, answering on it. Mirrors
 * [RadioBrowserSearch] one-for-one: a single daemon executor, a main-thread [Handler], a 5-minute
 * freshness window, and an LRU keyed by the [RadioCategory] cache key (province for Local, the joined
 * tag set for Podcasts).
 *
 * Callers drop a stale answer by comparing [Result.key] with their current intent. Favorites never
 * reaches [load] — the UI reads [IptvHistoryStore.favorites] directly.
 */
object RadioBrowserBrowse {
    sealed interface Result {
        val key: String
        data class Ready(override val key: String, val entries: List<IptvEntry>) : Result
        data class Failed(override val key: String, val message: String) : Result
    }

    private const val FRESHNESS_MS = 5 * 60 * 1000L
    private const val MAX_CACHED = 20

    private class Cached(val atMs: Long, val entries: List<IptvEntry>)

    private val cache = object : LinkedHashMap<String, Cached>(MAX_CACHED, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Cached>?) = size > MAX_CACHED
    }

    private val worker: ExecutorService by lazy {
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "radio-browser-browse").apply { isDaemon = true }
        }
    }
    private val main by lazy { Handler(Looper.getMainLooper()) }

    /**
     * Loads [category] (scoped to [state] for Local); [onResult] always runs on the main thread.
     * Local with a blank/absent province short-circuits to an empty [Result.Ready] with no network.
     */
    fun load(category: RadioCategory, state: String? = null, onResult: (Result) -> Unit) {
        val key = category.cacheKey(state)
        if (key == null) {
            // Favorites has no fetch; the UI reads the store. Guard against a mis-call.
            return
        }
        if (category == RadioCategory.LOCAL && state.isNullOrBlank()) {
            main.post { onResult(Result.Ready(key, emptyList())) }
            return
        }
        val hit = synchronized(cache) { cache[key] }
        if (hit != null && System.currentTimeMillis() - hit.atMs < FRESHNESS_MS) {
            main.post { onResult(Result.Ready(key, hit.entries)) }
            return
        }
        worker.execute {
            val result = runCatching { fetch(category, state) }.fold(
                onSuccess = { entries ->
                    synchronized(cache) { cache[key] = Cached(System.currentTimeMillis(), entries) }
                    Result.Ready(key, entries)
                },
                onFailure = { error -> Result.Failed(key, error.message ?: error.javaClass.simpleName) }
            )
            main.post { onResult(result) }
        }
    }

    /** The blocking fetch for a network [category]. Runs on [worker]; throws on total failure. */
    private fun fetch(category: RadioCategory, state: String?): List<IptvEntry> = when (category) {
        RadioCategory.IN_COUNTRY -> RadioBrowserApi.browse(RadioBrowserApi.byCountryUrl("TH"))
        RadioCategory.WORLD -> RadioBrowserApi.browse(RadioBrowserApi.topVoteUrl())
        RadioCategory.LOCAL -> {
            val url = RadioBrowserApi.byStateUrl(state.orEmpty()) ?: return emptyList()
            RadioBrowserApi.browse(url)
        }
        RadioCategory.PODCASTS -> fetchPodcasts()
        RadioCategory.FAVORITES -> emptyList()
    }

    /**
     * Fans out one [RadioBrowserApi.byTagUrl]+[RadioBrowserApi.browse] per podcast tag and merges the
     * answers. Tolerates partial failure: the merge is built from the successes, and only when ALL
     * tag fetches throw is the failure propagated.
     */
    private fun fetchPodcasts(): List<IptvEntry> {
        val answers = mutableListOf<List<IptvEntry>>()
        var lastError: Throwable? = null
        for (tag in RadioCategory.PODCAST_TAGS) {
            val url = RadioBrowserApi.byTagUrl(tag) ?: continue
            runCatching { RadioBrowserApi.browse(url) }.fold(
                onSuccess = { answers += it },
                onFailure = { lastError = it }
            )
        }
        if (answers.isEmpty()) throw (lastError ?: IllegalStateException("no podcast tags fetched"))
        return mergePodcasts(answers)
    }

    /**
     * Concatenates per-tag [answers] in tag order and cross-answer dedupes by url (first occurrence
     * wins), preserving each tag's internal vote order. Pure; unit-tested.
     */
    @VisibleForTesting
    internal fun mergePodcasts(answers: List<List<IptvEntry>>): List<IptvEntry> {
        val out = mutableListOf<IptvEntry>()
        val seen = mutableSetOf<String>()
        for (answer in answers) {
            for (entry in answer) {
                if (seen.add(entry.url)) out += entry
            }
        }
        return out
    }
}
