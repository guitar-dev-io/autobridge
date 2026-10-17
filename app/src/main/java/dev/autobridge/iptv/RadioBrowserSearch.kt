package dev.autobridge.iptv

import android.os.Handler
import android.os.Looper
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Online station search through radio-browser, off the main thread, answering on it.
 *
 * Results are [IptvEntry]s filed under the synthetic [SOURCE], so history, favourites and the
 * existing radio playback path treat them like any playlist station. Callers drop a stale answer
 * by comparing [Result.query] with what is in the search field now.
 */
object RadioBrowserSearch {
    val SOURCE = IptvSource(
        id = "radio-browser-search",
        name = "radio-browser",
        kind = IptvKind.RADIO,
        type = IptvSourceType.M3U,
        url = RadioBrowserApi.BASE
    )

    sealed interface Result {
        val query: String
        data class Ready(override val query: String, val entries: List<IptvEntry>) : Result
        data class Failed(override val query: String, val message: String) : Result
    }

    private const val FRESHNESS_MS = 5 * 60 * 1000L
    private const val MAX_CACHED = 20

    private class Cached(val atMs: Long, val entries: List<IptvEntry>)

    private val cache = object : LinkedHashMap<String, Cached>(MAX_CACHED, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Cached>?) = size > MAX_CACHED
    }

    private val worker: ExecutorService by lazy {
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "radio-browser-search").apply { isDaemon = true }
        }
    }
    private val main by lazy { Handler(Looper.getMainLooper()) }

    /** Searches for [query]; [onResult] always runs on the main thread. */
    fun search(query: String, onResult: (Result) -> Unit) {
        val key = RadioStationMatcher.apiQuery(query)
        if (key.isBlank()) {
            main.post { onResult(Result.Ready(query, emptyList())) }
            return
        }
        val hit = synchronized(cache) { cache[key] }
        if (hit != null && System.currentTimeMillis() - hit.atMs < FRESHNESS_MS) {
            main.post { onResult(Result.Ready(query, hit.entries)) }
            return
        }
        worker.execute {
            val result = runCatching { RadioBrowserApi.search(query) }.fold(
                onSuccess = { entries ->
                    synchronized(cache) { cache[key] = Cached(System.currentTimeMillis(), entries) }
                    Result.Ready(query, entries)
                },
                onFailure = { error -> Result.Failed(query, error.message ?: error.javaClass.simpleName) }
            )
            main.post { onResult(result) }
        }
    }
}
