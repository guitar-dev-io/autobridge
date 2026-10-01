package dev.autobridge.library

/** How the Streaming list is grouped, in display order. */
enum class StreamingGroup(val title: String) {
    VIDEO("Video"),
    MUSIC("Music"),
    LIVE("Live / Gaming"),
    ANIME("Anime")
}

/** One streaming website. It opens in the regular in-app browser, like any other page. */
data class StreamingLink(val title: String, val group: StreamingGroup, val url: String)

/**
 * The Streaming tile's catalog, shared by the phone home grid and the Android Auto home.
 *
 * Every entry is a plain website loaded by the existing browser stack (`CarWebRenderer` in the car,
 * `BrowserActivity` on the phone); there is no per-site player. Only services that play without
 * DRM are listed: the WebView has no dependable Widevine path, so Netflix, Disney+, Prime Video,
 * HBO Max, Viu, iQIYI, WeTV, Youku, AIS PLAY and Spotify (whose web player also needs Widevine)
 * would open a page that cannot play. TrueVisions NOW has no web player at all.
 */
object StreamingLinks {
    val all: List<StreamingLink> = listOf(
        StreamingLink("YouTube", StreamingGroup.VIDEO, "https://m.youtube.com"),
        StreamingLink("YouTube Kids", StreamingGroup.VIDEO, "https://www.youtubekids.com"),
        StreamingLink("TikTok", StreamingGroup.VIDEO, "https://www.tiktok.com"),
        StreamingLink("YouTube Music", StreamingGroup.MUSIC, "https://music.youtube.com"),
        StreamingLink("Twitch", StreamingGroup.LIVE, "https://www.twitch.tv"),
        // The international site; bilibili.com is the mainland-China front end.
        StreamingLink("Bilibili", StreamingGroup.ANIME, "https://www.bilibili.tv")
    )

    /** [all] split by group, in [StreamingGroup] order, skipping empty groups. */
    fun grouped(): List<Pair<StreamingGroup, List<StreamingLink>>> =
        StreamingGroup.entries.mapNotNull { group ->
            all.filter { it.group == group }.takeIf { it.isNotEmpty() }?.let { group to it }
        }
}
