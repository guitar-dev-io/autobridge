package dev.autobridge.core.state

import android.content.Context
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject

/**
 * Lightweight, persistent list of the most recent user activities across features
 * (Browser / Media / Mirror / Agent). Tapping a recent entry resumes that action by
 * routing through the existing navigation/actions (see the Recent screen + agent router);
 * this store only records "what happened", never the business logic to replay it.
 *
 * Stored as a small JSON array in SharedPreferences, capped to [MAX_ENTRIES]. Newest first.
 */
object RecentActivityStore {
    private const val PREFS_NAME = "autobridge_recent"
    private const val KEY_ENTRIES = "entries"
    private const val MAX_ENTRIES = 10

    enum class Kind { BROWSER, MEDIA, MIRROR, AGENT }

    /**
     * @param kind    which feature produced this entry
     * @param title   primary label shown to the user (e.g. "google.com", "Good Day")
     * @param subtitle optional secondary label
     * @param data    resume payload interpreted by the kind (e.g. URL for BROWSER, media uri for MEDIA)
     */
    data class Entry(
        val kind: Kind,
        val title: String,
        val subtitle: String? = null,
        val data: String? = null,
        val timestampMs: Long = System.currentTimeMillis()
    )

    fun record(context: Context, entry: Entry) {
        if (entry.title.isBlank()) return
        val current = list(context).toMutableList()
        // De-duplicate on (kind, data|title) so repeating an action just refreshes its position/time.
        val dedupeKey = "${entry.kind}:${entry.data ?: entry.title}"
        current.removeAll { "${it.kind}:${it.data ?: it.title}" == dedupeKey }
        current.add(0, entry)
        while (current.size > MAX_ENTRIES) current.removeAt(current.size - 1)
        persist(context, current)
    }

    fun list(context: Context, limit: Int = MAX_ENTRIES): List<Entry> {
        val raw = prefs(context).getString(KEY_ENTRIES, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index ->
                val obj = array.optJSONObject(index) ?: return@mapNotNull null
                val kind = runCatching { Kind.valueOf(obj.getString("kind")) }.getOrNull() ?: return@mapNotNull null
                Entry(
                    kind = kind,
                    title = obj.optString("title"),
                    subtitle = obj.optString("subtitle", "").takeIf { it.isNotBlank() },
                    data = obj.optString("data", "").takeIf { it.isNotBlank() },
                    timestampMs = obj.optLong("ts", 0L)
                )
            }
        }.getOrDefault(emptyList()).take(limit)
    }

    fun clear(context: Context) {
        prefs(context).edit { remove(KEY_ENTRIES) }
    }

    /** Human-friendly relative age, e.g. "2 min ago", "1 hour ago". */
    fun relativeAge(timestampMs: Long, now: Long = System.currentTimeMillis()): String {
        if (timestampMs <= 0L) return ""
        val deltaMs = (now - timestampMs).coerceAtLeast(0L)
        val minutes = deltaMs / 60_000L
        return when {
            minutes < 1 -> "just now"
            minutes < 60 -> "$minutes min ago"
            minutes < 60 * 24 -> "${minutes / 60} hour${if (minutes / 60 == 1L) "" else "s"} ago"
            else -> "${minutes / (60 * 24)} day${if (minutes / (60 * 24) == 1L) "" else "s"} ago"
        }
    }

    private fun persist(context: Context, entries: List<Entry>) {
        val array = JSONArray()
        entries.forEach { entry ->
            array.put(
                JSONObject().apply {
                    put("kind", entry.kind.name)
                    put("title", entry.title)
                    entry.subtitle?.let { put("subtitle", it) }
                    entry.data?.let { put("data", it) }
                    put("ts", entry.timestampMs)
                }
            )
        }
        prefs(context).edit { putString(KEY_ENTRIES, array.toString()) }
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
