package dev.autobridge.remote

import android.content.Context
import androidx.core.content.edit
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
            label = labelFor(command, result),
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

    private fun labelFor(command: AutoBridgeCommand, result: CommandResult): String {
        val base = when (command.type) {
            CommandType.OPEN_URL -> "เปิด ${shortUrl(command.payload)}"
            CommandType.SEARCH_WEB -> "ค้นหา ${command.payload.orEmpty()}"
            CommandType.SEND_TEXT_TO_SCREEN -> "ส่งข้อความ: ${command.payload.orEmpty()}"
            CommandType.RELOAD -> "Reload"
            CommandType.GO_BACK -> "กลับหน้าก่อน"
            CommandType.GO_FORWARD -> "ไปหน้าถัดไป"
            CommandType.ENTER_FULLSCREEN -> "Fullscreen"
            CommandType.EXIT_FULLSCREEN -> "Exit fullscreen"
            CommandType.ENABLE_DESKTOP_MODE -> "Desktop mode on"
            CommandType.DISABLE_DESKTOP_MODE -> "Desktop mode off"
            CommandType.OPEN_BROWSER -> "เปิด Browser"
            CommandType.OPEN_MIRROR -> "เปิด Mirror"
            CommandType.START_MIRROR -> "Start Mirror"
            CommandType.STOP_MIRROR -> "Stop Mirror"
            CommandType.OPEN_MEDIA -> "เปิด Media"
            CommandType.PLAY -> "เล่นเพลง"
            CommandType.PAUSE -> "หยุดเพลง"
            CommandType.NEXT -> "เพลงถัดไป"
            CommandType.PREVIOUS -> "เพลงก่อนหน้า"
            CommandType.OPEN_AGENT -> "Agent: ${command.payload.orEmpty()}"
            CommandType.OPEN_HOME -> "หน้าหลัก"
            CommandType.OPEN_SETTINGS -> "ตั้งค่า"
            CommandType.FOCUS_INPUT -> "Focus input"
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
