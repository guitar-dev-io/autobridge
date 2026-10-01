package dev.autobridge.browser

import java.net.URI

/**
 * Writes a video's playback position into the URL, so "Send to car" hands the car a resume point
 * instead of the top of the video.
 *
 * ## Why the position has to travel in the URL
 *
 * The phone and the car run two separate [android.webkit.WebView]s with two separate pages, and the
 * only thing the handoff carries across is one string — see `BrowserActivity.sendToCar`. Playback
 * position is state inside the phone's `<video>` element, so unless it is written into that string
 * the car loads the page cold and starts at 0:00. YouTube's `t` query parameter is exactly this
 * channel: it is how YouTube's own "copy link at current time" shares a position, and the car's
 * page honours it with no cooperation needed from this app.
 *
 * ## Why only YouTube
 *
 * There is no general web mechanism for "open this page where I left off". `t` is a YouTube
 * convention; other sites either keep the position server-side against the signed-in account or do
 * not keep it at all. So this recognises the watch pages it can actually act on and returns every
 * other URL untouched, rather than appending a parameter that would at best be ignored and at worst
 * confuse a site's own routing.
 *
 * Pure and free of Android types so the URL rewriting is covered by a plain JVM test, the same
 * reason [BrowserInputResolver] is shaped this way.
 */
object BrowserResumePoint {
    /**
     * Below this, resuming and starting over are the same thing to a listener, and a stray second
     * or two in the URL only makes the link look odd.
     */
    private const val MIN_RESUME_MS = 5_000L

    /**
     * How close to the end counts as "finished". Handing the car the last seconds of a video means
     * it opens and immediately stops, so near the end the car starts the video over instead.
     */
    private const val TAIL_MS = 10_000L

    private val YOUTUBE_WATCH_HOSTS =
        setOf("youtube.com", "www.youtube.com", "m.youtube.com", "music.youtube.com")

    /**
     * True when [url] is a page whose position can be carried in the URL at all. Callers check this
     * before paying for a round trip into the page to read the position.
     */
    fun supports(url: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        val host = uri.host?.lowercase() ?: return false
        return when {
            // A short link YouTube has not yet redirected; `t` works the same on it.
            host == "youtu.be" -> uri.rawPath.orEmpty().trim('/').isNotEmpty()
            // Only `/watch`. Shorts and the feed pages ignore `t`, so they are left alone.
            host in YOUTUBE_WATCH_HOSTS ->
                uri.rawPath == "/watch" && uri.rawQuery.orEmpty().split('&').any { it.startsWith("v=") }
            else -> false
        }
    }

    /**
     * [url] with the resume position written in, or [url] unchanged when there is no useful position
     * to carry.
     *
     * @param positionMs where the phone's player stands.
     * @param durationMs the clip's length, or 0 when the page reports none. Zero is the live-stream
     *   and still-loading case: a position on a live edge means nothing on a fresh page load, so the
     *   URL is left alone rather than seeking the car to an arbitrary point in its DVR window.
     */
    fun withResumeAt(url: String, positionMs: Long, durationMs: Long): String {
        if (!supports(url)) return url
        if (positionMs < MIN_RESUME_MS) return url
        if (durationMs <= 0L || positionMs >= durationMs - TAIL_MS) return url
        return withParam(url, "t", (positionMs / 1000L).toString())
    }

    /**
     * `m:ss`, or `h:mm:ss` past an hour — the position as a player would print it, for the message
     * that tells the user where the car is picking up.
     */
    fun clock(positionMs: Long): String {
        val total = (positionMs.coerceAtLeast(0L) / 1000L)
        val hours = total / 3600
        val minutes = (total % 3600) / 60
        val seconds = total % 60
        return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds)
        else "%d:%02d".format(minutes, seconds)
    }

    /**
     * Sets one query parameter, replacing it if the URL already carries it — a video reached through
     * a timestamped link arrives with a `t` of its own, and two of them would leave which one wins
     * up to the page. Everything else about the URL, fragment included, is preserved verbatim:
     * re-encoding a URL that the page itself produced is a good way to break it.
     */
    private fun withParam(url: String, name: String, value: String): String {
        val fragmentAt = url.indexOf('#')
        val base = if (fragmentAt < 0) url else url.substring(0, fragmentAt)
        val fragment = if (fragmentAt < 0) "" else url.substring(fragmentAt)
        val queryAt = base.indexOf('?')
        if (queryAt < 0) return "$base?$name=$value$fragment"
        val kept = base.substring(queryAt + 1)
            .split('&')
            .filter { it.isNotEmpty() && it.substringBefore('=') != name }
        return base.substring(0, queryAt) + "?" + (kept + "$name=$value").joinToString("&") + fragment
    }
}
