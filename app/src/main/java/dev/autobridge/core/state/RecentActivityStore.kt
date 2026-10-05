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
     * Where the action came from, when that is worth telling apart.
     *
     * The home dashboard's "Recently Sent" is exactly the entries that arrived from the phone
     * ([PHONE], [SHARE]); without this they are indistinguishable from something started on the
     * head unit itself. Null for every entry recorded before this field existed and for every
     * caller that has nothing meaningful to say, which is why the dashboard filters on it rather
     * than defaulting it.
     */
    enum class Origin { CAR, PHONE, SHARE, AGENT }

    /**
     * @param kind    which feature produced this entry
     * @param title   primary label shown to the user (e.g. "google.com", "Good Day")
     * @param subtitle optional secondary label
     * @param data    resume payload interpreted by the kind (e.g. URL for BROWSER, media uri for MEDIA)
     * @param origin  where the action was asked for, or null when the caller does not distinguish
     */
    data class Entry(
        val kind: Kind,
        val title: String,
        val subtitle: String? = null,
        val data: String? = null,
        val timestampMs: Long = System.currentTimeMillis(),
        val origin: Origin? = null
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
                    timestampMs = obj.optLong("ts", 0L),
                    origin = runCatching { Origin.valueOf(obj.optString("origin")) }.getOrNull()
                )
            }
        }.getOrDefault(emptyList()).take(limit)
    }

    fun clear(context: Context) {
        prefs(context).edit { remove(KEY_ENTRIES) }
    }

    /** How old an entry is, split into the unit a UI formats. Null for a missing timestamp. */
    data class Age(val unit: Unit, val count: Long) {
        enum class Unit { NOW, MINUTES, HOURS, DAYS }
    }

    /** Pure age bucketing, so the wording can be localised without duplicating the arithmetic. */
    fun age(timestampMs: Long, now: Long = System.currentTimeMillis()): Age? {
        if (timestampMs <= 0L) return null
        val minutes = (now - timestampMs).coerceAtLeast(0L) / 60_000L
        return when {
            minutes < 1 -> Age(Age.Unit.NOW, 0L)
            minutes < 60 -> Age(Age.Unit.MINUTES, minutes)
            minutes < 60 * 24 -> Age(Age.Unit.HOURS, minutes / 60)
            else -> Age(Age.Unit.DAYS, minutes / (60 * 24))
        }
    }

    /** Human-friendly relative age, e.g. "2 min ago", "1 hour ago". */
    fun relativeAge(timestampMs: Long, now: Long = System.currentTimeMillis()): String {
        val age = age(timestampMs, now) ?: return ""
        return when (age.unit) {
            Age.Unit.NOW -> "just now"
            Age.Unit.MINUTES -> "${age.count} min ago"
            Age.Unit.HOURS -> "${age.count} hour${if (age.count == 1L) "" else "s"} ago"
            Age.Unit.DAYS -> "${age.count} day${if (age.count == 1L) "" else "s"} ago"
        }
    }

    /**
     * The same age in the user's language. The car dashboard uses this one; the string-less
     * [relativeAge] stays for callers with no Context and for the tests that pin the arithmetic.
     */
    fun relativeAge(context: Context, timestampMs: Long, now: Long = System.currentTimeMillis()): String {
        val age = age(timestampMs, now) ?: return ""
        return when (age.unit) {
            Age.Unit.NOW -> context.getString(dev.autobridge.R.string.car_time_just_now)
            Age.Unit.MINUTES -> context.getString(dev.autobridge.R.string.car_time_minutes_ago, age.count)
            Age.Unit.HOURS -> context.getString(dev.autobridge.R.string.car_time_hours_ago, age.count)
            Age.Unit.DAYS -> context.getString(dev.autobridge.R.string.car_time_days_ago, age.count)
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
                    entry.origin?.let { put("origin", it.name) }
                }
            )
        }
        prefs(context).edit { putString(KEY_ENTRIES, array.toString()) }
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
