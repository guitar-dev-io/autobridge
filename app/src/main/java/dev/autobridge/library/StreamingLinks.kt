package dev.autobridge.library

/** How the Streaming list is grouped, in display order. */
enum class StreamingGroup(val title: String) {
    VIDEO("Video"),
    CHINESE("Chinese video"),
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
 * that play without DRM: the WebView has no dependable Widevine path, so Netflix, Disney+, Prime
 * Video, HBO Max, Viu, AIS PLAY and Spotify (whose web player also needs Widevine) would open a
 * page that cannot play. TrueVisions NOW has no web player at all.
 *
 * The [StreamingGroup.CHINESE] group is the exception, added at the driver's request: those sites
 * serve free and protected video alike and some are limited by region, so some of what they show
 * may not play here. The screens say so beside them rather than leaving a black video unexplained.
 *
 * Movies is deliberately just the Internet Archive for now: it is public-domain and plays from a
 * plain `<video>` tag, so it is the one "watch a movie" site that is both unambiguously legal and
 * known to work in a WebView without Widevine. A free ad-supported service (Tubi, Pluto TV, …)
 * would round this out, but whether its catalog plays without DRM in this WebView is unverified -
 * add it once that is confirmed, rather than listing a site that may just show a black screen.
 */
object StreamingLinks {
    val all: List<StreamingLink> = listOf(
        StreamingLink("YouTube", StreamingGroup.VIDEO, "https://m.youtube.com"),
        StreamingLink("TikTok", StreamingGroup.VIDEO, "https://www.tiktok.com"),
        StreamingLink("Facebook Watch", StreamingGroup.VIDEO, "https://m.facebook.com/watch"),
        StreamingLink("Dailymotion", StreamingGroup.VIDEO, "https://www.dailymotion.com"),
        StreamingLink("Vimeo", StreamingGroup.VIDEO, "https://vimeo.com"),
        StreamingLink("Tencent Video", StreamingGroup.CHINESE, "https://v.qq.com"),
        StreamingLink("WeTV", StreamingGroup.CHINESE, "https://wetv.vip"),
        StreamingLink("iQIYI", StreamingGroup.CHINESE, "https://www.iq.com"),
        StreamingLink("Youku", StreamingGroup.CHINESE, "https://www.youku.com"),
        StreamingLink("Mango TV", StreamingGroup.CHINESE, "https://www.mgtv.com"),
        StreamingLink("CCTV", StreamingGroup.CHINESE, "https://tv.cctv.com"),
        StreamingLink("Douyin", StreamingGroup.CHINESE, "https://www.douyin.com"),
        StreamingLink("Xiaohongshu", StreamingGroup.CHINESE, "https://www.xiaohongshu.com"),
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

    /** True for the group whose sites may not play here (protected video, or limited by region). */
    fun mayNotPlay(link: StreamingLink): Boolean = link.group == StreamingGroup.CHINESE

    /** [all] split by group, in [StreamingGroup] order, skipping empty groups. */
    fun grouped(): List<Pair<StreamingGroup, List<StreamingLink>>> =
        StreamingGroup.entries.mapNotNull { group ->
            all.filter { it.group == group }.takeIf { it.isNotEmpty() }?.let { group to it }
        }
}
