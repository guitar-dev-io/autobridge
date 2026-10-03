package dev.autobridge.remote

import android.content.Context
import androidx.core.content.edit
import dev.autobridge.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persistent, reactive history of the last ~20 executed commands (spec §9, §13).
 *
 * Stored as a JSON array in SharedPreferences and mirrored into a [StateFlow] so the Mobile Remote
 * "Recent Commands" screen updates live. Each entry can be re-run (its type + payload are kept).
 */
object CommandHistoryStore {
    private const val PREFS_NAME = "autobridge_command_history"
    private const val KEY_ITEMS = "items"
    private const val LIMIT = 20

    data class Entry(
        val commandId: String,
        val type: CommandType,
        val payload: String?,
        val label: String,
        val success: Boolean,
        val timestamp: Long
    )

    private val _entries = MutableStateFlow<List<Entry>>(emptyList())
    val entries: StateFlow<List<Entry>> = _entries.asStateFlow()

    fun restore(context: Context) {
        _entries.value = load(context)
    }

    fun record(context: Context, command: AutoBridgeCommand, result: CommandResult) {
        val entry = Entry(
            commandId = command.id,
            type = command.type,
            payload = command.payload,
            label = labelFor(context, command, result),
            success = result.isSuccess,
            timestamp = System.currentTimeMillis()
        )
        val updated = (listOf(entry) + _entries.value).take(LIMIT)
        _entries.value = updated
        persist(context, updated)
    }

    fun clear(context: Context) {
        _entries.value = emptyList()
        prefs(context).edit { remove(KEY_ITEMS) }
    }

    private fun labelFor(context: Context, command: AutoBridgeCommand, result: CommandResult): String {
        val res = context.resources
        val base = when (command.type) {
            CommandType.OPEN_URL -> res.getString(R.string.history_open_url, shortUrl(command.payload))
            CommandType.SEARCH_WEB -> res.getString(R.string.history_search_web, command.payload.orEmpty())
            CommandType.SEND_TEXT_TO_SCREEN -> res.getString(R.string.history_send_text, command.payload.orEmpty())
            CommandType.RELOAD -> res.getString(R.string.history_reload)
            CommandType.GO_BACK -> res.getString(R.string.history_go_back)
            CommandType.GO_FORWARD -> res.getString(R.string.history_go_forward)
            CommandType.ENTER_FULLSCREEN -> res.getString(R.string.history_enter_fullscreen)
            CommandType.EXIT_FULLSCREEN -> res.getString(R.string.history_exit_fullscreen)
            CommandType.ENABLE_DESKTOP_MODE -> res.getString(R.string.history_enable_desktop)
            CommandType.DISABLE_DESKTOP_MODE -> res.getString(R.string.history_disable_desktop)
            CommandType.OPEN_BROWSER -> res.getString(R.string.history_open_browser)
            CommandType.OPEN_MIRROR -> res.getString(R.string.history_open_mirror)
            CommandType.START_MIRROR -> res.getString(R.string.history_start_mirror)
            CommandType.STOP_MIRROR -> res.getString(R.string.history_stop_mirror)
            CommandType.OPEN_MEDIA -> res.getString(R.string.history_open_media)
            CommandType.PLAY_VIDEO -> res.getString(
                R.string.history_play_video,
                command.extras["title"] ?: shortUrl(command.payload)
            )
            CommandType.PLAY -> res.getString(R.string.history_play)
            CommandType.PAUSE -> res.getString(R.string.history_pause)
            CommandType.NEXT -> res.getString(R.string.history_next)
            CommandType.PREVIOUS -> res.getString(R.string.history_previous)
            CommandType.OPEN_AGENT -> res.getString(R.string.history_open_agent, command.payload.orEmpty())
            CommandType.OPEN_HOME -> res.getString(R.string.history_open_home)
            CommandType.OPEN_SETTINGS -> res.getString(R.string.history_open_settings)
            CommandType.FOCUS_INPUT -> res.getString(R.string.history_focus_input)
        }
        return base.trim()
    }

    private fun shortUrl(url: String?): String {
        val raw = url.orEmpty()
        return runCatching { android.net.Uri.parse(raw).host ?: raw }.getOrDefault(raw)
    }

    private fun load(context: Context): List<Entry> {
        val raw = prefs(context).getString(KEY_ITEMS, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index ->
                val obj = array.optJSONObject(index) ?: return@mapNotNull null
                val type = runCatching { CommandType.valueOf(obj.getString("type")) }.getOrNull()
                    ?: return@mapNotNull null
                Entry(
                    commandId = obj.optString("id"),
                    type = type,
                    payload = obj.optString("payload").takeIf { it.isNotBlank() },
                    label = obj.optString("label"),
                    success = obj.optBoolean("success", true),
                    timestamp = obj.optLong("ts", System.currentTimeMillis())
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun persist(context: Context, items: List<Entry>) {
        val array = JSONArray()
        items.forEach { entry ->
            array.put(
                JSONObject().apply {
                    put("id", entry.commandId)
                    put("type", entry.type.name)
                    put("payload", entry.payload.orEmpty())
                    put("label", entry.label)
                    put("success", entry.success)
                    put("ts", entry.timestamp)
                }
            )
        }
        prefs(context).edit { putString(KEY_ITEMS, array.toString()) }
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
