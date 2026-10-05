package dev.autobridge.iptv

/**
 * Where a channel logo comes from, and what makes one usable.
 *
 * `tvg-logo` is the attribute the format is known for, but playlists in the wild spell it several
 * ways and write the address in three shapes: absolute, protocol-relative (`//cdn/x.png`), and
 * relative to the playlist itself (`/logos/x.png`, `logos/x.png`). The loader behind the rows only
 * speaks http(s), so an address is either turned into that here or dropped — one left in the entry
 * costs a dead fetch on every surface that draws the row, and the row shows nothing more for it
 * than the accent initial it would have shown anyway.
 *
 * Kept apart from [M3uParser] for the same reason as [IptvPlaylistConventions]: the parser reads
 * the format, this knows what providers do with it. Pure string work, so every shape is
 * unit-tested without a network.
 */
internal object IptvLogos {
    /**
     * The attribute spellings seen in real playlists, in the order they are trusted.
     *
     * `tvg-logo` is the convention; `logo` and `url-logo` come out of panel generators, and
     * `tvg-logo-small` is sometimes the only one a list carries. Taking the first non-blank rather
     * than only the canonical name is what makes a logo appear on lists that use the others.
     */
    private val ATTRIBUTES = listOf("tvg-logo", "logo", "tvg-logo-small", "url-logo", "tvg-icon")

    /** Values a generator writes when it means "no logo"; they are addresses to nothing. */
    private val PLACEHOLDERS = setOf("null", "none", "nil", "n/a", "-", "0")

    /** The first logo attribute present in [attributes], trimmed; "" when the entry carries none. */
    fun pick(attributes: Map<String, String>): String =
        ATTRIBUTES.firstNotNullOfOrNull { key -> attributes[key]?.trim()?.ifBlank { null } }.orEmpty()

    /**
     * [raw] as an address the image loader can fetch, or "" when there is nothing usable.
     *
     * [base] is the playlist or portal URL the entry came from, which is the only thing that can
     * resolve a relative logo path. A scheme that is neither http nor https (`data:`, `file:`,
     * `ftp:`) is dropped rather than guessed at.
     */
    fun resolve(base: String, raw: String): String {
        val value = raw.trim().trim('"').trim()
        if (value.isEmpty() || value.lowercase() in PLACEHOLDERS) return ""
        // `//cdn/logo.png` inherits the page's scheme; https is the safe half of that choice.
        if (value.startsWith("//")) return "https:$value"
        if (value.startsWith("http://", ignoreCase = true)) return value
        if (value.startsWith("https://", ignoreCase = true)) return value
        if (SCHEME.containsMatchIn(value)) return ""
        val origin = origin(base) ?: return ""
        return if (value.startsWith("/")) origin + value else "${directory(base) ?: origin}/$value"
    }

    /** `scheme://host[:port]` of [url], or null when [url] is not an http(s) address. */
    private fun origin(url: String): String? {
        val separator = url.indexOf("://")
        if (separator <= 0) return null
        val scheme = url.substring(0, separator).lowercase()
        if (scheme != "http" && scheme != "https") return null
        val authority = url.substring(separator + 3).takeWhile { it != '/' && it != '?' && it != '#' }
        return if (authority.isBlank()) null else "$scheme://$authority"
    }

    /** The directory [url] sits in, so `logos/x.png` next to `playlist.m3u` resolves. */
    private fun directory(url: String): String? {
        val withoutQuery = url.substringBefore('?').substringBefore('#')
        val origin = origin(withoutQuery) ?: return null
        val path = withoutQuery.removePrefix(origin)
        if (!path.contains('/')) return origin
        return (origin + path.substringBeforeLast('/')).trimEnd('/')
    }

    /** A leading `scheme:` — matched to reject what cannot be fetched, not to parse it. */
    private val SCHEME = Regex("^[A-Za-z][A-Za-z0-9+.-]*:")
}
