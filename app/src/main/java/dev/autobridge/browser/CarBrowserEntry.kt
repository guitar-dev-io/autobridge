package dev.autobridge.browser

import java.net.URI

/**
 * Whether a car entry point should re-open the browser as it stands, or navigate.
 *
 * ## The bug this fixes
 *
 * Every site tile on the car home — YouTube, YouTube Music, the Web shortcuts, the streaming list —
 * used to call `renderer.load(url)` unconditionally before opening the browser screen. So the
 * sequence "YouTube → play → Home → YouTube" did not return to the video: it loaded
 * `m.youtube.com` over the top of it, which threw the page away and started playback over.
 *
 * Nothing was wrong with how the page was *kept*. [CarBrowserRuntime] holds the renderer across
 * screens on purpose and the page survives the trip to Home intact ("Surface re-attached, keeping
 * current page"). The state was being discarded on the way back *in*.
 *
 * ## The rule
 *
 * Tapping a site tile means "take me to that site", and when the browser is already on it, the
 * honest answer is to show it as it is — the same thing tapping an app icon on a phone does. A tile
 * carries a bare site root, so that is exactly what this recognises:
 *
 * - the target must be a **site root** (no path, query or fragment). A link to a specific page —
 *   a bookmark, a quick-launch shortcut, an IPTV channel — is a request for *that page* and must
 *   always load, which is why those callers do not consult this at all.
 * - the browser must already have a **live page on the same site**.
 *
 * `m.youtube.com`, `www.youtube.com` and `youtube.com` count as one site: they are the mobile and
 * desktop front ends of the same place, and which one is loaded depends on the user-agent identity
 * in effect ([BrowserUserAgentStore]) rather than on anything the driver chose. Only the `m.` and
 * `www.` prefixes are treated this way, so `music.youtube.com` stays a site of its own and the
 * YouTube Music tile still switches to it from a YouTube video.
 *
 * Pure and free of Android types so it is covered by a plain JVM test, like [BrowserInputResolver]
 * and [BrowserResumePoint].
 */
object CarBrowserEntry {
    /**
     * True when the browser should simply be re-opened on [current] instead of loading [target].
     *
     * @param current what a live WebView is showing right now — [CarWebRenderer.livePageUrl], which
     *   is null when no page is loaded yet. A remembered URL from a previous session must not be
     *   passed here: there is no page to go back to, so the site still has to be loaded.
     */
    fun resumes(current: String?, target: String): Boolean {
        if (current.isNullOrBlank()) return false
        if (!isSiteRoot(target)) return false
        val here = site(current) ?: return false
        return here == site(target)
    }

    /** A bare `https://host` or `https://host/`: the shape a site tile carries. */
    private fun isSiteRoot(url: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        if (uri.rawQuery != null || uri.rawFragment != null) return false
        return uri.rawPath.isNullOrEmpty() || uri.rawPath == "/"
    }

    /** The host with a leading `m.` or `www.` dropped, or null when [url] has no host. */
    private fun site(url: String): String? {
        val host = runCatching { URI(url).host }.getOrNull()?.lowercase() ?: return null
        for (prefix in listOf("m.", "www.")) {
            if (host.startsWith(prefix) && host.length > prefix.length) return host.removePrefix(prefix)
        }
        return host
    }
}
