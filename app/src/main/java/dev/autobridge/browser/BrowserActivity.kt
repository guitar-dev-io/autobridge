package dev.autobridge.browser

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.os.Message
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.*
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import dev.autobridge.core.model.Feature
import dev.autobridge.core.policy.FeaturePolicy
import dev.autobridge.entertainment.BrowserLauncher
import dev.autobridge.entertainment.ContentAddress
import dev.autobridge.entertainment.WebBookmarkStore
import dev.autobridge.entertainment.WebHistoryStore
import dev.autobridge.remote.CarScreenController
import dev.autobridge.safety.ParkingStateStore

/**
 * Phone presentation of the car browser; native views keep keyboard and touch accessible.
 *
 * Laid out as a [FrameLayout] with the page filling it and the toolbar composited on top, matching
 * the car surface's overlay model. The previous vertical [LinearLayout] gave the WebView a weight
 * of 1, so hiding the toolbar, showing the fullscreen handle or opening the keyboard each changed
 * the WebView's height and forced the page to re-run layout. Chrome now changes only its own
 * visibility, and the page keeps a constant viewport.
 *
 * Window insets are applied per-layer for the same reason: the status bar pads the toolbar, and the
 * IME pads the content container's bottom. Padding the shared root — as this did before — moved the
 * toolbar with the keyboard and resized the page at the same time.
 */
class BrowserActivity : Activity() {
    private lateinit var web: WebView

    /** YouTube add-ons (SponsorBlock, auto quality). Does nothing unless the user enabled them. */
    private val youtube by lazy { dev.autobridge.youtube.YouTubeEnhancer(this) }

    /** Per-site location prompt plus the runtime location permission; see [BrowserGeolocation]. */
    private val geolocation by lazy { BrowserGeolocation.Prompter(this) }
    private lateinit var address: EditText
    private lateinit var toolbar: LinearLayout
    private lateinit var chromeBar: FrameLayout
    private lateinit var handle: TextView
    private lateinit var menuButton: TextView
    private lateinit var toolbarMenuButton: Button
    private lateinit var stopReload: Button
    private lateinit var fullscreenButton: Button
    private lateinit var progress: ProgressBar
    private lateinit var blocked: TextView
    private lateinit var loadError: TextView
    private lateinit var content: FrameLayout

    /** The window root, held so entering fullscreen can re-request the page's top inset. */
    private lateinit var root: FrameLayout
    private lateinit var fullscreenController: FullscreenVideoController
    private var fullscreen = false
    private var pendingUrl: String? = null
    private var resumed = false
    private val parkingListener: (ParkingStateStore.State) -> Unit = { runOnUiThread { enforcePolicy() } }
    private fun allowed() = true
    // FeaturePolicy.app.isAvailable(Feature.BROWSER)
    private fun Int.dp() = (this * resources.displayMetrics.density).toInt()

    /**
     * Shared dp-based sizing. Icon size comes from the screen's density, never from its width, and
     * text is capped at [AutoUiSizes.MAX_FONT_SCALE] so a large accessibility font setting keeps
     * text readable without letting the toolbar grow without bound.
     */
    private val sizes: AutoUiSizes by lazy { AutoUiSizes.forDensity(resources.displayMetrics.density) }
    private val fontScale: Float by lazy { AutoUiSizes.clampFontScale(resources.configuration.fontScale) }

    /** Converts a token dp value to the sp figure a [TextView] needs, with the scale already capped. */
    private fun iconSp(dp: Float) = dp * (fontScale / resources.configuration.fontScale.coerceAtLeast(0.01f))

    /**
     * True while this activity is showing on the car's display instead of the phone's.
     *
     * Launched onto the Android Auto display the activity owns a real window there, so the WebView
     * is attached and hardware-accelerated and the system composites it — none of the software
     * capture the template route ([CarWebRenderer]) has to do. What the head unit does not have is
     * a user who can usefully reach the system bars, which is what [applyCarDisplayWindow] acts on.
     */
    private val onCarDisplay: Boolean
        get() {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
            val id = runCatching { display?.displayId }.getOrNull() ?: return false
            return id != Display.DEFAULT_DISPLAY
        }

