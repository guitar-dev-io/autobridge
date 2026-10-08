package dev.autobridge.browser

import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import android.media.AudioManager
import android.util.Log
import android.webkit.WebView
import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import dev.autobridge.bridge.AutoBridgeSessionManager
import dev.autobridge.remote.CarScreenController
import java.util.WeakHashMap

/**
 * Keeps the phone browser from cutting into music the car is already playing.
 *
 * ## The problem
 *
 * With music on the car, searching for the next song in the phone browser (m.youtube.com) starts
 * that video the moment it opens. The page's player asks for audio focus, the car's music pauses,
 * and the phone's video plays through the car speakers instead - while the driver only wanted to
 * find something to send.
 *
 * ## What this does
 *
 * While the car is connected and something is already playing, pages in the phone browser are
 * held silent: a document-start script mutes every media element and suspends Web Audio, the same
 * way [SidePaneAudio] keeps the split's side page quiet. Chromium does not request audio focus for
 * a muted player, so the car's music carries on. The browser shows that sound is held with a way
 * to let it through; [release] does that for the rest of the activity.
 *
 * The script reads `window.__abHold`, baked in when it is registered and flipped live through
 * [apply], so a page already open is muted or unmuted at once and the next document starts in the
 * right state.
 */
object PhoneAudioHold {
    private const val TAG = "AutoBridgePhoneAudio"

    private val handlers = WeakHashMap<WebView, ScriptHandler>()
    private val held = WeakHashMap<WebView, Boolean>()

    /**
     * Whether the car is playing something now. Car mode (Android Auto turns it on while it
     * projects) or a live AutoBridge car session, together with active music output.
     */
    fun carIsPlaying(context: Context): Boolean {
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return false
        if (!audio.isMusicActive) return false
        val uiMode = context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
        val carMode = uiMode?.currentModeType == Configuration.UI_MODE_TYPE_CAR
        return carMode || AutoBridgeSessionManager.current.connected || CarScreenController.host() != null
    }

    /** Holds or releases [webView]'s sound. Idempotent; UI thread only. Returns the state applied. */
    fun apply(webView: WebView, hold: Boolean): Boolean {
        if (held[webView] == hold && (handlers[webView] != null || !documentStartSupported())) return hold
        held[webView] = hold
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            handlers.remove(webView)?.let { runCatching { it.remove() } }
            runCatching { WebViewCompat.addDocumentStartJavaScript(webView, script(hold), setOf("*")) }
                .onSuccess { handlers[webView] = it }
                .onFailure { Log.w(TAG, "document-start script rejected by this WebView", it) }
        }
        // The page already open follows at once.
        runCatching { webView.evaluateJavascript(script(hold) + "\nwindow.__abSetHold && window.__abSetHold($hold);", null) }
        return hold
    }

    private fun documentStartSupported(): Boolean =
        WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)

    internal fun script(hold: Boolean): String = """
(function () {
  if (window.__abSetHold) { window.__abSetHold($hold); return; }
  window.__abHold = $hold;

  function silence(m) {
    try { if (!m.muted) m.__abMuted = true; m.muted = true; } catch (e) {}
  }
  function restore(m) {
    try { if (m.__abMuted) { m.muted = false; m.__abMuted = false; } } catch (e) {}
  }
  function media() {
    try { return document.querySelectorAll('audio,video'); } catch (e) { return []; }
  }

  window.__abSetHold = function (on) {
    window.__abHold = !!on;
    Array.prototype.forEach.call(media(), window.__abHold ? silence : restore);
  };

  // Before any page script: a play() while held is made silent first, so Chromium never sees an
  // audible player and never takes the car's audio focus.
  try {
    var play = HTMLMediaElement.prototype.play;
    HTMLMediaElement.prototype.play = function () {
      if (window.__abHold) silence(this);
      return play.apply(this, arguments);
    };
  } catch (e) {}

  // Media events do not bubble, so listen in the capture phase: catches a page unmuting a player.
  ['play', 'playing', 'volumechange'].forEach(function (type) {
    document.addEventListener(type, function (event) {
      var m = event.target;
      if (window.__abHold && m && m.tagName && (m.tagName === 'AUDIO' || m.tagName === 'VIDEO') && !m.muted) silence(m);
    }, true);
  });

  ['AudioContext', 'webkitAudioContext'].forEach(function (name) {
    var Ctx = window[name];
    if (!Ctx || !Ctx.prototype) return;
    try {
      var resume = Ctx.prototype.resume;
      Ctx.prototype.resume = function () {
        if (window.__abHold) { try { this.suspend(); } catch (e) {} return Promise.resolve(); }
        return resume.apply(this, arguments);
      };
    } catch (e) {}
  });

  if (window.__abHold) Array.prototype.forEach.call(media(), silence);
})();
""".trimIndent()
}
