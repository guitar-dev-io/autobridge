package dev.autobridge.agent

import dev.autobridge.agent.AgentCommandRouter.AgentAction
import dev.autobridge.agent.AgentCommandRouter.Command
import dev.autobridge.entertainment.ContentAddress

/**
 * Turns a spoken or typed phrase (Thai + English) into an [AgentCommandRouter.Command].
 *
 * Pure Kotlin on purpose: speech recognizers produce messy text (polite particles, "ดอทคอม",
 * mixed scripts), and the only way to keep this matcher honest is a JVM test table
 * (AgentCommandParserTest). Nothing here touches Android.
 *
 * Matching rules worth knowing:
 * - Thai "เปิด" (open) contains "ปิด" (close) as a substring, so "off" is detected with a
 *   look-behind ([OFF]) rather than a plain contains. A plain contains turned "เปิดเต็มจอ" into
 *   exit-fullscreen and "เปิดโหมดเดสก์ท็อป" into desktop-off.
 * - Specific intents (desktop, fullscreen, mirror, resume, recent, YouTube, search, a spoken
 *   domain) are checked before the generic "media" and "open" words, so "เล่นเพลงต่อ" resumes
 *   instead of opening Media and "ค้นหาเพลง ..." searches instead of opening Media.
 * - Anything unrecognised becomes a Google search, so a request never dead-ends.
 */
object AgentCommandParser {

    private val DESKTOP = listOf("desktop", "เดสก์ท็อป", "เดสท็อป", "เดสก์ทอป", "เดสทอป")
    private val MOBILE_MODE = listOf("mobile mode", "โหมดมือถือ", "โหมดโทรศัพท์")
    private val FULLSCREEN = listOf("fullscreen", "full screen", "เต็มหน้าจอ", "เต็มจอ")
    private val EXIT = listOf("exit", "leave", "ออก")
    private val MIRROR = listOf("mirror", "มิเรอร์", "มิร์เรอร์", "มิเรอ", "มิลเรอ", "สะท้อนจอ", "สะท้อน", "แชร์หน้าจอ", "แชร์จอ")
    private val RESUME = listOf("resume", "continue playing", "เล่นต่อ", "เพลงต่อ", "ฟังต่อ", "ดูต่อ")
    private val RECENT = listOf("recent", "history", "ล่าสุด", "ประวัติ")
    private val YOUTUBE = listOf("youtube", "you tube", "ยูทูป", "ยูทูบ", "ยูทู้ป")
    private val SEARCH = listOf("search for", "search", "look up", "find", "ค้นหา", "หา")
    private val MEDIA = listOf("media", "music", "video", "เพลง", "วิดีโอ", "วีดีโอ", "มีเดีย")
    private val OPEN = listOf("open", "go to", "launch", "browser", "เปิด", "ไปที่", "เบราว์เซอร์", "บราวเซอร์", "เว็บ")

    /** "ปิด" not preceded by "เ" (so not "เปิด"), or an English off/close/disable word. */
    private val OFF = Regex("(?<!เ)ปิด|\\b(off|turn off|disable|close)\\b")

    /** Words peeled off the front when isolating the thing to open or search for. Longest first. */
    private val LEAD = listOf(
        "search for", "look up", "go to", "please", "open", "launch", "search", "find", "browser",
        "website", "youtube", "you tube",
        "เว็บไซต์", "เว็บ", "เบราว์เซอร์", "บราวเซอร์", "ไปที่", "เปิด", "ค้นหา", "ขอดู", "ขอฟัง", "ขอเปิด",
        "ยูทูป", "ยูทูบ", "ยูทู้ป"
    )

    /** Whole suffixes peeled off the end ("... ในยูทูป", "... on youtube"). Longest first. */
    private val TRAIL = listOf(
        "on youtube", "in youtube", "youtube", "you tube",
        "ในยูทูป", "ในยูทูบ", "ใน ยูทูป", "ใน ยูทูบ", "ยูทูป", "ยูทูบ", "ยูทู้ป"
    )

    private val DOMAIN = Regex("(?i)\\b(https?://)?([a-z0-9-]+\\.)+[a-z]{2,}(/\\S*)?")

