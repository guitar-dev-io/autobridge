package dev.autobridge.browser

import android.util.Log
import android.webkit.WebView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import java.util.WeakHashMap

/**
 * Keeps the split's side page (a map, by default) from taking the car's audio away from the main
 * page.
 *
 * ## The problem this solves
 *
 * Every WebView runs its own Chromium media session, and each one requests system audio focus the
 * moment its page starts anything audible. With YouTube playing in the main pane and Google Maps
 * opened in the side pane, the map's own media grabbed focus, Chromium paused YouTube on the loss,
 * YouTube came back and grabbed it in turn — the two panes took the audio channel off each other
 * and the music kept cutting out.
 *
 * ## What this does
 *
 * The side pane is the passenger, not the speaker. A document-start script mutes every media
 * element the page creates or plays, and suspends every Web Audio context, so nothing in it ever
 * becomes audible. Chromium does not request audio focus for a muted player — the same rule that
 * lets a muted autoplay video run without stopping the music — so the main page keeps the channel.
 * The caller also turns autoplay off for the side page, so nothing starts there without a tap.
 */
object SidePaneAudio {
    private const val TAG = "AutoBridgeSideAudio"

    private val installed = WeakHashMap<WebView, Boolean>()

    /** Installs the mute on [webView]. Idempotent; must be called on the UI thread. */
    fun install(webView: WebView) {
        webView.settings.mediaPlaybackRequiresUserGesture = true
        if (installed[webView] != true &&
            WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
        ) {
            runCatching { WebViewCompat.addDocumentStartJavaScript(webView, SCRIPT, setOf("*")) }
                .onSuccess { installed[webView] = true }
                .onFailure { Log.w(TAG, "document-start script rejected by this WebView", it) }
        }
        // The page already loaded (if any) is patched directly; the script guards on its own marker.
        runCatching { webView.evaluateJavascript(SCRIPT, null) }
    }

    internal val SCRIPT: String = """
(function () {
  if (window.__autobridgeSideMuted) return;
  window.__autobridgeSideMuted = true;

  function silence(m) {
    try { m.muted = true; m.defaultMuted = true; m.volume = 0; } catch (e) {}
  }

  // Before any page script: every play() is made silent first, so Chromium never sees an audible
  // player and never asks for focus.
  try {
    var play = HTMLMediaElement.prototype.play;
    HTMLMediaElement.prototype.play = function () {
      silence(this);
      return play.apply(this, arguments);
    };
  } catch (e) {}

  // Media events do not bubble, so listen in the capture phase. Catches a page that unmutes an
  // element after starting it.
  ['play', 'playing', 'volumechange'].forEach(function (type) {
    document.addEventListener(type, function (event) {
      var m = event.target;
      if (m && m.tagName && (m.tagName === 'AUDIO' || m.tagName === 'VIDEO') && !m.muted) silence(m);
    }, true);
  });

  // Web Audio has no element to mute: keep every context suspended.
  ['AudioContext', 'webkitAudioContext'].forEach(function (name) {
    var Ctx = window[name];
    if (!Ctx || !Ctx.prototype) return;
    try {
      Ctx.prototype.resume = function () {
        var self = this;
        try { self.suspend(); } catch (e) {}
        return Promise.resolve();
      };
    } catch (e) {}
  });

  try { document.querySelectorAll('audio,video').forEach(silence); } catch (e) {}
})();
""".trimIndent()
}