    /**
     * Pins the page's CSS width to the same band the car surface uses, by re-basing the whole
     * activity's density. Doing it here rather than on the WebView alone keeps the toolbar and the
     * page on one density; see [CarDisplayScaling].
     */
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(CarDisplayScaling.rebase(newBase))
    }

    /**
     * Gives the page the whole car display.
     *
     * The status and navigation bars are phone affordances: on a head unit the user reaches the car
     * launcher through the host's own controls, so the height those bars reserve is simply lost.
     * Hiding them is what makes the window's aspect match the display's, which is the difference
     * between the page filling the panel and being laid out into a shorter box inside it. They stay
     * reachable by a swipe, so nothing becomes unrecoverable.
     *
     * The phone window is left exactly as it was — this runs only when [onCarDisplay].
     */
    private fun applyCarDisplayWindow() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, root).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        root = FrameLayout(this)
        applyStartPageBackground()

        content = FrameLayout(this)
        web = createWebView()
        blocked = TextView(this).apply {
            gravity = Gravity.CENTER; setTextColor(BrowserTheme.textPrimary)
        }
        loadError = TextView(this).apply {
            text = "โหลดหน้านี้ไม่สำเร็จ\nแตะเพื่อลองใหม่"
            gravity = Gravity.CENTER; setTextColor(BrowserTheme.errorAccent)
            setBackgroundColor(BrowserTheme.errorBackground)
            visibility = View.GONE
            setOnClickListener { visibility = View.GONE; web.reload() }
        }
        content.addView(web, FrameLayout.LayoutParams(-1, -1))
        content.addView(blocked, FrameLayout.LayoutParams(-1, -1))
        content.addView(loadError, FrameLayout.LayoutParams(-1, -1))

        menuButton = TextView(this).apply {
            text = "☰"
            textSize = iconSp(AutoUiSizes.ICON_MEDIUM_DP)
            gravity = Gravity.CENTER
            setTextColor(BrowserTheme.iconEnabled)
            contentDescription = "เมนูเบราว์เซอร์ (ลากเพื่อย้ายปุ่ม)"
            isFocusable = true
            background = GradientDrawable().apply {
                setColor(0xE6101113.toInt())
                cornerRadius = sizes.fabSize / 2f
                setStroke(1.dp(), 0xFF526FA6.toInt())
            }
            setOnClickListener { runFloatingButtonAction() }
        }
        makeMenuButtonDraggable()
        content.addView(
            menuButton,
            FrameLayout.LayoutParams(
                sizes.dpInt(AutoUiSizes.FAB_SIZE_DP),
                sizes.dpInt(AutoUiSizes.FAB_SIZE_DP),
                Gravity.BOTTOM or Gravity.END
            ).apply {
                rightMargin = sizes.dpInt(AutoUiSizes.FAB_MARGIN_DP)
                bottomMargin = sizes.dpInt(AutoUiSizes.FAB_MARGIN_DP)
            }
        )
        root.addView(content, FrameLayout.LayoutParams(-1, -1))

        chromeBar = buildChrome()
        root.addView(
            chromeBar,
            FrameLayout.LayoutParams(-1, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP)
        )

        handle = TextView(this).apply {
            text = "⌄"
            contentDescription = "Show browser toolbar"
            gravity = Gravity.CENTER
            setTextColor(BrowserTheme.iconEnabled)
            setBackgroundColor(Color.argb(90, 20, 22, 26))
            visibility = View.GONE
            setOnClickListener { setFullscreen(false) }
        }
        root.addView(
            handle,
            FrameLayout.LayoutParams(-1, sizes.dpInt(AutoUiSizes.HANDLE_HEIGHT_DP), Gravity.TOP)
        )

        // Per-layer insets: the status bar belongs to the toolbar, the IME to the content area.
        // Nothing here touches the root, so no inset change can move both at once.
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            chromeBar.setPadding(bars.left, bars.top, bars.right, 0)
            handle.setPadding(bars.left, bars.top, bars.right, 0)
            // The chrome is an overlay on top of the page, so the page has to start below both the
            // status bar and the toolbar. Leaving its top inset at 0 let a site's own sticky header
            // render into the status bar, so the clock sat on top of the page's title.
            val chromeHeight = if (fullscreen) 0 else sizes.dpInt(AutoUiSizes.TOOLBAR_HEIGHT_DP)
            content.setPadding(
                bars.left,
                bars.top + chromeHeight,
                bars.right,
                maxOf(bars.bottom, ime.bottom)
            )
            ViewportDebug.logWindow(
                event = if (ime.bottom > 0) ViewportDebug.Event.KEYBOARD else ViewportDebug.Event.LAYOUT,
                widthPx = root.width, heightPx = root.height,
                density = resources.displayMetrics.density,
                fontScale = resources.configuration.fontScale,
                systemBars = Rect(bars.left, bars.top, bars.right, bars.bottom),
                imeBottom = ime.bottom,
                chromeVisible = !fullscreen, fullscreen = fullscreen
            )
            insets
        }

        fullscreenController = FullscreenVideoController(this, content)
        setContentView(root)
        if (onCarDisplay) applyCarDisplayWindow()

        val restored = savedInstanceState?.getBundle("web")?.let { web.restoreState(it) } != null
        pendingUrl = savedInstanceState?.getString("pending_url")
            ?: if (!restored) intent.dataString?.let(ContentAddress::https) ?: BrowserStartupStore.coldStartUrl(this) else null
        setFullscreen(savedInstanceState?.getBoolean("fullscreen") == true)
        ParkingStateStore.addListener(parkingListener)
    }

    /**
     * Toolbar: `‹ › ↻  [ address ]  ⛶  ☰`, the same slot order the car surface draws. Each button
     * occupies a [AutoUiSizes.TOUCH_TARGET_DP] box while its glyph is drawn at
     * [AutoUiSizes.ICON_MEDIUM_DP], keeping the visual density infotainment-sized without shrinking
     * the tap area.
     */
    private fun buildChrome(): FrameLayout {
        toolbar = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(BrowserTheme.toolbarBackground)
            setPadding(sizes.dpInt(AutoUiSizes.HORIZONTAL_PADDING_DP), 0, sizes.dpInt(AutoUiSizes.HORIZONTAL_PADDING_DP), 0)
        }
        fun control(label: String, description: String, action: () -> Unit): Button = Button(this).apply {
            text = label
            contentDescription = description
            textSize = iconSp(AutoUiSizes.ICON_MEDIUM_DP)
            setTextColor(BrowserTheme.iconEnabled)
            background = null
            minWidth = 0
            minimumWidth = 0
            setPadding(0, 0, 0, 0)
            setOnClickListener { if (allowed()) action() }
            toolbar.addView(
                this,
                LinearLayout.LayoutParams(
                    sizes.dpInt(AutoUiSizes.TOUCH_TARGET_DP),
                    sizes.dpInt(AutoUiSizes.TOUCH_TARGET_DP)
                )
            )
        }
        control("‹", "Back") { if (web.canGoBack()) web.goBack() }
        control("›", "Forward") { if (web.canGoForward()) web.goForward() }
        stopReload = control("↻", "Reload") {
            if (web.progress < 100) web.stopLoading() else web.reload()
            updateNavigation()
        }
        address = EditText(this).apply {
            hint = "URL / ค้นหา"
            setTextColor(BrowserTheme.textPrimary)
            setHintTextColor(Color.LTGRAY)
            textSize = iconSp(AutoUiSizes.ICON_SMALL_DP * 0.85f)
            setSingleLine()
            setSelectAllOnFocus(true)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = EditorInfo.IME_ACTION_GO
            setPadding(sizes.dpInt(AutoUiSizes.HORIZONTAL_PADDING_DP), 0, sizes.dpInt(AutoUiSizes.HORIZONTAL_PADDING_DP), 0)
            background = GradientDrawable().apply {
                setColor(BrowserTheme.addressPillBackground)
                cornerRadius = sizes.cornerRadius
            }
            setOnFocusChangeListener { _, hasFocus ->
                // Editing needs the real URL; reading only needs the part that identifies the page.
                setText(if (hasFocus) web.url.orEmpty() else displayUrl(web.url.orEmpty()))
                if (hasFocus) setSelection(text.length)
            }
            setOnEditorActionListener { _, action, event ->
                if (action == EditorInfo.IME_ACTION_GO ||
                    (event?.keyCode == android.view.KeyEvent.KEYCODE_ENTER && event.action == android.view.KeyEvent.ACTION_UP)
                ) {
                    navigate(text.toString())
                    clearFocus()
                    (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(windowToken, 0)
                    true
                } else false
            }
        }
        toolbar.addView(
            address,
            LinearLayout.LayoutParams(0, sizes.dpInt(AutoUiSizes.TOUCH_TARGET_DP * 0.8f), 1f).apply {
                marginStart = sizes.dpInt(AutoUiSizes.CONTENT_GAP_DP)
                marginEnd = sizes.dpInt(AutoUiSizes.CONTENT_GAP_DP)
            }
        )
        fullscreenButton = control("⛶", "Fullscreen") { setFullscreen(!fullscreen) }
        // Same ☰ as the floating button. Both live on screen only when the floating button has
        // been rebound away from MENU; while it is still the default, the toolbar button would be
        // a second, identical-looking way to do the one thing the floating one already does.
        toolbarMenuButton = control("☰", "Browser menu") { showMenu() }

        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)
        return FrameLayout(this).apply {
            addView(
                toolbar,
                FrameLayout.LayoutParams(-1, sizes.dpInt(AutoUiSizes.TOOLBAR_HEIGHT_DP), Gravity.TOP)
            )
            addView(
                progress,
                FrameLayout.LayoutParams(-1, 2.dp(), Gravity.TOP).apply {
                    topMargin = sizes.dpInt(AutoUiSizes.TOOLBAR_HEIGHT_DP) - 2.dp()
                }
            )
        }
    }

    private fun createWebView(): WebView = WebView(this).apply {
        BrowserDefaults.configure(this@BrowserActivity, this)
        BrowserDefaults.configureDebugTools()
        // Match the viewport to the saved desktop/mobile preference before the first page loads,
        // so a desktop-mode session opens at desktop width rather than mobile-then-reflow.
        applyDesktopViewport(this)
        setDownloadListener(BrowserDownloads.listener(this@BrowserActivity) { toast(it) })
        webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, value: Int) {
                // Qualified: inside the WebView's apply block, a bare `progress` would resolve to
                // WebView.getProgress() rather than this Activity's ProgressBar.
                this@BrowserActivity.progress.progress = value
                this@BrowserActivity.progress.visibility =
                    if (value < 100 && allowed()) View.VISIBLE else View.GONE
                updateNavigation()
            }

            // Lets a page's own navigator.requestMediaKeySystemAccess() (Widevine EME) reach
            // Android's normal MediaDrm stack; only the protected-media resource is granted.
            override fun onPermissionRequest(request: PermissionRequest) =
                BrowserDefaults.grantProtectedMediaPermission(request)

            // navigator.geolocation (Google Maps "my location" etc.). Without these overrides the
            // request is never answered and map sites cannot get a GPS fix; see BrowserGeolocation.
            override fun onGeolocationPermissionsShowPrompt(
                origin: String,
                callback: GeolocationPermissions.Callback,
            ) = geolocation.show(origin, callback)

            override fun onGeolocationPermissionsHidePrompt() = geolocation.hide()

            /**
             * `window.open()` / `target="_blank"`. Without a handler the platform drops the window
             * silently, so YouTube's "Sign in" popup (a window.open to accounts.google.com) did
             * nothing at all. The popup WebView is configured with the same clean identity as the
             * main one — clean UA, no X-Requested-With header — so the request Google sees is not a
             * raw, rejected WebView. A sign-in origin is loaded into the main WebView in place (the
             * OAuth cookies belong there); any other new window becomes a same-tab navigation too,
             * since this single-WebView activity has no tab strip to host a second page.
             */
            override fun onCreateWindow(
                view: WebView,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: Message,
            ): Boolean {
                if (!isUserGesture) return false
                val transport = resultMsg.obj as? WebView.WebViewTransport ?: return false
                val popup = WebView(this@BrowserActivity).apply {
                    BrowserDefaults.configure(this@BrowserActivity, this)
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(
                            popupView: WebView,
                            request: WebResourceRequest,
                        ): Boolean {
                            val target = request.url.toString()
                            ContentAddress.https(target)?.let { web.loadUrl(it) }
                            popupView.destroy()
                            return true
                        }
                    }
                }
                transport.webView = popup
                resultMsg.sendToTarget()
                return true
            }

            // AutoBridgeVideoDiag (temporary): surface page console errors (e.g. video load
            // failures) to logcat. Remove with WebVideoDiagnostics.
            override fun onConsoleMessage(msg: android.webkit.ConsoleMessage): Boolean {
                android.util.Log.i(
                    WebVideoDiagnostics.TAG,
                    "console[${msg.messageLevel()}] ${msg.message()} @${msg.sourceId()}:${msg.lineNumber()}"
                )
                return super.onConsoleMessage(msg)
            }

            override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                // AutoBridgeVideoDiag (temporary): confirm the fullscreen path fires here and on
                // which display, then proceed exactly as before. Remove with WebVideoDiagnostics.
                WebVideoDiagnostics.logState(
                    "BrowserActivity", web,
                    "stage=onShowCustomView-FIRED onCarDisplay=$onCarDisplay customView=${view.javaClass.simpleName}"
                )
                fullscreenController.show(view, callback) { pageFullscreen -> setFullscreen(pageFullscreen) }
            }

            override fun onHideCustomView() {
                android.util.Log.i(WebVideoDiagnostics.TAG, "path=BrowserActivity stage=onHideCustomView-FIRED")
                fullscreenController.hide()
            }
        }
        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val target = request.url.toString()
                if (allowed() && BrowserDefaults.isExternalSignInHost(target)) {
                    promptExternalSignIn(target)
                    return true
                }
                return !allowed() || ContentAddress.https(target) == null
            }

            override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                loadError.visibility = View.GONE
                // Sign-in origins always get the clean mobile UA (see resolveForUrl): a desktop UA
                // contradicts the WebView's Android client hints and Google refuses it. This fires
                // for redirects too, so a desktop-mode page handing off to accounts.google.com is
                // corrected before the page loads.
                BrowserDefaults.applyIdentity(this@BrowserActivity, view, url)
            }

            override fun onPageFinished(view: WebView, url: String) {
                WebVideoDiagnostics.logState(
                    "BrowserActivity", view, "stage=onPageFinished onCarDisplay=$onCarDisplay"
                )
                if (allowed()) {
                    BrowserDefaults.remember(this@BrowserActivity, url)
                    WebHistoryStore.record(this@BrowserActivity, view.title, url)
                    youtube.onPageChanged(view, url)
                }
                updateNavigation()
            }

            /**
             * YouTube changes videos with `pushState`, which never reaches onPageFinished. This is
             * the callback that does fire for those in-page navigations.
             */
            override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
                if (allowed()) youtube.onPageChanged(view, url)
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (!request.isForMainFrame) return
                loadError.visibility = View.VISIBLE
            }
        }
    }

    /**
     * Matches the page's viewport to the desktop/mobile preference.
     *
     * A desktop User-Agent alone leaves modern sites on their mobile layout, because a site picks
     * its layout from the viewport width, not from the UA. The two modes want opposite settings:
     *
     *  - **Desktop** turns `useWideViewPort` on so the page's own `<meta name="viewport">` (or
     *    Chromium's 980px default) decides the layout width, with `loadWithOverviewMode` zooming
     *    the result down to fit. The initial scale is handed back to Chromium (`0`) precisely
     *    because the page is the one that knows how wide it wants to be.
     *  - **Mobile** turns both off and pins the scale to the display's density, so one CSS pixel is
     *    one dp and the page lays out at the window's dp width — which [CarDisplayScaling] has
     *    already placed inside the readable band on a car panel.
     *
     * This replaces an arithmetic attempt to force a fixed CSS width, whose percentage was a factor
     * of the display density too small: `setInitialScale` takes the scale in percent, where 100 is
     * one CSS pixel per *device* pixel, so dividing by a density-multiplied width asked a 1220px
     * window for a ~3900 CSS px page and rendered everything at a third of the intended size.
     */
    private fun applyDesktopViewport(view: WebView = web) {
        val desktop = BrowserUserAgentStore.isDesktopIdentity(this)
        view.settings.useWideViewPort = desktop
        view.settings.loadWithOverviewMode = desktop
        view.setInitialScale(
            if (desktop) 0 else (resources.displayMetrics.density * 100).toInt().coerceAtLeast(1)
        )
    }

    private fun navigate(input: String) {
        if (!allowed()) { toast(FeaturePolicy.app.denialMessage(Feature.BROWSER)); return }
        // AutoBridgeVideoDiag (temporary): the sentinel loads the self-contained non-DRM MP4 test
        // page on THIS (phone/hardware-composited) path. Remove with WebVideoDiagnostics.
        android.util.Log.i(WebVideoDiagnostics.TAG, "path=BrowserActivity navigate input='$input'")
        if (WebVideoDiagnostics.isSentinel(input)) {
            WebVideoDiagnostics.loadTestPage(web)
            WebVideoDiagnostics.logState("BrowserActivity", web, "stage=load-test-page onCarDisplay=$onCarDisplay")
            return
        }
        if (input.isNotBlank()) {
            val url = BrowserDefaults.resolve(input)
            address.setText(if (address.hasFocus()) url else displayUrl(url))
            web.loadUrl(url)
        }
    }

    private fun updateNavigation() {
        stopReload.text = if (web.progress < 100) "×" else "↻"
        stopReload.contentDescription = if (web.progress < 100) "Stop loading" else "Reload"
        if (!address.hasFocus()) address.setText(displayUrl(web.url.orEmpty()))
    }

    /**
     * Chrome-only transition. The WebView is neither resized nor re-created, so entering and
     * leaving fullscreen cannot reload the page or move its scroll position.
     */
    /**
     * What the address pill shows while it is not being edited: the site, the way every phone
     * browser shows it. Five toolbar buttons leave the pill about a third of the width, so a full
     * search URL rendered as "https://www.goo" — visually truncated at the one part of the address
     * that identifies nothing. The host always fits; focusing the field restores the real URL for
     * editing.
     */
    private fun displayUrl(url: String): String {
        if (url.isBlank()) return ""
        val host = runCatching { android.net.Uri.parse(url).host }.getOrNull()
        return host?.removePrefix("www.")?.takeIf { it.isNotBlank() }
            ?: BrowserDisplayUrl.compact(url, max = 32)
    }

    private fun setFullscreen(enabled: Boolean) {
        fullscreen = enabled
        applyFloatingButtonPreference()
        chromeBar.visibility = if (enabled) View.GONE else View.VISIBLE
        handle.visibility = if (enabled) View.VISIBLE else View.GONE
        fullscreenButton.contentDescription = if (enabled) "Exit fullscreen" else "Fullscreen"
        // The page's top inset depends on whether the toolbar is showing, so recompute it.
        ViewCompat.requestApplyInsets(root)
        ViewportDebug.logWindow(
            event = ViewportDebug.Event.FULLSCREEN,
            widthPx = web.width, heightPx = web.height,
            density = resources.displayMetrics.density,
            fontScale = resources.configuration.fontScale,
            systemBars = null, imeBottom = 0,
            chromeVisible = !enabled, fullscreen = enabled
        )
    }

    // ------------------------------------------------------------------ floating menu button

    /**
     * Makes the floating menu button draggable, and remembers where it was left.
     *
     * A control that is always on top of the page will sometimes sit on top of something the page
     * wanted that corner for — a site's own chat bubble, a "back to top" arrow, a video's controls.
     * The usual answer is to hide the button, which is what made the menu hard to find in the first
     * place. Letting it be moved keeps it present without it ever being permanently in the way.
     *
     * A drag is distinguished from a tap by [android.view.ViewConfiguration.getScaledTouchSlop], so
     * a touch that drifts a few pixels — which is every touch in a moving car — still opens the
     * menu instead of being swallowed as a failed drag.
     */
    private fun makeMenuButtonDraggable() {
        val slop = android.view.ViewConfiguration.get(this).scaledTouchSlop
        var downX = 0f
        var downY = 0f
        var startX = 0f
        var startY = 0f
        var dragging = false
        menuButton.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX; downY = event.rawY
                    startX = view.translationX; startY = view.translationY
                    dragging = false
                    true
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (!dragging && (kotlin.math.abs(dx) > slop || kotlin.math.abs(dy) > slop)) dragging = true
                    if (dragging) {
                        view.translationX = startX + dx
                        view.translationY = startY + dy
                        clampMenuButton()
                    }
                    true
                }
                android.view.MotionEvent.ACTION_UP -> {
                    if (dragging) saveMenuButtonPosition() else view.performClick()
                    true
                }
                android.view.MotionEvent.ACTION_CANCEL -> true
                else -> false
            }
        }
        // Rotation, the keyboard and the system bars all change the box the button may sit in, so
        // the saved offset is re-clamped on every layout rather than only when it is dragged.
        menuButton.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> clampMenuButton() }
        val saved = getSharedPreferences(FAB_PREFS, Context.MODE_PRIVATE)
        menuButton.translationX = saved.getFloat(FAB_KEY_X, 0f)
        menuButton.translationY = saved.getFloat(FAB_KEY_Y, 0f)
    }

    /**
     * Applies [BrowserControlsStore.alwaysShowFloatingButton].
     *
     * The button used to appear only in fullscreen, so with the toolbar on screen the menu existed
     * in exactly one place — a 46dp glyph in the top corner — and reaching it from the bottom of a
     * long page meant scrolling back up first. Being always present is now the default and the
     * whole point of a floating control; the old behaviour stays available for anyone who would
     * rather have the pixels back, and dragging keeps the button off whatever the page puts
     * underneath it either way.
     */
    private fun applyFloatingButtonPreference() {
        val always = BrowserControlsStore.alwaysShowFloatingButton(this)
        menuButton.visibility = if (always || fullscreen) View.VISIBLE else View.GONE
        // Honour the chosen resting corner. A drag offset is stored separately and re-clamped on
        // layout, so flipping the side resets the drag so the button lands cleanly on the new corner.
        val onLeft = BrowserControlsStore.floatingButtonOnLeft(this)
        (menuButton.layoutParams as? FrameLayout.LayoutParams)?.let { lp ->
            val wanted = Gravity.BOTTOM or (if (onLeft) Gravity.START else Gravity.END)
            if (lp.gravity != wanted) {
                lp.gravity = wanted
                menuButton.layoutParams = lp
                menuButton.translationX = 0f
                menuButton.translationY = 0f
                saveMenuButtonPosition()
            }
        }
        // The glyph says what the button will do, so a button rebound to "new tab" does not keep
        // claiming to be the menu.
        val action = BrowserControlsStore.floatingButtonAction(this)
        menuButton.text = action.glyph
        // The toolbar's own ☰ only appears once the floating button stops being the fixed way to
        // reach the menu; otherwise the two sat side by side doing the same thing.
        toolbarMenuButton.visibility = if (action == FloatingButtonAction.MENU) View.GONE else View.VISIBLE
    }

    /**
     * The backdrop the window shows behind the page — most visible on the start page and while a
     * page is still loading or transparent. [BrowserStartupStore.gradientBackground] (on by default)
     * draws the app's branded gradient; off falls back to the flat surface colour.
     */
    private fun applyStartPageBackground() {
        root.background = if (BrowserStartupStore.gradientBackground(this)) {
            GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(BrowserTheme.surfaceContainerHigh, BrowserTheme.background)
            )
        } else {
            null.also { root.setBackgroundColor(BrowserTheme.background) }
        }
    }

    /** Runs whatever the user bound the floating button to; the menu is only the default. */
    private fun runFloatingButtonAction() {
        if (!allowed()) return
        when (BrowserControlsStore.floatingButtonAction(this)) {
            FloatingButtonAction.MENU -> showMenu()
            // The phone presentation keeps one page per activity — tabs live on the car surface —
            // so both tab actions land on the menu, which is where its page actions are.
            FloatingButtonAction.TABS, FloatingButtonAction.NEW_TAB -> showMenu()
            FloatingButtonAction.HOME -> navigate(BrowserStartupStore.homePage(this))
            FloatingButtonAction.ADDRESS -> focusAddressBar()
            FloatingButtonAction.FULLSCREEN -> setFullscreen(!fullscreen)
        }
    }

    /** Brings the toolbar back if it is hidden, then puts the caret in the address field. */
    private fun focusAddressBar() {
        if (fullscreen) setFullscreen(false)
        address.requestFocus()
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
            .showSoftInput(address, InputMethodManager.SHOW_IMPLICIT)
    }

    /** Keeps the button inside the content area, whatever the offset was before. */
    private fun clampMenuButton() {
        if (menuButton.width == 0 || content.width == 0) return
        val minX = (content.paddingLeft - menuButton.left).toFloat()
        val maxX = (content.width - content.paddingRight - menuButton.right).toFloat()
        val minY = (content.paddingTop - menuButton.top).toFloat()
        val maxY = (content.height - content.paddingBottom - menuButton.bottom).toFloat()
        menuButton.translationX = menuButton.translationX.coerceIn(minOf(minX, maxX), maxOf(minX, maxX))
        menuButton.translationY = menuButton.translationY.coerceIn(minOf(minY, maxY), maxOf(minY, maxY))
    }

    private fun saveMenuButtonPosition() {
        getSharedPreferences(FAB_PREFS, Context.MODE_PRIVATE).edit()
            .putFloat(FAB_KEY_X, menuButton.translationX)
            .putFloat(FAB_KEY_Y, menuButton.translationY)
            .apply()
    }

    // ------------------------------------------------------------------ menu sheet

    /**
     * Live state for the menu sheet, read fresh each time it renders so the switch it just moved
     * and the Back tile it just used both redraw against the truth.
     */
    private fun menuState() = BrowserMenuState(
        appName = "AutoBridge",
        pageTitle = web.title?.takeIf { it.isNotBlank() } ?: displayUrl(web.url.orEmpty()),
        url = web.url.orEmpty(),
        isDesktop = BrowserUserAgentStore.mode(this) == BrowserUserAgentMode.DESKTOP,
        canGoBack = web.canGoBack(),
        canGoForward = web.canGoForward(),
        version = "v${dev.autobridge.BuildConfig.VERSION_NAME}",
        surface = MenuSurface.PHONE,
    )

    /**
     * Opens the browser menu: the same sheet the car surface draws, built from the same item lists.
     *
     * It replaced a grid of undifferentiated tiles in an `AlertDialog`. See [BrowserMenuSheet] for
     * what that cost and why the shape changed; the actions themselves are unchanged apart from the
     * two that only make sense on this surface ([DrawerAction.SEND_TO_CAR] and its opposite), which
     * used to be buried in a secondary list of ten.
     */
    private fun showMenu() {
        BrowserMenuSheet(
            activity = this,
            sizes = sizes,
            state = { menuState() },
            onNavigate = { navigate(it) },
            onAction = { runMenuAction(it) },
            onSendToCar = { openSendToCar() },
            onMore = { openMoreActions() },
        ).show()
    }

    // ------------------------------------------------------------------ send / get to car

    /**
     * Opens the dedicated "Send to car" sheet, seeded with the current page.
     *
     * The actual send is one call — [sendToCar] — so the sheet never has to know how the car is
     * reached; it only resolves what the user typed into a URL and hands it over.
     */
    private fun openSendToCar() {
        SendToCarSheet(
            activity = this,
            sizes = sizes,
            currentUrl = { web.url?.let(ContentAddress::https) },
            currentTitle = { web.title?.takeIf { it.isNotBlank() } },
            engine = { SearchEngineStore.engine(this) },
            onEngineChange = { SearchEngineStore.setEngine(this, it) },
            onSend = { input, selectedEngine ->
                sendToCar(BrowserInputResolver.resolveBrowserInput(input, selectedEngine))
            },
            onSendUrl = { url -> sendToCar(url) },
        ).show()
    }

    /**
     * Hands one resolved HTTPS URL to the browser running on the car surface, opening a fresh car
     * browser if none is on top. The single choke point every "send" path funnels through, so the
     * not-connected and empty-input cases are handled once.
     *
     * @return true when the URL was accepted by a car browser, so the caller (the sheet) can close
     *   itself only on success and leave itself open with the input intact otherwise.
     */
    private fun sendToCar(url: String?): Boolean {
        if (!allowed()) return false
        if (url.isNullOrBlank()) { toast("ยังไม่มีหน้าหรือคำค้นให้ส่ง"); return false }
        val target = CarScreenController.requireBrowser()
        if (target == null) { toast("ยังไม่ได้เชื่อมต่อ Android Auto"); return false }
        target.openUrl(url)
        toast("ส่งไปที่จอรถแล้ว")
        return true
    }

    /** Pulls whatever the car surface is showing back onto the phone. Unchanged behaviour. */
    private fun receiveFromCar() {
        val url = CarScreenController.activeBrowser?.currentUrl
        if (url.isNullOrBlank()) toast("เปิด Browser บน Android Auto ก่อน") else navigate(url)
    }

    /**
     * Opens the secondary "More actions" sheet: a compact vertical list of the rarely-used entries
     * moved off the main sheet. Each row dispatches straight back through [runMenuAction], so no
     * behaviour is duplicated here.
     */
    private fun openMoreActions() {
        MoreActionsSheet(
            activity = this,
            sizes = sizes,
            onAction = { runMenuAction(it) },
            onBack = { showMenu() },
        ).show()
    }

    /**
     * Runs one menu entry.
     *
     * Entries the phone cannot perform never reach here: [BrowserDrawerModel] builds the phone's
     * lists from [MenuSurface.PHONE] and simply does not offer the car's tabs, media screens or
     * agent, so this is not a place where unsupported actions are silently swallowed.
     */
    private fun runMenuAction(action: DrawerAction) {
        if (!allowed()) return
        when (action) {
            DrawerAction.NAV_BACK -> if (web.canGoBack()) web.goBack()
            DrawerAction.NAV_FORWARD -> if (web.canGoForward()) web.goForward()
            DrawerAction.RELOAD -> web.reload()
            DrawerAction.BOOKMARKS -> showBookmarks()
            DrawerAction.OPEN_EXTERNAL ->
                if (!BrowserLauncher.openUrl(this, web.url.orEmpty())) toast("เปิดเบราว์เซอร์ไม่ได้")
            DrawerAction.SETTINGS -> showBrowserSettings()
            DrawerAction.HOME -> navigate(BrowserStartupStore.homePage(this))
            DrawerAction.HISTORY -> showHistory()
            DrawerAction.DOWNLOADS -> showDownloads()
            DrawerAction.TOGGLE_DESKTOP -> {
                val desktop = BrowserUserAgentStore.mode(this) == BrowserUserAgentMode.DESKTOP
                BrowserUserAgentStore.select(
                    this,
                    if (desktop) BrowserUserAgentMode.MOBILE else BrowserUserAgentMode.DESKTOP
                )
                BrowserDefaults.configure(this, web)
                applyDesktopViewport()
                web.reload()
            }
            DrawerAction.BOOKMARK_PAGE -> bookmarkCurrentPage()
            DrawerAction.FIND_IN_PAGE -> showFindInPage()
            DrawerAction.COPY_URL -> {
                val manager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                manager.setPrimaryClip(ClipData.newPlainText("URL", web.url.orEmpty()))
                toast("คัดลอก URL แล้ว")
            }
            DrawerAction.PASTE_AND_GO -> clipboardText()?.let { navigate(it) } ?: toast("คลิปบอร์ดว่าง")
            DrawerAction.ZOOM_IN -> web.zoomBy(1.25f)
            DrawerAction.ZOOM_OUT -> web.zoomBy(0.8f)
            DrawerAction.CLEAR_DATA -> confirmClearBrowsingData()
            // Opens the dedicated "Send to car" sheet rather than firing immediately: the sheet
            // lets the user send the current page, a different URL, or a search query, and pick
            // the engine. One-tap "send current page" still lives there as the default.
            DrawerAction.SEND_TO_CAR -> openSendToCar()
            DrawerAction.RECEIVE_FROM_CAR -> receiveFromCar()
            DrawerAction.SUPPORT ->
                if (!BrowserLauncher.openUrl(this, "https://buymeacoffee.com/guitar.story")) toast("เปิดเบราว์เซอร์ไม่ได้")
            DrawerAction.LICENSES -> showOpenSourceLicenses()
            DrawerAction.GITHUB ->
                if (!BrowserLauncher.openUrl(this, "https://github.com/guitar-dev-io/autobridge")) toast("เปิดเบราว์เซอร์ไม่ได้")
            // The sheet's own footer pair: leaving the browser is this activity finishing.
            DrawerAction.APP_HOME -> finish()
            // Handled inside the sheet, which owns a real text field and does not need the activity
            // to open a keyboard screen the way the car surface does.
            DrawerAction.ADDRESS_KEYBOARD, DrawerAction.ADDRESS_CLEAR -> Unit
            // Car-surface entries; [MenuSurface.PHONE] never lists them.
            DrawerAction.NEW_TAB, DrawerAction.TABS, DrawerAction.MEDIA_CENTER,
            DrawerAction.NOW_PLAYING, DrawerAction.MEDIA_LIBRARY, DrawerAction.AGENT,
            DrawerAction.DIAGNOSTICS, DrawerAction.TOGGLE_FULLSCREEN, DrawerAction.SPLIT_LAYOUT -> Unit
            // Sheet navigation, resolved before an action is dispatched.
            DrawerAction.MORE, DrawerAction.BACK_TO_MENU, DrawerAction.CLOSE_SHEET -> Unit
        }
    }

    /**
     * Opens the Play-services open-source licenses screen when it is on the classpath, and
     * otherwise falls back to the LICENSE on GitHub so the entry is never a dead end on a build
     * without the oss-licenses menu. The in-app list is populated at build time by the
     * oss-licenses Gradle plugin from the dependency POMs.
     */
    private fun showOpenSourceLicenses() {
        val opened = runCatching {
            val clazz = Class.forName("com.google.android.gms.oss.licenses.OssLicensesMenuActivity")
            startActivity(Intent(this, clazz))
            true
        }.getOrDefault(false)
        if (!opened && !BrowserLauncher.openUrl(this, "https://github.com/guitar-dev-io/autobridge/blob/main/LICENSE")) {
            toast("เปิดสัญญาอนุญาตไม่ได้")
        }
    }

    /** Saves the page on screen, the one action the phone menu could list bookmarks but not add to. */
    private fun bookmarkCurrentPage() {
        val url = web.url.orEmpty()
        if (url.isBlank()) { toast("ยังไม่มีหน้าให้บันทึก"); return }
        val added = WebBookmarkStore.add(this, web.title?.takeIf { it.isNotBlank() } ?: url, url)
        toast(if (added) "บันทึกบุ๊กมาร์กแล้ว" else "บันทึกไม่สำเร็จ")
    }

    /**
     * The browser's Settings, as one scrollable [BrowserSettingsSheet] rather than the stack of
     * `AlertDialog`s this used to be. Every group reads and writes a store directly inside the
     * sheet; the callbacks here are only the apply-to-the-live-WebView side (push the new text zoom,
     * colour scheme, floating-button binding or Widevine level onto the running page), plus the two
     * editors that need a text field (Home page, custom User-Agent) and the three privacy actions.
     *
     * Shared stores mean the car surface picks the same choices up the next time its surface
     * attaches, exactly as the old dialogs did.
     */
    private fun showBrowserSettings() {
        BrowserSettingsSheet(
            activity = this,
            sizes = sizes,
            onBack = { showMenu() },
            onAppearanceChanged = {
                BrowserAppearanceStore.apply(this, web)
                web.reload()
            },
            onDisplayScaleChanged = { BrowserDisplayScaleStore.apply(this, web) },
            onFloatingButtonChanged = { applyFloatingButtonPreference() },
            onDrmChanged = {
                BrowserDefaults.applyDrmPreference(this)
                web.reload()
            },
            onStartPageBackgroundChanged = { applyStartPageBackground() },
            onEditHomePage = { showHomePageEditor() },
            onEditUserAgent = { showUserAgentChooser() },
            onResetPermissions = { confirmResetSitePermissions() },
            onDeleteSiteData = { confirmDeleteSiteData() },
            onClearBrowsingData = { confirmClearBrowsingData() },
        ).show()
    }

    /** Edits the configured Home page. A URL field, validated through [BrowserStartupStore]. */
    private fun showHomePageEditor() {
        val field = EditText(this).apply {
            setText(BrowserStartupStore.homePage(this@BrowserActivity))
            hint = "https://…"
            setSingleLine()
            setSelectAllOnFocus(true)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
        }
        val pad = sizes.dpInt(AutoUiSizes.HORIZONTAL_PADDING_DP)
        val container = FrameLayout(this).apply {
            setPadding(pad, pad / 2, pad, 0)
            addView(field)
        }
        AlertDialog.Builder(this)
            .setTitle("Home page")
            .setView(container)
            .setPositiveButton("บันทึก") { _, _ ->
                if (BrowserStartupStore.setHomePage(this, field.text.toString())) {
                    toast("ตั้งค่าหน้าแรกแล้ว")
                    showBrowserSettings()
                } else {
                    toast("URL ไม่ถูกต้อง")
                }
            }
            .setNegativeButton("ยกเลิก") { _, _ -> showBrowserSettings() }
            .show()
    }

    /**
     * Resets saved per-site permission grants. The phone browser only ever grants the protected-media
     * (Widevine) permission per request and persists nothing of its own, so this clears the WebView's
     * geolocation database — the one permission store Android keeps for a WebView — so a site that was
     * allowed to locate the user is asked again next time.
     */
    private fun confirmResetSitePermissions() {
        AlertDialog.Builder(this)
            .setTitle("Reset saved site permissions")
            .setMessage("ล้างสิทธิ์ที่เว็บไซต์เคยได้รับ (เช่น ตำแหน่งที่ตั้ง) เว็บจะถามใหม่ครั้งถัดไป")
            .setPositiveButton("รีเซ็ต") { _, _ ->
                @Suppress("DEPRECATION")
                android.webkit.GeolocationPermissions.getInstance().clearAll()
                showBrowserSettings()
                toast("รีเซ็ตสิทธิ์แล้ว")
            }
            .setNegativeButton("ยกเลิก") { _, _ -> showBrowserSettings() }
            .show()
    }

    /** Deletes cookies and local site data, leaving the cache, history and bookmarks in place. */
    private fun confirmDeleteSiteData() {
        AlertDialog.Builder(this)
            .setTitle("Delete cookies and site data")
            .setMessage("ลบคุกกี้และข้อมูลเว็บที่เก็บไว้ในเครื่อง (ประวัติและบุ๊กมาร์กจะไม่ถูกลบ)")
            .setPositiveButton("ลบ") { _, _ ->
                CookieManager.getInstance().removeAllCookies(null)
                CookieManager.getInstance().flush()
                WebStorage.getInstance().deleteAllData()
                toast("ลบคุกกี้และข้อมูลเว็บแล้ว")
                showBrowserSettings()
            }
            .setNegativeButton("ยกเลิก") { _, _ -> showBrowserSettings() }
            .show()
    }

    /**
     * Browser identity on the phone: Mobile, Desktop, a typed Custom string, or one of the presets.
     * Stored in [BrowserUserAgentStore], so the car browser picks up the same choice — typing a UA
     * here is far easier than on a head-unit keyboard.
     */
    private fun showUserAgentChooser() {
        val presets = BrowserUserAgentCodec.presets(WebSettings.getDefaultUserAgent(this))
        val mode = BrowserUserAgentStore.mode(this)
        val custom = BrowserUserAgentStore.custom(this)
        val labels = buildList {
            add(if (mode == BrowserUserAgentMode.MOBILE) "✓ Mobile" else "Mobile")
            add(if (mode == BrowserUserAgentMode.DESKTOP) "✓ Desktop" else "Desktop")
            add(if (mode == BrowserUserAgentMode.CUSTOM) "✓ กำหนดเอง… (${custom.take(40)})" else "กำหนดเอง…")
            presets.forEach { preset ->
                val selected = mode == BrowserUserAgentMode.CUSTOM && preset.userAgent == custom
                add(if (selected) "✓ ${preset.label}" else preset.label)
            }
        }.toTypedArray<CharSequence>()
        AlertDialog.Builder(this)
            .setTitle("User-Agent")
            .setItems(labels) { _, index ->
                when (index) {
                    0 -> { BrowserUserAgentStore.select(this, BrowserUserAgentMode.MOBILE); applyUserAgentChange() }
                    1 -> { BrowserUserAgentStore.select(this, BrowserUserAgentMode.DESKTOP); applyUserAgentChange() }
                    2 -> showCustomUserAgentEditor()
                    else -> {
                        BrowserUserAgentStore.saveCustom(this, presets[index - 3].userAgent)
                        applyUserAgentChange()
                    }
                }
            }
            .setNegativeButton("ยกเลิก", null)
            .show()
    }

    private fun showCustomUserAgentEditor() {
        val field = EditText(this).apply {
            setText(BrowserUserAgentStore.custom(this@BrowserActivity).ifBlank { web.settings.userAgentString.orEmpty() })
            hint = "Mozilla/5.0 (…)"
            minLines = 3
            setSelectAllOnFocus(false)
        }
        val pad = sizes.dpInt(AutoUiSizes.HORIZONTAL_PADDING_DP)
        val container = FrameLayout(this).apply {
            setPadding(pad, pad / 2, pad, 0)
            addView(field)
        }
        AlertDialog.Builder(this)
            .setTitle("User-Agent กำหนดเอง")
            .setView(container)
            .setPositiveButton("บันทึก") { _, _ ->
                if (BrowserUserAgentStore.saveCustom(this, field.text.toString())) {
                    applyUserAgentChange()
                } else {
                    toast("User-Agent ไม่ถูกต้อง (ว่าง, ยาวเกิน 512 ตัว หรือมีขึ้นบรรทัดใหม่)")
                }
            }
            .setNegativeButton("ยกเลิก", null)
            .show()
    }

    /** Pushes the stored identity onto this WebView and the car browser, then reloads. */
    private fun applyUserAgentChange() {
        BrowserDefaults.configure(this, web)
        applyDesktopViewport()
        web.reload()
        // The car browser picks the new identity up from the shared store the next time its
        // surface attaches (CarWebRenderer compares identityKey() on start).
        toast("User-Agent: ${BrowserUserAgentStore.label(this)}")
    }

    private companion object {
        /** [WebViewTimerGate] owner tag for the phone browser. */
        const val TIMER_GATE_OWNER = "phone-browser"

        /** Shared with [BrowserDefaults] and [BrowserUserAgentStore]: one browser preference file. */
        const val FAB_PREFS = "autobridge_browser"
        const val FAB_KEY_X = "menu_button_dx"
        const val FAB_KEY_Y = "menu_button_dy"
    }

    private fun clipboardText(): String? {
        val manager = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return null
        val clip = manager.primaryClip ?: return null
        if (clip.itemCount == 0) return null
        return clip.getItemAt(0).coerceToText(this)?.toString()?.trim()?.takeIf { it.isNotEmpty() }
    }

    private fun showFindInPage() {
        val query = EditText(this).apply { hint = "ค้นหาในหน้า"; setSingleLine() }
        AlertDialog.Builder(this).setTitle("ค้นหาในหน้า").setView(query)
            .setPositiveButton("ค้นหา") { _, _ ->
                if (!allowed()) return@setPositiveButton
                web.findAllAsync(query.text.toString())
                AlertDialog.Builder(this).setTitle("ค้นหาในหน้า")
                    .setPositiveButton("ถัดไป", null).setNegativeButton("ก่อนหน้า", null)
                    .setNeutralButton("ปิด") { _, _ -> web.clearMatches() }
                    .create().also { dialog ->
                        dialog.setOnShowListener {
                            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { web.findNext(true) }
                            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener { web.findNext(false) }
                        }
                        dialog.show()
                    }
            }.setNegativeButton("ยกเลิก", null).show()
    }

    /**
     * Clears the same set the car surface clears: cache, cookies, web storage, form data and the
     * visited-page list. Bookmarks are user-curated content and are deliberately left alone.
     */
    private fun confirmClearBrowsingData() {
        AlertDialog.Builder(this)
            .setTitle("ล้างข้อมูลการท่องเว็บ")
            .setMessage("ล้างแคช คุกกี้ ที่เก็บข้อมูลเว็บ และประวัติ (บุ๊กมาร์กจะไม่ถูกลบ)")
            .setPositiveButton("ล้าง") { _, _ ->
                CookieManager.getInstance().removeAllCookies(null)
                CookieManager.getInstance().flush()
                WebStorage.getInstance().deleteAllData()
                web.clearCache(true)
                web.clearFormData()
                web.clearHistory()
                WebHistoryStore.clear(this)
                toast("ล้างข้อมูลแล้ว")
            }
            .setNegativeButton("ยกเลิก", null)
            .show()
    }

    private fun showDownloads() {
        val downloads = BrowserDownloads.list(this)
        if (downloads.isEmpty()) { toast("ยังไม่มีไฟล์ที่ดาวน์โหลด"); return }
        val labels = downloads.map { entry ->
            val status = BrowserDownloads.status(this, entry.id)
            if (status == null) entry.fileName else "${entry.fileName}  •  $status"
        }
        AlertDialog.Builder(this).setTitle("ดาวน์โหลด")
            .setItems(labels.toTypedArray(), null)
            .setNeutralButton("ล้างรายการ") { _, _ -> BrowserDownloads.clear(this) }
            .setNegativeButton("ปิด", null)
            .show()
    }

    /** Tap opens the page; long-press removes it, matching the bookmarks long-press-to-remove pattern. */
    private fun showBookmarks() {
        val saved = WebBookmarkStore.list(this).toMutableList()
        if (saved.isEmpty()) { toast("ยังไม่มีบุ๊กมาร์ก"); return }
        val adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, saved.map { it.title }.toMutableList())
        val dialog = AlertDialog.Builder(this).setTitle("บุ๊กมาร์ก (กดค้างเพื่อลบ)")
            .setAdapter(adapter) { _, i -> navigate(saved[i].url) }
            .setNegativeButton("ปิด", null)
            .create()
        dialog.show()
        dialog.listView.setOnItemLongClickListener { _, _, i, _ ->
            WebBookmarkStore.remove(this, saved[i].url)
            saved.removeAt(i)
            adapter.remove(adapter.getItem(i))
            toast("ลบบุ๊กมาร์กแล้ว")
            if (saved.isEmpty()) dialog.dismiss()
            true
        }
    }

    /** Tap opens the page; long-press removes just that entry. */
    private fun showHistory() {
        val visited = WebHistoryStore.list(this).toMutableList()
        if (visited.isEmpty()) { toast("ยังไม่มีประวัติการเข้าชม"); return }
        val adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, visited.map { it.title }.toMutableList())
        val dialog = AlertDialog.Builder(this).setTitle("ประวัติ (กดค้างเพื่อลบ)")
            .setAdapter(adapter) { _, i -> navigate(visited[i].url) }
            .setNeutralButton("ล้างทั้งหมด") { _, _ -> WebHistoryStore.clear(this); toast("ล้างประวัติแล้ว") }
            .setNegativeButton("ปิด", null)
            .create()
        dialog.show()
        dialog.listView.setOnItemLongClickListener { _, _, i, _ ->
            WebHistoryStore.remove(this, visited[i].url)
            visited.removeAt(i)
            adapter.remove(adapter.getItem(i))
            if (visited.isEmpty()) dialog.dismiss()
            true
        }
    }

    /**
     * Google/Microsoft/Apple block sign-in inside any embedded WebView (anti-phishing policy); the
     * only correct handling is to hand off to a real browser surface, never a user-agent workaround.
     * Opened as a Custom Tab (shares Chrome's cookie jar) rather than a full external browser
     * switch, so a device already signed in to Chrome skips the credential prompt entirely.
     */
    private fun promptExternalSignIn(url: String) {
        AlertDialog.Builder(this)
            .setTitle("เข้าสู่ระบบ")
            .setMessage("เพื่อความปลอดภัย ผู้ให้บริการนี้ไม่อนุญาตให้ล็อกอินในเบราว์เซอร์ที่ฝังอยู่ในแอปอื่น")
            .setPositiveButton("เข้าสู่ระบบ") { _, _ ->
                if (!BrowserLauncher.openSignIn(this, url)) toast("เปิดเบราว์เซอร์ไม่ได้")
            }
            .setNegativeButton("ยกเลิก", null)
            .show()
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

    private fun enforcePolicy() {
        val permitted = allowed()
        web.visibility = if (permitted) View.VISIBLE else View.GONE
        blocked.visibility = if (permitted) View.GONE else View.VISIBLE
        blocked.text = if (permitted) "" else FeaturePolicy.app.denialMessage(Feature.BROWSER)
        if (!permitted) {
            pendingUrl = web.url?.let(ContentAddress::https) ?: pendingUrl
            web.stopLoading(); web.onPause()
            if (web.url != "about:blank") web.loadUrl("about:blank")
        } else if (resumed) {
            web.onResume()
            pendingUrl?.let { pendingUrl = null; navigate(it) }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // AutoBridgeVideoDiag (temporary): allow triggering the test page via adb without typing,
        // e.g. `adb shell am start -n dev.autobridge/.browser.BrowserActivity -d videodiag`.
        val data = intent.dataString
        if (data != null && WebVideoDiagnostics.isSentinel(data)) {
            WebVideoDiagnostics.loadTestPage(web)
            WebVideoDiagnostics.logState("BrowserActivity", web, "stage=load-test-page(intent) onCarDisplay=$onCarDisplay")
            return
        }
        data?.let(ContentAddress::https)?.let { pendingUrl = it }
        enforcePolicy()
    }

    /**
     * Handled in-process (see the manifest's `configChanges`). Recreating the Activity on rotation
     * would rebuild the WebView and restart the page from a saved state; keeping it alive means a
     * rotation resizes the view and nothing more.
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        ViewportDebug.logWindow(
            event = ViewportDebug.Event.CONFIG_CHANGE,
            widthPx = web.width, heightPx = web.height,
            density = resources.displayMetrics.density,
            fontScale = newConfig.fontScale,
            systemBars = null, imeBottom = 0,
            chromeVisible = !fullscreen, fullscreen = fullscreen,
            extra = "orientation=${newConfig.orientation} screen=${newConfig.screenWidthDp}x${newConfig.screenHeightDp}dp"
        )
    }

    override fun onResume() {
        super.onResume(); resumed = true
        // Timers are process-wide; the car browser may have paused them while it was hidden.
        WebViewTimerGate.hold(TIMER_GATE_OWNER, web)
        val previousAgent = web.settings.userAgentString
        BrowserDefaults.configure(this, web)
        enforcePolicy()
        if (allowed() && previousAgent != web.settings.userAgentString) web.reload()
    }

    override fun onPause() {
        resumed = false
        web.onPause()
        WebViewTimerGate.release(TIMER_GATE_OWNER, web)
        // WebView writes cookies to disk lazily, so a session established moments ago can still be
        // memory-only at this point. Flushing on the way to the background is what keeps a fresh
        // sign-in from being lost when the process is killed before Chromium's own periodic flush
        // runs. It blocks on I/O, which is acceptable once per backgrounding.
        CookieManager.getInstance().flush()
        super.onPause()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (fullscreenController.onBackPressed()) return
        if (fullscreen) { setFullscreen(false); return }
        if (allowed() && web.canGoBack()) { web.goBack(); return }
        super.onBackPressed()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBundle("web", Bundle().also { web.saveState(it) })
        outState.putString("pending_url", pendingUrl)
        outState.putBoolean("fullscreen", fullscreen)
        super.onSaveInstanceState(outState)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        geolocation.onRequestPermissionsResult(requestCode, grantResults)
    }

    override fun onDestroy() {
        ParkingStateStore.removeListener(parkingListener)
        youtube.release()
        geolocation.release()
        web.stopLoading(); web.destroy(); super.onDestroy()
    }
}
