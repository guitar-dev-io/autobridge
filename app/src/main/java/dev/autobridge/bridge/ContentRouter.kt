package dev.autobridge.bridge

import java.net.URI
import java.util.Locale

/**
 * Decides which [dev.autobridge.bridge.engine.PlaybackEngine] a source should go to.
 *
 * ```text
 * URL
 *  ├─ protected by DRM?            → UNSUPPORTED (reported as DRM_PROTECTED_CONTENT)
 *  ├─ direct playable media URL?   → NATIVE
 *  ├─ a page the browser renders?  → BROWSER
 *  ├─ remote stream host set up?   → REMOTE_STREAM
 *  └─ otherwise                    → UNSUPPORTED
 * ```
 *
 * Pure, and deliberately so: "why did this link open in the browser instead of the player" is the
 * question the bridge gets asked most, and a pure decision function means the answer is a unit
 * test rather than a DHU session. [explain] returns the same decision with its reason attached,
 * which is what the logs and the diagnostics screen print.
 *
 * ## The DRM branch comes first, on purpose
 *
 * Netflix, Disney+, Prime Video and the rest serve Widevine-protected streams. They are perfectly
 * ordinary web pages, so the browser branch would happily accept them, and the remote-stream
 * branch would accept them after that — which is exactly the shape of a DRM workaround. Routing
 * them anywhere but UNSUPPORTED would make this app a circumvention tool, so the check sits above
 * every branch that could serve one, and the user is told the content is protected instead of
 * being handed a black rectangle with audio. Nothing here strips, bypasses or downgrades
 * protection, and the remote-stream path is specifically closed to these hosts
 * (see [isProtected]).
 */
object ContentRouter {

    /** Containers and manifests ExoPlayer opens directly, with no page around them. */
    private val DIRECT_MEDIA_EXTENSIONS = setOf(
        "mp4", "m4v", "webm", "mkv", "mov", "3gp", "ts", "avi",
        "mp3", "m4a", "aac", "wav", "ogg", "oga", "opus", "flac",
        "m3u8", "mpd"
    )

    /**
     * Hosts whose video is Widevine/FairPlay protected.
     *
     * Matched on the registrable suffix so `www.netflix.com` and `netflix.com` both hit. The list
     * is deliberately short and explicit rather than a heuristic: wrongly calling something DRM
     * blocks a link that would have worked, and a heuristic for "is this DRM" does not exist.
     */
    private val PROTECTED_HOSTS = setOf(
        "netflix.com",
        "disneyplus.com",
        "hotstar.com",
        "primevideo.com",
        "hbomax.com",
        "max.com",
        "appletv.com",
        "tv.apple.com",
        "peacocktv.com",
        "hulu.com",
        "paramountplus.com",
        "viu.com",
        "trueid.net",
        "monomax.me"
    )

    /** Schemes the browser engine will render. Everything else is not a web page. */
    private val WEB_SCHEMES = setOf("http", "https")

    /** A routing decision together with the reason it was reached. */
    data class Decision(
        val engine: EngineKind,
        val reason: Reason,
        /** Set when the decision is UNSUPPORTED, so the caller can report the right error. */
        val error: BridgeErrorType? = null
    )

    enum class Reason {
        DRM_PROTECTED,
        DIRECT_MEDIA,
        WEB_PAGE,
        REMOTE_STREAM_HOST,
        NO_ENGINE,
        MALFORMED
    }

    /**
     * The engine [source] should open on.
     *
     * @param remoteStreamAvailable whether a remote stream host is configured AND enabled. When it
     *   is false the remote branch is skipped entirely, which is what makes "attempts
     *   RemoteStreamPlaybackEngine only when a stream host is configured" true by construction
     *   rather than by a check at the call site.
     */
    fun route(source: BridgeSource, remoteStreamAvailable: Boolean): EngineKind =
        explain(source, remoteStreamAvailable).engine

    /** [route] with the reason and, for a refusal, the error to report. */
    fun explain(source: BridgeSource, remoteStreamAvailable: Boolean): Decision {
        val url = source.url.trim()
        val uri = runCatching { URI(url) }.getOrNull()
        val scheme = uri?.scheme?.lowercase(Locale.ROOT)
        val host = uri?.host?.lowercase(Locale.ROOT)

        if (uri == null || scheme == null) {
            return Decision(EngineKind.UNSUPPORTED, Reason.MALFORMED, BridgeErrorType.UNSUPPORTED_CONTENT)
        }

        if (host != null && isProtected(host)) {
            return Decision(
                EngineKind.UNSUPPORTED,
                Reason.DRM_PROTECTED,
                BridgeErrorType.DRM_PROTECTED_CONTENT
            )
        }

        if (isDirectMedia(url, source.mimeType)) {
            return Decision(EngineKind.NATIVE, Reason.DIRECT_MEDIA)
        }

        if (scheme in WEB_SCHEMES && !host.isNullOrBlank()) {
            return Decision(EngineKind.BROWSER, Reason.WEB_PAGE)
        }

        if (remoteStreamAvailable) {
            return Decision(EngineKind.REMOTE_STREAM, Reason.REMOTE_STREAM_HOST)
        }

        return Decision(EngineKind.UNSUPPORTED, Reason.NO_ENGINE, BridgeErrorType.UNSUPPORTED_CONTENT)
    }

    /**
     * The fallback engine to try when [current] could not render [source] after all.
     *
     * Routing by URL is a prediction; a page that turns out to render nothing useful on the car
     * only reveals that at runtime. This is the step down that prediction: browser → remote
     * stream, and nothing below that. Native never falls back to the browser, because a direct
     * media URL that ExoPlayer refused is a broken or unsupported file, not a page.
     *
     * A protected source never gets a fallback at all — the `null` here is the second place the
     * DRM boundary is enforced, so a browser failure on a protected page cannot quietly become a
     * remote-stream render of it.
     */
    fun fallback(
        source: BridgeSource,
        current: EngineKind,
        remoteStreamAvailable: Boolean
    ): EngineKind? {
        val host = runCatching { URI(source.url).host }.getOrNull()?.lowercase(Locale.ROOT)
        if (host != null && isProtected(host)) return null
        return when {
            current == EngineKind.BROWSER && remoteStreamAvailable -> EngineKind.REMOTE_STREAM
            else -> null
        }
    }

    /** True when [url]'s path names a container or manifest ExoPlayer opens on its own. */
    fun isDirectMedia(url: String, mimeType: String? = null): Boolean {
        val mime = mimeType?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT)
        if (mime != null && (mime.startsWith("video/") || mime.startsWith("audio/"))) return true
        if (mime == "application/x-mpegurl" || mime == "application/vnd.apple.mpegurl") return true
        if (mime == "application/dash+xml") return true

        val path = url.substringBefore('#').substringBefore('?').lowercase(Locale.ROOT)
        val extension = path.substringAfterLast('.', missingDelimiterValue = "")
        return extension in DIRECT_MEDIA_EXTENSIONS
    }

    /** True when [host] (or a parent domain of it) serves DRM-protected video. */
    fun isProtected(host: String): Boolean {
        val clean = host.lowercase(Locale.ROOT).removeSuffix(".")
        return PROTECTED_HOSTS.any { clean == it || clean.endsWith(".$it") }
    }
}
