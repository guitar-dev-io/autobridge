package dev.autobridge.youtube

/**
 * Recognising YouTube pages and pulling the video id out of them.
 *
 * Pure string work with no Android or network dependency, so every URL shape the app will meet on
 * a head unit — `m.youtube.com` watch links, `youtu.be` shares, Shorts, embeds, and links that
 * arrive with a playlist or a `t=` offset attached — is covered by a JVM test rather than by
 * trying them by hand in a car.
 */
object YouTubeUrls {
    /**
     * Matched exactly, never by suffix. Only these front ends serve a watch page the add-ons can
     * drive; hosts such as `v.youtube.com` sit under the same domain without being one, and a
     * suffix match would arm SponsorBlock and the quality script on a page with no player.
     */
    private val HOSTS = setOf(
        "youtube.com", "www.youtube.com", "m.youtube.com", "music.youtube.com",
        "youtube-nocookie.com", "www.youtube-nocookie.com", "youtu.be", "www.youtu.be"
    )

    /** An 11-character id; anything else is a channel, a search or a malformed link. */
    private val ID = Regex("^[A-Za-z0-9_-]{11}$")

    fun isYouTube(url: String): Boolean = host(url) in HOSTS

    /** True for the music front end, which has its own player and no Shorts. */
    fun isYouTubeMusic(url: String): Boolean = host(url) == "music.youtube.com"

    /**
     * The video id [url] plays, or null when the page is not a single video (a channel, the home
     * feed, search results).
     */
    fun videoId(url: String): String? {
        val host = host(url) ?: return null
        if (host !in HOSTS) return null
        val path = path(url)

        if (host.endsWith("youtu.be")) return path.trim('/').substringBefore('/').validId()

        val segments = path.trim('/').split('/').filter { it.isNotEmpty() }
        when (segments.firstOrNull()) {
            // /shorts/<id>, /embed/<id>, /live/<id>, /v/<id>
            "shorts", "embed", "live", "v" -> return segments.getOrNull(1).validId()
        }
        return query(url)["v"].validId()
    }

    private fun String?.validId(): String? = this?.takeIf { ID.matches(it) }

    private fun host(url: String): String? {
        val withoutScheme = url.substringAfter("://", "").ifEmpty { return null }
        return withoutScheme.substringBefore('/').substringBefore('?')
            .substringAfter('@').substringBefore(':')
            .lowercase()
            .takeIf { it.isNotBlank() }
    }

    private fun path(url: String): String {
        val withoutScheme = url.substringAfter("://", "")
        val afterHost = withoutScheme.substringAfter('/', "")
        return afterHost.substringBefore('?').substringBefore('#')
    }

    private fun query(url: String): Map<String, String> {
        val raw = url.substringAfter('?', "").substringBefore('#')
        if (raw.isBlank()) return emptyMap()
        return raw.split('&').mapNotNull { pair ->
            val key = pair.substringBefore('=').takeIf { it.isNotBlank() } ?: return@mapNotNull null
            key to pair.substringAfter('=', "")
        }.toMap()
    }
}
