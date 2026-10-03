package dev.autobridge.audio

import android.webkit.WebView
import dev.autobridge.display.StructuredLog

/** What the page is doing with sound, as far as the native layer can tell. */
enum class WebAudioState { IDLE, PLAYING, PAUSED }

/**
 * Connects a [WebView]'s `<audio>`/`<video>` playback to the native audio layer.
 *
 * ## Why this is a bridge and not a JavaScript interface
 *
 * The browser loads arbitrary sites. Installing a `@JavascriptInterface` object would hand every
 * page that loads a handle on the app's audio focus, volume and media session — a capability no
 * website should have. So the bridge is one-way by default: it *reads* media state and *applies*
 * commands through scripts this app authored, and it never exposes a callable native object to page
 * script. Pages are told nothing; they are acted upon.
 *
 * The scripts touch only `document.querySelectorAll('audio,video')`, take no input from the page,
 * and are the same fixed strings every time, so there is no injection surface.
 */
class WebAudioBridge(private val webViewProvider: () -> WebView?) {

    private companion object {
        /** Records when any media element starts playing. Fixed string, takes nothing from the page. */
        const val PLAY_TRACKING_SCRIPT = """
            (function(){
              if (window.__abPlayHook) return 0;
              window.__abPlayHook = 1;
              var mark = function(){ window.__abLastPlayAt = Date.now(); };
              document.addEventListener('play', mark, true);
              document.addEventListener('playing', mark, true);
              return 1;
            })();
        """
    }

    var state: WebAudioState = WebAudioState.IDLE
        private set

    /** Volume the page was at before ducking, so it can be restored exactly. */
    private var volumeBeforeDuck: Double? = null

    /** Applies a focus decision to whatever the page is playing. */
    fun apply(action: AudioFocusAction) {
        when (action) {
            AudioFocusAction.PAUSE -> pauseAll()
            AudioFocusAction.PLAY -> resumeAll()
            AudioFocusAction.DUCK -> duck()
            AudioFocusAction.UNDUCK -> unduck()
            AudioFocusAction.NOTHING -> Unit
        }
    }

    private fun pauseAll() {
        evaluate(
            """
            (function(){
              var n = 0;
              document.querySelectorAll('audio,video').forEach(function(m){
                if (!m.paused) { m.dataset.abResume = '1'; m.pause(); n++; }
              });
              return n;
            })();
            """.trimIndent()
        ) { StructuredLog.i("AUDIO", "web pause -> $it element(s)") }
        state = WebAudioState.PAUSED
    }

    /** Only resumes what this bridge paused, so a clip the user stopped by hand stays stopped. */
    private fun resumeAll() {
        evaluate(
            """
            (function(){
              var n = 0;
              document.querySelectorAll('audio,video').forEach(function(m){
                if (m.dataset.abResume === '1') { delete m.dataset.abResume; m.play(); n++; }
              });
              return n;
            })();
            """.trimIndent()
        ) { StructuredLog.i("AUDIO", "web resume -> $it element(s)") }
        state = WebAudioState.PLAYING
    }

    /**
     * Tags whatever is playing right now for a later [resumeMarked], without pausing it, and
     * reports whether anything was playing.
     *
     * Used when the car takes its surface away (the rear camera on reverse, a pushed template):
     * the page may be hidden and pause its own media on `visibilitychange`, which [resumeAll]
     * would otherwise never undo because only media this bridge paused carries the tag. Reports
     * false when there is no WebView to ask.
     */
    fun markPlayingForResume(onResult: (Boolean) -> Unit) {
        val view = webViewProvider()
        if (view == null) {
            onResult(false)
            return
        }
        evaluate(
            """
            (function(){
              var n = 0;
              document.querySelectorAll('audio,video').forEach(function(m){
                if (!m.paused && !m.ended) { m.dataset.abResume = '1'; n++; }
              });
              return n;
            })();
            """.trimIndent()
        ) { result ->
            val count = result.toIntOrNull() ?: 0
            StructuredLog.i("AUDIO", "web mark-for-resume -> $count element(s)")
            if (count > 0) state = WebAudioState.PLAYING
            onResult(count > 0)
        }
    }

    /**
     * Reports whether any media element is playing right now, without tagging or changing
     * anything. Always answers, false when there is no WebView, so callers can chain on it.
     */
    fun isAnyPlaying(onResult: (Boolean) -> Unit) {
        if (webViewProvider() == null) {
            onResult(false)
            return
        }
        evaluate(
            """
            (function(){
              var n = 0;
              document.querySelectorAll('audio,video').forEach(function(m){
                if (!m.paused && !m.ended) n++;
              });
              return n;
            })();
            """.trimIndent()
        ) { result -> onResult((result.toIntOrNull() ?: 0) > 0) }
    }

    /** Resumes media tagged by [markPlayingForResume] or by a focus pause. */
    fun resumeMarked() = resumeAll()

