package dev.autobridge.projection

import android.annotation.SuppressLint
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.ShapeDrawable
import android.graphics.drawable.shapes.OvalShape
import android.os.Bundle
import android.os.SystemClock
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
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import com.google.android.apps.auto.sdk.CarActivity
import com.google.android.apps.auto.sdk.SearchCallback
import com.google.android.apps.auto.sdk.SearchController
import com.google.android.apps.auto.sdk.SearchItem
import dev.autobridge.audio.WebAudioBridge
import dev.autobridge.audio.WebMediaStatus
import dev.autobridge.browser.BrowserAdBlock
import dev.autobridge.browser.BrowserControlsStore
import dev.autobridge.browser.BrowserDefaults
import dev.autobridge.browser.BrowserDisplayUrl
import dev.autobridge.browser.BrowserInputResolver
import dev.autobridge.browser.BrowserTab
import dev.autobridge.browser.BrowserTabStore
import dev.autobridge.browser.BrowserTabsState
import dev.autobridge.browser.BrowserTheme
import dev.autobridge.browser.BrowserUserAgentMode
import dev.autobridge.browser.BrowserUserAgentStore
import dev.autobridge.browser.CarKey
import dev.autobridge.browser.CarKeyboardLanguage
import dev.autobridge.browser.CarKeyboardLayouts
import dev.autobridge.browser.CarKeyboardStore
import dev.autobridge.browser.ChromeVisibility
import dev.autobridge.browser.SearchEngineStore
import dev.autobridge.browser.WebViewTimerGate
import dev.autobridge.core.model.Feature
import dev.autobridge.core.policy.FeaturePolicy
import dev.autobridge.logging.StructuredLog
import dev.autobridge.entertainment.ContentAddress
import dev.autobridge.media.WebMediaHub
import dev.autobridge.media.WebMediaSource
import dev.autobridge.remote.AutoBridgeStateRepository
import dev.autobridge.remote.CarScreenController
import dev.autobridge.safety.ParkingStateStore
import dev.autobridge.safety.SafetyEnforcement
import org.json.JSONObject

/**
 * The browser as a projection-route car activity: a real view tree on the Android Auto display,
 * opened in the main area with the navigation app kept in the side panel (see the projection
 * AndroidManifest for why this route splits the screen and the template route does not).
 *
 * A mobile-browser-like control shell around one WebView: an address/search control, navigation
 * buttons (Back, Forward, Reload, Home) plus quick-site shortcuts, a loading progress bar, and the
 * page's audio published to the media session through [WebMediaHub] so the steering wheel and the
 * media card control it. Text entry on this SDK goes through the car host's own search surface
 * ([SearchController]) rather than an app-drawn EditText, because the host owns the IME.
 *
 * Audio focus is left to the WebView's Chromium, which requests and handles it itself. Holding a
 * second request from this app is what made the template route's page pause right after play.
 */
class ProjectionBrowserActivity : CarActivity(), CarScreenController.BrowserTarget {
    private companion object {
        const val TIMER_GATE_OWNER = "projection-browser"

        /** Toolbar metrics, in dp. The touch target is the car minimum; nothing here goes under it. */
        const val BAR_HEIGHT = 60
        const val TOUCH_TARGET = 52
        const val PILL_HEIGHT = 44
        const val FAB_SIZE = 60
        const val FAB_MARGIN = 16

        /**
         * Height of the band that recalls hidden chrome. Deliberately slim — it sits over the page,
         * so every dp of it is a dp of page that does not respond to the page's own taps.
         */
        const val EDGE_REVEAL = 22

        /** How often idle time is re-checked while the toolbar is up. */
        const val CHROME_TICK_MS = 500L

        /** On-screen keyboard metrics, in dp. */
        const val KEY_HEIGHT = 48
        const val KEY_GAP = 3
        // Destinations the toolbar is worth spending width on. Google is deliberately absent: it
        // is [BrowserDefaults.HOME], so a Google chip was the Home button under a second name.
        val QUICK_SITES = listOf(
            "YouTube" to "https://m.youtube.com/",
            "YT Music" to "https://music.youtube.com/",
        )
    }

    private var webView: WebView? = null
    private var root: FrameLayout? = null
    private var blocked: TextView? = null
    private var fullscreenView: View? = null
    private var fullscreenCallback: WebChromeClient.CustomViewCallback? = null

    // Browser-shell controls. All nullable fields (not buildLayout locals) because top-level
    // methods must reach them: enforcePolicy() hides progress and the host search box, and the
    // chrome/view clients toggle forward enablement. Assigned in buildLayout()/onCreate, nulled in
    // onDestroy. Every call site uses a safe call.
    private var urlField: TextView? = null
    private var securityBadge: TextView? = null
    private var faviconView: ImageView? = null
    private var backButton: TextView? = null
    private var forwardButton: TextView? = null
    private var reloadButton: TextView? = null
    private var tabButton: TextView? = null
    private var progress: ProgressBar? = null

    /** The chrome stack (toolbar, hairline, progress) as one view, so it fades as one thing. */
    private var chromeBar: View? = null

    /** The band that brings [chromeBar] back; clickable only while the chrome is away. */
    private var edgeRevealView: View? = null

    /** The floating button: the one control reachable in every chrome state. */
    private var fab: TextView? = null

    /** The menu or tab-list overlay currently on screen, so a second one cannot stack on it. */
    private var overlay: View? = null

    /**
     * Whether the page is still loading, so the reload control can offer "stop" instead — the same
     * one-button behaviour the phone and car chrome have. Written only by `onProgressChanged`.
     */
    private var loading = false

    /** The live page's own icon, or null before one arrives. Cleared at every page start. */
    private var pageIcon: Bitmap? = null

    /**
     * The car draws the dark scheme unconditionally, as [dev.autobridge.browser.CarWebRenderer]
     * does: a light toolbar inside the host's dark dashboard frame reads as a hole in it. Held as
     * a field so every control below is tinted from one place rather than from literals.
     *
     * Named `scheme`, not `colors`: `GradientDrawable` has a `colors` property of its own, which
     * silently shadows a field of that name inside every `apply {}` on one.
     */
    private val scheme = BrowserTheme.dark

    /**
     * When the toolbar is allowed to fade. The same pure, clock-injected state machine the car
     * renderer runs, so both surfaces answer "is the chrome up?" the same way; here it decides
     * *when*, and the platform's view animator does the *how*.
     */
    private val chromeVisibility = ChromeVisibility()

    /**
     * Pinned chrome is laid out above the page; auto-hiding chrome floats over it.
     *
     * Floating is what makes a fade free — a toolbar the page is laid out beneath would resize the
     * viewport and reflow the page on every hide and every reveal. But a *pinned* bar that floats
     * would cover the top of the page forever, which on YouTube is its search field. So the pin
     * setting picks the layout, and only changing the setting (rare) costs a relayout.
     */
    private var pinnedChrome = false

    /** Re-checks idle time while the chrome is up. Stopped whenever the chrome is pinned or gone. */
    private val chromeTicker = object : Runnable {
        override fun run() {
            if (chromeVisibility.tick(SystemClock.uptimeMillis())) applyChromeVisible(false)
            if (chromeVisibility.isShown) chromeBar?.postDelayed(this, CHROME_TICK_MS)
        }
    }

