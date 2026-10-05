package dev.autobridge.youtube

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import dev.autobridge.logging.StructuredLog
import java.util.concurrent.Executors

/**
 * Applies the YouTube add-ons to whatever a [WebView] is currently showing.
 *
 * One instance per WebView. Both browser surfaces — the phone [dev.autobridge.browser.BrowserActivity]
 * and the car [dev.autobridge.browser.CarWebRenderer] — call [onPageChanged] from their
 * `WebViewClient`, so the behaviour is identical in the car and in the hand.
 *
 * ## Why this is driven by URL changes and not by page loads alone
 *
 * YouTube is a single-page app: moving from one video to the next rewrites the address with
 * `pushState` and never fires `onPageFinished`. Hooking only page loads meant the first video of a
 * session was handled and every video after it was not. `doUpdateVisitedHistory` does fire for
 * those in-page navigations, and this class ignores a repeat of the id it already armed, so both
 * callbacks can be wired to it without doing the work twice.
 */
class YouTubeEnhancer(private val context: Context) {

    private val main = Handler(Looper.getMainLooper())

    /** One thread: lookups are small, and ordering them keeps a fast scroll from fanning out. */
    private val lookups = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "sponsorblock-lookup").apply { isDaemon = true }
    }

    private var armedVideoId: String? = null
    private var qualityAppliedTo: String? = null
    private var adSkipArmed = false

    /**
     * Call from `onPageFinished` and `doUpdateVisitedHistory`.
     *
     * [view] is read and written only on the main thread; the network part happens on [lookups].
     */
    fun onPageChanged(view: WebView, url: String) {
        applyAdSkip(view, url)

        val videoId = YouTubeUrls.videoId(url)
        if (videoId == null) {
            armedVideoId = null
            qualityAppliedTo = null
            return
        }

        if (YouTubeSettings.autoHighestQuality(context) && qualityAppliedTo != videoId) {
            qualityAppliedTo = videoId
            // The player is built after the document settles; a single delayed attempt is enough
            // in practice and costs nothing when it is early — the script reports 'no-player' and
            // the next navigation tries again.
            main.postDelayed({
                evaluate(view, SponsorBlock.highestQualityScript()) { result ->
                    StructuredLog.i("YOUTUBE", "auto quality -> $result")
                }
            }, PLAYER_SETTLE_MS)
        }

        val categories = YouTubeSettings.enabledCategories(context)
        if (categories.isEmpty()) {
            if (armedVideoId != null) {
                armedVideoId = null
                evaluate(view, SponsorBlock.clearScript()) {}
            }
            return
        }
        if (armedVideoId == videoId) return
        armedVideoId = videoId

        SponsorBlockClient.cached(videoId, categories)?.let { cached ->
            arm(view, videoId, cached)
            return
        }
        lookups.execute {
            val segments = SponsorBlockClient.segments(videoId, categories)
            main.post {
                // The user may have navigated on while the lookup was in flight; arming then
                // would skip parts of a different video.
                if (armedVideoId == videoId) arm(view, videoId, segments)
            }
        }
    }

    /**
     * Arms or disarms [YouTubeAdSkip] for whatever page [view] is on.
     *
     * Unlike SponsorBlock this is per-document, not per-video: one armed poll covers every video
     * reached by an in-page navigation afterwards, and the script's own guard makes the repeat call
     * on each navigation cheap. It is also armed on pages that are not a single video — the feed and
     * search results carry ad slots of their own — so it runs before the [YouTubeUrls.videoId] check
     * rather than after it.
     *
     * Leaving YouTube for another site needs no clear: the document goes, and the timer with it.
     * [adSkipArmed] exists only for the case that matters, which is the setting being switched off
     * while a YouTube page is still open.
     */
    private fun applyAdSkip(view: WebView, url: String) {
        if (YouTubeSettings.adSkipEnabled(context) && YouTubeUrls.isYouTube(url)) {
            adSkipArmed = true
            evaluate(view, YouTubeAdSkip.script()) { result ->
                StructuredLog.i("YOUTUBE", "ad skip -> $result")
            }
        } else if (adSkipArmed) {
            adSkipArmed = false
            evaluate(view, YouTubeAdSkip.clearScript()) {}
        }
    }

    /** Drops the executor. The enhancer cannot be used afterwards. */
    fun release() {
        lookups.shutdownNow()
    }

    private fun arm(view: WebView, videoId: String, segments: List<SponsorSegment>) {
        if (segments.isEmpty()) {
            StructuredLog.i("YOUTUBE", "SponsorBlock: no segments")
            return
        }
        evaluate(view, SponsorBlock.script(videoId, segments)) { result ->
            StructuredLog.i("YOUTUBE", "SponsorBlock ${segments.size} segment(s) -> $result")
        }
    }

    private fun evaluate(view: WebView, script: String, onResult: (String?) -> Unit) {
        runCatching { view.evaluateJavascript(script) { onResult(it) } }
            .onFailure { StructuredLog.w("YOUTUBE", "script failed: ${it.message}") }
    }

    private companion object {
        const val PLAYER_SETTLE_MS = 1_200L
    }
}
