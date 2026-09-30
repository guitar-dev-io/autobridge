package dev.autobridge.weather

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors

/**
 * Process-wide cache in front of [WeatherClient], shared by the phone Activity and the Android
 * Auto screen so both surfaces show the same snapshot and a background head unit refresh does not
 * duplicate a phone-triggered one. Mirrors [dev.autobridge.iptv.IptvCatalog]'s cache/dedupe shape.
 */
object WeatherRepository {
    sealed interface Result {
        data class Ready(val snapshot: WeatherSnapshot) : Result
        data class Failed(val message: String) : Result
        /** No place has been picked yet; callers should prompt for a search instead of retrying. */
        data object NoLocation : Result
    }

    private const val FRESHNESS_MS = 15L * 60L * 1000L

    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "weather-repository").apply { isDaemon = true }
    }
    private val main = Handler(Looper.getMainLooper())

    @Volatile private var cached: WeatherSnapshot? = null
    private val waiting = mutableListOf<(Result) -> Unit>()
    private var loading = false

    /** Cached snapshot, or null when nothing has loaded yet this process. */
    fun cachedSnapshot(): WeatherSnapshot? = cached

    /**
     * Resolves the saved place's current conditions, serving the cache when still fresh.
     * [forceRefresh] bypasses the cache for an explicit "Refresh" tap.
     */
    fun load(context: Context, forceRefresh: Boolean = false, onResult: (Result) -> Unit) {
        val place = WeatherLocationStore.place(context)
        if (place == null) {
            main.post { onResult(Result.NoLocation) }
            return
        }
        val snapshot = cached
        val fresh = snapshot != null && snapshot.place == place &&
            System.currentTimeMillis() - snapshot.fetchedAtMs < FRESHNESS_MS
        if (snapshot != null && fresh && !forceRefresh) {
            main.post { onResult(Result.Ready(snapshot)) }
            return
        }

        synchronized(waiting) {
            waiting += onResult
            if (loading) return
            loading = true
        }

        worker.execute {
            val result = runCatching { WeatherClient.forecast(place) }.fold(
                onSuccess = { snap -> cached = snap; Result.Ready(snap) },
                onFailure = { error -> Result.Failed(error.message ?: error.javaClass.simpleName) }
            )
            val callbacks = synchronized(waiting) {
                loading = false
                val copy = waiting.toList()
                waiting.clear()
                copy
            }
            main.post { callbacks.forEach { it(result) } }
        }
    }

    /** Free-text place search, run off the main thread. */
    fun search(query: String, onResult: (List<WeatherPlace>) -> Unit) {
        worker.execute {
            val places = runCatching { WeatherClient.search(query) }.getOrDefault(emptyList())
            main.post { onResult(places) }
        }
    }

    /** Drops the cached snapshot after the user picks a new place. */
    fun invalidate() {
        cached = null
    }

    /**
     * Short "18°C" label for the home status chip, or null when nothing has loaded yet (no place
     * set, or the first fetch has not landed). Only ever reads the cache — call [refreshIfStale]
     * to keep it warm.
     */
    fun label(): String? {
        val snapshot = cached ?: return null
        return "${Math.round(snapshot.temperatureC)}°C"
    }

    /**
     * Fires [onLoaded] once, only when a background fetch actually lands new data. A caller with a
     * fresh cache (or no saved place) gets no callback, so a chip that calls this on every redraw
     * cannot loop back into itself.
     */
    fun refreshIfStale(context: Context, onLoaded: () -> Unit) {
        val place = WeatherLocationStore.place(context) ?: return
        val snapshot = cached
        val fresh = snapshot != null && snapshot.place == place &&
            System.currentTimeMillis() - snapshot.fetchedAtMs < FRESHNESS_MS
        if (fresh) return
        load(context, forceRefresh = false) { result -> if (result is Result.Ready) onLoaded() }
    }
}