    // ---------------------------------------------------------------- tabs
    //
    // One WebView, many tabs: switching saves the live WebView's own back/forward stack into a
    // Bundle and restores the target tab's. A second WebView per tab is what a phone browser can
    // afford and a head unit cannot, and it is also what BrowserTabsState was written against.

    private var tabs = BrowserTabsState()

    /** Per-tab WebView state, process-scoped by design: a back stack is not written to disk. */
    private val tabStates = HashMap<Long, Bundle>()

    private var nextTabId = 1L

    // ---------------------------------------------------------------- on-screen keyboard
    //
    // See [CarKeyboardLayouts] for why the app draws one at all. What is typed accumulates here
    // rather than going into the page keystroke by keystroke: a car keyboard is used at arm's
    // length with glances, and a per-key round trip into the page would make every typo a page
    // event. The whole string is committed once, through the same path the phone's remote uses.

    private var keyboardPanel: View? = null
    private var keyboardRows: LinearLayout? = null
    private var keyboardPreview: TextView? = null
    private val typed = StringBuilder()
    private var keyboardShift = false
    private var keyboardSymbols = false
    private var keyboardLanguage = CarKeyboardLanguage.THAI

    // SDK (com.google.android.apps.auto.sdk.SearchController): the host-serviced search surface.
    // Acquired in onCreate; the callback below is registered BEFORE any box call because
    // showSearchBox/hideSearchBox/startSearch/stopSearch throw IllegalStateException until a
    // callback is set (verified against aauto.aar bytecode).
    private var searchController: SearchController? = null

    // One reusable callback instance, registered once in onCreate in the same breath as acquisition.
    private val searchCallback = object : SearchCallback() {
        override fun onSearchTextChanged(text: String) { /* no suggestions in phase 1 */ }
        override fun onSearchItemSelected(item: SearchItem) { /* no items offered in phase 1 */ }
        override fun onSearchSubmitted(query: String): Boolean {
            navigateFromInput(query)
            runCatching { searchController?.stopSearch() }
                .onFailure { StructuredLog.w("PROJECTION", "stopSearch failed: ${it.message}") }
            return true
        }
    }

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
        // Acquire the search controller and register the callback FIRST — before the existing
        // enforcePolicy() below, which may call hideSearchBox(). Every box method throws
        // IllegalStateException until a callback is set (verified against aauto.aar bytecode), so a
        // launch-while-driving (allowed() false -> enforcePolicy() -> hideSearchBox) would crash on
        // startup without this ordering. getSearchController() can be null, so acquire defensively.
        searchController = runCatching { carUiController.searchController }.getOrNull()
        searchController?.let { controller ->
            runCatching { controller.setSearchCallback(searchCallback) }
                .onFailure { StructuredLog.w("PROJECTION", "setSearchCallback failed: ${it.message}") }
        }
        ParkingStateStore.addListener(parkingListener)
        WebMediaHub.register(this, mediaSource)
        // Held for the activity's whole life, not just while it is on top. The template route
        // registers per screen because several screens compete for the slot there; here this
        // activity IS the browser, and a link sent from the phone while the driver is looking at
        // Maps should be loaded and waiting when they come back, not dropped.
        CarScreenController.activeBrowser = this
        enforcePolicy()
        restoreTabs()
        applyChromePinning()
        webView?.loadUrl(tabs.active?.url ?: BrowserDefaults.lastUrl(this))
        StructuredLog.i("PROJECTION", "browser activity created, ${tabs.count} tab(s)")
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

