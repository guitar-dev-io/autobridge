package dev.autobridge.browser

import android.util.Log
import android.webkit.WebView
import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import java.util.WeakHashMap

/**
 * Keeps page audio running after the car takes the browser's screen away.
 *
 * ## The problem this solves
 *
 * The native side already does its part. When the car surface detaches — a template pushed over the
 * browser, the driver going back to the dashboard, the rear camera on reverse —
 * [CarWebRenderer.stop] deliberately keeps audio focus and does not call `WebView.onPause()` on a
 * pane that is playing; it only tags the media so it can be resumed later. And yet leaving the
 * browser screen stopped the music.
 *
 * The page stops itself. With no surface the window is no longer visible, so `document.hidden`
 * becomes true, `visibilitychange` fires, and every serious media site pauses on that event —
 * YouTube included. The native resume ([dev.autobridge.audio.WebAudioBridge.resumeMarked]) only
 * runs when the surface comes back, so the rear camera case recovered within seconds while "back to
 * Home" stayed silent for as long as the driver stayed away: nothing was coming back to resume it.
 *
 * ## What this does
 *
 * Does not let the page find out. A document-start script pins the Page Visibility API to "visible"
 * and swallows the event, so the site's own pause handler never runs and playback simply continues.
 * The same approach a dedicated car browser takes, and the reason Fermata's WebView keeps playing.
 *
 * Installed through [WebViewCompat.addDocumentStartJavaScript] like its sibling [DesktopSiteMode],
 * so the override is in place before any page script runs, in every frame. Always on: a browser on
 * a car screen exists to put media on it, and a page that pauses the moment the driver looks at the
 * map is not useful on any of this app's surfaces. The phone browser is unaffected in practice
 * because `BrowserActivity.onPause` pauses its WebView outright when it goes to the background.
 *
 * ## What it does not touch
 *
 * - **`pagehide`/`unload`** belong to real navigation, where a page genuinely is going away and its
 *   cleanup should run. Only the visibility events are suppressed.
 * - **`blur`** is focus, not visibility, and the browser's own keyboard and address bar move focus
 *   around constantly; blocking it would break more than it fixes.
 * - **Video** stops regardless — there is no surface to decode onto, which is the whole reason the
 *   page was hidden. Audio is what survives, which is what "keep the music playing" needs.
 * - **Chromium's own suspension** of a hidden renderer, if the WebView build does it, is below
 *   anything page script can reach.
 */
object BackgroundPlaybackMode {
    private const val TAG = "AutoBridgeBgAudio"

    private val handlers = WeakHashMap<WebView, ScriptHandler>()

    fun isSupported(): Boolean = WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)

    /**
     * Installs the override on [webView]. Idempotent, so it can run from every
     * [BrowserDefaults.configure] call. Must be called on the UI thread.
     *
     * The document-start registration covers every page from the next navigation on. The page that
     * is already loaded is patched directly as well, so a WebView configured while it is playing
     * does not have to be reloaded to survive the first time the screen is taken away.
     */
    fun install(webView: WebView) {
        if (handlers[webView] == null) {
            // Once per WebView, in the Send log: whether this phone's WebView can pin the page visible at all.
            dev.autobridge.logging.StructuredLog.i("BGAUDIO", "visibility pin supported=${isSupported()}")
        }
        if (isSupported() && handlers[webView] == null) {
            runCatching { WebViewCompat.addDocumentStartJavaScript(webView, SCRIPT, setOf("*")) }
                .onSuccess { handlers[webView] = it }
                .onFailure { Log.w(TAG, "document-start script rejected by this WebView", it) }
        }
        // The script guards on its own marker, so running it again on an already-patched page is a
        // no-op rather than a second set of listeners.
        runCatching { webView.evaluateJavascript(SCRIPT, null) }
    }

    internal val SCRIPT: String = """
(function () {
  if (window.__autobridgeBackgroundAudio) return;
  window.__autobridgeBackgroundAudio = true;

  // Own properties on the document instance, shadowing the real accessors on Document.prototype.
  function pin(name, value) {
    try {
      Object.defineProperty(document, name, { get: function () { return value; }, configurable: true });
    } catch (e) {}
  }
  pin('hidden', false);
  pin('webkitHidden', false);
  pin('visibilityState', 'visible');
  pin('webkitVisibilityState', 'visible');

  // Capture phase on window, registered before any page script runs: window is the first hop of
  // the event path for an event dispatched at document, so this listener is reached before any the
  // page adds on document itself, and before document.onvisibilitychange. Stopping it immediately
  // means the site's pause handler is never called at all.
  ['visibilitychange', 'webkitvisibilitychange'].forEach(function (type) {
    window.addEventListener(type, function (event) { event.stopImmediatePropagation(); }, true);
  });
})();
""".trimIndent()
}
