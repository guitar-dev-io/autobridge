package dev.autobridge.browser

import android.content.Context
import android.net.Uri
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject

/** One browser tab. [id] is stable for the tab's lifetime and keys its saved WebView state. */
data class BrowserTab(val id: Long, val url: String, val title: String) {
    /** Host shown in compact tab UI; falls back to the raw URL when it cannot be parsed. */
    val host: String
        get() = runCatching { Uri.parse(url).host }.getOrNull()?.removePrefix("www.").orEmpty()
            .ifBlank { url }

    val displayTitle: String get() = title.takeIf { it.isNotBlank() } ?: host
}

/**
 * Pure tab-list state: which tabs exist, which is active, and what the cap does.
 *
 * Kept free of Android types and of the WebView itself so the same rules serve the car surface and
 * the phone activity, and so the cap/eviction behaviour is directly testable. The *page* behind a
 * tab lives in one reusable WebView (see [CarWebRenderer]); switching tabs saves and restores that
 * WebView's own state rather than allocating a second one, which is what keeps memory bounded on a
 * head unit and satisfies "never recreate the WebView for a UI state change".
 */
data class BrowserTabsState(
    val tabs: List<BrowserTab> = emptyList(),
    val activeId: Long = 0L,
) {
    companion object {
        /**
         * Hard cap. Each tab costs a retained WebView state bundle and a thumbnail; a head unit has
         * far less headroom than a phone, and an unbounded strip is also unusable at arm's length.
         */
        const val MAX_TABS = 6

        fun single(url: String, title: String = "", id: Long = 1L): BrowserTabsState =
            BrowserTabsState(listOf(BrowserTab(id, url, title)), id)
    }

    val active: BrowserTab? get() = tabs.firstOrNull { it.id == activeId } ?: tabs.firstOrNull()
    val isFull: Boolean get() = tabs.size >= MAX_TABS
    val count: Int get() = tabs.size

    /**
     * Opens a tab and makes it active. At the cap the *least recently active* tab (the one furthest
     * from the end of the list) is evicted; [evicted] tells the caller which saved state to drop.
     */
    fun open(id: Long, url: String, title: String = ""): Pair<BrowserTabsState, BrowserTab?> {
        val withNew = tabs + BrowserTab(id, url, title)
        if (withNew.size <= MAX_TABS) return copy(tabs = withNew, activeId = id) to null
        val evicted = withNew.firstOrNull { it.id != id }
        return copy(tabs = withNew.filterNot { it.id == evicted?.id }, activeId = id) to evicted
    }

    /** Closes a tab. Closing the last one leaves an empty state; the caller decides what to open. */
    fun close(id: Long): BrowserTabsState {
        val index = tabs.indexOfFirst { it.id == id }
        if (index < 0) return this
        val remaining = tabs.filterNot { it.id == id }
        if (remaining.isEmpty()) return BrowserTabsState()
        val nextActive = if (id != activeId) activeId
        else remaining[index.coerceAtMost(remaining.size - 1)].id
        return BrowserTabsState(remaining, nextActive)
    }

    fun activate(id: Long): BrowserTabsState =
        if (tabs.any { it.id == id }) copy(activeId = id) else this

    /** Records a navigation on the active tab. Titles are kept when a page reports a blank one. */
    fun updateActive(url: String, title: String?): BrowserTabsState {
        val current = active ?: return this
        val nextTitle = title?.trim().takeUnless { it.isNullOrEmpty() } ?: current.title
        if (current.url == url && current.title == nextTitle) return this
        return copy(tabs = tabs.map { if (it.id == current.id) it.copy(url = url, title = nextTitle) else it })
    }
}

/**
 * Durable tab list, so "restore previous session" survives the car app being torn down and the
 * process being killed. Only URLs and titles are persisted — WebView back/forward stacks are
 * process-scoped and are intentionally not written to disk.
 */
object BrowserTabStore {
    private const val PREFS_NAME = "autobridge_browser_tabs"
    private const val KEY_TABS = "tabs"
    private const val KEY_ACTIVE = "active"

    fun save(context: Context, state: BrowserTabsState) {
        val array = JSONArray()
        state.tabs.forEach { tab ->
            array.put(
                JSONObject().apply {
                    put("id", tab.id)
                    put("url", tab.url)
                    put("title", tab.title)
                }
            )
        }
        prefs(context).edit {
            putString(KEY_TABS, array.toString())
            putLong(KEY_ACTIVE, state.activeId)
        }
    }

    fun restore(context: Context): BrowserTabsState {
        val raw = prefs(context).getString(KEY_TABS, null) ?: return BrowserTabsState()
        val tabs = runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index ->
                val obj = array.optJSONObject(index) ?: return@mapNotNull null
                val url = dev.autobridge.entertainment.ContentAddress.https(obj.optString("url"))
                    ?: return@mapNotNull null
                BrowserTab(obj.optLong("id"), url, obj.optString("title"))
            }
        }.getOrDefault(emptyList()).take(BrowserTabsState.MAX_TABS)
        if (tabs.isEmpty()) return BrowserTabsState()
        val active = prefs(context).getLong(KEY_ACTIVE, tabs.first().id)
        return BrowserTabsState(tabs, if (tabs.any { it.id == active }) active else tabs.first().id)
    }

    fun clear(context: Context) {
        prefs(context).edit { clear() }
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
