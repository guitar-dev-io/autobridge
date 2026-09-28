package dev.autobridge.remote

import android.net.Uri
import dev.autobridge.entertainment.ContentAddress

/**
 * Turns free text (structured or natural language, Thai + English) into an [AutoBridgeCommand].
 *
 * Flow (spec §5):
 *   text → parse → if recognized: a concrete command → execute
 *                → if NOT recognized: [CommandType.OPEN_AGENT] carrying the original text so the
 *                  Agent can take over. Nothing is dropped.
 *
 * This intentionally reuses the same keyword philosophy as
 * [dev.autobridge.agent.AgentCommandRouter.parse]; it is a superset that also covers media and
 * navigation commands the Mobile Remote exposes.
 */
object CommandParser {

    fun parse(input: String, source: CommandSource = CommandSource.MOBILE): AutoBridgeCommand? {
        val text = input.trim()
        if (text.isEmpty()) return null
        val lower = text.lowercase()

        fun any(vararg keys: String) = keys.any { lower.contains(it) }
        fun cmd(type: CommandType, payload: String? = null) =
            AutoBridgeCommand(type = type, payload = payload, source = source)

        return when {
            // --- Display toggles (check "off/exit" variants before the "on" variants) ---
            any("desktop", "เดสก์ท็อป") && any(" off", "ปิด", "disable") ->
                cmd(CommandType.DISABLE_DESKTOP_MODE)
            any("desktop", "เดสก์ท็อป") ->
                cmd(CommandType.ENABLE_DESKTOP_MODE)
            any("exit fullscreen", "ออกเต็มจอ", "ปิดเต็มจอ", "ออกจากเต็มหน้าจอ") ->
                cmd(CommandType.EXIT_FULLSCREEN)
            any("fullscreen", "เต็มหน้าจอ", "เต็มจอ") ->
                cmd(CommandType.ENTER_FULLSCREEN)

            // --- Mirror ---
            any("stop mirror", "หยุดมิเรอร์", "ปิดมิเรอร์", "หยุดสะท้อน") ->
                cmd(CommandType.STOP_MIRROR)
            any("start mirror", "เริ่มมิเรอร์", "เริ่มสะท้อน") ->
                cmd(CommandType.START_MIRROR)
            any("mirror", "มิเรอร์", "สะท้อนหน้าจอ", "สะท้อน") ->
                cmd(CommandType.OPEN_MIRROR)

            // --- Media transport ---
            any("pause", "หยุดเพลง", "หยุดชั่วคราว", "พัก") ->
                cmd(CommandType.PAUSE)
            any("next", "เพลงถัดไป", "ถัดไป", "ข้าม") ->
                cmd(CommandType.NEXT)
            any("previous", "prev", "เพลงก่อน", "ย้อนเพลง", "ก่อนหน้า") ->
                cmd(CommandType.PREVIOUS)
            any("resume", "play", "เล่นเพลงต่อ", "เล่นต่อ", "เล่นเพลง", "เปิดเพลง") ->
                cmd(CommandType.PLAY)
            any("media", "music", "เพลง", "มีเดีย", "video", "วิดีโอ") ->
                cmd(CommandType.OPEN_MEDIA)

            // --- Navigation ---
            any("go back", "back", "กลับ", "ย้อนกลับ", "หน้าก่อน") ->
                cmd(CommandType.GO_BACK)
            any("forward", "ถัดไปหน้า", "ไปหน้า") ->
                cmd(CommandType.GO_FORWARD)
            any("reload", "refresh", "รีเฟรช", "โหลดใหม่") ->
                cmd(CommandType.RELOAD)
            any("home", "หน้าหลัก", "หน้าแรก") ->
                cmd(CommandType.OPEN_HOME)
            any("setting", "ตั้งค่า") ->
                cmd(CommandType.OPEN_SETTINGS)
            any("agent", "ผู้ช่วย") ->
                cmd(CommandType.OPEN_AGENT)

            // --- Web search (explicit) ---
            any("search", "ค้นหา", "หา ") -> {
                val query = stripLeadWords(text)
                if (query.isBlank()) cmd(CommandType.OPEN_BROWSER)
                else cmd(CommandType.SEARCH_WEB, query)
            }

            // --- Open browser / URL ("เปิด google.com", "open youtube", "เปิดเว็บ") ---
            any("open ", "เปิด", "go to", "ไปที่", "browser", "เบราว์เซอร์", "เว็บ") -> {
                val target = stripLeadWords(text)
                when {
                    target.isBlank() -> cmd(CommandType.OPEN_BROWSER)
                    isUrlLike(target) -> cmd(CommandType.OPEN_URL, normalizeUrl(target))
                    else -> cmd(CommandType.OPEN_URL, normalizeUrl(target))
                }
            }

            // A bare domain typed on its own → open it.
            isUrlLike(text) -> cmd(CommandType.OPEN_URL, normalizeUrl(text))

            // Unknown → hand the raw text to the Agent (spec §5, §12).
            else -> cmd(CommandType.OPEN_AGENT, text)
        }
    }

    private fun stripLeadWords(text: String): String = text
        .replace(Regex("(?i)\\b(open|go to|launch|search for|search)\\b"), " ")
        .replace(Regex("(เปิด|ไปที่|เว็บไซต์|เว็บ|เบราว์เซอร์|ค้นหา|หา)"), " ")
        .trim()

    private fun isUrlLike(text: String): Boolean =
        text.contains(".") && !text.contains(" ") ||
            text.startsWith("http", ignoreCase = true)

    /** Bare domain → https; otherwise a Google search URL. Reuses [ContentAddress] validation. */
    fun normalizeUrl(target: String): String {
        val t = target.trim()
        val https = ContentAddress.https(t)
        if (https != null) return https
        return "https://www.google.com/search?q=" + Uri.encode(t)
    }
}
