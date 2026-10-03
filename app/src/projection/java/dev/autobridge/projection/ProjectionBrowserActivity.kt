package dev.autobridge.projection

import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.apps.auto.sdk.CarActivity
import dev.autobridge.audio.WebAudioBridge
import dev.autobridge.audio.WebMediaStatus
import dev.autobridge.browser.BrowserAdBlock
import dev.autobridge.browser.BrowserDefaults
import dev.autobridge.browser.WebViewTimerGate
import dev.autobridge.core.model.Feature
import dev.autobridge.core.policy.FeaturePolicy
import dev.autobridge.display.StructuredLog
import dev.autobridge.entertainment.ContentAddress
import dev.autobridge.media.WebMediaHub
import dev.autobridge.media.WebMediaSource
import dev.autobridge.safety.ParkingStateStore
import dev.autobridge.safety.SafetyEnforcement

/**
 * The browser as a projection-route car activity: a real view tree on the Android Auto display,
 * opened in the main area with the navigation app kept in the side panel (see the projection
 * AndroidManifest for why this route splits the screen and the template route does not).
 *
 * Deliberately small for a first version: one WebView, a row of quick actions, fullscreen video,
 * and the page's audio published to the media session through [WebMediaHub] so the steering wheel
 * and the media card control it. No URL bar yet - text entry on this SDK goes through the car's
 * own input UI and is a separate piece of work.
 *
 * Audio focus is left to the WebView's Chromium, which requests and handles it itself. Holding a
 * second request from this app is what made the template route's page pause right after play.
 */
class ProjectionBrowserActivity : CarActivity() {
    private companion object {
        const val TIMER_GATE_OWNER = "projection-browser"
        val QUICK_SITES = listOf(
            "YouTube" to "https://m.youtube.com/",
            "YT Music" to "https://music.youtube.com/",
            "Google" to BrowserDefaults.HOME,
        )
    }

    private var webView: WebView? = null
    private var root: FrameLayout? = null
    private var blocked: TextView? = null
    private var fullscreenView: View? = null
    private var fullscreenCallback: WebChromeClient.CustomViewCallback? = null

    private val audio = WebAudioBridge { webView }

    private val mediaSource = object : WebMediaSource {
        override fun readMediaStatus(onResult: (WebMediaStatus) -> Unit) = audio.readState(onResult)
        override fun play() {
            webView?.onResume()
            audio.userPlay()
        }
        override fun pause() = audio.userPause()
        override fun seekTo(positionMs: Long) = audio.seekTo(positionMs)

        /** Same queue as the template route: one list, whichever surface is showing it. */
        override fun skipToNext(): Boolean {
            val next = dev.autobridge.browser.BrowserPlayQueue.takeNext(
                this@ProjectionBrowserActivity
            ) ?: return false
            StructuredLog.i("PROJECTION", "play queue -> ${next.url}")
            webView?.loadUrl(next.url) ?: return false
            return true
        }
    }

    private val parkingListener: (ParkingStateStore.State) -> Unit = {
        webView?.post { enforcePolicy() }
    }

    private fun allowed() =
        SafetyEnforcement.gateParked(ParkingStateStore.isParked) && FeaturePolicy.app.isAvailable(Feature.BROWSER)

