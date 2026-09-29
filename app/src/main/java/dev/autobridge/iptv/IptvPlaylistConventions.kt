package dev.autobridge.iptv

/**
 * Conventions that public community playlists follow but the `#EXTM3U` format itself says nothing
 * about, kept apart from [M3uParser] so the parser stays a plain reader of the format.
 *
 * Two of them matter enough to act on:
 *
 * 1. Not every line under an `#EXTINF` is a stream. Curated lists point a channel at a YouTube or
 *    Twitch *page* when that is where the broadcaster publishes its live feed. Handing such a URL
 *    to ExoPlayer produces a parse failure the user cannot act on, so it is classified here and
 *    opened in the browser instead.
 * 2. [Free-TV](https://github.com/Free-TV/IPTV) encodes per-channel notes as circled letters glued
 *    onto the display name (`Ⓢ` standard definition, `Ⓖ` geo-blocked, `Ⓨ` YouTube, `Ⓣ` Twitch,
 *    `Ⓓ` Dailymotion). Left in place they are noise in a title read at a glance on a head unit;
 *    read out they are exactly the warnings worth showing as a subtitle.
 *
 * Pure string work, so it is unit-testable without a network or a device.
 */
object IptvPlaylistConventions {
    /** A display name split into the title to show and the notes its markers carried. */
    data class Label(val title: String, val hints: List<String>)

    /** Free-TV's circled-letter markers, in the order they are worth reading back. */
    private val MARKERS = listOf(
        'Ⓨ' to "YouTube",
        'Ⓣ' to "Twitch",
        'Ⓓ' to "Dailymotion",
        'Ⓖ' to "Geo-blocked",
        'Ⓢ' to "SD"
    )

    /**
     * Hosts that serve a watch *page*, never a stream a media player can open. Matched on the host
     * only: a query string mentioning youtube.com must not turn a real stream into a web page.
     */
    private val PAGE_HOSTS = listOf(
        "youtube.com", "youtu.be", "twitch.tv", "dailymotion.com", "dai.ly", "facebook.com"
    )

    /** Strips the markers out of [name] and returns them as readable hints. */
    fun label(name: String): Label {
        val found = MARKERS.filter { (marker, _) -> name.indexOf(marker) >= 0 }
        if (found.isEmpty()) return Label(name.trim(), emptyList())
        val stripped = name.filterNot { character -> MARKERS.any { it.first == character } }
        // Removing a marker leaves the space that separated it from the name behind.
        return Label(stripped.trim().replace(WHITESPACE, " "), found.map { it.second })
    }

    /**
     * True when [url] addresses a page to browse rather than a stream to decode. Anything that is
     * not recognisably a watch page stays a stream: provider endpoints hide behind `.php` and
     * `.htm` URLs often enough that guessing from the extension would break working channels.
     */
    fun isWebPage(url: String): Boolean {
        val host = host(url) ?: return false
        return PAGE_HOSTS.any { host == it || host.endsWith(".$it") }
    }

    private fun host(url: String): String? {
        val withoutScheme = when {
            url.startsWith("http://", ignoreCase = true) -> url.substring(7)
            url.startsWith("https://", ignoreCase = true) -> url.substring(8)
            else -> return null
        }
        val authority = withoutScheme.takeWhile { it != '/' && it != '?' && it != '#' }
        return authority.substringAfter('@').substringBefore(':').lowercase().ifBlank { null }
    }

    private val WHITESPACE = Regex("\\s{2,}")
}