    /**
     * Installs the page-side play timestamp used by [msSinceLastPlay]. Idempotent; call it when a
     * page finishes loading. Listens in the capture phase because media events do not bubble.
     */
    fun installPlayTracking() {
        evaluate(PLAY_TRACKING_SCRIPT) { }
    }

    /**
     * Milliseconds since media on the page last started playing, or [Long.MAX_VALUE] when unknown
     * (tracking not installed, no WebView).
     *
     * Why this exists: the WebView's own Chromium media session requests audio focus the moment a
     * page starts playing. Both requests come from this app, so the system hands focus to Chromium
     * and tells this app's controller it has lost focus permanently — which used to pause the page
     * a few milliseconds after the user pressed play. A loss that lands right after a play started
     * on this page is that self-inflicted one, not another app taking over.
     */
    fun msSinceLastPlay(onResult: (Long) -> Unit) {
        if (webViewProvider() == null) {
            onResult(Long.MAX_VALUE)
            return
        }
        evaluate(
            """
            (function(){
              var t = window.__abLastPlayAt || 0;
              return t > 0 ? Math.max(0, Date.now() - t) : -1;
            })();
            """.trimIndent()
        ) { raw ->
            val value = raw.toLongOrNull() ?: -1L
            onResult(if (value < 0) Long.MAX_VALUE else value)
        }
    }

    /**
     * A pause the user asked for (car media card, steering wheel). Unlike a focus pause it clears
     * the focus tag, so a later focus gain does not restart what the user stopped, and tags the
     * element for [userPlay] instead.
     */
    fun userPause() {
        evaluate(
            """
            (function(){
              var n = 0;
              document.querySelectorAll('audio,video').forEach(function(m){
                if (!m.paused) { delete m.dataset.abResume; m.dataset.abUser = '1'; m.pause(); n++; }
              });
              return n;
            })();
            """.trimIndent()
        ) { StructuredLog.i("AUDIO", "web user pause -> $it element(s)") }
        state = WebAudioState.PAUSED
    }

    /**
     * A play the user asked for: resumes what [userPause] stopped, or else the first element that
     * has a source and has not ended. The returned promise is swallowed so a rejected play (no
     * source yet) does not surface as an unhandled rejection on the page.
     */
    fun userPlay() {
        evaluate(
            """
            (function(){
              var all = Array.prototype.slice.call(document.querySelectorAll('audio,video'));
              var targets = all.filter(function(m){ return m.dataset.abUser === '1'; });
              if (!targets.length) {
                targets = all.filter(function(m){ return m.currentSrc && !m.ended; }).slice(0, 1);
              }
              targets.forEach(function(m){
                delete m.dataset.abUser;
                var p = m.play();
                if (p && p.catch) p.catch(function(){});
              });
              return targets.length;
            })();
            """.trimIndent()
        ) { StructuredLog.i("AUDIO", "web user play -> $it element(s)") }
        state = WebAudioState.PLAYING
    }

    /**
     * Seeks the element that is playing (or, failing that, the first one with a duration). The
     * only value placed in the script is a number this app computed, so it stays a fixed shape.
     */
    fun seekTo(positionMs: Long) {
        val seconds = positionMs.coerceAtLeast(0L) / 1000.0
        evaluate(
            """
            (function(){
              var all = Array.prototype.slice.call(document.querySelectorAll('audio,video'));
              var m = all.filter(function(x){ return !x.paused && !x.ended; })[0] ||
                      all.filter(function(x){ return isFinite(x.duration) && x.duration > 0; })[0];
              if (!m) return 0;
              m.currentTime = $seconds;
              return 1;
            })();
            """.trimIndent()
        ) { StructuredLog.i("AUDIO", "web seek ${positionMs}ms -> $it element(s)") }
    }

    private fun duck() {
        evaluate(
            """
            (function(){
              var v = null;
              document.querySelectorAll('audio,video').forEach(function(m){
                if (v === null) v = m.volume;
                if (m.dataset.abDuck === undefined) { m.dataset.abDuck = String(m.volume); }
                m.volume = Math.min(m.volume, 0.2);
              });
              return v === null ? -1 : v;
            })();
            """.trimIndent()
        ) { result ->
            volumeBeforeDuck = result.toDoubleOrNull()?.takeIf { it >= 0 }
            StructuredLog.i("AUDIO", "web duck from volume=$volumeBeforeDuck")
        }
    }

    private fun unduck() {
        evaluate(
            """
            (function(){
              var n = 0;
              document.querySelectorAll('audio,video').forEach(function(m){
                if (m.dataset.abDuck !== undefined) {
                  m.volume = parseFloat(m.dataset.abDuck);
                  delete m.dataset.abDuck;
                  n++;
                }
              });
              return n;
            })();
            """.trimIndent()
        ) { StructuredLog.i("AUDIO", "web unduck -> $it element(s)") }
        volumeBeforeDuck = null
    }

