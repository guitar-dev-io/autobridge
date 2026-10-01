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

    /** Resumes media tagged by [markPlayingForResume] or by a focus pause. */
    fun resumeMarked() = resumeAll()

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
        evaluate(
            """
            (function(){
              var playing = false, dur = 0, pos = 0;
              document.querySelectorAll('audio,video').forEach(function(m){
                if (!m.paused && !m.ended) {
                  playing = true;
                  if (isFinite(m.duration)) dur = m.duration;
                  pos = m.currentTime;
                }
              });
              var md = (navigator.mediaSession && navigator.mediaSession.metadata) || null;
              return JSON.stringify({
                playing: playing,
                duration: Math.round(dur * 1000),
                position: Math.round(pos * 1000),
                title: md ? md.title : '',
                artist: md ? md.artist : '',
                album: md ? md.album : '',
                artwork: (md && md.artwork && md.artwork.length) ? md.artwork[0].src : ''
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
    val durationMs: Long = 0,
    val positionMs: Long = 0,
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val artworkUrl: String = "",
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
                durationMs = number(text, "duration"),
                positionMs = number(text, "position"),
                title = string(text, "title"),
                artist = string(text, "artist"),
                album = string(text, "album"),
                artworkUrl = string(text, "artwork"),
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
