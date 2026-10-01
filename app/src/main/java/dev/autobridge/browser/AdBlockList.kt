package dev.autobridge.browser

/**
 * Decides whether a single request is an advertising or tracking request the browser may drop.
 *
 * Pure string work with no Android and no network, so the part that can break a page — which host
 * is matched, and how — is asserted in a JVM test rather than by loading sites on a head unit.
 *
 * ## What this can and cannot do
 *
 * Dropping requests removes banner and interstitial ads, the scripts that place them, and the
 * beacons that measure them. It does **not** remove YouTube's pre-roll and mid-roll video ads:
 * those are served from `googlevideo.com`, the same host as the video the user asked for, and their
 * placement arrives inside the `/youtubei/v1/player` response alongside the video's own metadata.
 * There is no host or path that separates the two, so the in-video case is handled by
 * [dev.autobridge.youtube.YouTubeAdSkip] inside the page instead.
 *
 * ## Why the match is exact-or-subdomain and never a substring
 *
 * `host.endsWith("doubleclick.net")` also matches `notdoubleclick.net`, and a substring test on the
 * whole URL matches `https://evil.example/?ref=doubleclick.net`. Both hand an attacker a way to get
 * a request dropped, and the second silently breaks ordinary pages whose query strings mention an
 * ad network. [blocks] therefore parses the host out and compares it either exactly or against
 * `".$rule"`, the same rule [dev.autobridge.youtube.YouTubeUrls] uses for its front ends.
 */
object AdBlockList {

    /**
     * Hosts whose entire purpose is advertising, tracking or audience measurement. A rule matches
     * the host itself and any subdomain of it.
     *
     * Deliberately absent, because dropping them breaks something the user asked for:
     *
     *  - `googlevideo.com` — the video and audio streams themselves, ads and content alike.
     *  - `accounts.google.com`, `apis.google.com`, `gstatic.com` — sign-in, which this browser goes
     *    to some length to keep working (see [BrowserDefaults.isSignInPopupHost]).
     *  - `ytimg.com`, `ggpht.com` — thumbnails and avatars.
     *  - `connect.facebook.net` — carries "Sign in with Facebook" as well as the tracking pixel.
     */
    private val HOSTS = setOf(
        // Google's ad stack. Serving, bidding and the creative CDN.
        "doubleclick.net",
        "googlesyndication.com",
        "googleadservices.com",
        "googletagservices.com",
        "adservice.google.com",
        "2mdn.net",
        "app-measurement.com",
        // Google's analytics stack. Measurement only; no page depends on a reply.
        "google-analytics.com",
        "googletagmanager.com",
        "analytics.google.com",
        // YouTube's own ad hosts, which are not the front ends that serve the watch page.
        "ads.youtube.com",
        "ad.youtube.com",
        // Exchanges and demand-side platforms.
        "adnxs.com",
        "adsrvr.org",
        "pubmatic.com",
        "rubiconproject.com",
        "openx.net",
        "casalemedia.com",
        "smartadserver.com",
        "adform.net",
        "serving-sys.com",
        "amazon-adsystem.com",
        "media.net",
        "criteo.com",
        "criteo.net",
        "zedo.com",
        "teads.tv",
        "sharethrough.com",
        // Content recommendation widgets, which are paid placements.
        "taboola.com",
        "outbrain.com",
        "zergnet.com",
        // Audience measurement and profile brokers.
        "scorecardresearch.com",
        "quantserve.com",
        "quantcount.com",
        "moatads.com",
        "demdex.net",
        "bluekai.com",
        "rlcdn.com",
        "crwdcntrl.net",
        "adsafeprotected.com",
        "omnitagjs.com",
        "hotjar.com",
        "mixpanel.com",
        "branch.io"
    )

    /**
     * Path prefixes that are ads on hosts this list cannot block outright.
     *
     * YouTube serves its ad placement and ad-measurement endpoints from the same hosts as the watch
     * page, so these are matched by path and only on those hosts. Everything else under
     * `youtube.com` — `/youtubei/v1/player` above all, which carries the video's streams — is left
     * alone, because a dropped reply there means no playback at all.
     */
    private val YOUTUBE_AD_PATHS = listOf(
        "/pagead/",
        "/api/stats/ads",
        "/ptracking",
        "/pcs/activeview",
        "/get_midroll_info",
        "/get_video_ads"
    )

    /** The hosts [YOUTUBE_AD_PATHS] applies to, matched as host-or-subdomain like [HOSTS]. */
    private val YOUTUBE_HOSTS = setOf("youtube.com", "youtube-nocookie.com", "youtu.be")

    /**
     * True when [url] may be dropped.
     *
     * Callers must not apply this to a main-frame navigation: a page the user typed or tapped is a
     * page they asked for, and answering it with an empty document would read as a broken browser
     * rather than as a blocked ad.
     */
    fun blocks(url: String): Boolean {
        val host = host(url) ?: return false
        if (HOSTS.any { matches(host, it) }) return true
        if (YOUTUBE_HOSTS.any { matches(host, it) }) {
            val path = path(url)
            return YOUTUBE_AD_PATHS.any { path.startsWith(it) }
        }
        return false
    }

    /** The host itself, or a subdomain of it. `ads.doubleclick.net` yes, `notdoubleclick.net` no. */
    private fun matches(host: String, rule: String): Boolean =
        host == rule || host.endsWith(".$rule")

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
        return "/" + afterHost.substringBefore('?').substringBefore('#')
    }
}