    /**
     * Reads what the page is playing: state plus whatever metadata it exposes through the Media
     * Session API, which is what a site fills in for the system's own media controls.
     */
    fun readState(onResult: (WebMediaStatus) -> Unit) {
        // The tracking hook rides along, so a page loaded before it existed still gets it on the
        // next read. The state object below stays the script's last value, which is what returns.
        evaluate(
            PLAY_TRACKING_SCRIPT.trimIndent() + "\n" +
            """
            (function(){
              var playing = false, dur = 0, pos = 0, held = null, finished = null;
              document.querySelectorAll('audio,video').forEach(function(m){
                if (!m.paused && !m.ended) {
                  playing = true;
                  if (isFinite(m.duration)) dur = m.duration;
                  pos = m.currentTime;
                } else if (held === null && m.currentSrc && !m.ended) {
                  held = m;
                } else if (finished === null && m.currentSrc && m.ended) {
                  // What "the clip finished" means, straight from the element. Read rather than
                  // inferred from position vs duration, which is wrong for a stream and for a page
                  // that reports a duration it has not loaded yet.
                  finished = m;
                }
              });
              // Nothing playing: report where the paused element stands, so a paused card still
              // shows the right position instead of jumping to zero.
              if (!playing && held !== null) {
                if (isFinite(held.duration)) dur = held.duration;
                pos = held.currentTime;
              }
              var md = (navigator.mediaSession && navigator.mediaSession.metadata) || null;
              return JSON.stringify({
                playing: playing,
                // Only while nothing else is going: an element that ended beside one still playing
                // (an ad that handed over to the feature, a second player on the page) has not
                // finished anything the listener would call finished.
                ended: !playing && finished !== null,
                duration: Math.round(dur * 1000),
                position: Math.round(pos * 1000),
                title: md ? md.title : '',
                artist: md ? md.artist : '',
                album: md ? md.album : '',
                artwork: (md && md.artwork && md.artwork.length) ? md.artwork[0].src : '',
                pageTitle: document.title || ''
              });
            })();
            """.trimIndent()
        ) { raw ->
            val status = WebMediaStatus.parse(raw)
            state = if (status.playing) WebAudioState.PLAYING else WebAudioState.PAUSED
            onResult(status)
        }
    }

    private fun evaluate(script: String, onResult: (String) -> Unit) {
        val view = webViewProvider() ?: return
        runCatching {
            view.evaluateJavascript(script) { value -> onResult(unquote(value.orEmpty())) }
        }
    }

    private fun unquote(value: String): String {
        if (value.length >= 2 && value.startsWith('"') && value.endsWith('"')) {
            return value.substring(1, value.length - 1)
                .replace("\\\"", "\"")
                .replace("\\\\", "\\")
        }
        return value
    }
}

/** Media state read back out of a page, in native terms. */
data class WebMediaStatus(
    val playing: Boolean = false,
    /**
     * The page had something playing and it reached its end. The signal
     * [dev.autobridge.browser.BrowserPlayQueue] advances on; see [WebAudioBridge.readState] for why
     * it comes from the element rather than from comparing position with duration.
     */
    val ended: Boolean = false,
    val durationMs: Long = 0,
    val positionMs: Long = 0,
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val artworkUrl: String = "",
    /** `document.title`, the fallback name for sites that publish no Media Session metadata. */
    val pageTitle: String = "",
) {
    val hasMetadata: Boolean get() = title.isNotBlank() || artist.isNotBlank()

    companion object {
        /**
         * Parses the JSON the page script returns.
         *
         * Hand-rolled rather than `org.json` so the parsing is exercised by a plain JVM test; the
         * shape is fixed by the script above, and anything that does not match yields an empty
         * status rather than throwing inside the WebView's evaluate callback.
         */
        fun parse(raw: String): WebMediaStatus {
            val text = raw.trim()
            if (!text.startsWith("{") || !text.endsWith("}")) return WebMediaStatus()
            return WebMediaStatus(
                playing = boolean(text, "playing"),
                ended = boolean(text, "ended"),
                durationMs = number(text, "duration"),
                positionMs = number(text, "position"),
                title = string(text, "title"),
                artist = string(text, "artist"),
                album = string(text, "album"),
                artworkUrl = string(text, "artwork"),
                pageTitle = string(text, "pageTitle"),
            )
        }

        private fun boolean(json: String, key: String): Boolean =
            Regex("\"" + key + "\"\\s*:\\s*(true|false)").find(json)?.groupValues?.get(1) == "true"

        /** Times are clamped at zero: a page can report a negative or NaN-derived value. */
        private fun number(json: String, key: String): Long =
            Regex("\"" + key + "\"\\s*:\\s*(-?\\d+)").find(json)
                ?.groupValues?.get(1)?.toLongOrNull()?.coerceAtLeast(0L) ?: 0L

        private fun string(json: String, key: String): String {
            val match = Regex("\"" + key + "\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").find(json)
                ?: return ""
            return match.groupValues[1].replace("\\\"", "\"").replace("\\\\", "\\")
        }
    }
}
