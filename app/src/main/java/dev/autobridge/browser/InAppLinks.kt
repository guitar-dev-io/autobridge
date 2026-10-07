package dev.autobridge.browser

import dev.autobridge.entertainment.ContentAddress
import java.net.URLDecoder

/**
 * Keeps a link that asks for another app on the web, in this app's browser.
 *
 * Sites hand a phone an `intent:` link when they would rather their app opened ("Open in app",
 * the YouTube and Facebook app banners, store pages). The browsers here only load HTTPS, so such
 * a link used to do nothing at all. Most carry the page to show instead, in
 * `S.browser_fallback_url`, or are an ordinary web address wrapped in `scheme=https`; [webPage]
 * finds that page so the browser can simply go there.
 */
object InAppLinks {

    /** The HTTPS page [link] stands for, or null when it is not an app link with one. */
    fun webPage(link: String): String? {
        if (!link.startsWith("intent:", ignoreCase = true)) return null
        val fragment = link.substringAfter("#Intent;", "")
        val extras = fragment.split(';').mapNotNull {
            val key = it.substringBefore('=', "")
            if (key.isEmpty()) null else key to it.substringAfter('=')
        }.toMap()
        extras["S.browser_fallback_url"]
            ?.let { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrNull() }
            ?.let(ContentAddress::https)
            ?.let { return it }
        // "intent://host/path#Intent;scheme=https;..." is the web address itself.
        if (extras["scheme"]?.lowercase() != "https") return null
        val body = link.substringAfter(':').removePrefix("//").substringBefore("#Intent;")
        if (body.isBlank() || !body.substringBefore('/').contains('.')) return null
        return ContentAddress.https("https://$body")
    }
}
