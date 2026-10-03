package dev.autobridge.iptv

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Process-wide cache in front of [XtreamClient], shared by the phone Activity and the Android Auto
 * screens so a portal is fetched once and both surfaces show the same catalog.
 *
 * Large Xtream accounts return tens of thousands of entries, so the result is kept in memory with
 * a freshness window and reused until the user asks for a refresh. Callbacks are always delivered
 * on the main thread; a load already in flight adopts new callers instead of firing a second
 * request.
 */
object IptvCatalog {
    /** What a caller sees while a source is being resolved. */
    sealed interface Result {
        data class Ready(val data: IptvCatalogData, val loadedAtMs: Long) : Result
        data class Failed(val message: String) : Result
    }

    private const val FRESHNESS_MS = 30L * 60L * 1000L

    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "iptv-catalog").apply { isDaemon = true }
    }
    private val main = Handler(Looper.getMainLooper())
    private val cache = ConcurrentHashMap<String, Result.Ready>()
    private val inFlight = ConcurrentHashMap<String, MutableList<(Result) -> Unit>>()
    private val episodeCache = ConcurrentHashMap<String, List<IptvEntry>>()

    /** Cached catalog for [sourceId], or null when nothing has been loaded yet. */
    fun cached(sourceId: String): IptvCatalogData? = cache[sourceId]?.data

    fun isLoading(sourceId: String): Boolean = inFlight.containsKey(sourceId)

    /**
     * Resolves [source], serving the cache when it is still fresh. [forceRefresh] bypasses the
     * cache for the user's explicit "Refresh" action.
     */
    fun load(
        context: Context,
        source: IptvSource,
        forceRefresh: Boolean = false,
        onResult: (Result) -> Unit
    ) {
        val cached = cache[source.id]
        val fresh = cached != null && System.currentTimeMillis() - cached.loadedAtMs < FRESHNESS_MS
        if (cached != null && fresh && !forceRefresh) {
            main.post { onResult(cached) }
            return
        }
        if (forceRefresh) cache.remove(source.id)

        synchronized(inFlight) {
            val waiting = inFlight[source.id]
            if (waiting != null) {
                waiting += onResult
                return
            }
            inFlight[source.id] = mutableListOf(onResult)
        }

        val applicationContext = context.applicationContext
        worker.execute {
            val result = runCatching { XtreamClient.load(source) }.fold(
                onSuccess = { data ->
                    Result.Ready(data, System.currentTimeMillis()).also { cache[source.id] = it }
                },
                onFailure = { error ->
                    Result.Failed(error.message ?: error.javaClass.simpleName)
                }
            )
            // Touching the store keeps the source alive for the callbacks even if it was removed.
            if (result is Result.Failed && IptvSourceStore.find(applicationContext, source.id) == null) {
                cache.remove(source.id)
            }
            val callbacks = synchronized(inFlight) { inFlight.remove(source.id) }.orEmpty()
            main.post { callbacks.forEach { it(result) } }
        }
    }

    /** Series episodes for [entry], cached per series so reopening a show is instant. */
    fun loadEpisodes(source: IptvSource, entry: IptvEntry, onResult: (List<IptvEntry>) -> Unit) {
        val key = "${source.id}:${entry.seriesId}"
        episodeCache[key]?.let { cached ->
            main.post { onResult(cached) }
            return
        }
        worker.execute {
            val episodes = runCatching { XtreamClient.seriesEpisodes(source, entry.seriesId) }
                .getOrDefault(emptyList())
            if (episodes.isNotEmpty()) episodeCache[key] = episodes
            main.post { onResult(episodes) }
        }
    }

    /** Drops cached data for a source after it is edited or deleted. */
    fun invalidate(sourceId: String) {
        cache.remove(sourceId)
        episodeCache.keys.filter { it.startsWith("$sourceId:") }.forEach { episodeCache.remove(it) }
    }

    /**
     * Drops every in-memory catalog and episode list for a "clear cache" action. A load already in
     * flight is left alone: it holds its own callback list and will repopulate the cache when it
     * finishes, which is the same as any fresh load after clearing.
     */
    fun clearAll() {
        cache.clear()
        episodeCache.clear()
    }
}
