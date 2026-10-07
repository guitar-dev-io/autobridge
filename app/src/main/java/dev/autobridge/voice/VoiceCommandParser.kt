package dev.autobridge.voice

import dev.autobridge.agent.AgentCommandParser
import dev.autobridge.agent.AgentCommandRouter.AgentAction

enum class VoiceAction { OPEN_APP, SEARCH, PLAY, OPEN_URL, SEND_TO_CAR, GO_HOME, GO_BACK, UNKNOWN }

enum class VoiceTarget { YOUTUBE, YOUTUBE_MUSIC, TIKTOK, IQIYI, BROWSER }

/**
 * What a transcript asks for. A description only: nothing here can run anything. [CommandValidator]
 * decides whether and how it may be executed, and the executor maps it onto actions the app
 * already has.
 *
 * @param text the transcript it was parsed from, kept so an [VoiceAction.UNKNOWN] command can fall
 *   back to the Agent's own parser.
 */
data class VoiceCommand(
    val action: VoiceAction,
    val target: VoiceTarget? = null,
    val query: String? = null,
    val url: String? = null,
    val confidence: Float = 0f,
    val text: String = ""
)

/**
 * Turns a Whisper transcript (Thai, English, or both in one sentence) into a [VoiceCommand], by
 * rule - no cloud model, no network, and the same answer every time for the same words.
 *
 * Built for what Whisper actually writes, which is not what a keyboard writes: brand names come
 * back in either script ("YouTube" or "ยูทูบ"/"ยูทูป"), spaced or not ("YouTube Music",
 * "ยูทูบมิวสิก"), with punctuation and polite particles ("ครับ") attached. Polite particles and
 * spoken "dot" are handled by the Agent parser's [AgentCommandParser.normalizeSpeech], shared so the
 * two parsers agree on cleanup.
 *
 * Order of the rules, and why:
 * 1. Home and Back are matched against the *whole* utterance, never as a substring: "กลับ" (back)
 *    is inside a song title as easily as anywhere else, and leaving a page by accident is worse
 *    than not understanding a command.
 * 2. "Send to car" wraps another command ("ส่ง YouTube เพลง Bodyslam ไปที่รถ").
 * 3. A spoken web address opens that address.
 * 4. A known service (longest alias first, so "YouTube Music" is never read as "YouTube") plus
 *    whatever remains after the verb, the service name and filler is the query: nothing left means
 *    open the service, something left means search it (or play, when the verb was "play").
 * 5. A search verb with no service searches the web.
 * Anything else is [VoiceAction.UNKNOWN], which the caller hands to the existing Agent parser so
 * its vocabulary (resume, mirror, desktop mode, fuel log...) still works by voice. Those Agent
 * intents are also checked up front, before rule 2, so a phrase the Agent already owned keeps
 * meaning what it meant.
 */
object VoiceCommandParser {

    /**
     * Prompt given to Whisper with every recognition. Whisper continues the style of its prompt, so
     * naming the services in their usual Latin spelling inside a Thai sentence makes it write
     * "เปิด YouTube" rather than transliterating the name - which is what the user expects to read
     * back - and nudges it towards the words commands are made of.
     */
    const val RECOGNITION_PROMPT = "เปิด YouTube, YouTube Music, TikTok, iQIYI, Google ค้นหา เล่นเพลง"

