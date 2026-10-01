package dev.autobridge.browser

import android.util.Log
import android.view.View
import android.webkit.WebView

/**
 * TEMPORARY, REVERSIBLE diagnostic for the "YouTube UI visible but video black on Android Auto DHU"
 * investigation. Delete this file and the few call sites tagged `AutoBridgeVideoDiag` to remove it
 * entirely — it adds no behaviour to the normal browser paths.
 *
 * Purpose: prove where the video layer stops compositing, with YouTube, DRM, UA spoofing and
 * [dev.autobridge.youtube.YouTubeEnhancer] all removed from the equation. It does that two ways:
 *
 *  1. [TEST_PAGE_HTML] — a self-contained HTML5 `<video>` of a public, **non-DRM** MP4. It is
 *     loaded with [loadTestPage] via `loadDataWithBaseURL`, NOT a URL navigation, so it never goes
 *     through `shouldOverrideUrlLoading`/`ContentAddress.https` and needs no server. The only
 *     network fetch is the plain MP4 over https.
 *  2. [logState] — a one-line snapshot of the rendering path, display id, attached-to-window state,
 *     hardware-acceleration state, layer type and current URL. Call it from both the phone activity
 *     ([BrowserActivity]) and the car renderer ([CarWebRenderer]) so the two can be compared.
 *
 * All logs use the single tag [TAG] so `adb logcat -s AutoBridgeVideoDiag` shows the whole story.
 */
object WebVideoDiagnostics {
    const val TAG = "AutoBridgeVideoDiag"

    /**
     * Type any of these in either browser's URL bar to open the non-DRM MP4 test page on that path.
     * It is not a real URL (it never reaches the network) — the two `load`/`navigate` entry points
     * intercept it before validation and divert to [loadTestPage]. Matching is lenient on purpose
     * (case-insensitive, scheme/slashes optional) because the URI keyboard makes `://` awkward and
     * a near-miss would otherwise silently become a Google search.
     */
    const val DIAG_SENTINEL = "autobridge://videodiag"

    /** True if [input] is any accepted spelling of the diagnostic sentinel. */
    fun isSentinel(input: String): Boolean {
        val v = input.trim().lowercase().removeSuffix("/")
        return v == "autobridge://videodiag" ||
            v == "autobridge:videodiag" ||
            v == "videodiag" ||
            v == "autobridge://videodiag"
    }

    /**
     * A well-known, publicly hosted, non-DRM MP4 (Google's sample asset). If this frame is black on
     * a path, the problem is that path's compositing — not DRM and not YouTube.
     */
    const val TEST_MP4_URL =
        "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/BigBuckBunny.mp4"

    /** Base URL only anchors relative resources + origin; the page itself is inlined, not fetched. */
    private const val TEST_BASE_URL = "https://autobridge.local/"

    val TEST_PAGE_HTML: String = """
        <!DOCTYPE html>
        <html>
        <head>
          <meta name="viewport" content="width=device-width, initial-scale=1">
          <style>
            html,body{margin:0;background:#101113;color:#fff;font-family:sans-serif}
            .wrap{padding:12px}
            video{width:100%;height:auto;background:#000;display:block}
            .label{padding:8px 0;font-size:15px;line-height:1.4}
            .ok{color:#5fd38d}
            button{font-size:18px;padding:10px 18px;margin:8px 0;background:#2b6cff;color:#fff;border:0;border-radius:8px}
          </style>
        </head>
        <body>
          <div class="wrap">
            <div class="label">AutoBridgeVideoDiag — non-DRM MP4 test</div>
            <video id="v" controls playsinline muted></video>
            <button id="play">▶ Play</button>
            <div class="label">เห็นกระต่าย = path นี้ composite วิดีโอได้<br>
              ดำแต่เห็นปุ่ม = path นี้แสดง video layer ไม่ได้</div>
            <div class="label ok" id="state">init…</div>
          </div>
          <script>
            // Page-local only; no host bridge/injection. The <source> is set from JS so a parse
            // problem shows up as an explicit state line rather than a silently empty <video>.
            var v = document.getElementById('v'), s = document.getElementById('state');
            function set(t){ s.textContent = t; console.log('[videodiag] ' + t); }
            function dump(tag){
              set(tag + ' net=' + v.networkState + ' ready=' + v.readyState +
                  ' err=' + (v.error ? v.error.code : '-') +
                  ' size=' + v.videoWidth + 'x' + v.videoHeight);
            }
            v.src = "$TEST_MP4_URL";
            v.addEventListener('loadedmetadata', function(){ dump('metadata'); });
            v.addEventListener('playing', function(){ dump('PLAYING'); });
            v.addEventListener('waiting', function(){ dump('waiting'); });
            v.addEventListener('stalled', function(){ dump('stalled'); });
            v.addEventListener('error', function(){ dump('ERROR'); });
            document.getElementById('play').addEventListener('click', function(){
              v.play().then(function(){ dump('play() ok'); })
                      .catch(function(e){ set('play() rejected: ' + e); });
            });
            // Try autoplay once; if the platform blocks it the button is the fallback.
            v.play().then(function(){ dump('autoplay ok'); })
                    .catch(function(e){ set('autoplay blocked — tap Play (' + e.name + ')'); });
          </script>
        </body>
        </html>
    """.trimIndent()

    /**
     * Loads the self-contained non-DRM test page into [webView] without a URL navigation, so the
     * HTTPS-only `shouldOverrideUrlLoading` gate on both paths is bypassed and no server is needed.
     */
    fun loadTestPage(webView: WebView) {
        Log.i(TAG, "loadTestPage into ${webView.javaClass.simpleName}")
        webView.loadDataWithBaseURL(TEST_BASE_URL, TEST_PAGE_HTML, "text/html", "utf-8", null)
    }

    /** One-line state snapshot. [path] names the rendering route, e.g. "BrowserActivity" / "CarWebRenderer". */
    fun logState(path: String, webView: WebView?, extra: String = "") {
        if (webView == null) {
            Log.i(TAG, "path=$path webView=null $extra")
            return
        }
        val displayId = runCatching { webView.display?.displayId }.getOrNull()
        Log.i(
            TAG,
            "path=$path " +
                "attachedToWindow=${webView.isAttachedToWindow} " +
                "displayId=$displayId " +
                "hwAccel=${webView.isHardwareAccelerated} " +
                "layerType=${layerTypeName(webView.layerType)} " +
                "size=${webView.width}x${webView.height} " +
                "url=${webView.url} " +
                extra
        )
    }

    private fun layerTypeName(type: Int): String = when (type) {
        View.LAYER_TYPE_NONE -> "NONE"
        View.LAYER_TYPE_SOFTWARE -> "SOFTWARE"
        View.LAYER_TYPE_HARDWARE -> "HARDWARE"
        else -> "UNKNOWN($type)"
    }
}
