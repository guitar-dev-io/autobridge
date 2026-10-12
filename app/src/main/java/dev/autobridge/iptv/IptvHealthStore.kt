package dev.autobridge.iptv

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Which channels stopped working, remembered across restarts.
 *
 * [StreamPing]'s answers live for five minutes in memory, so a channel found dead yesterday looked
 * fine again this morning until it was checked or tapped. Every probe and every playback attempt
 * now leaves a record here, and a row whose last attempt failed says so at once ("Not working ·
 * 3 h ago") — before any new check has run, and on the head unit as well as the phone.
 *
 * A record clears itself the moment the channel answers or plays again, and is forgotten after
 * [MEMORY_MS], because a channel retired for a week has usually been fixed or removed upstream.
 *
 * Stream addresses are stored only as SHA-256 digests: an Xtream address embeds the account's
 * username and password, and [IptvSourceStore]'s policy is that credentials never reach the disk
 * in the clear.
 */
object IptvHealthStore {
    /** What the store knows about one address. [reason] is the last failure, already readable. */
    data class Record(val lastFailMs: Long, val lastOkMs: Long, val failures: Int, val reason: String) {
        /** The last attempt failed. */
        val isBroken: Boolean get() = lastFailMs > lastOkMs

        fun encode(): String = "$lastFailMs|$lastOkMs|$failures|${reason.replace('|', ' ')}"

        companion object {
            fun decode(raw: String?): Record? {
                val parts = raw?.split('|', limit = 4) ?: return null
                if (parts.size != 4) return null
                return Record(
                    parts[0].toLongOrNull() ?: return null,
                    parts[1].toLongOrNull() ?: return null,
                    parts[2].toIntOrNull() ?: return null,
                    parts[3]
                )
            }
        }
    }

    private const val PREFS_NAME = "autobridge_iptv_health"
    private const val MEMORY_MS = 7L * 24 * 60 * 60 * 1000
    private const val MAX_RECORDS = 3_000

    @Volatile private var appContext: Context? = null
    private val digests = ConcurrentHashMap<String, String>()

    /**
     * Starts recording every [StreamPing] answer. Called once from the Application, so a check run
     * from the car records as surely as one run from the phone.
     */
    fun install(context: Context) {
        appContext = context.applicationContext
        StreamPing.observer = { url, result ->
            when (result) {
                is StreamPing.Result.Alive -> recordSuccess(url)
                is StreamPing.Result.Refused -> recordFailure(url, "HTTP ${result.status}")
                is StreamPing.Result.Unreachable -> recordFailure(url, result.reason)
                // Nothing was asked, so nothing was learned.
                StreamPing.Result.Unsupported -> Unit
            }
        }
    }

    /** The record for [url] while its last attempt failed and is recent enough to trust. */
    fun broken(url: String, nowMs: Long = System.currentTimeMillis()): Record? {
        val record = record(url) ?: return null
        return record.takeIf { it.isBroken && nowMs - it.lastFailMs < MEMORY_MS }
    }

    fun recordFailure(url: String, reason: String) = update(url) { old, now ->
        Record(now, old?.lastOkMs ?: 0L, (old?.takeIf { it.isBroken }?.failures ?: 0) + 1, reason)
    }

    fun recordSuccess(url: String) = update(url) { old, now ->
        // Writing only when something changes keeps a page of working channels from touching disk.
        if (old != null && !old.isBroken && now - old.lastOkMs < 60 * 60 * 1000L) null
        else Record(old?.lastFailMs ?: 0L, now, 0, "")
    }

    /**
     * The entries among [entries] whose last attempt failed, in their own order — the "Not
     * working" group. A catalog can hold tens of thousands of channels, so this hashes them only
     * when something is actually broken, and remembers its answer until the broken set changes.
     */
    fun brokenAmong(entries: List<IptvEntry>): List<IptvEntry> {
        val broken = brokenDigests()
        if (broken.isEmpty()) return emptyList()
        lastBrokenAmong?.let { (list, set, result) -> if (list === entries && set == broken) return result }
        val result = entries.filter { it.url.isNotBlank() && sha(it.url.trim()) in broken }
        lastBrokenAmong = Triple(entries, broken, result)
        return result
    }

    @Volatile private var lastBrokenAmong: Triple<List<IptvEntry>, Set<String>, List<IptvEntry>>? = null

    /** The digests of every address whose last attempt failed recently. */
    private fun brokenDigests(): Set<String> {
        val prefs = prefs() ?: return emptySet()
        val now = System.currentTimeMillis()
        return prefs.all.mapNotNullTo(HashSet()) { (key, value) ->
            key.takeIf { Record.decode(value as? String)?.let { it.isBroken && now - it.lastFailMs < MEMORY_MS } == true }
        }
    }

    private fun record(url: String): Record? {
        if (url.isBlank()) return null
        return Record.decode(prefs()?.getString(digest(url), null))
    }

    private inline fun update(url: String, change: (Record?, Long) -> Record?) {
        if (url.isBlank()) return
        val prefs = prefs() ?: return
        val key = digest(url)
        val next = change(Record.decode(prefs.getString(key, null)), System.currentTimeMillis()) ?: return
        prefs.edit { putString(key, next.encode()) }
        if (prefs.all.size > MAX_RECORDS) prune(prefs)
    }

    /** Drops expired records, then the oldest, until the store is back under its cap. */
    private fun prune(prefs: SharedPreferences) {
        val now = System.currentTimeMillis()
        val records = prefs.all.mapNotNull { (key, value) -> Record.decode(value as? String)?.let { key to it } }
        val keep = records
            .filter { (_, record) -> now - maxOf(record.lastFailMs, record.lastOkMs) < MEMORY_MS }
            .sortedByDescending { (_, record) -> maxOf(record.lastFailMs, record.lastOkMs) }
            .take(MAX_RECORDS * 3 / 4)
            .map { it.first }
            .toSet()
        prefs.edit { prefs.all.keys.filter { it !in keep }.forEach(::remove) }
    }

    private fun digest(url: String): String {
        // Rows look addresses up on every repaint; a catalog-sized sweep is not worth remembering.
        if (digests.size > 5_000) digests.clear()
        return digests.getOrPut(url.trim()) { sha(url.trim()) }
    }

    private fun sha(url: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(url.toByteArray())
        val out = CharArray(32)
        for (index in 0 until 16) {
            val value = bytes[index].toInt() and 0xff
            out[index * 2] = HEX[value ushr 4]
            out[index * 2 + 1] = HEX[value and 0x0f]
        }
        return String(out)
    }

    private val HEX = "0123456789abcdef".toCharArray()

    private fun prefs(): SharedPreferences? =
        appContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
