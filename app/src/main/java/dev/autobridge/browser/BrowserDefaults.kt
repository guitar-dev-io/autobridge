package dev.autobridge.browser

import android.content.Context
import android.net.Uri
import android.util.Log
import java.net.URLEncoder
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import dev.autobridge.BuildConfig
import dev.autobridge.entertainment.ContentAddress

/** Shared page identity, navigation and preferences for both browser presentations. */
object BrowserDefaults {
    private const val TAG_WEBVIEW = "[AutoBridge/WebView]"
    private const val TAG_DRM = "[AutoBridge/DRM]"

    const val HOME = "https://www.google.com/"

    // Identity providers that block sign-in inside any embedded WebView by checking for the "; wv)"
    // token Android's WebView adds to its default user-agent (anti-phishing policy). BrowserUserAgentStore
    // strips that token from the mobile UA (the same technique the Fermata Auto project uses for its
    // YouTube tab — see AndreyPavlenko/Fermata's FermataWebView.UserAgent), which lets accounts.google.com
    // complete sign-in inside this WebView instead of bouncing out. Apple/Microsoft haven't been verified
    // against the same technique, so they still hand off to a real external browser.
    private val externalSignInHosts = setOf(
        "appleid.apple.com", "login.microsoftonline.com", "login.live.com"
    )

    fun isExternalSignInHost(url: String): Boolean =
        runCatching { Uri.parse(url).host?.lowercase() }.getOrNull() in externalSignInHosts

    fun resolve(input: String): String = ContentAddress.https(input)
        ?: "https://www.google.com/search?q=" + URLEncoder.encode(input.trim(), "UTF-8")

    /**
     * WebView capabilities both presentations share. Each one is enabled for a named feature; the
     * ones left off are left off on purpose:
     *
     * - `allowFileAccess`/`allowContentAccess` stay **false** — the browser only ever loads HTTPS,
     *   so a page has no legitimate reason to reach `file://` or this app's content providers.
     * - `setAcceptThirdPartyCookies` **is** enabled, reversing an earlier decision to leave it off.
     *   First-party cookies alone keep an already-signed-in site signed in, but they are not enough
     *   to *complete* a Google sign-in: the handoff between `accounts.google.com` and
     *   `youtube.com` leans on requests made to the accounts origin from a page served by the
     *   content origin, which WebView classifies as third-party and strips cookies from when this
     *   is off. The visible symptom is a sign-in that appears to succeed and then lands on a page
     *   still showing a signed-out header. The cost is real and accepted knowingly: third-party
     *   cookies are also the mechanism of cross-site tracking, so enabling them widens what
     *   embedded content can correlate. It is a per-WebView setting, not a global one, which is why
     *   [configure] takes the WebView rather than only its [WebSettings].
     * - JavaScript interfaces / `addJavascriptInterface` are never registered, so no page can reach
     *   Android APIs.
     *
     * Zoom controls are enabled without the on-screen widgets: `builtInZoomControls` is the switch
     * that makes `WebView.zoomBy()` and pinch work at all, and the floating +/- buttons it would
     * otherwise draw are unwanted clutter on a car surface.
     */
    fun configure(context: Context, webView: WebView) {
        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.mediaPlaybackRequiresUserGesture = false
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        settings.setSupportZoom(true)
        settings.builtInZoomControls = true
        settings.displayZoomControls = false
        // target="_blank" / window.open() are handled as new tabs by the chrome clients; without
        // this the platform silently drops them and such links appear broken.
        settings.setSupportMultipleWindows(true)
        settings.javaScriptCanOpenWindowsAutomatically = false
        settings.textZoom = 100
        settings.userAgentString = BrowserUserAgentStore.resolve(context, WebSettings.getDefaultUserAgent(context))
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
        // WebView adds an "X-Requested-With: <package name>" header to every request by default —
        // a second signal (independent of the "; wv)" UA token stripped above) that identifies the
        // request as coming from an embedded WebView rather than a real browser. Google's sign-in
        // flow checks for it too, so an empty allow-list here removes it from every origin. Only
        // available on WebView versions that support this androidx.webkit feature; older WebViews
        // keep sending the header and are unaffected by this call.
        if (WebViewFeature.isFeatureSupported(WebViewFeature.REQUESTED_WITH_HEADER_ALLOW_LIST)) {
            WebSettingsCompat.setRequestedWithHeaderOriginAllowList(settings, emptySet())
        }
    }

    /**
     * Grants only the protected-media (Widevine EME) permission a page requests, so a page's own
     * `navigator.requestMediaKeySystemAccess()` can reach Android's normal MediaDrm/CDM stack.
     * Any other requested resource (camera, mic, MIDI, etc.) is left denied — never a blanket grant.
     */
    fun grantProtectedMediaPermission(request: PermissionRequest) {
        val protectedMedia = request.resources.filter { it == PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID }
        if (protectedMedia.isNotEmpty()) {
            request.grant(protectedMedia.toTypedArray())
            Log.i(TAG_DRM, "protected-media permission granted origin=${request.origin}")
        } else {
            request.deny()
            Log.i(TAG_DRM, "permission denied (no protected-media resource requested) origin=${request.origin}")
        }
    }

    /** Enables WebView content debugging (chrome://inspect) on debug builds only. */
    fun configureDebugTools() {
        if (BuildConfig.DEBUG) {
            WebView.setWebContentsDebuggingEnabled(true)
            Log.i(TAG_WEBVIEW, "content debugging enabled (debug build)")
        }
    }

    fun lastUrl(context: Context): String = context.getSharedPreferences("autobridge_browser", Context.MODE_PRIVATE)
        .getString("last_url", HOME)?.let(ContentAddress::https) ?: HOME

    fun remember(context: Context, url: String) {
        val valid = ContentAddress.https(url) ?: return
        context.getSharedPreferences("autobridge_browser", Context.MODE_PRIVATE)
            .edit().putString("last_url", valid).apply()
    }
}
