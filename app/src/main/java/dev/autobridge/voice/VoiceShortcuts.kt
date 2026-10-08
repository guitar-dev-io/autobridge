package dev.autobridge.voice

import android.content.Context
import androidx.core.content.edit
import dev.autobridge.agent.AgentCommandParser
import org.json.JSONArray
import org.json.JSONObject

/**
 * A phrase the driver taught the voice commands: say [phrase], get [kind] with [value].
 *
 * The built-in rules ([VoiceCommandParser]) cover the general shapes - "เปิด YouTube", "เล่น ...
 * บน YouTube Music" - but not the words one person actually uses for one place ("เพลงประจำ",
 * "ข่าวเช้า", "ไปบ้านแม่"), and Whisper's spelling of them. A shortcut maps those words straight
 * to where they should go, ahead of every built-in rule.
 */
data class VoiceShortcut(val phrase: String, val kind: Kind, val value: String) {
    enum class Kind {
        /** [value] is a [VoiceScreen] name: opens that AutoBridge screen. */
        SCREEN,

        /** [value] is a web address: opens it (on the car when said on the car). */
        URL,

        /** [value] is another command, said in full: "เล่น Bodyslam บน YouTube Music". */
        COMMAND,
    }
}

/** Matching, kept pure so it is tested on the JVM. */
object VoiceShortcutMatcher {
    private val SPACE = Regex("\\s+")
    private val PUNCTUATION = Regex("[,，、!?！？。\"“”.]")

    /**
     * Whisper spaces Thai unpredictably and adds polite particles, so a phrase is compared with
     * spacing, punctuation and case removed, after the same particle cleanup the parser uses.
     */
    fun key(text: String): String =
        AgentCommandParser.normalizeSpeech(text.replace(PUNCTUATION, " ").trim())
            .lowercase().replace(SPACE, "")

    /**
     * The shortcut [utterance] calls for: one whose phrase is the whole utterance, else the longest
     * phrase found inside it ("ขอข่าวเช้าหน่อย" still finds "ข่าวเช้า"). Phrases under two characters
     * never match inside a sentence; a single letter is in everything.
     */
    fun match(utterance: String, shortcuts: List<VoiceShortcut>): VoiceShortcut? {
        val said = key(utterance)
        if (said.isEmpty()) return null
        val keyed = shortcuts.map { it to key(it.phrase) }.filter { it.second.isNotEmpty() }
        keyed.firstOrNull { it.second == said }?.let { return it.first }
        return keyed.filter { it.second.length >= 2 && said.contains(it.second) }
            .maxByOrNull { it.second.length }?.first
    }
}

/** The driver's shortcuts, in the order they were added. */
object VoiceShortcutStore {
    private const val PREFS = "autobridge_voice_shortcuts"
    private const val KEY = "items"

    fun all(context: Context): List<VoiceShortcut> = decode(prefs(context).getString(KEY, null))

    /** Adds [shortcut], replacing one with the same phrase. */
    fun add(context: Context, shortcut: VoiceShortcut) {
        val key = VoiceShortcutMatcher.key(shortcut.phrase)
        save(context, all(context).filter { VoiceShortcutMatcher.key(it.phrase) != key } + shortcut)
    }

    fun remove(context: Context, shortcut: VoiceShortcut) = save(context, all(context) - shortcut)

    private fun save(context: Context, items: List<VoiceShortcut>) =
        prefs(context).edit { putString(KEY, encode(items)) }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun encode(items: List<VoiceShortcut>): String = JSONArray().apply {
        items.forEach { put(JSONObject().put("phrase", it.phrase).put("kind", it.kind.name).put("value", it.value)) }
    }.toString()

    fun decode(json: String?): List<VoiceShortcut> {
        if (json.isNullOrBlank()) return emptyList()
        val array = runCatching { JSONArray(json) }.getOrNull() ?: return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            val kind = runCatching { VoiceShortcut.Kind.valueOf(item.optString("kind")) }.getOrNull() ?: return@mapNotNull null
            val phrase = item.optString("phrase").trim()
            val value = item.optString("value").trim()
            if (phrase.isEmpty() || value.isEmpty()) null else VoiceShortcut(phrase, kind, value)
        }
    }
}
