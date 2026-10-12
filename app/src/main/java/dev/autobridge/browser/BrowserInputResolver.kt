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
 * The rules, in order ([resolve]):
 *  1. An address — one word with a scheme, a dot in its host, or `localhost` — opens, validated by
 *     [ContentAddress.https] (which rejects `javascript:`, `intent:` and the like). A typed
 *     `http://` is upgraded to https, the only scheme the browsers load.
 *  2. Anything else is a search on [engine], including a single word: `weather` searches, it does
 *     not open `https://weather`.
 *
 * Every text box resolves through here — the phone and car address bars, Send to car, the Remote
 * and Agent commands, Quick Launch web shortcuts — so the same words give the same page wherever
 * they are typed or spoken. Several of them used to keep their own copy, which opened a single
 * word as a dead host, searched a typed `http://` address, and ignored the chosen engine.
 */
object BrowserInputResolver {
    /** The one decision: an address opens, anything else searches on [engine]. Blank is an empty search. */
    fun resolve(input: String, engine: SearchEngine): String {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return engine.searchUrl("")
        return addressOf(trimmed) ?: engine.searchUrl(trimmed)
    }

    /** Same as [resolve]; kept for the phone, Send-to-car and projection call sites. */
    fun resolveBrowserInput(input: String, engine: SearchEngine): String = resolve(input, engine)

    /** True when [input] is an address [resolve] would open rather than search. */
    fun looksLikeUrl(input: String): Boolean = addressOf(input) != null

    /** Same as [resolve]; the car keyboard's Go. */
    fun resolveTyped(input: String, engine: SearchEngine): String = resolve(input, engine)

    /** The https URL [input] names, or null when it reads as a search. */
    fun addressOf(input: String): String? {
        val trimmed = input.trim()
        if (trimmed.isEmpty() || trimmed.any { it.isWhitespace() }) return null
        val upgraded = if (trimmed.startsWith("http://", ignoreCase = true)) "https://" + trimmed.substring(7) else trimmed
        val url = ContentAddress.https(upgraded) ?: return null
        if (upgraded.contains("://")) return url
        val host = runCatching { java.net.URI(url).host }.getOrNull().orEmpty()
        return url.takeIf { host.contains('.') || host.equals("localhost", ignoreCase = true) }
    }
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