    private val TARGET_ALIASES: List<Pair<VoiceTarget, String>> = listOf(
        VoiceTarget.YOUTUBE_MUSIC to "youtube music",
        VoiceTarget.YOUTUBE_MUSIC to "you tube music",
        VoiceTarget.YOUTUBE_MUSIC to "yt music",
        VoiceTarget.YOUTUBE_MUSIC to "ยูทูบ มิวสิก",
        VoiceTarget.YOUTUBE_MUSIC to "ยูทูบ มิวสิค",
        VoiceTarget.YOUTUBE_MUSIC to "ยูทูป มิวสิก",
        VoiceTarget.YOUTUBE_MUSIC to "ยูทูป มิวสิค",
        VoiceTarget.YOUTUBE_MUSIC to "youtube มิวสิก",
        VoiceTarget.YOUTUBE_MUSIC to "youtube มิวสิค",
        VoiceTarget.YOUTUBE to "youtube",
        VoiceTarget.YOUTUBE to "you tube",
        VoiceTarget.YOUTUBE to "ยูทูบ",
        VoiceTarget.YOUTUBE to "ยูทูป",
        VoiceTarget.YOUTUBE to "ยูทู้ป",
        VoiceTarget.YOUTUBE to "ยูทิวบ์",
        VoiceTarget.TIKTOK to "tiktok",
        VoiceTarget.TIKTOK to "tik tok",
        VoiceTarget.TIKTOK to "ติ๊กต็อก",
        VoiceTarget.TIKTOK to "ติ๊กต๊อก",
        VoiceTarget.TIKTOK to "ติกต็อก",
        VoiceTarget.TIKTOK to "ติ๊กตอก",
        VoiceTarget.TIKTOK to "ติกตอก",
        VoiceTarget.IQIYI to "iqiyi",
        VoiceTarget.IQIYI to "iq yi",
        VoiceTarget.IQIYI to "i qiyi",
        VoiceTarget.IQIYI to "อ้ายฉีอี้",
        VoiceTarget.IQIYI to "อ้ายฉีอี๋",
        VoiceTarget.IQIYI to "ไอฉีอี้",
        VoiceTarget.IQIYI to "ไอคิวยี่",
        VoiceTarget.IQIYI to "ไอคิวอี้",
        VoiceTarget.BROWSER to "browser",
        VoiceTarget.BROWSER to "เบราว์เซอร์",
        VoiceTarget.BROWSER to "บราวเซอร์",
        VoiceTarget.BROWSER to "เว็บไซต์",
        VoiceTarget.BROWSER to "เว็บ"
    ).sortedByDescending { it.second.length }

    /** Alias as a pattern: case-insensitive, and any space in it may be absent or repeated. */
    private val TARGET_PATTERNS: List<Pair<VoiceTarget, Regex>> = TARGET_ALIASES.map { (target, alias) ->
        val body = alias.split(' ').joinToString("\\s*") { Regex.escape(it) }
        // An English alias must not be part of a longer Latin word ("youtube" is not in "youtuber"),
        // but Thai may touch it directly: Whisper often writes "เปิดYouTube" without a space.
        val pattern = if (alias.first().code < 128) "(?<![A-Za-z0-9])$body(?![A-Za-z0-9])" else body
        target to Regex(pattern, RegexOption.IGNORE_CASE)
    }

    private val HOME = Regex(
        "^(?:(?:กลับ|ไป|กลับไป|ไปที่|go(?: back)?(?: to)?|back to|open)\\s*)?" +
            "(?:หน้าแรก|หน้าหลัก|home(?: screen| page)?)$",
        RegexOption.IGNORE_CASE
    )
    private val BACK = Regex(
        "^(?:ย้อนกลับ|ถอยกลับ|กลับ|กลับไป|ย้อน|ย้อนไป|go back|back|previous page|หน้าก่อน(?:หน้า)?)" +
            "(?:\\s*(?:ไป|หน่อย))*$",
        RegexOption.IGNORE_CASE
    )
    private val SEND_TO_CAR = Regex(
        "(?:ส่ง\\s*(?:ไป|ขึ้น)?\\s*(?:ที่|ยัง|บน)?\\s*(?:หน้าจอ\\s*)?รถ|send(?: it)? to(?: the)? car|(?:on|to) the car|ไปที่รถ|ขึ้นจอรถ)",
        RegexOption.IGNORE_CASE
    )
    private val SEND_VERB = Regex("^(?:ส่ง|send)\\s*", RegexOption.IGNORE_CASE)

    private val DOMAIN = Regex("(?i)\\b(?:https?://)?(?:[a-z0-9-]+\\.)+[a-z]{2,}(?:/\\S*)?")

