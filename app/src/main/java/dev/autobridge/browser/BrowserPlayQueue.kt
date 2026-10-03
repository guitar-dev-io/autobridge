package dev.autobridge.browser

import android.content.Context
import androidx.core.content.edit
import dev.autobridge.entertainment.ContentAddress
import org.json.JSONArray
import org.json.JSONObject

/**
 * A play-next queue of web pages, owned by this app rather than by the site.
 *
 * ## Why this exists
 *
 * A site's own queue is unreachable from outside it. [dev.autobridge.media.WebMediaPlayer] says so
 * where it explains why it offers no next/previous: "a page's own media-session handlers cannot be
 * invoked from outside it, and a guessed next button would be worse than none". So YouTube's Up next
 * is YouTube's business, it only ever holds YouTube, and nothing can queue a video behind a track on
 * another site.
 *
 * This is the queue that can: a plain ordered list of URLs this app navigates to itself. Because the
 * list is ours, "what plays next" stops being a guess — it is whatever the driver put there, from
 * any site.
 *
 * ## Shape
 *
 * Deliberately a list of addresses and nothing more. It holds no playback state, no positions and no
 * per-site knowledge: an entry is consumed by being loaded ([takeNext]), and from that moment the
 * page owns playback exactly as it does when the driver opens it by hand. Nothing here has to know
 * what a YouTube URL means.
 *
 * Stored as JSON in the shared `autobridge_browser` preference file, alongside
 * [BrowserDefaults] and [SearchEngineStore], so the browser keeps reading one file. The codec and
 * the list rules are pure functions so a plain JVM test covers them.
 */
object BrowserPlayQueue {
    private const val PREFS_NAME = "autobridge_browser"
    private const val KEY_ITEMS = "play_queue"

    /** A queued page. [title] is for display only and may be blank. */
    data class Item(val url: String, val title: String = "", val addedAtMs: Long = 0L)

    // ------------------------------------------------------------------------------- pure rules

    /**
     * [items] with [item] appended, or null when that URL is already queued.
     *
     * A duplicate is refused rather than moved or added twice: the driver who taps "add to queue"
     * again on a page they already queued made a mistake, and silently reordering their queue is a
     * worse answer than telling them it is already in there.
     */
    fun appended(items: List<Item>, item: Item): List<Item>? {
        if (items.any { it.url == item.url }) return null
        return items + item
    }

    fun encode(items: List<Item>): String {
        val array = JSONArray()
        items.forEach { item ->
            array.put(
                JSONObject()
                    .put("url", item.url)
                    .put("title", item.title)
                    .put("addedAt", item.addedAtMs)
            )
        }
        return array.toString()
    }

    /**
     * Parses what [encode] wrote. Anything unreadable yields an empty queue rather than throwing:
     * a corrupt preference must not make the browser unusable, and the cost is one lost queue.
     * Entries without a valid HTTPS URL are dropped, so nothing in the queue can navigate anywhere
     * [ContentAddress] would refuse.
     */
    fun decode(raw: String?): List<Item> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index ->
                val obj = array.optJSONObject(index) ?: return@mapNotNull null
                val url = ContentAddress.https(obj.optString("url")) ?: return@mapNotNull null
                Item(url, obj.optString("title"), obj.optLong("addedAt"))
            }
        }.getOrDefault(emptyList())
    }

    // -------------------------------------------------------------------------------- persisted

    fun items(context: Context): List<Item> = decode(prefs(context).getString(KEY_ITEMS, null))

    fun size(context: Context): Int = items(context).size

    /**
     * Queues [url], which may be any address or search result the resolver produced.
     *
     * @return the queue's new length, or null when the URL is unusable or already queued, so the
     *   caller can say which of the two happened by checking the URL itself.
     */
    fun add(context: Context, url: String, title: String = ""): Int? {
        val safe = ContentAddress.https(url) ?: return null
        val next = appended(
            items(context),
            Item(safe, title.trim(), System.currentTimeMillis())
        ) ?: return null
        save(context, next)
        return next.size
    }

    /** Removes and returns the head of the queue, or null when it is empty. */
    fun takeNext(context: Context): Item? {
        val items = items(context)
        val head = items.firstOrNull() ?: return null
        save(context, items.drop(1))
        return head
    }

    fun remove(context: Context, url: String) {
        save(context, items(context).filterNot { it.url == url })
    }

    fun clear(context: Context) = save(context, emptyList())

    private fun save(context: Context, items: List<Item>) {
        prefs(context).edit { putString(KEY_ITEMS, encode(items)) }
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