    private fun Int.dp(): Int = (this * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The car display changes density and size as the host lays out its panels; rebuilding the
        // WebView for each of those would reload the page.
        setIgnoreConfigChanges(-1)
        carUiController.statusBarController.hideAppHeader()
        carUiController.menuController.hideMenuButton()
        BrowserDefaults.configureDebugTools()
        setContentView(buildLayout())
        ParkingStateStore.addListener(parkingListener)
        WebMediaHub.register(this, mediaSource)
        enforcePolicy()
        webView?.loadUrl(BrowserDefaults.lastUrl(this))
        StructuredLog.i("PROJECTION", "browser activity created")
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun buildLayout(): View {
        val web = WebView(this).apply {
            BrowserDefaults.configure(this@ProjectionBrowserActivity, this)
            webViewClient = object : WebViewClient() {
                /**
                 * Drops advertising and tracking subresources when the user has turned blocking on.
                 * Returns null — "fetch it as usual" — for everything else.
                 */
                override fun shouldInterceptRequest(
                    view: WebView,
                    request: WebResourceRequest,
                ): WebResourceResponse? =
                    BrowserAdBlock.intercept(this@ProjectionBrowserActivity, request)

                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    // Only web pages load here; app links and other schemes are dropped rather than
                    // handed to an intent the car display cannot show.
                    val scheme = request.url.scheme?.lowercase()
                    return scheme != "https" && scheme != "http"
                }

                override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                    BrowserDefaults.applyIdentity(this@ProjectionBrowserActivity, view, url)
                }

                override fun onPageFinished(view: WebView, url: String) {
                    BrowserDefaults.remember(this@ProjectionBrowserActivity, url)
                    audio.installPlayTracking()
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onPermissionRequest(request: PermissionRequest) =
                    BrowserDefaults.grantProtectedMediaPermission(request)

                override fun onShowCustomView(view: View, callback: CustomViewCallback) = enterFullscreen(view, callback)

                override fun onHideCustomView() = exitFullscreen()
            }
        }
        WebViewTimerGate.hold(TIMER_GATE_OWNER, web)
        webView = web

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.rgb(24, 24, 24))
            setPadding(8.dp(), 0, 8.dp(), 0)
            addView(action("Back") { goBack() })
            addView(action("Reload") { webView?.reload() })
            QUICK_SITES.forEach { (label, url) -> addView(action(label) { open(url) }) }
        }

        val blockedView = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 20f
            gravity = Gravity.CENTER
            visibility = View.GONE
        }
        blocked = blockedView

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 56.dp()))
            addView(web, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }

        return FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(column, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(blockedView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            root = this
        }
    }

    /** A text action sized for a car touch target (48dp minimum), announced by its label. */
    private fun action(label: String, onClick: () -> Unit): TextView = TextView(this).apply {
        text = label
        contentDescription = label
        setTextColor(Color.WHITE)
        textSize = 16f
        gravity = Gravity.CENTER
        minWidth = 48.dp()
        minHeight = 48.dp()
        setPadding(16.dp(), 0, 16.dp(), 0)
        isClickable = true
        isFocusable = true
        setOnClickListener { onClick() }
    }

    private fun open(url: String) {
        if (!allowed()) return enforcePolicy()
        ContentAddress.https(url)?.let { webView?.loadUrl(it) }
    }

    private fun goBack() {
        when {
            fullscreenView != null -> exitFullscreen()
            webView?.canGoBack() == true -> webView?.goBack()
        }
    }

    private fun enforcePolicy() {
        val permitted = allowed()
        webView?.visibility = if (permitted) View.VISIBLE else View.INVISIBLE
        blocked?.visibility = if (permitted) View.GONE else View.VISIBLE
        blocked?.text = if (permitted) "" else FeaturePolicy.app.denialMessage(Feature.BROWSER)
        if (!permitted) {
            exitFullscreen()
            audio.userPause()
        }
    }

    private fun enterFullscreen(view: View, callback: WebChromeClient.CustomViewCallback) {
        exitFullscreen()
        fullscreenView = view
        fullscreenCallback = callback
        root?.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    private fun exitFullscreen() {
        val view = fullscreenView ?: return
        fullscreenView = null
        root?.removeView(view)
        fullscreenCallback?.onCustomViewHidden()
        fullscreenCallback = null
    }

    override fun onBackPressed() {
        if (fullscreenView != null || webView?.canGoBack() == true) goBack() else super.onBackPressed()
    }

    override fun onResume() {
        super.onResume()
        webView?.onResume()
        webView?.let { WebViewTimerGate.hold(TIMER_GATE_OWNER, it) }
    }

    // No onPause() override: the WebView is deliberately not paused when the host stops this
    // activity (the driver switching apps), so music keeps playing then, as it does in Fermata.

    override fun onDestroy() {
        ParkingStateStore.removeListener(parkingListener)
        WebMediaHub.unregister(mediaSource)
        exitFullscreen()
        webView?.let { view ->
            WebViewTimerGate.release(TIMER_GATE_OWNER, view)
            view.stopLoading()
            view.destroy()
        }
        webView = null
        root = null
        StructuredLog.i("PROJECTION", "browser activity destroyed")
        super.onDestroy()
    }
}