                override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                    BrowserDefaults.applyIdentity(this@ProjectionBrowserActivity, view, url)
                    // The previous page's icon must not survive into this one, even for the second
                    // it takes the new one to arrive — a stale favicon is a lie about where you are.
                    pageIcon = favicon
                    syncAddress()
                    // A navigation is the moment the address and the loading bar matter most, and
                    // the progress bar lives inside the chrome — hidden chrome would swallow it.
                    revealChrome()
                    updateNav()
                }

                override fun onPageFinished(view: WebView, url: String) {
                    BrowserDefaults.remember(this@ProjectionBrowserActivity, url)
                    audio.installPlayTracking()
                    syncAddress()
                    syncTabs(url, view.title)
                    // What the Mobile Remote shows as "the car is on this page".
                    AutoBridgeStateRepository.setBrowserActive(url, view.title)
                    updateNav()
                }

                override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
                    // SPA / in-page navigations that never fire onPageFinished still move the URL.
                    syncAddress()
                    syncTabs(url, view.title)
                    updateNav()
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onPermissionRequest(request: PermissionRequest) =
                    BrowserDefaults.grantProtectedMediaPermission(request)

                override fun onShowCustomView(view: View, callback: CustomViewCallback) = enterFullscreen(view, callback)

                override fun onHideCustomView() = exitFullscreen()

                // M1 — progress-visibility ownership. onProgressChanged is the SOLE writer that
                // SHOWS progress (visible when <100 and allowed()) and HIDES it at ==100.
                // enforcePolicy() is the only other writer and it only HIDES progress as part of
                // blocking the WebView when the gate denies. If the gate flips mid-load,
                // onProgressChanged may not fire again (no progress tick), so enforcePolicy() (via
                // parkingListener) is what hides a stranded bar in that case; the allowed() guard
                // here only stops a new tick from re-showing it.
                override fun onProgressChanged(view: WebView, newProgress: Int) {
                    val bar = this@ProjectionBrowserActivity.progress
                    bar?.progress = newProgress.coerceIn(0, 100)
                    bar?.visibility = if (newProgress < 100 && allowed()) View.VISIBLE else View.GONE
                    loading = newProgress < 100
                    updateNav()
                }

                override fun onReceivedTitle(view: WebView, title: String?) {
                    // The URL is the identity; the title is only a fallback when there is no URL
                    // yet. Writes go to our TextView only, never to the host search box, so a
                    // driver typing in the host surface is never clobbered.
                    if (!view.url.isNullOrEmpty()) syncAddress()
                    else if (!title.isNullOrEmpty()) urlField?.text = title
                    view.url?.let { syncTabs(it, title) }
                    updateNav()
                }

                override fun onReceivedIcon(view: WebView, icon: Bitmap?) {
                    pageIcon = icon
                    showFavicon()
                }
            }
        }
        WebViewTimerGate.hold(TIMER_GATE_OWNER, web)
        webView = web

        // Navigation glyphs, not words. The same "‹ › ↻ ⌂" the phone and car chrome already draw,
        // which is both the house style and about a third of the width six labelled buttons took —
        // the reason the old bar ran off the right of the display and had to be scrolled to reach
        // its last shortcut.
        val back = glyphButton("‹", "Back") { navigateBack() }
        backButton = back
        val forward = glyphButton("›", "Forward") {
            if (webView?.canGoForward() == true) webView?.goForward()
        }
        forwardButton = forward
        // One control for reload and stop, swapping glyph with [loading]: while a page is still
        // coming in, "reload" is never what the driver wants and "stop" was not offered at all.
        val reload = glyphButton("↻", "Reload") {
            if (loading) webView?.stopLoading() else webView?.reload()
        }
        reloadButton = reload

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(8.dp(), 0, 8.dp(), 0)
            addView(back)
            addView(forward)
            addView(reload)
            addView(glyphButton("⌂", "Home") { open(BrowserDefaults.HOME) })
            // The pill takes the width the glyphs gave back, so the address is readable rather
            // than a 200dp stub next to six buttons.
            addView(
                addressPill(),
                LinearLayout.LayoutParams(0, PILL_HEIGHT.dp(), 1f).apply {
                    marginStart = 8.dp()
                    marginEnd = 4.dp()
                }
            )
            addView(tabCountButton())
            addView(divider())
            QUICK_SITES.forEach { (label, url) -> addView(chip(label) { open(url) }) }
        }

        // The bar can still overflow a narrow car display (a long chip list, a large host font), so
        // it stays scrollable. fillViewport is what lets the pill's weight claim the slack when it
        // does fit — without it the row is measured unbounded and the pill collapses to its minimum.
        val barScroller = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            isFillViewport = true
            setBackgroundColor(scheme.toolbarBackground)
            addView(
                bar,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
            )
        }

        // A hairline, not a colour step: it separates chrome from page even when the page's own
        // background happens to be the same near-black as the toolbar, which YouTube's is.
        val hairline = View(this).apply { setBackgroundColor(scheme.outlineVariant) }

        val progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            visibility = View.GONE
            contentDescription = "Page loading"
            progressTintList = ColorStateList.valueOf(scheme.accent)
            progressBackgroundTintList = ColorStateList.valueOf(scheme.surfaceContainerHigh)
        }
        progress = progressBar

        val blockedView = TextView(this).apply {
            setTextColor(scheme.textPrimary)
            textSize = 20f
            gravity = Gravity.CENTER
            visibility = View.GONE
        }
        blocked = blockedView

        // The three bands fade together, so they are one view rather than three siblings whose
        // alphas would have to be kept in step.
        val chrome = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(barScroller, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, BAR_HEIGHT.dp()))
            addView(hairline, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1))
            addView(progressBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 3.dp()))
        }
        chromeBar = chrome

        // Transparent on purpose: it is a tap target, not something to look at. isClickable is
        // flipped with the chrome so that while the toolbar is up this band does not sit there
        // swallowing taps aimed at the page behind it.
        val reveal = View(this).apply {
            isClickable = false
            setOnClickListener { revealChrome() }
        }
        edgeRevealView = reveal

        val floating = floatingButton()
        fab = floating

        // M2 — seed the address so it is never blank before the first onPageStarted. Uses the
        // single currentUrl/syncAddress() helper (HOME on cold start, last URL once resolved).
        syncAddress()

        return FrameLayout(this).apply {
            setBackgroundColor(scheme.background)
            // Order is z-order: page, then the band that recalls chrome, then chrome itself, then
            // the floating button, and the denial message over all of it.
            addView(web, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(
                reveal,
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, EDGE_REVEAL.dp(), Gravity.TOP)
            )
            addView(
                chrome,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP
                )
            )
            addView(
                floating,
                FrameLayout.LayoutParams(FAB_SIZE.dp(), FAB_SIZE.dp(), fabGravity()).apply {
                    setMargins(FAB_MARGIN.dp(), FAB_MARGIN.dp(), FAB_MARGIN.dp(), FAB_MARGIN.dp())
                }
            )
            addView(blockedView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            root = this
        }
    }

    /**
     * The corner the floating button sits in. The right-hand corner is where video players put
     * "next" and chat apps put "send", so a fixed right-hand button can land on exactly the control
     * the user was reaching for — hence the setting.
     */
    private fun fabGravity(): Int =
        Gravity.BOTTOM or (if (BrowserControlsStore.floatingButtonOnLeft(this)) Gravity.START else Gravity.END)

    /** The floating button: an M3 primary-container circle carrying the menu. */
    private fun floatingButton(): TextView = TextView(this).apply {
        text = "☰"
        contentDescription = "Browser menu"
        setTextColor(scheme.onFabContainer)
        textSize = 24f
        gravity = Gravity.CENTER
        background = RippleDrawable(
            ColorStateList.valueOf(scheme.outline),
            GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(scheme.fabContainer)
            },
            ShapeDrawable(OvalShape())
        )
        isClickable = true
        isFocusable = true
        setOnClickListener { openMenu() }
    }

    /**
     * The tab count, drawn the way every tabbed browser draws it: the number inside a rounded
     * square. A glyph plus a separate badge would need two views and still read as one control.
     */
    private fun tabCountButton(): TextView = TextView(this).apply {
        contentDescription = "Tabs"
        setTextColor(scheme.iconEnabled)
        textSize = 15f
        gravity = Gravity.CENTER
        background = outlinedRipple(8f)
        isClickable = true
        isFocusable = true
        setOnClickListener { noteInteraction(); openTabList() }
        layoutParams = LinearLayout.LayoutParams(34.dp(), 34.dp()).apply { marginStart = 4.dp() }
        tabButton = this
    }

    /**
     * A navigation control: a glyph in a round, 52dp car touch target with a ripple.
     *
     * [label] is never drawn — it is what TalkBack announces and what a car host's own focus UI
     * reads — so the control stays accessible while costing the width of one character.
     */
    private fun glyphButton(symbol: String, label: String, onClick: () -> Unit): TextView =
        TextView(this).apply {
            text = symbol
            contentDescription = label
            setTextColor(scheme.iconEnabled)
            textSize = 24f
            gravity = Gravity.CENTER
            minWidth = TOUCH_TARGET.dp()
            minHeight = TOUCH_TARGET.dp()
            background = circleRipple()
            isClickable = true
            isFocusable = true
            setOnClickListener { noteInteraction(); onClick() }
        }

    /** A quick-site shortcut: an M3 filled-tonal chip, so a destination never reads as a verb. */
    private fun chip(label: String, onClick: () -> Unit): TextView = TextView(this).apply {
        text = label
        contentDescription = label
        setTextColor(scheme.onSecondaryContainer)
        textSize = 15f
        gravity = Gravity.CENTER
        isSingleLine = true
        minHeight = PILL_HEIGHT.dp()
        background = roundedRipple(scheme.tileBackground, PILL_HEIGHT / 2f)
        setPadding(18.dp(), 0, 18.dp(), 0)
        isClickable = true
        isFocusable = true
        setOnClickListener { noteInteraction(); onClick() }
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, PILL_HEIGHT.dp()
        ).apply { marginStart = 6.dp() }
    }

    /** The rule that separates "what this bar does to the page" from "where it can take you". */
    private fun divider(): View = View(this).apply {
        setBackgroundColor(scheme.outlineVariant)
        layoutParams = LinearLayout.LayoutParams(1, 26.dp()).apply {
            marginStart = 4.dp()
            marginEnd = 6.dp()
        }
    }

    /**
     * The address control: a rounded pill carrying a security badge and the current URL.
     *
     * NOT editable — the host owns the IME on this SDK — so it is a display row that opens the
     * host search box on tap. The URL is shortened by [BrowserDisplayUrl]: a search result's query
     * string is thousands of characters of noise, and the host-ellipsised raw URL meant the part
     * that identifies the site scrolled out of view behind `https://www.`.
     */
    private fun addressPill(): View {
        val badge = TextView(this).apply {
            textSize = 13f
            gravity = Gravity.CENTER
            isDuplicateParentStateEnabled = true
        }
        securityBadge = badge

        // The site's own icon, which identifies a page faster than its hostname does. It replaces
        // the padlock rather than sitting beside it: on an https page the padlock is the default
        // and says nothing, while on an http one the warning is the more important of the two and
        // keeps the slot. A page with no icon falls back to the badge.
        val favicon = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            visibility = View.GONE
            isDuplicateParentStateEnabled = true
        }
        faviconView = favicon

        val text = TextView(this).apply {
            isSingleLine = true
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTextColor(scheme.textPrimary)
            textSize = 15f
            gravity = Gravity.CENTER_VERTICAL
            isDuplicateParentStateEnabled = true
        }
        urlField = text

        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = roundedRipple(scheme.addressPillBackground, PILL_HEIGHT / 2f)
            // After the background: a drawable background resets a view's padding.
            setPadding(14.dp(), 0, 14.dp(), 0)
            minimumWidth = 160.dp()
            contentDescription = "Address and search"
            isClickable = true
            // Focusable for D-pad/rotary traversal only; it responds solely to click. No soft
            // keyboard attaches — the host owns the IME on this SDK.
            isFocusable = true
            // The app's own keyboard, not the host's search box: the host box is the one surface
            // on this route that can take text, and not every host backs it with a keyboard — the
            // Desktop Head Unit opens it with nothing to type on. The box stays reachable from the
            // menu for the hosts where it does work.
            setOnClickListener { noteInteraction(); openKeyboard() }
            addView(
                favicon,
                LinearLayout.LayoutParams(18.dp(), 18.dp()).apply { marginEnd = 8.dp() }
            )
            addView(
                badge,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = 8.dp() }
            )
            addView(
                text,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            )
        }
    }

    /** A rounded outline: a control that is a container for its own content, like the tab count. */
    private fun outlinedRipple(radiusDp: Float): Drawable {
        val density = resources.displayMetrics.density
        val outline = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radiusDp * density
            setColor(Color.TRANSPARENT)
            setStroke((2 * density).toInt(), scheme.iconEnabled)
        }
        val mask = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radiusDp * density
            setColor(Color.WHITE)
        }
        return RippleDrawable(ColorStateList.valueOf(scheme.outline), outline, mask)
    }

    /** Borderless round ripple for a glyph control: feedback without a visible button edge. */
    private fun circleRipple(): Drawable =
        RippleDrawable(ColorStateList.valueOf(scheme.outline), null, ShapeDrawable(OvalShape()))

    /** A filled, rounded surface that shows a ripple clipped to its own corners. */
    private fun roundedRipple(fill: Int, radiusDp: Float): Drawable {
        fun rounded(color: Int) = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radiusDp * resources.displayMetrics.density
            setColor(color)
        }
        return RippleDrawable(
            ColorStateList.valueOf(scheme.outline), rounded(fill), rounded(Color.WHITE)
        )
    }

    private fun open(url: String) {
        if (!allowed()) return enforcePolicy()
        ContentAddress.https(url)?.let { webView?.loadUrl(it) }
    }

    /**
     * The page's current URL, or HOME when there is none yet. Single source for "current URL", and
     * what the phone reads back over [CarScreenController.BrowserTarget].
     */
    override val currentUrl: String get() = webView?.url ?: BrowserDefaults.HOME

    /**
     * Writes the live page URL into the display field (never into the host search box), shortened
     * for reading at a glance, and sets the badge that says whether the connection is encrypted.
     * The untruncated URL stays on the pill's description, so nothing is lost to a screen reader.
     */
    private fun syncAddress() {
        val url = currentUrl
        urlField?.text = BrowserDisplayUrl.compact(url)
        val secure = url.startsWith("https://", ignoreCase = true)
        securityBadge?.apply {
            text = if (secure) "\uD83D\uDD12" else "!"
            setTextColor(if (secure) scheme.secureBadge else scheme.insecureBadge)
            contentDescription = if (secure) "Secure connection" else "Not secure"
        }
        showFavicon()
        (urlField?.parent as? View)?.contentDescription = "Address and search: $url"
    }

    /**
     * Reflects the WebView's state onto the controls: a control that cannot act is dimmed and
     * un-clickable rather than silently doing nothing, and reload becomes stop while a page loads.
     */
    private fun updateNav() {
        fun TextView?.setEnabledState(enabled: Boolean) = this?.let {
            it.isEnabled = enabled
            it.alpha = if (enabled) 1f else 0.4f
        }
        backButton.setEnabledState(webView?.canGoBack() == true || fullscreenView != null)
        forwardButton.setEnabledState(webView?.canGoForward() == true)
        reloadButton?.apply {
            text = if (loading) "\u00D7" else "↻"
            contentDescription = if (loading) "Stop loading" else "Reload"
        }
    }

    // ------------------------------------------------------------------ chrome visibility

    /**
     * Records that the driver is using the chrome, so idle time cannot take the toolbar away
     * mid-reach. Called from every control on the bar rather than from a global touch hook: the
     * page's own taps go straight to the WebView and are never seen here, which is the point —
     * scrolling a page is not a reason to keep chrome over it.
     */
    private fun noteInteraction() {
        chromeVisibility.onInteraction(SystemClock.uptimeMillis())
        if (chromeBar?.visibility != View.VISIBLE) applyChromeVisible(true)
        scheduleChromeTick()
    }

    /** Brings the toolbar back from the edge band or the floating button. */
    private fun revealChrome() {
        chromeVisibility.show(SystemClock.uptimeMillis())
        applyChromeVisible(true)
        scheduleChromeTick()
    }

    private fun scheduleChromeTick() {
        val bar = chromeBar ?: return
        bar.removeCallbacks(chromeTicker)
        if (!pinnedChrome) bar.postDelayed(chromeTicker, CHROME_TICK_MS)
    }

    /**
     * Fades the chrome in or out. Alpha only — never visibility alone — so the transition reads as
     * a fade rather than a flicker, and INVISIBLE rather than GONE because the bar is an overlay
     * and taking it out of the layout would buy nothing.
     */
    private fun applyChromeVisible(shown: Boolean) {
        val bar = chromeBar ?: return
        bar.animate().cancel()
        if (shown) {
            bar.visibility = View.VISIBLE
            bar.animate().alpha(1f).setDuration(ChromeVisibility.DEFAULT_FADE_MS).start()
        } else {
            bar.animate().alpha(0f).setDuration(ChromeVisibility.DEFAULT_FADE_MS)
                .withEndAction { bar.visibility = View.INVISIBLE }.start()
        }
        // While the toolbar is up the band underneath it must not swallow page taps.
        edgeRevealView?.isClickable = !shown
        // The floating button is how everything the toolbar carries stays reachable once it has
        // gone, so by default it stays behind. Turning that off leaves the page entirely uncovered.
        fab?.visibility =
            if (shown || BrowserControlsStore.alwaysShowFloatingButton(this)) View.VISIBLE
            else View.INVISIBLE
    }

    /**
     * Applies the pin preference: a pinned bar is laid out above the page (the page shrinks once),
     * an auto-hiding one floats over it (the page never moves). See [pinnedChrome].
     */
    private fun applyChromePinning() {
        pinnedChrome = BrowserControlsStore.alwaysShowUrlBar(this)
        chromeVisibility.setAutoHide(SystemClock.uptimeMillis(), !pinnedChrome)
        webView?.let { view ->
            // The declared metrics, not a measured height: this runs before the first layout pass.
            val inset = if (pinnedChrome) (BAR_HEIGHT + 3).dp() + 1 else 0
            (view.layoutParams as? FrameLayout.LayoutParams)?.let { params ->
                if (params.topMargin != inset) {
                    params.topMargin = inset
                    view.layoutParams = params
                }
            }
        }
        if (pinnedChrome) {
            chromeBar?.removeCallbacks(chromeTicker)
            applyChromeVisible(true)
        } else {
            revealChrome()
        }
    }

    // ------------------------------------------------------------------ overlays

    /**
     * A sheet over the page: a scrim that dismisses on tap, and a rounded card of rows.
     *
     * One at a time by construction — [overlay] is replaced, never stacked — so the menu and the
     * tab list can never end up on screen together with no way to tell which tap belongs to which.
     */
    private fun showOverlay(heading: String, build: LinearLayout.() -> Unit) {
        dismissOverlay()
        closeKeyboard()
        val parent = root ?: return
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 20 * resources.displayMetrics.density
                setColor(scheme.sheetBackground)
            }
            setPadding(12.dp(), 12.dp(), 12.dp(), 12.dp())
            // Swallows its own taps: the scrim dismisses, and a miss inside the card must not.
            isClickable = true
            addView(
                TextView(this@ProjectionBrowserActivity).apply {
                    text = heading
                    setTextColor(scheme.textSecondary)
                    textSize = 14f
                    setPadding(12.dp(), 4.dp(), 12.dp(), 10.dp())
                }
            )
            build()
        }
        val scrim = FrameLayout(this).apply {
            setBackgroundColor(scheme.scrim)
            isClickable = true
            setOnClickListener { dismissOverlay() }
            addView(
                ScrollView(this@ProjectionBrowserActivity).apply { addView(card) },
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER
                ).apply { setMargins(24.dp(), 24.dp(), 24.dp(), 24.dp()) }
            )
        }
        overlay = scrim
        parent.addView(
            scrim,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
    }

    private fun dismissOverlay() {
        val current = overlay ?: return
        overlay = null
        root?.removeView(current)
    }

    /** One sheet row: a glyph, a label, and an optional second line of state. */
    private fun menuRow(
        glyph: String,
        label: String,
        detail: String? = null,
        onClick: () -> Unit,
    ): LinearLayout {
        val text = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(
                TextView(this@ProjectionBrowserActivity).apply {
                    this.text = label
                    setTextColor(scheme.textPrimary)
                    textSize = 17f
                    isSingleLine = true
                    ellipsize = android.text.TextUtils.TruncateAt.END
                }
            )
            if (!detail.isNullOrBlank()) {
                addView(
                    TextView(this@ProjectionBrowserActivity).apply {
                        this.text = detail
                        setTextColor(scheme.textSecondary)
                        textSize = 13f
                        isSingleLine = true
                        ellipsize = android.text.TextUtils.TruncateAt.END
                    }
                )
            }
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = roundedRipple(scheme.sheetCardBackground, 14f)
            setPadding(14.dp(), 0, 14.dp(), 0)
            minimumHeight = 64.dp()
            contentDescription = if (detail.isNullOrBlank()) label else "$label, $detail"
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
            addView(
                TextView(this@ProjectionBrowserActivity).apply {
                    this.text = glyph
                    setTextColor(scheme.textPrimary)
                    textSize = 20f
                    gravity = Gravity.CENTER
                },
                LinearLayout.LayoutParams(32.dp(), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    marginEnd = 14.dp()
                }
            )
            addView(
                text,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            )
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 6.dp() }
        }
    }

    /** The floating button's sheet: the controls that do not earn permanent width on the bar. */
    private fun openMenu() {
        noteInteraction()
        if (!allowed()) return enforcePolicy()
        val desktop = BrowserUserAgentStore.isDesktopIdentity(this)
        showOverlay("Browser") {
            addView(menuRow("▣", "Tabs", "${tabs.count} open") { dismissOverlay(); openTabList() })
            addView(menuRow("+", "New tab") { dismissOverlay(); openInNewTab(BrowserDefaults.HOME) })
            addView(menuRow("⛶", "Desktop site", if (desktop) "On" else "Off") {
                BrowserUserAgentStore.select(
                    this@ProjectionBrowserActivity,
                    if (desktop) BrowserUserAgentMode.MOBILE else BrowserUserAgentMode.DESKTOP
                )
                dismissOverlay()
                // The identity is read at navigation time, so the open page only changes on reload.
                webView?.reload()
            })
            addView(menuRow("⇧", "Keep toolbar on screen", if (pinnedChrome) "On" else "Off") {
                BrowserControlsStore.setAlwaysShowUrlBar(this@ProjectionBrowserActivity, !pinnedChrome)
                dismissOverlay()
                applyChromePinning()
            })
            addView(menuRow("⌨", "Keyboard") { dismissOverlay(); openKeyboard() })
            addView(menuRow("▭", "Car's own search box") { dismissOverlay(); openAddressEntry() })
        }
    }

    // ------------------------------------------------------------------ tabs

    /** The tab list: which pages are open, which one is live, and a way to close or add one. */
    private fun openTabList() {
        noteInteraction()
        if (!allowed()) return enforcePolicy()
        syncTabs(currentUrl, webView?.title)
        showOverlay("Tabs") {
            tabs.tabs.forEach { tab -> addView(tabRow(tab)) }
            if (!tabs.isFull) {
                addView(menuRow("+", "New tab") { dismissOverlay(); openInNewTab(BrowserDefaults.HOME) })
            }
        }
    }

    private fun tabRow(tab: BrowserTab): View {
        val active = tab.id == tabs.activeId
        return menuRow(if (active) "●" else "○", tab.displayTitle, tab.host) {
            dismissOverlay()
            activateTab(tab.id)
        }.apply {
            addView(
                TextView(this@ProjectionBrowserActivity).apply {
                    text = "\u2715"
                    contentDescription = "Close ${tab.displayTitle}"
                    setTextColor(scheme.textSecondary)
                    textSize = 18f
                    gravity = Gravity.CENTER
                    background = circleRipple()
                    isClickable = true
                    isFocusable = true
                    setOnClickListener {
                        closeTab(tab.id)
                        // Reopened rather than mutated: the list is derived from the state, and
                        // rebuilding it is what keeps the two from disagreeing.
                        openTabList()
                    }
                },
                LinearLayout.LayoutParams(TOUCH_TARGET.dp(), TOUCH_TARGET.dp())
            )
        }
    }

    private fun newTabId(): Long = nextTabId++

    /** Restores the saved session, or opens one tab on the last page if there is nothing saved. */
    private fun restoreTabs() {
        tabs = BrowserTabStore.restore(this)
        nextTabId = (tabs.tabs.maxOfOrNull { it.id } ?: 0L) + 1
        if (tabs.tabs.isEmpty()) {
            tabs = BrowserTabsState.single(BrowserDefaults.lastUrl(this), id = newTabId())
        }
        updateTabButton()
    }

    /** Parks the live WebView's back/forward stack under the active tab before switching away. */
    private fun saveActiveTabState() {
        val view = webView ?: return
        val id = tabs.active?.id ?: return
        val bundle = Bundle()
        if (view.saveState(bundle) != null) tabStates[id] = bundle else tabStates.remove(id)
    }

    private fun activateTab(id: Long) {
        if (id == tabs.activeId) return
        saveActiveTabState()
        tabs = tabs.activate(id)
        BrowserTabStore.save(this, tabs)
        showActiveTab()
    }

    private fun closeTab(id: Long) {
        tabStates.remove(id)
        val wasActive = id == tabs.activeId
        tabs = tabs.close(id)
        if (tabs.tabs.isEmpty()) {
            // Closing the last tab leaves a browser with nothing in it, so one is opened for it.
            tabs = BrowserTabsState.single(BrowserDefaults.HOME, id = newTabId())
        }
        BrowserTabStore.save(this, tabs)
        if (wasActive) showActiveTab()
        updateTabButton()
    }

    /**
     * Puts the active tab on screen: its saved stack when the process still has one, its URL
     * otherwise (a restored session carries URLs only — back stacks are never written to disk).
     */
    private fun showActiveTab() {
        val view = webView ?: return
        val id = tabs.activeId
        val restored = tabStates[id]?.let { view.restoreState(it) != null } == true
        if (!restored) tabs.active?.url?.let { view.loadUrl(it) }
        updateTabButton()
        syncAddress()
        updateNav()
    }

    private fun openInNewTab(url: String) {
        if (!allowed()) return enforcePolicy()
        val target = ContentAddress.https(url) ?: return
        saveActiveTabState()
        val (next, evicted) = tabs.open(newTabId(), target)
        evicted?.let { tabStates.remove(it.id) }
        tabs = next
        BrowserTabStore.save(this, tabs)
        webView?.loadUrl(target)
        updateTabButton()
    }

    /** Records the live page on the active tab, so the list and the saved session stay truthful. */
    private fun syncTabs(url: String, title: String?) {
        val next = tabs.updateActive(url, title)
        if (next == tabs) return
        tabs = next
        BrowserTabStore.save(this, tabs)
        updateTabButton()
    }

    private fun updateTabButton() {
        tabButton?.apply {
            text = tabs.count.coerceAtLeast(1).toString()
            contentDescription = "Tabs, ${tabs.count} open"
        }
    }

    /**
     * Shows the site's own icon in the address pill when there is one and the page is encrypted;
     * otherwise the security badge keeps the slot, because "not secure" outranks decoration.
     */
    private fun showFavicon() {
        val useIcon = pageIcon != null && currentUrl.startsWith("https://", ignoreCase = true)
        faviconView?.apply {
            setImageBitmap(pageIcon)
            visibility = if (useIcon) View.VISIBLE else View.GONE
        }
        securityBadge?.visibility = if (useIcon) View.GONE else View.VISIBLE
    }

    /** Opens the host search box, pre-seeded with the current URL, honouring the parked gate. */
    private fun openAddressEntry() {
        if (!allowed()) return enforcePolicy()
        val controller = searchController ?: run {
            StructuredLog.w("PROJECTION", "search controller unavailable; address entry disabled")
            return
        }
        // Box methods throw IllegalStateException until a callback is set and only catch
        // RemoteException internally. The callback is registered once in onCreate; wrapping here is
        // the fallback for a failed registration (acquired-but-unusable controller).
        runCatching {
            controller.setSearchHint("Search or type a URL")
            controller.showSearchBox()
            controller.startSearch(currentUrl)
        }.onFailure { StructuredLog.w("PROJECTION", "search box unavailable: ${it.message}") }
    }

    /** Normalizes typed text to a URL via the shared resolver, then navigates. Honours the gate. */
    private fun navigateFromInput(input: String) {
        if (!allowed()) return enforcePolicy()
        val url = BrowserInputResolver.resolveBrowserInput(input, SearchEngineStore.engine(this))
        StructuredLog.i("PROJECTION", "address entry -> $url")
        webView?.loadUrl(url)
    }

    /**
     * One step back: out of a fullscreen video first, then through page history. Named apart from
     * the [CarScreenController.BrowserTarget.goBack] override, which has to report whether there
     * was anywhere to go.
     */
    private fun navigateBack() {
        when {
            fullscreenView != null -> exitFullscreen()
            webView?.canGoBack() == true -> webView?.goBack()
        }
    }

    // ------------------------------------------------------------------ on-screen keyboard

    /**
     * Opens the app's own keyboard over the page.
     *
     * [seed] pre-fills the buffer; the address pill passes nothing, because a driver tapping it
     * almost always wants to search for something new rather than edit the URL they are on, and
     * editing a long URL a key at a time in a car is not a thing anyone does.
     */
    private fun openKeyboard(seed: String = "") {
        if (!allowed()) return enforcePolicy()
        if (keyboardPanel != null) return
        dismissOverlay()
        val parent = root ?: return
        keyboardLanguage = CarKeyboardStore.language(this)
        keyboardShift = false
        keyboardSymbols = false
        typed.setLength(0)
        typed.append(seed)

        val preview = TextView(this).apply {
            setTextColor(scheme.textPrimary)
            textSize = 20f
            isSingleLine = true
            ellipsize = android.text.TextUtils.TruncateAt.START
            gravity = Gravity.CENTER_VERTICAL
            setPadding(16.dp(), 0, 16.dp(), 0)
        }
        keyboardPreview = preview

        val rows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        keyboardRows = rows

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(scheme.surfaceContainerLow)
            setPadding(KEY_GAP.dp(), KEY_GAP.dp(), KEY_GAP.dp(), KEY_GAP.dp())
            // Taps that miss a key stay here rather than reaching the page underneath.
            isClickable = true
            // The page keeps DOM focus only while nothing else takes window focus, and the commit
            // path types into document.activeElement. So no key is focusable: they are touch
            // targets, not a focus ring. The cost is that a rotary-only head unit cannot drive
            // this keyboard — such a unit has no touch to draw it for either.
            descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            addView(
                LinearLayout(this@ProjectionBrowserActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(
                        preview,
                        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
                    )
                    addView(
                        TextView(this@ProjectionBrowserActivity).apply {
                            text = "\u2715"
                            contentDescription = "Close keyboard"
                            setTextColor(scheme.textSecondary)
                            textSize = 20f
                            gravity = Gravity.CENTER
                            background = circleRipple()
                            isClickable = true
                            setOnClickListener { closeKeyboard() }
                        },
                        LinearLayout.LayoutParams(TOUCH_TARGET.dp(), TOUCH_TARGET.dp())
                    )
                },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, TOUCH_TARGET.dp())
            )
            addView(
                rows,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }
        keyboardPanel = panel
        parent.addView(
            panel,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM
            )
        )
        // The denial message owns the screen whenever it is up, including over this.
        blocked?.bringToFront()
        // The floating button would sit on the keys, and everything it opens is unreachable while
        // typing anyway.
        fab?.visibility = View.INVISIBLE
        revealChrome()
        rebuildKeys()
        syncKeyboardPreview()
    }

    private fun closeKeyboard() {
        val panel = keyboardPanel ?: return
        keyboardPanel = null
        keyboardRows = null
        keyboardPreview = null
        typed.setLength(0)
        root?.removeView(panel)
        // Restores the floating button and the bar to whatever the chrome state already says —
        // deliberately not noteInteraction(), which would reveal them even when the caller is
        // enforcePolicy() taking the whole browser away.
        applyChromeVisible(chromeVisibility.isShown)
        scheduleChromeTick()
    }

    /** Redraws the key grid after a shift, language or layer change. */
    private fun rebuildKeys() {
        val rows = keyboardRows ?: return
        rows.removeAllViews()
        CarKeyboardLayouts.rows(keyboardLanguage, keyboardShift, keyboardSymbols).forEach { row ->
            val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            row.forEach { key ->
                line.addView(
                    keyButton(key),
                    LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.MATCH_PARENT, CarKeyboardLayouts.weight(key)
                    ).apply {
                        marginStart = KEY_GAP.dp()
                        marginEnd = KEY_GAP.dp()
                    }
                )
            }
            rows.addView(
                line,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, KEY_HEIGHT.dp()
                ).apply { topMargin = KEY_GAP.dp() }
            )
        }
    }

    private fun keyButton(key: CarKey): TextView {
        val fill = when (key) {
            is CarKey.Go -> scheme.fabContainer
            is CarKey.Text -> scheme.surfaceContainerHigh
            else -> scheme.tileBackground
        }
        val ink = when (key) {
            is CarKey.Go -> scheme.onFabContainer
            is CarKey.Text -> scheme.textPrimary
            else -> scheme.onSecondaryContainer
        }
        return TextView(this).apply {
            text = keyLabel(key)
            contentDescription = keyDescription(key)
            setTextColor(ink)
            textSize = if (key is CarKey.Text) 19f else 15f
            gravity = Gravity.CENTER
            background = roundedRipple(fill, 10f)
            isClickable = true
            isFocusable = false
            setOnClickListener { onKey(key) }
        }
    }

    private fun keyLabel(key: CarKey): String = when (key) {
        is CarKey.Text -> if (keyboardShift) key.upper else key.lower
        is CarKey.Shift -> "⇧"
        is CarKey.Backspace -> "⌫"
        is CarKey.Space -> "space"
        is CarKey.Go -> "Go"
        // Each of these names what it switches TO, not what is showing: a key labelled with the
        // state you are already in reads as "you are here" rather than as a button.
        is CarKey.Language -> if (keyboardLanguage == CarKeyboardLanguage.THAI) "EN" else "ไทย"
        is CarKey.Symbols ->
            if (keyboardSymbols) {
                if (keyboardLanguage == CarKeyboardLanguage.THAI) "กขค" else "ABC"
            } else {
                "?123"
            }
    }

    private fun keyDescription(key: CarKey): String = when (key) {
        is CarKey.Text -> keyLabel(key)
        is CarKey.Shift -> "Shift"
        is CarKey.Backspace -> "Backspace"
        is CarKey.Space -> "Space"
        is CarKey.Go -> "Search"
        is CarKey.Language -> "Switch language"
        is CarKey.Symbols -> "Switch symbols"
    }

    private fun onKey(key: CarKey) {
        when (key) {
            is CarKey.Text -> {
                typed.append(if (keyboardShift) key.upper else key.lower)
                // One-shot, as every phone keyboard behaves: shift applies to the next key only.
                if (keyboardShift) {
                    keyboardShift = false
                    rebuildKeys()
                }
            }
            is CarKey.Space -> typed.append(' ')
            is CarKey.Backspace -> if (typed.isNotEmpty()) typed.setLength(typed.length - 1)
            is CarKey.Shift -> {
                keyboardShift = !keyboardShift
                rebuildKeys()
            }
            is CarKey.Symbols -> {
                keyboardSymbols = !keyboardSymbols
                keyboardShift = false
                rebuildKeys()
            }
            is CarKey.Language -> {
                keyboardLanguage =
                    if (keyboardLanguage == CarKeyboardLanguage.THAI) CarKeyboardLanguage.LATIN
                    else CarKeyboardLanguage.THAI
                CarKeyboardStore.setLanguage(this, keyboardLanguage)
                keyboardSymbols = false
                keyboardShift = false
                rebuildKeys()
            }
            is CarKey.Go -> return commitTyped()
        }
        syncKeyboardPreview()
    }

    private fun syncKeyboardPreview() {
        keyboardPreview?.apply {
            val value = typed.toString()
            if (value.isEmpty()) {
                text = "Search or type a URL"
                setTextColor(scheme.textSecondary)
            } else {
                text = value
                setTextColor(scheme.textPrimary)
            }
        }
    }

    /**
     * Commits what was typed down the same path the phone's remote uses: into whatever field the
     * page has focused, and failing that as a search. One implementation, so typing on the car
     * screen and typing on the phone can never behave differently.
     */
    private fun commitTyped() {
        val value = typed.toString().trim()
        closeKeyboard()
        if (value.isEmpty()) return
        StructuredLog.i("PROJECTION", "car keyboard -> ${value.length} chars")
        sendTextToSearch(value, autoSubmit = true)
    }

    // ------------------------------------------------------------------ phone remote
    //
    // The head unit's host owns the IME, and it only attaches a keyboard to its search surface
    // when the car reports parked — so on the move the box opens with nowhere to type. The phone
    // is the way in, and the app already had the whole path for it: the Mobile Remote's text box
    // reaches AutoBridgeSessionManager.sendKeyboard -> TextInjectionController ->
    // CarScreenController.activeBrowser, and "Send to car" reaches BrowserPlaybackEngine ->
    // CarScreenController.requireBrowser(). Both end at whatever is registered in that one slot,
    // which until now only CarBrowserScreen ever filled. Registering here is what makes every one
    // of those phone-side controls work against the projection route as well.

    /**
     * Commands arrive off the command bus, not from the view hierarchy, so each one is posted onto
     * the WebView's own thread. A command that arrives after teardown lands on a null view and is
     * dropped, which is the right answer for "control a browser that is no longer there".
     */
    private fun onUi(action: () -> Unit) {
        webView?.post(action)
    }

    override fun openUrl(url: String) = onUi { open(url) }

    override fun reload() = onUi { webView?.reload() }

    /**
     * Reports whether there was anywhere to go before moving, which is what the router turns into
     * "done" or "nothing to go back to" on the phone.
     */
    override fun goBack(): Boolean {
        val can = fullscreenView != null || webView?.canGoBack() == true
        onUi { navigateBack() }
        return can
    }

    override fun goForward(): Boolean {
        val can = webView?.canGoForward() == true
        onUi { if (webView?.canGoForward() == true) webView?.goForward() }
        return can
    }

    /**
     * There is no way to put a page into HTML5 fullscreen from outside it — only the page's own
     * script can — so on this route "fullscreen" means the one thing this app does control: every
     * pixel of chrome off the page, or back.
     */
    override fun setFullscreen(enabled: Boolean) = onUi {
        chromeVisibility.setFullscreen(SystemClock.uptimeMillis(), enabled)
        applyChromeVisible(!enabled)
        if (!enabled) scheduleChromeTick()
    }

    override fun setDesktopMode(enabled: Boolean) = onUi {
        BrowserUserAgentStore.select(
            this,
            if (enabled) BrowserUserAgentMode.DESKTOP else BrowserUserAgentMode.MOBILE
        )
        // The identity is read at navigation time, so the open page only changes on reload.
        webView?.reload()
    }

    /**
     * Types [text] into the page, the way the car renderer does: into whatever field the page has
     * focused, and failing that into the address bar as a search.
     *
     * The fallback is the important half here. Most pages have nothing focused, so what the driver
     * gets for "ดูหนัง" typed on the phone is a search for it — which is exactly what they wanted
     * from the Google box they could not type into.
     */
    override fun sendTextToSearch(text: String, autoSubmit: Boolean) = onUi {
        val clean = text.trim()
        if (clean.isEmpty()) return@onUi
        val view = webView ?: return@onUi
        // JSONObject.quote produces a complete, escaped JS string literal (quotes included), so no
        // hand-rolled escaper has to be kept correct here.
        val value = JSONObject.quote(clean)
        val submit = if (autoSubmit) {
            "if (el.form) { el.form.submit(); } else { el.dispatchEvent(new KeyboardEvent('keydown', {key:'Enter', keyCode:13, which:13, bubbles:true})); }"
        } else {
            ""
        }
        val script = """
            (function(){
              var el = document.activeElement;
              var editable = el && (el.tagName === 'INPUT' || el.tagName === 'TEXTAREA' || el.isContentEditable);
              if (!editable) return 'NO_FOCUS';
              if (el.isContentEditable) { el.textContent = $value; }
              else { el.value = $value; el.dispatchEvent(new Event('input', {bubbles:true})); }
              $submit
              return 'OK';
            })();
        """.trimIndent()
        view.evaluateJavascript(script) { result ->
            if (result?.contains("OK") != true) navigateFromInput(clean)
        }
    }

    private fun enforcePolicy() {
        val permitted = allowed()
        if (!permitted) {
            // A sheet or keyboard left open over the denial message would still take taps. Closed
            // first, because closing restores chrome visibility and would otherwise undo the
            // INVISIBLE set below.
            dismissOverlay()
            closeKeyboard()
        }
        webView?.visibility = if (permitted) View.VISIBLE else View.INVISIBLE
        blocked?.visibility = if (permitted) View.GONE else View.VISIBLE
        blocked?.text = if (permitted) "" else FeaturePolicy.app.denialMessage(Feature.BROWSER)
        chromeBar?.visibility = if (permitted) View.VISIBLE else View.INVISIBLE
        fab?.visibility = if (permitted) View.VISIBLE else View.INVISIBLE
        if (!permitted) {
            exitFullscreen()
            audio.userPause()
            // hideSearchBox() throws IllegalStateException if no callback is set; the onCreate
            // ordering guarantees one is set before this can run, and runCatching is the fallback.
            runCatching { searchController?.hideSearchBox() }
                .onFailure { StructuredLog.w("PROJECTION", "hideSearchBox failed: ${it.message}") }
            // M1 — enforcePolicy() is the owner that HIDES stranded progress when the gate denies
            // (e.g. gate flips mid-load and onProgressChanged does not fire again). It never shows.
            progress?.visibility = View.GONE
            chromeBar?.removeCallbacks(chromeTicker)
        } else {
            revealChrome()
        }
    }

    private fun enterFullscreen(view: View, callback: WebChromeClient.CustomViewCallback) {
        exitFullscreen()
        fullscreenView = view
        fullscreenCallback = callback
        root?.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        // Back now means "leave fullscreen", so it must not be sitting there dimmed.
        updateNav()
    }

    private fun exitFullscreen() {
        val view = fullscreenView ?: return
        fullscreenView = null
        root?.removeView(view)
        fullscreenCallback?.onCustomViewHidden()
        fullscreenCallback = null
        updateNav()
    }

    override fun onBackPressed() {
        // An open sheet or keyboard is the top of the stack: back dismisses it before the page.
        if (overlay != null) return dismissOverlay()
        if (keyboardPanel != null) return closeKeyboard()
        if (fullscreenView != null || webView?.canGoBack() == true) navigateBack()
        else super.onBackPressed()
    }

    override fun onResume() {
        super.onResume()
        webView?.onResume()
        webView?.let { WebViewTimerGate.hold(TIMER_GATE_OWNER, it) }
    }

    // No onPause() override: the WebView is deliberately not paused when the host stops this
    // activity (the driver switching apps), so music keeps playing then, as it does in Fermata.

    override fun onDestroy() {
        if (CarScreenController.activeBrowser === this) CarScreenController.activeBrowser = null
        BrowserTabStore.save(this, tabs)
        tabStates.clear()
        dismissOverlay()
        closeKeyboard()
        // After closeKeyboard(), which re-arms the ticker on its way out.
        chromeBar?.removeCallbacks(chromeTicker)
        ParkingStateStore.removeListener(parkingListener)
        WebMediaHub.unregister(mediaSource)
        exitFullscreen()
        // Tear down the host search surface so it does not outlive the activity. Both throw
        // IllegalStateException when no callback is set, so wrap them.
        runCatching { searchController?.stopSearch() }
            .onFailure { StructuredLog.w("PROJECTION", "stopSearch failed: ${it.message}") }
        runCatching { searchController?.hideSearchBox() }
            .onFailure { StructuredLog.w("PROJECTION", "hideSearchBox failed: ${it.message}") }
        webView?.let { view ->
            WebViewTimerGate.release(TIMER_GATE_OWNER, view)
            view.stopLoading()
            view.destroy()
        }
        webView = null
        root = null
        searchController = null
        urlField = null
        securityBadge = null
        faviconView = null
        pageIcon = null
        backButton = null
        forwardButton = null
        reloadButton = null
        tabButton = null
        keyboardPanel = null
        keyboardRows = null
        keyboardPreview = null
        chromeBar = null
        edgeRevealView = null
        fab = null
        progress = null
        StructuredLog.i("PROJECTION", "browser activity destroyed")
        super.onDestroy()
    }
}
