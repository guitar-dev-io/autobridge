package dev.autobridge.bridge

import android.content.Context
import androidx.core.content.edit
import org.json.JSONObject

/**
 * The two pieces of bridge state that have to outlive the process: the Send-to-Car request that
 * arrived while the car was not connected, and the session to offer back after a reconnect.
 *
 * Everything else the bridge persists already has a home and keeps it — the queue is
 * [dev.autobridge.browser.BrowserPlayQueue], favorites are
 * [dev.autobridge.entertainment.WebBookmarkStore], recents are
 * [dev.autobridge.core.state.RecentActivityStore]. This adds one more `autobridge_*`
 * SharedPreferences file rather than a database, which is what
 * [dev.autobridge.core.state.AppDataManager] already knows how to enumerate and clear.
 *
 * The codec is pure so the tolerant-decode behaviour — a corrupt value yields "nothing pending"
 * rather than an exception on the next car connect — is a unit test.
 */
object BridgeStore {
    private const val PREFS_NAME = "autobridge_bridge"
    private const val KEY_PENDING = "pending_send"
    private const val KEY_LAST_SESSION = "last_session"

    /**
     * A session worth offering to resume: what was playing, on which engine, and where.
     *
     * [durationMs] is 0 whenever the engine never reported one - a browser page often does not -
     * and the home dashboard then shows the elapsed time without a progress bar rather than
     * drawing a bar against a length it had to invent.
     */
    data class Snapshot(
        val source: BridgeSource,
        val engine: EngineKind,
        val positionMs: Long,
        val savedAtMs: Long,
        val durationMs: Long = 0L
    )

    // ------------------------------------------------------------------------------ pure codec

    fun encodeSource(source: BridgeSource): String = JSONObject()
        .put("url", source.url)
        .put("title", source.title)
        .put("positionMs", source.positionMs)
        .put("mimeType", source.mimeType ?: JSONObject.NULL)
        .put("origin", source.origin.name)
        .toString()

    /** Null for anything unreadable or without a usable URL, never an exception. */
    fun decodeSource(raw: String?): BridgeSource? {
        if (raw.isNullOrBlank()) return null
        return runCatching {
            val json = JSONObject(raw)
            val url = ShareIntake.normalize(json.optString("url")) ?: return null
            BridgeSource(
                url = url,
                title = json.optString("title"),
                positionMs = json.optLong("positionMs"),
                mimeType = json.optString("mimeType").takeIf { it.isNotBlank() && it != "null" },
                origin = runCatching { BridgeSource.Origin.valueOf(json.optString("origin")) }
                    .getOrDefault(BridgeSource.Origin.SHARE)
            )
        }.getOrNull()
    }

    fun encodeSnapshot(snapshot: Snapshot): String = JSONObject()
        .put("source", JSONObject(encodeSource(snapshot.source)))
        .put("engine", snapshot.engine.name)
        .put("positionMs", snapshot.positionMs)
        .put("durationMs", snapshot.durationMs)
        .put("savedAt", snapshot.savedAtMs)
        .toString()

    fun decodeSnapshot(raw: String?): Snapshot? {
        if (raw.isNullOrBlank()) return null
        return runCatching {
            val json = JSONObject(raw)
            val source = decodeSource(json.optJSONObject("source")?.toString()) ?: return null
            Snapshot(
                source = source,
                engine = runCatching { EngineKind.valueOf(json.optString("engine")) }
                    .getOrDefault(EngineKind.BROWSER),
                positionMs = json.optLong("positionMs"),
                savedAtMs = json.optLong("savedAt"),
                durationMs = json.optLong("durationMs")
            )
        }.getOrNull()
    }

    // ------------------------------------------------------------------------------- persisted

    /**
     * The request waiting for a car to connect, if any.
     *
     * Exactly one is held, not a list: a second share before the car connects replaces the first,
     * because "open this on the car" means the most recent one. Anything the user wanted to keep
     * belongs in the queue, which is what the queue is for.
     */
    fun pending(context: Context): BridgeSource? =
        decodeSource(prefs(context).getString(KEY_PENDING, null))

    fun setPending(context: Context, source: BridgeSource?) {
        prefs(context).edit {
            if (source == null) remove(KEY_PENDING) else putString(KEY_PENDING, encodeSource(source))
        }
    }

    fun lastSession(context: Context): Snapshot? =
        decodeSnapshot(prefs(context).getString(KEY_LAST_SESSION, null))

    fun setLastSession(context: Context, snapshot: Snapshot?) {
        prefs(context).edit {
            if (snapshot == null) remove(KEY_LAST_SESSION)
            else putString(KEY_LAST_SESSION, encodeSnapshot(snapshot))
        }
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
