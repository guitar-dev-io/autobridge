package dev.autobridge.voice

import dev.autobridge.entertainment.ContentAddress
import dev.autobridge.library.StreamingLinks
import java.net.IDN
import java.net.URI
import java.net.URLEncoder

/**
 * A [VoiceCommand] that passed [CommandValidator]: the one form the executors accept.
 *
 * @param url the fully built HTTPS address to open, or null for an action that needs none
 *   (going home or back, opening the browser on its start page).
 * @param autoExecute true when the action may run without the user confirming it first.
 */
data class ValidatedCommand(val command: VoiceCommand, val url: String?, val autoExecute: Boolean)

sealed interface Validation {
    data class Valid(val command: ValidatedCommand) : Validation
    data class Rejected(val reason: Reason) : Validation

    enum class Reason { UNKNOWN_COMMAND, MISSING_TARGET, EMPTY_QUERY, QUERY_TOO_LONG, MALFORMED_URL, UNSUPPORTED_SCHEME, UNSAFE_HOST }
}

/**
 * The gate between speech and action.
 *
 * Whisper's output is untrusted text - it is whatever was said near the phone, or whatever the
 * model imagined in noise - so it never reaches anything that executes as it is. Commands are a
 * closed set of actions ([VoiceAction]) on a closed set of services ([VoiceTarget]); the only free
 * text that survives is a search query, which is URL-encoded into a fixed search address, and a
 * spoken web address, which has to come out of this as a plain HTTPS URL to a public host. No
 * Android intent, package name, scheme handler or shell is ever built from a transcript.
 *
 * Opening a service, searching or playing on it, and going home or back run straight away (when
 * the user leaves Auto Execute on); opening an arbitrary site or sending to the car waits for a
 * tap, because those are the two where a misheard word sends the user somewhere they did not ask
 * to go.
 */
object CommandValidator {

    const val MAX_QUERY_LENGTH = 200

    private val AUTO_EXECUTABLE = setOf(
        VoiceAction.OPEN_APP, VoiceAction.SEARCH, VoiceAction.PLAY, VoiceAction.GO_HOME, VoiceAction.GO_BACK,
        VoiceAction.OPEN_SCREEN
    )

    /** Schemes a spoken address may not use; everything but https is refused anyway, these are named for the log. */
    private val BLOCKED_SCHEMES = setOf(
        "javascript", "intent", "file", "content", "data", "about", "market", "tel", "sms", "smsto",
        "mailto", "android-app", "chrome", "blob", "ftp", "ws", "wss"
    )

    fun validate(command: VoiceCommand): Validation {
        val auto = command.action in AUTO_EXECUTABLE
        return when (command.action) {
            VoiceAction.UNKNOWN -> Validation.Rejected(Validation.Reason.UNKNOWN_COMMAND)
            VoiceAction.GO_HOME, VoiceAction.GO_BACK -> valid(command, null, auto)
            VoiceAction.OPEN_SCREEN ->
                if (command.screen == null) Validation.Rejected(Validation.Reason.MISSING_TARGET)
                else valid(command, null, auto)
            VoiceAction.OPEN_APP -> {
                val target = command.target ?: return Validation.Rejected(Validation.Reason.MISSING_TARGET)
                valid(command, homeUrl(target), auto)
            }
            VoiceAction.SEARCH, VoiceAction.PLAY -> {
                val target = command.target ?: return Validation.Rejected(Validation.Reason.MISSING_TARGET)
                val query = cleanQuery(command.query) ?: return Validation.Rejected(Validation.Reason.EMPTY_QUERY)
                if (query.length > MAX_QUERY_LENGTH) return Validation.Rejected(Validation.Reason.QUERY_TOO_LONG)
                valid(command.copy(query = query), searchUrl(target, query), auto)
            }
            VoiceAction.OPEN_URL -> when (val url = safeUrl(command.url)) {
                is UrlCheck.Ok -> valid(command.copy(url = url.url), url.url, auto)
                is UrlCheck.Bad -> Validation.Rejected(url.reason)
            }
            VoiceAction.SEND_TO_CAR -> {
                // Whatever the wrapped command would open is what goes to the car.
                val inner = when {
                    command.url != null -> command.copy(action = VoiceAction.OPEN_URL)
                    command.query != null -> command.copy(action = VoiceAction.SEARCH)
                    command.target != null -> command.copy(action = VoiceAction.OPEN_APP)
                    else -> return Validation.Rejected(Validation.Reason.MISSING_TARGET)
                }
                when (val result = validate(inner)) {
                    is Validation.Rejected -> result
                    is Validation.Valid -> {
                        val url = result.command.url ?: return Validation.Rejected(Validation.Reason.MISSING_TARGET)
                        valid(result.command.command.copy(action = VoiceAction.SEND_TO_CAR), url, auto)
                    }
                }
            }
        }
    }