    private val OPEN_VERBS = listOf("open", "launch", "go to", "start", "เปิด", "ไปที่", "เข้า", "ขอเปิด")
    private val SEARCH_VERBS = listOf("search for", "search", "look up", "find", "ค้นหา", "หา", "ค้น")
    private val PLAY_VERBS = listOf("play", "listen to", "เล่น", "ฟัง", "ขอฟัง", "เปิดเพลง")

    /** Words that introduce what to look for and are not part of it ("เพลง Bodyslam" -> "Bodyslam"). */
    private val QUERY_MARKERS = listOf("เพลงของ", "เพลง", "songs by", "song by", "songs", "song", "music by", "วิดีโอ", "คลิป", "video")
    /** Prepositions left dangling once the service name is removed ("... ใน YouTube Music"). */
    private val PREPOSITIONS = listOf("ใน", "บน", "ที่", "จาก", "on", "in", "at", "from", "with")

    private val DEFERRED_AGENT_ACTIONS = setOf(
        AgentAction.RESUME_MEDIA, AgentAction.OPEN_MIRROR, AgentAction.ENABLE_DESKTOP,
        AgentAction.DISABLE_DESKTOP, AgentAction.ENTER_FULLSCREEN, AgentAction.EXIT_FULLSCREEN,
        AgentAction.OPEN_RECENT, AgentAction.LOG_FUEL
    )

    private val PUNCTUATION = Regex("[,，、!?！？。\"“”]")
    private val SPACES = Regex("\\s+")

    fun parse(input: String): VoiceCommand {
        // Sentence-final full stops go before the particle cleanup, which only looks at the very end
        // ("... Bodyslam ครับ."); a dot inside the text is left alone, it may be a web address.
        val text = AgentCommandParser.normalizeSpeech(input.replace(PUNCTUATION, " ").trim().trimEnd('.', ' '))
            .replace(SPACES, " ").trim().trimEnd('.')
        if (text.isEmpty()) return VoiceCommand(VoiceAction.UNKNOWN, text = text)

        if (HOME.matches(text)) return VoiceCommand(VoiceAction.GO_HOME, confidence = 0.95f, text = text)
        if (BACK.matches(text)) return VoiceCommand(VoiceAction.GO_BACK, confidence = 0.95f, text = text)

        // The Agent's own intents (resume, mirror, desktop mode, fullscreen, recent, a fuel log) keep
        // their meaning by voice: "เล่นต่อ" is "resume", not "play ต่อ".
        // A named service wins, though: "YouTube เพลงล่าสุด" is a search, not "open recent".
        if (TARGET_PATTERNS.none { it.second.containsMatchIn(text) } &&
            AgentCommandParser.parse(text)?.action in DEFERRED_AGENT_ACTIONS
        ) {
            return VoiceCommand(VoiceAction.UNKNOWN, query = text, confidence = 0.5f, text = text)
        }

        SEND_TO_CAR.find(text)?.let { match ->
            val rest = text.removeRange(match.range).replace(SEND_VERB, "").trim()
            val inner = parseContent(rest)
            return if (inner.action == VoiceAction.UNKNOWN && rest.isBlank()) {
                VoiceCommand(VoiceAction.SEND_TO_CAR, confidence = 0.5f, text = text)
            } else {
                inner.copy(
                    action = VoiceAction.SEND_TO_CAR,
                    // "ส่ง ข่าววันนี้ ไปที่รถ": no service named, so it is a web search sent to the car.
                    target = inner.target ?: VoiceTarget.BROWSER.takeIf { inner.url == null },
                    text = text,
                    confidence = inner.confidence * 0.9f
                )
            }
        }
        return parseContent(text).copy(text = text)
    }

