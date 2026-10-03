package dev.autobridge.bridge

import java.net.URI
import java.util.Locale

/**
 * Turns whatever another app put on the share sheet into a [BridgeSource], or into nothing.
 *
 * Share extras are not URLs. YouTube sends `"Watch this\n\nhttps://youtu.be/ID"`, a browser sends
 * the bare page URL with a separate `EXTRA_SUBJECT` title, a messaging app sends a paragraph with
 * a link somewhere inside it, and plenty of apps send text with no link at all. This finds the
 * first usable link in any of that and discards the prose around it.
 *
 * Pure, with no Android types, so every one of those shapes is a unit test.
 */
object ShareIntake {

    /**
     * Matches an http(s) link inside running text.
     *
     * The trailing-character trim below is what the regex deliberately does not try to do: a link
     * at the end of a sentence picks up the full stop, and a link in brackets picks up the closing
     * bracket. Expressing that in the pattern makes it unreadable; trimming afterwards does not.
     */
    private val URL_PATTERN = Regex("""https?://[^\s<>"']+""", RegexOption.IGNORE_CASE)

    /** Punctuation that is far more likely to be the sentence's than the URL's. */
    private const val TRAILING_JUNK = ".,;:!?)]}>'\"»"

    /**
     * A bare host typed or shared without a scheme ("youtube.com/watch?v=x"). Accepted because the
     * address bar accepts it, and refusing it here would make the share sheet stricter than the
     * thing it feeds.
     */
    private val BARE_HOST_PATTERN =
        Regex("""^[a-z0-9]([a-z0-9-]*[a-z0-9])?(\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+(/\S*)?$""", RegexOption.IGNORE_CASE)

    /**
     * The first usable link in [text], normalised, or null when there is none.
     *
     * @param subject the sharing app's `EXTRA_SUBJECT`, used as the title when it is not just the
     *   URL repeated.
     */
    fun parse(text: String?, subject: String? = null, origin: BridgeSource.Origin = BridgeSource.Origin.SHARE): BridgeSource? {
        val raw = text?.trim().orEmpty()
        if (raw.isEmpty()) return null

        val found = URL_PATTERN.find(raw)?.value?.trimEnd { it in TRAILING_JUNK }
            ?: raw.takeIf { BARE_HOST_PATTERN.matches(it) }?.let { "https://$it" }
            ?: return null

        val normalized = normalize(found) ?: return null
        val title = subject?.trim()
            ?.takeIf { it.isNotEmpty() && !it.equals(found, ignoreCase = true) && !it.startsWith("http") }
            ?: titleFromText(raw, found)

        return BridgeSource(url = normalized, title = title.orEmpty(), origin = origin)
    }

    /**
     * A canonical form of [url], or null when it is not something that can be opened.
     *
     * `youtu.be/ID` becomes a `watch?v=ID` URL because the rest of the app — the resume-point
     * rewriter above all ([dev.autobridge.browser.BrowserResumePoint]) — recognises watch URLs and
     * not short ones, and a share from the YouTube app is the single most common way a link
     * reaches this code. The short link's `t` parameter is carried across, so sharing "copy link
     * at current time" from the phone already arrives as a handoff.
     */
    fun normalize(url: String): String? {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return null
        val withScheme = if (trimmed.contains("://")) trimmed else "https://$trimmed"
        val uri = runCatching { URI(withScheme) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return null
        if (scheme != "http" && scheme != "https") return null
        val host = uri.host?.lowercase(Locale.ROOT)?.takeIf { it.isNotBlank() } ?: return null
        // Credentials in a shared URL are either an accident or an attack; neither is worth opening.
        if (uri.userInfo != null) return null

        if (host == "youtu.be") {
            val id = uri.rawPath.orEmpty().trim('/').substringBefore('/')
            if (id.isNotEmpty()) {
                val start = uri.rawQuery.orEmpty().split('&').firstOrNull { it.startsWith("t=") }
                val suffix = start?.let { "&$it" }.orEmpty()
                return "https://m.youtube.com/watch?v=$id$suffix"
            }
        }
        return withScheme
    }

    /**
     * The prose around a shared link, as a title.
     *
     * Capped and single-lined because this ends up in a car row, and a shared paragraph would
     * otherwise push everything else off it.
     */
    private fun titleFromText(text: String, url: String): String? {
        val withoutUrl = text.replace(url, " ").replace(Regex("\\s+"), " ").trim()
        if (withoutUrl.isEmpty() || withoutUrl.length > 120) return null
        return withoutUrl
    }
}