    private fun valid(command: VoiceCommand, url: String?, auto: Boolean) =
        Validation.Valid(ValidatedCommand(command, url, auto))

    private fun cleanQuery(query: String?): String? =
        query?.replace(Regex("[\\p{Cntrl}]"), " ")?.replace(Regex("\\s+"), " ")?.trim()?.takeIf { it.isNotEmpty() }

    /** The service's front page, from the same catalog the Streaming screen lists. Null for the browser. */
    fun homeUrl(target: VoiceTarget): String? = when (target) {
        VoiceTarget.YOUTUBE -> streaming("YouTube")
        VoiceTarget.YOUTUBE_MUSIC -> streaming("YouTube Music")
        VoiceTarget.TIKTOK -> streaming("TikTok")
        VoiceTarget.IQIYI -> streaming("iQIYI")
        VoiceTarget.BROWSER -> null
    }

    fun searchUrl(target: VoiceTarget, query: String): String = when (target) {
        VoiceTarget.YOUTUBE -> ContentAddress.youtubeSearch(query)
        VoiceTarget.YOUTUBE_MUSIC -> "https://music.youtube.com/search?q=" + encode(query)
        VoiceTarget.TIKTOK -> "https://www.tiktok.com/search?q=" + encode(query)
        VoiceTarget.IQIYI -> "https://www.iq.com/search?query=" + encode(query)
        VoiceTarget.BROWSER -> ContentAddress.webSearch(query)
    }

    private fun streaming(title: String): String = StreamingLinks.all.first { it.title == title }.url

    private fun encode(value: String) = URLEncoder.encode(value.trim(), "UTF-8")

    private sealed interface UrlCheck {
        data class Ok(val url: String) : UrlCheck
        data class Bad(val reason: Validation.Reason) : UrlCheck
    }

    /**
     * A spoken address made safe to open: http is upgraded to https (a spoken address has no
     * scheme, and the browser accepts https only - see [ContentAddress.https]), any other scheme is
     * refused, as are credentials in the URL and hosts that are not public names (localhost, IP
     * literals, single labels) - a voice command has no business reaching into the local network.
     */
    private fun safeUrl(raw: String?): UrlCheck {
        val input = raw?.trim().orEmpty()
        if (input.isEmpty() || input.any { it.isWhitespace() || it.isISOControl() }) {
            return UrlCheck.Bad(Validation.Reason.MALFORMED_URL)
        }
        val schemeEnd = input.indexOf(':')
        val scheme = if (schemeEnd > 0 && input.substring(0, schemeEnd).all { it.isLetterOrDigit() || it in "+-." }) {
            input.substring(0, schemeEnd).lowercase()
        } else null
        val withScheme = when {
            scheme == null -> "https://$input"
            scheme == "https" -> input
            scheme == "http" -> "https://" + input.substring(schemeEnd + 1).removePrefix("//")
            scheme in BLOCKED_SCHEMES -> return UrlCheck.Bad(Validation.Reason.UNSUPPORTED_SCHEME)
            // "google.com:443/x" parses as scheme "google.com"; a dotted "scheme" is a host.
            scheme.contains('.') -> "https://$input"
            else -> return UrlCheck.Bad(Validation.Reason.UNSUPPORTED_SCHEME)
        }
        val uri = runCatching { URI(withScheme) }.getOrNull() ?: return UrlCheck.Bad(Validation.Reason.MALFORMED_URL)
        if (uri.userInfo != null) return UrlCheck.Bad(Validation.Reason.UNSAFE_HOST)
        val host = uri.host?.lowercase()?.trimEnd('.') ?: return UrlCheck.Bad(Validation.Reason.MALFORMED_URL)
        if (!isPublicHostName(host)) return UrlCheck.Bad(Validation.Reason.UNSAFE_HOST)
        val safe = ContentAddress.https(withScheme) ?: return UrlCheck.Bad(Validation.Reason.MALFORMED_URL)
        return UrlCheck.Ok(safe)
    }

    private fun isPublicHostName(host: String): Boolean {
        val ascii = runCatching { IDN.toASCII(host) }.getOrNull() ?: return false
        if (ascii == "localhost" || ascii.endsWith(".localhost") || ascii.endsWith(".local") ||
            ascii.endsWith(".internal") || ascii.endsWith(".lan")
        ) return false
        val labels = ascii.split('.')
        if (labels.size < 2 || labels.any { it.isEmpty() || it.length > 63 }) return false
        // IPv4 literal (all-numeric labels) or IPv6 literal.
        if (labels.all { label -> label.all(Char::isDigit) } || ascii.contains(':')) return false
        val tld = labels.last()
        return tld.length >= 2 && tld.all { it.isLetter() } || tld.startsWith("xn--")
    }
}
