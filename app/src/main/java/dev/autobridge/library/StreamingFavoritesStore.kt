package dev.autobridge.library

import android.content.Context
import androidx.core.content.edit
import dev.autobridge.entertainment.ContentAddress
import org.json.JSONArray
import org.json.JSONObject

/** A site the driver pinned to the top of the Streaming list. */
data class StreamingFavorite(val title: String, val url: String)

/**
 * The driver's own favourite streaming sites, on this phone only, in the order they were added.
 * Kept apart from the car browser's bookmarks, which carry their own defaults (Google, Maps) that
 * do not belong in a list of video sites. Addresses are checked through [ContentAddress.https].
 */
object StreamingFavoritesStore {
    private const val PREFS = "autobridge_streaming_favorites"
    private const val KEY_ITEMS = "items"

    fun list(context: Context): List<StreamingFavorite> {
        val raw = prefs(context).getString(KEY_ITEMS, null) ?: return emptyList()
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            val url = item.optString("url").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            StreamingFavorite(item.optString("title").ifBlank { url }, url)
        }
    }

    /** Adds (or renames) a favourite; false when [rawUrl] is not a web address. */
    fun add(context: Context, title: String, rawUrl: String): Boolean {
        val url = ContentAddress.https(rawUrl) ?: return false
        val kept = list(context).filterNot { it.url == url }
        write(context, kept + StreamingFavorite(title.trim().ifEmpty { url }, url))
        return true
    }

    fun remove(context: Context, url: String) = write(context, list(context).filterNot { it.url == url })

    fun contains(context: Context, url: String): Boolean = list(context).any { it.url == url }

    private fun write(context: Context, items: List<StreamingFavorite>) {
        val array = JSONArray()
        items.forEach { array.put(JSONObject().put("title", it.title).put("url", it.url)) }
        prefs(context).edit { putString(KEY_ITEMS, array.toString()) }
    }

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
