package dev.autobridge.entertainment

import android.content.Context
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject

/** A single visited page, newest entries kept first. */
data class WebHistoryEntry(val title: String, val url: String, val timestampMs: Long)

/**
 * Persisted browser history, separate from [WebBookmarkStore] (user-curated shortcuts) and from
 * `RecentActivityStore` (cross-feature, capped at 10). Every finished page load is recorded here so
 * the browser's "History" screen can show what was actually visited, mirroring the bookmarks/history
 * split a Fermata-style browser exposes.
 */
object WebHistoryStore {
    private const val PREFS_NAME = "autobridge_web_history"
    private const val KEY_ITEMS = "items"
    private const val MAX_ENTRIES = 40

    fun record(context: Context, title: String?, rawUrl: String) {
        val url = ContentAddress.https(rawUrl) ?: return
        val safeTitle = title?.trim().takeUnless { it.isNullOrEmpty() } ?: url
        val current = list(context).toMutableList()
        current.removeAll { it.url == url }
        current.add(0, WebHistoryEntry(safeTitle, url, System.currentTimeMillis()))
        while (current.size > MAX_ENTRIES) current.removeAt(current.size - 1)
        persist(context, current)
    }

    fun list(context: Context): List<WebHistoryEntry> {
        val raw = prefs(context).getString(KEY_ITEMS, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index ->
                val obj = array.optJSONObject(index) ?: return@mapNotNull null
                val url = obj.optString("url").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                WebHistoryEntry(obj.optString("title", url), url, obj.optLong("ts", 0L))
            }
        }.getOrDefault(emptyList())
    }

    fun remove(context: Context, url: String) {
        persist(context, list(context).filterNot { it.url == url })
    }

    fun clear(context: Context) {
        prefs(context).edit { remove(KEY_ITEMS) }
    }

    private fun persist(context: Context, entries: List<WebHistoryEntry>) {
        val array = JSONArray()
        entries.forEach { entry ->
            array.put(
                JSONObject().apply {
                    put("title", entry.title)
                    put("url", entry.url)
                    put("ts", entry.timestampMs)
                }
            )
        }
        prefs(context).edit { putString(KEY_ITEMS, array.toString()) }
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