    fun parse(input: String): Command? {
        val text = normalizeSpeech(input)
        if (text.isEmpty()) return null
        val lower = text.lowercase()

        fun has(words: List<String>) = words.any { lower.contains(it) }
        val off = OFF.containsMatchIn(lower)

        return when {
            // "เติม 40 ลิตร 1,400 บาท": an amount with its unit and a price, nothing less.
            dev.autobridge.fuel.FuelVoiceParser.parse(text) != null -> Command(AgentAction.LOG_FUEL, text)
            has(MOBILE_MODE) -> Command(AgentAction.DISABLE_DESKTOP)
            has(DESKTOP) ->
                Command(if (off) AgentAction.DISABLE_DESKTOP else AgentAction.ENABLE_DESKTOP)
            has(FULLSCREEN) ->
                Command(if (off || has(EXIT)) AgentAction.EXIT_FULLSCREEN else AgentAction.ENTER_FULLSCREEN)
            has(MIRROR) -> Command(AgentAction.OPEN_MIRROR)
            has(RESUME) -> Command(AgentAction.RESUME_MEDIA)
            has(RECENT) -> Command(AgentAction.OPEN_RECENT)
            DOMAIN.containsMatchIn(text) ->
                Command(AgentAction.OPEN_URL, toHttps(DOMAIN.find(text)!!.value))
            has(YOUTUBE) -> {
                val query = stripFiller(text)
                Command(
                    AgentAction.OPEN_URL,
                    if (query.isEmpty()) YOUTUBE_HOME else ContentAddress.youtubeSearch(query)
                )
            }
            has(SEARCH) -> {
                val query = stripFiller(text)
                if (query.isEmpty()) Command(AgentAction.OPEN_BROWSER)
                else Command(AgentAction.OPEN_URL, ContentAddress.webSearch(query))
            }
            has(MEDIA) -> Command(AgentAction.OPEN_MEDIA)
            has(OPEN) -> {
                val target = stripFiller(text)
                if (target.isEmpty()) Command(AgentAction.OPEN_BROWSER)
                else Command(AgentAction.OPEN_URL, ContentAddress.webSearch(target))
            }
            else -> Command(AgentAction.OPEN_URL, ContentAddress.webSearch(text))
        }
    }

    /**
     * Cleans recognizer output: collapses whitespace, turns spoken "dot"/"ดอท" into ".", and drops
     * trailing Thai polite particles ("ครับ", "ค่ะ", "หน่อย", ...) that carry no intent.
     */
    internal fun normalizeSpeech(input: String): String {
        var text = input.trim().replace(Regex("\\s+"), " ")
        text = text.replace(Regex("\\s*ดอท\\s*"), ".")
        text = text.replace(Regex("(?i)\\s+dot\\s+"), ".")
        text = text.replace(Regex("(?i)\\.\\s*คอม"), ".com")
        text = text.replace(Regex("(ให้หน่อย|หน่อย|ด้วย|ครับ|คับ|ค่ะ|คะ|นะ|จ้ะ|จ้า|\\s)+$"), "")
        return text.trim()
    }

    /**
     * Peels filler words off the start and end only. Thai has no spaces between words, so removing
     * "หา" anywhere would also cut it out of "ปัญหา"; anchoring to the edges avoids that.
     */
    private fun stripFiller(text: String): String {
        var result = text.trim()
        var changed = true
        while (changed && result.isNotEmpty()) {
            changed = false
            val lower = result.lowercase()
            for (word in LEAD) {
                if (lower.startsWith(word) && endsAtBoundary(lower, word.length, word)) {
                    result = result.substring(word.length).trim()
                    changed = true
                    break
                }
            }
            if (changed) continue
            val lowerNow = result.lowercase()
            for (word in TRAIL) {
                if (lowerNow.endsWith(word) && startsAtBoundary(lowerNow, lowerNow.length - word.length, word)) {
                    result = result.substring(0, result.length - word.length).trim()
                    changed = true
                    break
                }
            }
        }
        return result
    }

    private fun isAscii(word: String) = word.first().code < 128

    /** English filler must end on a word boundary ("open" must not eat "openai"); Thai need not. */
    private fun endsAtBoundary(text: String, end: Int, word: String): Boolean =
        !isAscii(word) || end >= text.length || !text[end].isLetterOrDigit()

    private fun startsAtBoundary(text: String, start: Int, word: String): Boolean =
        !isAscii(word) || start <= 0 || !text[start - 1].isLetterOrDigit()

    /** The browser only accepts HTTPS (ContentAddress.https), so a spoken "http://" is upgraded. */
    private fun toHttps(value: String): String {
        val withoutScheme = value.replace(Regex("(?i)^https?://"), "")
        val host = withoutScheme.substringBefore('/').lowercase()
        val path = withoutScheme.substring(host.length)
        return "https://$host$path"
    }

    private const val YOUTUBE_HOME = "https://m.youtube.com"
}
