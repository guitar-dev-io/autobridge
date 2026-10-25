package dev.autobridge.library

/** How the Streaming list is grouped, in display order. */
enum class StreamingGroup(val title: String) {
    /** The protected-video services (Widevine). They play in Chrome for Linux, so they lead, as large cards. */
    PREMIUM("Streaming services"),
    /** General video that plays in the built-in browser, as large cards. */
    VIDEO("Video"),
    SOCIAL("Short and social video"),
    MOVIES("Movies"),
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
 * `BrowserActivity` on the phone); there is no per-site player. The main list keeps to services
 * that play without DRM: the WebView has no dependable Widevine path, so AIS PLAY and Spotify
 * (whose web player also needs Widevine) would open a page that cannot play. TrueVisions NOW has
 * no web player at all.
 *
 * Two groups are the exception, added at the driver's request: the main [StreamingGroup.VIDEO]
 * grid (Netflix, Disney+, Prime Video, HBO Max, Viu, WeTV, iQIYI beside YouTube) and
 * [StreamingGroup.CHINESE]. Those sites serve protected video and some are limited by region, so
 * some of what they show may not play here. The screens say so beside them ([mayNotPlay]) rather
 * than leaving a black video unexplained.
 *
 * Movies is deliberately just the Internet Archive for now: it is public-domain and plays from a
 * plain `<video>` tag, so it is the one "watch a movie" site that is both unambiguously legal and
 * known to work in a WebView without Widevine. A free ad-supported service (Tubi, Pluto TV, …)
 * would round this out, but whether its catalog plays without DRM in this WebView is unverified -
 * add it once that is confirmed, rather than listing a site that may just show a black screen.
 */
object StreamingLinks {
    val all: List<StreamingLink> = listOf(
        // The protected-video services first, in the order a driver expects them. They need Chrome.
        StreamingLink("Netflix", StreamingGroup.PREMIUM, "https://www.netflix.com"),
        StreamingLink("Disney+", StreamingGroup.PREMIUM, "https://www.disneyplus.com"),
        StreamingLink("Prime Video", StreamingGroup.PREMIUM, "https://www.primevideo.com"),
        StreamingLink("HBO Max", StreamingGroup.PREMIUM, "https://www.hbomax.com"),
        StreamingLink("Viu", StreamingGroup.PREMIUM, "https://www.viu.com"),
        StreamingLink("WeTV", StreamingGroup.PREMIUM, "https://wetv.vip"),
        StreamingLink("iQIYI", StreamingGroup.PREMIUM, "https://www.iq.com"),
        // General video: plays in the built-in browser.
        StreamingLink("YouTube", StreamingGroup.VIDEO, "https://m.youtube.com"),
        StreamingLink("Dailymotion", StreamingGroup.VIDEO, "https://www.dailymotion.com"),
        StreamingLink("Vimeo", StreamingGroup.VIDEO, "https://vimeo.com"),
        StreamingLink("TikTok", StreamingGroup.SOCIAL, "https://www.tiktok.com"),
        StreamingLink("Facebook Watch", StreamingGroup.SOCIAL, "https://m.facebook.com/watch"),
        StreamingLink("Internet Archive: Feature Films", StreamingGroup.MOVIES, "https://archive.org/details/feature_films"),
        StreamingLink("YouTube Music", StreamingGroup.MUSIC, "https://music.youtube.com"),
        StreamingLink("SoundCloud", StreamingGroup.MUSIC, "https://m.soundcloud.com"),
        StreamingLink("Mixcloud", StreamingGroup.MUSIC, "https://www.mixcloud.com"),
        // Live radio from around the world, Thai stations included, on a globe.
        StreamingLink("Radio Garden", StreamingGroup.MUSIC, "https://radio.garden"),
        StreamingLink("YouTube Live", StreamingGroup.LIVE, "https://m.youtube.com/live"),
        StreamingLink("Twitch", StreamingGroup.LIVE, "https://www.twitch.tv"),
        StreamingLink("Kick", StreamingGroup.LIVE, "https://kick.com"),
        // The international site; bilibili.com is the mainland-China front end.
        StreamingLink("Bilibili", StreamingGroup.ANIME, "https://www.bilibili.tv"),
        // Licensed anime, free on YouTube with Thai subtitles on much of it.
        StreamingLink("Muse Asia", StreamingGroup.ANIME, "https://m.youtube.com/@MuseAsia"),
        StreamingLink("Ani-One Asia", StreamingGroup.ANIME, "https://m.youtube.com/@AniOneAsia")
    )

    /**
     * The services in the main grid that stream protected video (Widevine DRM) or are limited by
     * region, so what they show may not play in this WebView. Listed at the driver's request, with
     * the warning beside them.
     */
    private val PROTECTED = setOf("Netflix", "Disney+", "Prime Video", "HBO Max", "Viu", "WeTV", "iQIYI")

    /** Large cards: the protected services and the general video sites. */
    fun isFeatured(link: StreamingLink): Boolean =
        link.group == StreamingGroup.PREMIUM || link.group == StreamingGroup.VIDEO

    /** True for a site that may not play here (protected video, or limited by region). */
    fun mayNotPlay(link: StreamingLink): Boolean = link.title in PROTECTED

    /** [all] split by group, in [StreamingGroup] order, skipping empty groups. */
    fun grouped(): List<Pair<StreamingGroup, List<StreamingLink>>> =
        StreamingGroup.entries.mapNotNull { group ->
            all.filter { it.group == group }.takeIf { it.isNotEmpty() }?.let { group to it }
        }
}
