package dev.autobridge.browser

import android.content.Context
import androidx.core.content.edit
import dev.autobridge.entertainment.ContentAddress
import java.net.URLEncoder

/**
 * Where a typed query is sent when it is not a URL.
 *
 * YouTube is the default because this browser is most often used to put media on the car screen,
 * and a bare phrase ("bodyslam live") is far more likely to be a video the user wants than a web
 * search. Google stays available for everything else.
 */
enum class SearchEngine(
    /** Short label shown on the engine chooser. */
    val label: String,
    /** The host the search runs on, for a sub-label. */
    val host: String,
) {
    YOUTUBE("YouTube", "m.youtube.com"),
    GOOGLE("Google", "www.google.com");

    /** Builds the search-results URL for [query] on this engine. */
    fun searchUrl(query: String): String {
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        return when (this) {
            YOUTUBE -> "https://m.youtube.com/results?search_query=$encoded"
            GOOGLE -> "https://www.google.com/search?q=$encoded"
        }
    }
}

/**
 * Turns whatever the user typed — a full URL, a bare hostname, or a free-text query — into a single
 * URL to open, choosing a search engine only when the input is not already an address.
 *
 * This is the one place the "is this a page or a search?" decision lives, so the main address bar,
 * the "Send to car" sheet and anything else that takes a text box all resolve input the same way.
 * It is pure and side-effect free so it can be unit-tested without an Android framework.
 *
 * The rules, in order:
 *  1. A valid `http`/`https` URL (or a bare host like `youtube.com`) resolves to that URL, through
 *     [ContentAddress.https] — the same validator the rest of the app trusts, which also upgrades a
 *     scheme-less host to `https://` and rejects unsafe schemes (`javascript:`, `intent:`, …).
 *  2. Anything else is treated as a search query and encoded onto [engine]'s results URL.
 */
object BrowserInputResolver {
    fun resolveBrowserInput(input: String, engine: SearchEngine): String {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return engine.searchUrl("")
        return ContentAddress.https(trimmed) ?: engine.searchUrl(trimmed)
    }

    /** True when [input] is (or normalises to) a real address rather than a search query. */
    fun looksLikeUrl(input: String): Boolean = ContentAddress.https(input.trim()) != null
}

/**
 * Persists the last search engine the user picked for "Send to car" and the address bar.
 *
 * Stored in the shared `autobridge_browser` preference file alongside [BrowserControlsStore] and
 * [BrowserDefaults], so the whole browser keeps reading one file. Defaults to
 * [SearchEngine.YOUTUBE] for the media-first reason above.
 */
object SearchEngineStore {
    private const val PREFS_NAME = "autobridge_browser"
    private const val KEY_ENGINE = "search_engine"

    val DEFAULT = SearchEngine.YOUTUBE

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun engine(context: Context): SearchEngine =
        runCatching {
            SearchEngine.valueOf(prefs(context).getString(KEY_ENGINE, DEFAULT.name).orEmpty())
        }.getOrDefault(DEFAULT)

    fun setEngine(context: Context, engine: SearchEngine) {
        prefs(context).edit { putString(KEY_ENGINE, engine.name) }
    }
}
