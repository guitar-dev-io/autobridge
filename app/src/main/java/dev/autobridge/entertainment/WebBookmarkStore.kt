package dev.autobridge.entertainment

import android.content.Context
import androidx.core.content.edit

/** A user-editable web shortcut shown on the car web screen. */
data class WebBookmark(val title: String, val url: String)

/**
 * Persisted, user-editable web bookmarks for the car web launcher. Seeds a small default set on
 * first use so the screen is never empty, and stores entries as "title|url" pairs. URLs are
 * validated through [ContentAddress.https] before being saved.
 */
object WebBookmarkStore {
    private const val PREFS_NAME = "autobridge_web_bookmarks"
    private const val KEY_ITEMS = "items"
    private const val KEY_SEEDED = "seeded"

    private val defaults = listOf(
        WebBookmark("YouTube", "https://m.youtube.com"),
        WebBookmark("Google", "https://www.google.com"),
        WebBookmark("Google Maps", "https://maps.google.com"),
        WebBookmark("Wikipedia", "https://www.wikipedia.org")
    )

    fun list(context: Context): List<WebBookmark> {
        val prefs = prefs(context)
        if (!prefs.getBoolean(KEY_SEEDED, false)) {
            save(context, defaults)
            prefs.edit { putBoolean(KEY_SEEDED, true) }
            return defaults
        }
        return decode(prefs.getStringSet(KEY_ITEMS, emptySet()).orEmpty())
    }

    /** Whether [rawUrl] is already saved, compared the way [add] stores it. */
    fun contains(context: Context, rawUrl: String): Boolean {
        val url = ContentAddress.https(rawUrl) ?: return false
        return runCatching { list(context).any { it.url == url } }.getOrDefault(false)
    }

    fun add(context: Context, title: String, rawUrl: String): Boolean {
        val url = ContentAddress.https(rawUrl) ?: return false
        val safeTitle = title.trim().ifEmpty { url }
        val current = list(context).toMutableList()
        current.removeAll { it.url == url }
        current.add(WebBookmark(safeTitle, url))
        save(context, current)
        return true
    }

    fun remove(context: Context, url: String) {
        save(context, list(context).filterNot { it.url == url })
    }

    private fun save(context: Context, items: List<WebBookmark>) {
        prefs(context).edit {
            putStringSet(KEY_ITEMS, items.map { "${it.title}|${it.url}" }.toSet())
            putBoolean(KEY_SEEDED, true)
        }
    }

    private fun decode(raw: Set<String>): List<WebBookmark> =
        raw.mapNotNull { entry ->
            val separator = entry.indexOf('|')
            if (separator <= 0) return@mapNotNull null
            WebBookmark(entry.substring(0, separator), entry.substring(separator + 1))
        }.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title })

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
