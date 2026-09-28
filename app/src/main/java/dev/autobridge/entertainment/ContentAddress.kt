package dev.autobridge.entertainment

import java.net.URI
import java.net.URLEncoder

/** Web pages and direct streams are separate sources; YouTube pages are never stream URLs. */
object ContentAddress {
    fun https(input: String): String? = runCatching {
        val raw = input.trim()
        if (raw.isEmpty()) return null
        val uri = URI(if (raw.contains("://")) raw else "https://$raw")
        if (uri.scheme != "https" || uri.host.isNullOrBlank() || uri.userInfo != null) null
        else uri.toASCIIString()
    }.getOrNull()

    /** Generic web search, used by the phone home's voice button. */
    fun webSearch(query: String): String =
        "https://www.google.com/search?q=" + URLEncoder.encode(query.trim(), "UTF-8")

    fun youtubeSearch(query: String): String =
        "https://m.youtube.com/results?search_query=" + URLEncoder.encode(query.trim(), "UTF-8")
}