    private fun parseContent(text: String): VoiceCommand {
        DOMAIN.find(text)?.let { match ->
            return VoiceCommand(VoiceAction.OPEN_URL, VoiceTarget.BROWSER, url = match.value, confidence = 0.9f)
        }

        var rest = text
        val target = TARGET_PATTERNS.firstNotNullOfOrNull { (target, pattern) ->
            pattern.find(rest)?.let { match ->
                rest = (removeTrailingPreposition(rest.substring(0, match.range.first)) + " " +
                    rest.substring(match.range.last + 1)).trim()
                target
            }
        }

        // The verb leads the sentence; a trailing one is rare enough not to guess at.
        val play = startsWithAny(rest, PLAY_VERBS)
        val search = !play && startsWithAny(rest, SEARCH_VERBS)
        val open = !play && !search && startsWithAny(rest, OPEN_VERBS)
        rest = stripLeading(rest, PLAY_VERBS + SEARCH_VERBS + OPEN_VERBS)
        rest = removeLeadingPreposition(rest)
        val hadMarker = startsWithAny(rest, QUERY_MARKERS)
        rest = stripLeading(rest, QUERY_MARKERS)
        rest = removeTrailingPreposition(rest).trim()

        val query = rest.takeIf { it.isNotBlank() }
        return when {
            target != null && query == null ->
                VoiceCommand(VoiceAction.OPEN_APP, target, confidence = if (open || play || search) 0.95f else 0.8f)
            target != null && play -> VoiceCommand(VoiceAction.PLAY, target, query, confidence = 0.9f)
            target != null -> VoiceCommand(VoiceAction.SEARCH, target, query, confidence = if (open || search) 0.9f else 0.75f)
            query != null && play -> VoiceCommand(VoiceAction.PLAY, VoiceTarget.YOUTUBE, query, confidence = 0.75f)
            query != null && search -> VoiceCommand(VoiceAction.SEARCH, VoiceTarget.BROWSER, query, confidence = 0.75f)
            // "เปิดเพลง Bodyslam": a song with no service named plays on YouTube.
            query != null && hadMarker -> VoiceCommand(VoiceAction.PLAY, VoiceTarget.YOUTUBE, query, confidence = 0.6f)
            else -> VoiceCommand(VoiceAction.UNKNOWN, query = query ?: text.takeIf { it.isNotBlank() }, confidence = 0.2f)
        }
    }

    private fun startsWithAny(text: String, words: List<String>): Boolean =
        words.any { word -> leadingMatch(text, word) }

    /** Removes leading [words], repeatedly ("ช่วย เปิด เพลง ..."), longest first. */
    private fun stripLeading(text: String, words: List<String>): String {
        var result = text.trim()
        val ordered = words.sortedByDescending { it.length }
        var changed = true
        while (changed) {
            changed = false
            for (word in ordered) {
                if (leadingMatch(result, word)) {
                    result = result.substring(word.length).trim()
                    changed = true
                    break
                }
            }
        }
        return result
    }

    private fun leadingMatch(text: String, word: String): Boolean {
        if (!text.startsWith(word, ignoreCase = true)) return false
        // English words end at a boundary ("open" must not eat "openai"); Thai words need not.
        val ascii = word.first().code < 128
        return !ascii || text.length == word.length || !text[word.length].isLetterOrDigit()
    }

    /** "ใน Taylor Swift" -> "Taylor Swift", but only a preposition standing as its own word. */
    private fun removeLeadingPreposition(text: String): String {
        for (word in PREPOSITIONS.sortedByDescending { it.length }) {
            if (text.length > word.length && text.startsWith(word, ignoreCase = true) &&
                text[word.length].isWhitespace()
            ) return text.substring(word.length).trim()
        }
        return text
    }

    private fun removeTrailingPreposition(text: String): String {
        val trimmed = text.trimEnd()
        for (word in PREPOSITIONS.sortedByDescending { it.length }) {
            if (!trimmed.endsWith(word, ignoreCase = true)) continue
            val start = trimmed.length - word.length
            val ascii = word.first().code < 128
            if (ascii && start > 0 && trimmed[start - 1].isLetterOrDigit()) continue
            // A Thai preposition only counts when it stands as its own word ("... ใน" after a space
            // or alone); "ไป" inside a longer Thai word is left alone.
            if (!ascii && start > 0 && !trimmed[start - 1].isWhitespace()) continue
            return trimmed.substring(0, start).trimEnd()
        }
        return trimmed
    }
}
