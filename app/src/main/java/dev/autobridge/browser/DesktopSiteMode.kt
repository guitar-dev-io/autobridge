package dev.autobridge.browser

import android.util.Log
import android.webkit.WebView
import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import java.util.WeakHashMap

/**
 * The page-side half of desktop mode.
 *
 * The User-Agent string and the client hints ([BrowserDefaults.applyUserAgentMetadata]) are only
 * what the *server* is told. Once the page runs, its own scripts ask the device directly, and a
 * WebView on a phone answers every one of those questions "touch phone":
 *
 *  - `navigator.platform` is `Linux armv8l` and `navigator.maxTouchPoints` is 5+;
 *  - `screen.width` is the phone's ~400 dp, which is the number most "is this mobile?" checks use;
 *  - `matchMedia('(pointer: coarse)')` / `(hover: none)` match, and responsive JS keys off those;
 *  - `<meta name="viewport" content="width=device-width">` pins the phone window's layout to the
 *    phone's dp width, which no UA string can override.
 *
 * Any one of them is enough for a site to switch back to its mobile layout after the desktop HTML
 * arrived, which is why desktop mode used to look like it did nothing. This installs a
 * document-start script (before any page script runs, in every frame) that answers them the way a
 * desktop browser would. Sign-in origins are skipped, matching
 * [BrowserUserAgentStore.resolveForUrl], so Google sees one consistent mobile identity there.
 *
 * CSS `@media (hover/pointer)` rules in stylesheets cannot be changed from script and still match
 * the touch screen; only script-visible values are corrected.
 */
object DesktopSiteMode {
    private const val TAG = "AutoBridgeDesktop"

    /** CSS width a desktop page is laid out at; the same width the car viewport pins. */
    private const val DESKTOP_WIDTH = BrowserViewport.DESKTOP_CONTENT_WIDTH_DP

    private val handlers = WeakHashMap<WebView, ScriptHandler>()

    fun isSupported(): Boolean = WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)

    /**
     * Installs or removes the script on [webView]. Idempotent, so it can run from every
     * [BrowserDefaults.configure] call. Takes effect from the next navigation, so callers reload.
     * Must be called on the UI thread.
     */
    fun apply(webView: WebView, desktop: Boolean) {
        if (!isSupported()) return
        val existing = handlers[webView]
        if (desktop) {
            if (existing != null) return
            runCatching { WebViewCompat.addDocumentStartJavaScript(webView, SCRIPT, setOf("*")) }
                .onSuccess { handlers[webView] = it }
                .onFailure { Log.w(TAG, "document-start script rejected by this WebView", it) }
        } else if (existing != null) {
            runCatching { existing.remove() }
            handlers.remove(webView)
        }
    }

    internal val SCRIPT: String = """
(function () {
  if (window.__autobridgeDesktop) return;
  var host = location.hostname || '';
  if (/(^|\.)accounts\.(google|youtube)\.com$/.test(host)) return;
  window.__autobridgeDesktop = true;
  var W = $DESKTOP_WIDTH;

  function getter(target, name, get) {
    try { Object.defineProperty(target, name, { get: get, configurable: true }); } catch (e) {}
  }

  // Device identity read by page scripts.
  getter(Navigator.prototype, 'platform', function () { return 'Linux x86_64'; });
  getter(Navigator.prototype, 'maxTouchPoints', function () { return 0; });

  // Screen size: at least a desktop-width monitor, never smaller than the real window.
  function sw() { return Math.max(W, window.innerWidth || 0); }
  function sh() { return Math.max(Math.round(W * 10 / 16), window.innerHeight || 0); }
  getter(Screen.prototype, 'width', sw);
  getter(Screen.prototype, 'availWidth', sw);
  getter(Screen.prototype, 'height', sh);
  getter(Screen.prototype, 'availHeight', sh);

  // matchMedia: a touch screen is "coarse / no hover"; a desktop is "fine / hover". Swapping the
  // queried value makes each query answer as it would on a mouse-driven desktop.
  var nativeMatchMedia = window.matchMedia;
  if (nativeMatchMedia) {
    var swap = function (query) {
      return String(query).replace(
        /(any-)?(pointer|hover)\s*:\s*(coarse|fine|none|hover)/gi,
        function (m, any, feature, value) {
          feature = feature.toLowerCase();
          value = value.toLowerCase();
          if (feature === 'pointer') {
            value = value === 'coarse' ? 'fine' : value === 'fine' ? 'coarse' : value;
          } else {
            value = value === 'none' ? 'hover' : value === 'hover' ? 'none' : value;
          }
          return (any || '') + feature + ': ' + value;
        });
    };
    window.matchMedia = function (query) { return nativeMatchMedia.call(window, swap(query)); };
  }

  // <meta name=viewport content=width=device-width> pins the layout to the phone's width.
  // Rewrite it to a desktop width; the WebView's overview mode then zooms the page to fit.
  var VIEWPORT = 'width=' + W;
  function fix(node) {
    if (node && node.nodeName === 'META' && (node.getAttribute('name') || '').toLowerCase() === 'viewport' &&
        node.getAttribute('content') !== VIEWPORT) {
      node.setAttribute('content', VIEWPORT);
    }
  }
  function scan(root) {
    if (!root || !root.querySelectorAll) return;
    var metas = root.querySelectorAll('meta[name="viewport" i]');
    for (var i = 0; i < metas.length; i++) fix(metas[i]);
  }
  var observer = new MutationObserver(function (records) {
    for (var i = 0; i < records.length; i++) {
      var r = records[i];
      if (r.type === 'attributes') { fix(r.target); continue; }
      for (var j = 0; j < r.addedNodes.length; j++) {
        var n = r.addedNodes[j];
        fix(n);
        if (n.nodeName === 'HEAD' || n.nodeName === 'HTML') scan(n);
      }
    }
  });
  observer.observe(document, {
    childList: true, subtree: true, attributes: true, attributeFilter: ['content', 'name']
  });
  scan(document);
  window.addEventListener('load', function () {
    scan(document);
    // Pages rarely touch the viewport after load; stop paying for the observer.
    setTimeout(function () { observer.disconnect(); }, 3000);
  });
})();
""".trimIndent()
}
