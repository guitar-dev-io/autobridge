package dev.autobridge.iptv

/**
 * Minimal `#EXTM3U` reader for IPTV playlists, including the `m3u_plus` attribute form Xtream
 * portals serve from `get.php`.
 *
 * Only the attributes AutoBridge actually uses are read (`group-title`, the logo, `tvg-name`, and
 * the `tvg-id`/`tvg-country` pair that ties a channel to its guide);
 * anything else on the line is ignored rather than rejected, because provider playlists routinely
 * carry vendor-specific extras. Which attribute carries the logo, and what makes its address
 * usable, is [IptvLogos]' business — the value arrives here however the provider wrote it.
 * Pure string work, so it is unit-testable without a network.
 */
object M3uParser {
    /**
     * One playlist line pair: its `#EXTINF` metadata plus the URL that follows it. [guideId] is the
     * `tvg-id` an XMLTV guide files this channel's programmes under; [country] is `tvg-country`.
     */
    data class Channel(
        val name: String,
        val url: String,
        val group: String = "",
        val logo: String = "",
        val guideId: String = "",
        val country: String = ""
    )

    /**
     * The XMLTV guides the playlist's `#EXTM3U` header points at. Generators disagree on the name
     * (`x-tvg-url`, `url-tvg`, `tvg-url`) and some list several addresses in one value, separated
     * by commas; every spelling is read and every address kept, in the order written.
     */
    fun guideUrls(text: String): List<String> {
        val header = text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: return emptyList()
        if (!header.startsWith("#EXTM3U", ignoreCase = true)) return emptyList()
        val attributes = parseAttributes(header)
        return GUIDE_ATTRIBUTES.mapNotNull { attributes[it] }
            .flatMap { it.split(',') }
            .map { it.trim() }
            .filter { it.startsWith("http://", ignoreCase = true) || it.startsWith("https://", ignoreCase = true) }
            .distinct()
    }

    fun parse(text: String): List<Channel> {
        val channels = mutableListOf<Channel>()
        var pending: Channel? = null

        text.lineSequence().forEach { rawLine ->
            val line = rawLine.trim()
            when {
                line.isEmpty() -> Unit
                line.startsWith("#EXTINF", ignoreCase = true) -> pending = parseExtInf(line)
                // Other directives (#EXTGRP, #EXTIMG, #EXTVLCOPT, #EXTM3U) refine or precede it.
                line.startsWith("#EXTGRP:", ignoreCase = true) -> {
                    val group = line.substringAfter(':').trim()
                    pending = pending?.copy(group = group)
                }
                // Some generators put the logo on its own line instead of in an attribute; an
                // #EXTINF that carried one too keeps it, because the attribute is the convention.
                line.startsWith("#EXTIMG:", ignoreCase = true) -> {
                    val logo = line.substringAfter(':').trim()
                    pending = pending?.let { if (it.logo.isBlank()) it.copy(logo = logo) else it }
                }
                line.startsWith("#") -> Unit
                else -> {
                    val entry = pending
                    if (entry != null && entry.name.isNotBlank()) {
                        channels += entry.copy(url = line)
                    }
                    pending = null
                }
            }
        }
        return channels
    }

    /** `#EXTINF:-1 tvg-logo="…" group-title="…",Channel name` */
    private fun parseExtInf(line: String): Channel {
        val payload = line.substringAfter(':', "")
        // The display name is everything after the LAST comma that is not inside a quoted value.
        val nameStart = lastUnquotedComma(payload)
        val name = if (nameStart >= 0) payload.substring(nameStart + 1).trim() else ""
        val attributes = parseAttributes(if (nameStart >= 0) payload.substring(0, nameStart) else payload)
        return Channel(
            name = name.ifBlank { attributes["tvg-name"].orEmpty() },
            url = "",
            group = attributes["group-title"].orEmpty(),
            logo = IptvLogos.pick(attributes),
            guideId = attributes["tvg-id"].orEmpty().trim(),
            country = attributes["tvg-country"].orEmpty().trim()
        )
    }

    private fun lastUnquotedComma(value: String): Int {
        var quoted = false
        var index = -1
        value.forEachIndexed { position, character ->
            when (character) {
                '"' -> quoted = !quoted
                ',' -> if (!quoted) index = position
            }
        }
        return index
    }

    private fun parseAttributes(value: String): Map<String, String> {
        val attributes = mutableMapOf<String, String>()
        val matcher = ATTRIBUTE.toRegex()
        matcher.findAll(value).forEach { match ->
            attributes[match.groupValues[1].lowercase()] = match.groupValues[2]
        }
        return attributes
    }

    private val GUIDE_ATTRIBUTES = listOf("x-tvg-url", "url-tvg", "tvg-url")

    private const val ATTRIBUTE = "([A-Za-z0-9_-]+)=\"([^\"]*)\""
}
