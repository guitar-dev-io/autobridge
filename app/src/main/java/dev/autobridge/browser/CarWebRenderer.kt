package dev.autobridge.browser

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.SystemClock
import android.text.TextPaint
import android.text.TextUtils
import android.util.Log
import android.view.MotionEvent
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import dev.autobridge.entertainment.ContentAddress
import dev.autobridge.entertainment.WebHistoryStore
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * AA-Browser-style native web surface for Android Auto.
 *
 * A [WebView] is created off-screen (never attached to an Activity view tree), measured/laid out to
 * the car surface size, and drawn directly onto the Android Auto [Surface] via a software
 * [Canvas]. Touch/scroll events arriving from the car [androidx.car.app.SurfaceCallback] are
 * dispatched straight into the WebView, so this does NOT depend on MediaProjection mirroring or an
 * accessibility/Shizuku input backend.
 *
 * ## Layout contract
 *
 * The page is laid out **once per surface geometry change** and never re-measured for a UI state
 * change. Toolbar, drawer, tab switcher and fullscreen are composited *over* the already-laid-out
 * page ([BrowserViewport] deliberately knows nothing about chrome height), and transitions animate
 * opacity rather than size. Only three things may re-measure the page: the car surface changing
 * size, the host reporting a materially different stable area, and a device configuration change.
 * Every one of those is traced through [ViewportDebug] so an unexpected reflow can be attributed
 * rather than guessed at.
 *
 * Everything runs on the main thread because [WebView] is not thread-safe. The redraw pump repaints
 * unconditionally while the surface is attached, and that is deliberate: a detached WebView's
 * software draw is **pull-based**. Chromium rasterises during `view.draw(canvas)` and routinely
 * completes a page over several consecutive draws, so skipping frames when "nothing changed" leaves
 * a half-rastered page on screen until something forces another draw. An earlier
 * invalidation-driven pump did exactly that: after a layout change the page rendered at part of its
 * width and only completed once the user scrolled.
 *
 * Known platform limits (documented, not a bug): software-canvas capture of a WebView cannot show
 * fully hardware-accelerated composited layers (some DRM video/WebGL). This mirrors the same
 * constraint AA-Browser-style projects hit and cannot be verified from this host.
 *
 * [BrowserDefaults.grantProtectedMediaPermission] and [FullscreenVideoController] give the
 * phone-hosted, hardware-composited WebViews ([BrowserActivity], [dev.autobridge.entertainment.EntertainmentActivity])
 * a working Widevine-L3-via-EME path. This car surface intentionally does not attempt the same: doing
 * so would mean either defeating a secure/hardware-composited surface (not something this app does)
 * or a full rendering-architecture rewrite, so it stays software-canvas and DRM-blank as documented.
 */
class CarWebRenderer(context: Context) {
    private companion object {
        const val TAG = "AutoBridgeCarWeb"
        const val FRAME_INTERVAL_MS = 33L // ~30fps repaint pump
        const val DEFAULT_HOME = BrowserDefaults.HOME

        /** Per-frame velocity decay for the synthetic fling; ~0.92 reads as a natural glide. */
        const val FLING_DECAY = 0.92f
        const val FLING_MIN_VELOCITY = 12f

        /**
         * A stable-area change smaller than this is host chrome breathing, not a real layout
         * change. Re-measuring the page for it is what made the viewport drift on its own.
         */
        const val STABLE_AREA_EPSILON_DP = 4f

        /**
         * Window over which a burst of host stable-area callbacks is coalesced into one layout.
         * Long enough to swallow the connect-time storm, short enough that a genuine host chrome
         * change still lands before the user notices.
         */
        const val STABLE_AREA_DEBOUNCE_MS = 150L

        const val ZOOM_STEP = 1.25f
        const val THUMBNAIL_WIDTH = 240
    }

    /**
     * Navigation and messaging the renderer cannot perform itself. Implemented by
     * [dev.autobridge.car.CarBrowserScreen] so screen pushes keep going through the one
     * `ScreenManager` the car UI already uses, and no navigation logic is duplicated here.
     */
    interface Host {
        fun openAddressInput()
        fun openFindInPage()
        fun openAgent()
        fun openBookmarks()
        fun openHistory()
        fun openDownloads()
        fun openMediaCenter()
        fun openNowPlaying()
        fun openMediaLibrary()
        fun openSettings()
        fun openDiagnostics()
        fun openExternal(url: String)
        fun showMessage(text: String)

        /** Browser state changed in a way the car template should reflect. */
        fun onBrowserStateChanged()
    }

    /** Which full-surface overlay is currently composited over the page. */
    private enum class Overlay { NONE, DRAWER, TABS }

    /**
     * Exposes the scroll extents so page scrolling can be bounded.
     *
     * [View.scrollTo] does **not** clamp, and `WebView` does not clamp on its behalf. Driving the
     * page with an unclamped `scrollBy` let the offset run past the content and stick there —
     * observed on a head unit as `viewScroll=-279,255`, which both froze scrolling and drew the
     * page shifted sideways.
     */
    private class ScrollableWebView(context: Context) : WebView(context) {
        val horizontalRange: Int get() = computeHorizontalScrollRange()
        val verticalRange: Int get() = computeVerticalScrollRange()
        val horizontalExtent: Int get() = computeHorizontalScrollExtent()
        val verticalExtent: Int get() = computeVerticalScrollExtent()
        val maxScrollX: Int get() = (horizontalRange - horizontalExtent).coerceAtLeast(0)
        val maxScrollY: Int get() = (verticalRange - verticalExtent).coerceAtLeast(0)
    }

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())

    var host: Host? = null

    private var webView: ScrollableWebView? = null
    private var surface: Surface? = null
    private var surfaceWidth = 0
    private var surfaceHeight = 0
    private var surfaceDpi = 0
    private var running = false
    private var currentUrl: String = BrowserDefaults.lastUrl(appContext)
    private var currentTitle: String? = null
    private var loadingProgress = 100
    private var loadError: String? = null

    /** Applied host stable area. Never cleared by a transient surface teardown. */
    private var stableArea: Rect? = null
    private var pendingStableArea: Rect? = null
    private val applyStableArea = Runnable {
        val candidate = pendingStableArea ?: return@Runnable
        pendingStableArea = null
        stableArea = Rect(candidate)
        layoutWebView(surfaceWidth, surfaceHeight, ViewportDebug.Event.STABLE_AREA)
    }

    private var sizes: AutoUiSizes = AutoUiSizes.forDensity(1f)
    private var viewport: BrowserViewport = BrowserViewport.create(1, 1, 1f, 1f)
    private var chrome: BrowserChromeLayout = BrowserChromeLayout.create(sizes, viewport)
    private val visibility = ChromeVisibility()
    private var overlay: Overlay = Overlay.NONE
    private var drawer: BrowserDrawerModel? = null
    private var drawerScroll = 0f

    private var tabs: BrowserTabsState = BrowserTabsState()
    private val tabStates = HashMap<Long, Bundle>()
    private val tabThumbnails = HashMap<Long, Bitmap>()
    private var nextTabId = 1L

    private var flingX = 0f
    private var flingY = 0f


    private val webViewDensity: Float get() = appContext.resources.displayMetrics.density

    var onFindResult: ((activeMatch: Int, matchCount: Int) -> Unit)? = null

    // Latest find-in-page result (1-based active match, total matches) for UI that reads on demand.
    var findActiveMatch: Int = 0
        private set
    var findMatchCount: Int = 0
        private set
    var findQuery: String = ""
        private set

    private val toolbarPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val addressPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val detailPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glyphPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
    }

    var onPageChanged: ((url: String, title: String?) -> Unit)? = null

    /** Fired instead of navigating when a page is a provider that blocks sign-in inside any WebView. */
    var onExternalSignInRequired: ((url: String) -> Unit)? = null

    private val frameRunnable = object : Runnable {
        override fun run() {
            if (!running) return
            val now = SystemClock.uptimeMillis()
            // Idle timing and fling stepping advance every frame; the surface is then repainted
            // unconditionally, because a detached WebView only rasterises while it is being drawn.
            visibility.tick(now)
            stepFling()
            drawFrame(now)
            mainHandler.postDelayed(this, FRAME_INTERVAL_MS)
        }
    }

    val canGoBack: Boolean get() = webView?.canGoBack() == true
    val canGoForward: Boolean get() = webView?.canGoForward() == true
    val url: String get() = webView?.url ?: currentUrl
    val title: String? get() = webView?.title
    val isLoading: Boolean get() = loadingProgress < 100
    val isFullscreen: Boolean get() = visibility.fullscreen
    val hasError: Boolean get() = loadError != null
    val tabCount: Int get() = tabs.count.coerceAtLeast(1)

    // ------------------------------------------------------------------ geometry

    /**
     * Applies the host's stable area, ignoring the transient and the trivially different.
     *
     * Android Auto reports a new stable area whenever its own chrome appears or disappears. The
     * previous implementation re-measured the WebView on every one of those callbacks — and on the
     * `null` that arrives with surface teardown — so simply opening and closing a menu reflowed the
     * page twice. Only a change larger than [STABLE_AREA_EPSILON_DP] is worth a reflow.
     */
    fun setStableArea(area: Rect?) = runOnMain {
        val candidate = area?.takeUnless { it.isEmpty } ?: run {
            trace(ViewportDebug.Event.STABLE_AREA_IGNORED, "reason=empty_or_null")
            return@runOnMain
        }
        val applied = stableArea
        val epsilon = sizes.dp(STABLE_AREA_EPSILON_DP)
        if (applied != null && withinEpsilon(applied, candidate, epsilon)) {
            trace(ViewportDebug.Event.STABLE_AREA_IGNORED, "delta<${epsilon.roundToInt()}px")
            return@runOnMain
        }
        // Debounced, not just epsilon-filtered. On connect the host emits a burst of very different
        // areas within a few milliseconds (observed: 800x400 full, then 442x282 at 336,106, then the
        // real 752x300 at 24,88), and an epsilon cannot reject those because each differs wildly
        // from the last. Coalescing the burst leaves exactly one layout.
        pendingStableArea = Rect(candidate)
        mainHandler.removeCallbacks(applyStableArea)
        mainHandler.postDelayed(applyStableArea, STABLE_AREA_DEBOUNCE_MS)
    }

    private fun withinEpsilon(a: Rect, b: Rect, epsilon: Float): Boolean =
        abs(a.left - b.left) <= epsilon && abs(a.top - b.top) <= epsilon &&
            abs(a.right - b.right) <= epsilon && abs(a.bottom - b.bottom) <= epsilon

    /**
     * Fullscreen is a pure chrome change: it hides the toolbar and nothing else. The page keeps the
     * same measured size, the same scroll offset and the same DOM, so entering or leaving cannot
     * reload it or move the content.
     */
    fun setFullscreen(enabled: Boolean) = runOnMain {
        if (visibility.fullscreen == enabled) return@runOnMain
        visibility.setFullscreen(SystemClock.uptimeMillis(), enabled)
        trace(ViewportDebug.Event.FULLSCREEN, "enabled=$enabled")
        host?.onBrowserStateChanged()
    }

    fun toggleFullscreen() = setFullscreen(!visibility.fullscreen)

    // ------------------------------------------------------------------ find

    fun findInPage(query: String) = runOnMain {
        val view = webView ?: return@runOnMain
        findQuery = query
        if (query.isBlank()) {
            view.clearMatches()
            findActiveMatch = 0
            findMatchCount = 0
            onFindResult?.invoke(0, 0)
        } else {
            view.findAllAsync(query)
        }
    }

    fun findNext(forward: Boolean) = runOnMain { webView?.findNext(forward) }

    fun clearFind() = runOnMain {
        webView?.clearMatches()
        findQuery = ""
        findActiveMatch = 0
        findMatchCount = 0
    }

    /** Desktop-mode convenience toggle that flips the persisted User-Agent and reloads. */
    fun toggleDesktopMode(context: Context) = runOnMain {
        val next = if (BrowserUserAgentStore.mode(context) == BrowserUserAgentMode.DESKTOP) {
            BrowserUserAgentMode.MOBILE
        } else {
            BrowserUserAgentMode.DESKTOP
        }
        BrowserUserAgentStore.select(context, next)
        applyUserAgentAndReload()
    }

    // ------------------------------------------------------------------ lifecycle

    @SuppressLint("SetJavaScriptEnabled")
    fun start(surface: Surface, width: Int, height: Int, dpi: Int, startUrl: String? = null) {
        runOnMain {
            val firstStart = webView == null
            this.surface = surface
            this.surfaceWidth = width
            this.surfaceHeight = height
            if (dpi > 0) this.surfaceDpi = dpi
            if (webView == null) {
                webView = createWebView()
                restoreTabs()
            }
            webView?.onResume()
            layoutWebView(width, height, ViewportDebug.Event.SURFACE_AVAILABLE)
            running = true
            // Give the user the full idle window from the moment the browser appears.
            visibility.onInteraction(SystemClock.uptimeMillis())
            // Only load on the very first start (or when an explicit URL is requested). A surface
            // recreation/resize must re-fit the existing page, never reload and lose it. The
            // User-Agent is applied when the WebView is created and when the user changes it, not
            // on every re-attach — doing it here used to risk a reload on each foreground cycle.
            when {
                startUrl != null -> load(startUrl)
                firstStart -> load(tabs.active?.url ?: currentUrl)
                else -> Log.i(TAG, "Surface re-attached ${width}x$height, keeping current page")
            }
            mainHandler.removeCallbacks(frameRunnable)
            mainHandler.post(frameRunnable)
        }
    }

    /**
     * Called when the car surface itself is resized. Relays the new pixel dimensions to the WebView
     * so the page reflows while rendering and touch mapping keep the same scale. A resize to the
     * same geometry is dropped, so a duplicate host callback cannot cause a reflow.
     */
    fun resize(width: Int, height: Int, dpi: Int = surfaceDpi) {
        runOnMain {
            if (width <= 0 || height <= 0) return@runOnMain
            if (width == surfaceWidth && height == surfaceHeight && dpi == surfaceDpi) return@runOnMain
            surfaceWidth = width
            surfaceHeight = height
            if (dpi > 0) surfaceDpi = dpi
            layoutWebView(width, height, ViewportDebug.Event.SURFACE_RESIZED)
        }
    }

    fun stop() {
        runOnMain {
            running = false
            mainHandler.removeCallbacks(frameRunnable)
            surface = null
            saveActiveTabState()
            BrowserTabStore.save(appContext, tabs)
            webView?.onPause()
            trace(ViewportDebug.Event.SURFACE_DESTROYED)
        }
    }

    fun destroy() {
        runOnMain {
            running = false
            mainHandler.removeCallbacks(frameRunnable)
            mainHandler.removeCallbacks(applyStableArea)
            surface = null
            saveActiveTabState()
            BrowserTabStore.save(appContext, tabs)
            webView?.apply {
                stopLoading()
                destroy()
            }
            webView = null
            tabThumbnails.values.forEach { it.recycle() }
            tabThumbnails.clear()
            host = null
        }
    }

    // ------------------------------------------------------------------ navigation

    /** Loads a validated HTTPS URL or turns free text into a Google search. */
    fun load(input: String) {
        runOnMain {
            val target = BrowserDefaults.resolve(input)
            currentUrl = target
            loadError = null
            if (tabs.tabs.isEmpty()) {
                tabs = BrowserTabsState.single(target, id = nextTabId++)
            }
            webView?.loadUrl(target)
        }
    }

    /** Re-requests the page that failed to load (tapped from the error overlay). */
    fun retry() = runOnMain {
        loadError = null
        webView?.reload()
    }

    fun goBack() = runOnMain { webView?.let { if (it.canGoBack()) it.goBack() } }
    fun goForward() = runOnMain { webView?.let { if (it.canGoForward()) it.goForward() } }
    fun reload() = runOnMain { webView?.reload() }
    fun stopLoading() = runOnMain { webView?.stopLoading(); loadingProgress = 100 }
    fun goHome() = runOnMain { load(DEFAULT_HOME) }

    fun applyUserAgentAndReload() = runOnMain {
        val view = webView ?: return@runOnMain
        val mobileDefault = WebSettings.getDefaultUserAgent(appContext)
        val selected = BrowserUserAgentStore.resolve(appContext, mobileDefault)
        if (view.settings.userAgentString != selected) {
            view.settings.userAgentString = selected
            view.reload()
        }
    }

    // ------------------------------------------------------------------ tabs

    /**
     * Switches tabs by saving and restoring state on the **same** WebView. A second WebView per tab
     * would multiply the per-tab cost on a head unit and would break the rule that no UI state ever
     * recreates the renderer's WebView.
     */
    fun activateTab(id: Long) = runOnMain {
        if (tabs.activeId == id) return@runOnMain
        val view = webView ?: return@runOnMain
        saveActiveTabState()
        tabs = tabs.activate(id)
        val saved = tabStates[id]
        if (saved != null && view.restoreState(saved) != null) {
            currentUrl = view.url ?: tabs.active?.url ?: currentUrl
        } else {
            tabs.active?.let { view.loadUrl(it.url) }
        }
        loadError = null
        trace(ViewportDebug.Event.TAB_SWITCH, "tab=$id count=${tabs.count}")
        host?.onBrowserStateChanged()
    }

    fun openNewTab(url: String = DEFAULT_HOME) = runOnMain {
        saveActiveTabState()
        val target = BrowserDefaults.resolve(url)
        val (next, evicted) = tabs.open(nextTabId++, target)
        evicted?.let {
            tabStates.remove(it.id)
            tabThumbnails.remove(it.id)?.recycle()
        }
        tabs = next
        webView?.loadUrl(target)
        loadError = null
        overlay = Overlay.NONE
        visibility.setDrawerOpen(SystemClock.uptimeMillis(), false)
        host?.onBrowserStateChanged()
    }

    fun closeTab(id: Long) = runOnMain {
        val wasActive = tabs.activeId == id
        tabStates.remove(id)
        tabThumbnails.remove(id)?.recycle()
        tabs = tabs.close(id)
        if (tabs.tabs.isEmpty()) {
            tabs = BrowserTabsState.single(DEFAULT_HOME, id = nextTabId++)
            webView?.loadUrl(DEFAULT_HOME)
        } else if (wasActive) {
            val target = tabs.activeId
            tabs = tabs.activate(target)
            val saved = tabStates[target]
            val view = webView
            if (view != null) {
                if (saved == null || view.restoreState(saved) == null) {
                    tabs.active?.let { view.loadUrl(it.url) }
                }
            }
        }
        host?.onBrowserStateChanged()
    }

    private fun saveActiveTabState() {
        val view = webView ?: return
        val id = tabs.activeId.takeIf { it != 0L } ?: return
        runCatching {
            val bundle = Bundle()
            view.saveState(bundle)
            tabStates[id] = bundle
            captureThumbnail(id, view)
        }
    }

    private fun restoreTabs() {
        val restored = BrowserTabStore.restore(appContext)
        tabs = if (restored.tabs.isEmpty()) {
            BrowserTabsState.single(currentUrl, id = nextTabId++)
        } else {
            nextTabId = restored.tabs.maxOf { it.id } + 1
            restored
        }
        currentUrl = tabs.active?.url ?: currentUrl
    }

    private fun captureThumbnail(id: Long, view: WebView) {
        if (view.width <= 0 || view.height <= 0) return
        runCatching {
            val scale = THUMBNAIL_WIDTH.toFloat() / view.width
            val bitmap = Bitmap.createBitmap(
                THUMBNAIL_WIDTH,
                (view.height * scale).roundToInt().coerceAtLeast(1),
                Bitmap.Config.RGB_565
            )
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.WHITE)
            canvas.scale(scale, scale)
            view.draw(canvas)
            tabThumbnails.put(id, bitmap)?.recycle()
        }
    }

    // ------------------------------------------------------------------ tools

    fun zoomIn() = runOnMain { webView?.zoomBy(ZOOM_STEP) }
    fun zoomOut() = runOnMain { webView?.zoomBy(1f / ZOOM_STEP) }

    fun copyUrl(): Boolean {
        val manager = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            ?: return false
        manager.setPrimaryClip(ClipData.newPlainText("URL", url))
        return true
    }

    /** Returns clipboard text usable as an address, or null when there is nothing to paste. */
    fun clipboardText(): String? {
        val manager = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            ?: return null
        val clip = manager.primaryClip ?: return null
        if (clip.itemCount == 0) return null
        return clip.getItemAt(0).coerceToText(appContext)?.toString()?.trim()?.takeIf { it.isNotEmpty() }
    }

    /**
     * Clears the browsing data this app is responsible for: the WebView's cache, cookies, web
     * storage, form data and per-tab history, plus AutoBridge's own visited-page list. It does not
     * touch bookmarks, which are user-curated content rather than browsing traces.
     */
    fun clearBrowsingData() = runOnMain {
        val view = webView
        runCatching {
            CookieManager.getInstance().removeAllCookies(null)
            CookieManager.getInstance().flush()
            WebStorage.getInstance().deleteAllData()
            view?.clearCache(true)
            view?.clearFormData()
            view?.clearHistory()
        }
        WebHistoryStore.clear(appContext)
        tabStates.clear()
    }

    // ------------------------------------------------------------------ text input

    /**
     * Delivers [text] to the browser (spec §10). Prefers a currently focused input field inside the
     * WebView; if none is focused it falls back to loading the text as an address/search. JS is used
     * ONLY here and ONLY against this WebView, which AutoBridge fully controls; the value is escaped
     * to prevent injection. [autoSubmit] submits the owning form / triggers a search (spec §11).
     */
    fun submitText(text: String, autoSubmit: Boolean) = runOnMain {
        val view = webView
        val clean = text.trim()
        if (view == null) {
            // No live WebView: nothing focused, so treat as a search/address load.
            if (clean.isNotEmpty()) load(clean)
            return@runOnMain
        }
        if (clean.isEmpty()) {
            // Empty text is a "focus the search box" request → open a fresh search page context.
            return@runOnMain
        }
        val jsValue = jsEscape(clean)
        // Try the focused element first; if it is not a text field, fall back to load().
        val script = """
            (function(){
              var el = document.activeElement;
              var editable = el && (el.tagName === 'INPUT' || el.tagName === 'TEXTAREA' || el.isContentEditable);
              if (!editable) return 'NO_FOCUS';
              if (el.isContentEditable) { el.textContent = '$jsValue'; }
              else { el.value = '$jsValue'; el.dispatchEvent(new Event('input', {bubbles:true})); }
              ${if (autoSubmit) "if (el.form) { el.form.submit(); } else { el.dispatchEvent(new KeyboardEvent('keydown', {key:'Enter', keyCode:13, which:13, bubbles:true})); }" else ""}
              return 'OK';
            })();
        """.trimIndent()
        view.evaluateJavascript(script) { result ->
            val ok = result?.contains("OK") == true
            if (!ok) {
                // Fallback: no focused field → address/search load (auto-submits by nature).
                load(clean)
            }
        }
    }

    /** Escapes a string for safe embedding inside a single-quoted JS string literal. */
    private fun jsEscape(input: String): String = buildString {
        for (c in input) {
            when (c) {
                '\\' -> append("\\\\")
                '\'' -> append("\\'")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                ' ' -> append("\\u2028")
                ' ' -> append("\\u2029")
                '<' -> append("\\x3C")
                else -> append(c)
            }
        }
    }

    // ------------------------------------------------------------------ input

    /**
     * Routes a car surface tap. Chrome is hit-tested first and consumes the event; everything else
     * reaches the page. The hit test uses the very rectangles the toolbar was drawn from, so a
     * button can never be drawn in one place and tapped in another.
     */
    fun onSurfaceClick(x: Float, y: Float) = runOnMain {
        val now = SystemClock.uptimeMillis()
        stopFling()
        if (overlay == Overlay.TABS) {
            handleTabOverlayClick(x, y)
            return@runOnMain
        }
        val drawerPanel = drawer?.panel.takeIf { overlay == Overlay.DRAWER }
        if (overlay == Overlay.DRAWER) {
            val row = drawer?.rowAt(x, y)
            if (row != null) {
                closeDrawer()
                performDrawerAction(row.item.action)
                return@runOnMain
            }
            if (drawerPanel?.contains(x, y) != true) {
                closeDrawer()
                return@runOnMain
            }
            return@runOnMain
        }

        // The *target* state, not the animated opacity: once the user has asked for the toolbar,
        // its buttons must be hittable immediately. Testing the fade alpha made every tap during
        // the 180ms fade-in fall through to the edge-reveal band instead of the button under it.
        val chromeVisible = visibility.isShown
        val zone = chrome.hitTest(x, y, chromeVisible, null)
        if (ViewportDebug.enabled) {
            Log.i(
                TAG,
                "surfaceClick $x,$y zone=$zone chromeVisible=$chromeVisible overlay=$overlay " +
                    "vp=${viewport.left},${viewport.top} ${viewport.width}x${viewport.height}"
            )
        }
        when (zone) {
            ChromeZone.BACK -> { visibility.onInteraction(now); goBack() }
            ChromeZone.FORWARD -> { visibility.onInteraction(now); goForward() }
            ChromeZone.RELOAD -> {
                visibility.onInteraction(now)
                if (isLoading) stopLoading() else reload()
            }
            ChromeZone.ADDRESS -> { visibility.onInteraction(now); host?.openAddressInput() }
            ChromeZone.FULLSCREEN -> toggleFullscreen()
            ChromeZone.MENU -> openDrawer()
            ChromeZone.HANDLE, ChromeZone.EDGE_REVEAL -> {
                // Recalling chrome must not also leave fullscreen: the page keeps every pixel it
                // has, the toolbar simply fades back in over it.
                visibility.show(now)
            }
            ChromeZone.DRAWER_SCRIM -> closeDrawer()
            ChromeZone.TOOLBAR_BACKGROUND -> {
                // Consumed: keeps the chrome awake without letting the tap through to the page.
                visibility.onInteraction(now)
            }
            ChromeZone.NONE -> {
                visibility.onInteraction(now)
                if (loadError != null) retry() else dispatchPageTap(x, y)
            }
        }
    }

    private fun dispatchPageTap(x: Float, y: Float) {
        val view = webView ?: return
        if (!viewport.contains(x, y)) return
        val now = SystemClock.uptimeMillis()
        val webX = viewport.toWebX(x)
        val webY = viewport.toWebY(y)
        val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, webX, webY, 0)
        val up = MotionEvent.obtain(now, now + 20, MotionEvent.ACTION_UP, webX, webY, 0)
        view.dispatchTouchEvent(down)
        view.dispatchTouchEvent(up)
        down.recycle()
        up.recycle()
    }

    /**
     * Scrolls the page by a device-pixel delta, clamped to the content. Returns true when the
     * offset actually moved, which is what tells a fling it has reached the end.
     */
    private fun scrollPageBy(dx: Int, dy: Int): Boolean {
        val view = webView ?: return false
        val nextX = PageScroll.clamp(view.scrollX, dx, view.maxScrollX)
        val nextY = PageScroll.clamp(view.scrollY, dy, view.maxScrollY)
        if (nextX == view.scrollX && nextY == view.scrollY) return false
        view.scrollTo(nextX, nextY)
        return true
    }

    /** Scrolls the page content, or the open overlay, by the given car-surface delta. */
    fun scrollBy(distanceX: Float, distanceY: Float) = runOnMain {
        val now = SystemClock.uptimeMillis()
        visibility.onInteraction(now)
        if (overlay == Overlay.DRAWER) {
            val model = drawer ?: return@runOnMain
            drawerScroll = (drawerScroll + distanceY).coerceIn(0f, model.maxScroll)
            rebuildDrawer()
            return@runOnMain
        }
        if (overlay == Overlay.TABS) return@runOnMain
        val scale = viewport.scale.takeIf { it > 0f } ?: 1f
        scrollPageBy((distanceX / scale).toInt(), (distanceY / scale).toInt())
    }

    /**
     * Starts a decaying glide instead of applying the whole fling as one jump. The previous
     * `scrollBy(-velocity / 8)` teleported the page by a large delta in a single frame, which read
     * as the screen lurching rather than scrolling.
     */
    fun fling(velocityX: Float, velocityY: Float) = runOnMain {
        if (overlay != Overlay.NONE) return@runOnMain
        visibility.onInteraction(SystemClock.uptimeMillis())
        flingX = -velocityX / 60f
        flingY = -velocityY / 60f
    }

    private fun stopFling() {
        flingX = 0f
        flingY = 0f
    }

    /** Advances an in-flight fling by one frame. Returns true while the page is still moving. */
    private fun stepFling(): Boolean {
        if (abs(flingX) < FLING_MIN_VELOCITY && abs(flingY) < FLING_MIN_VELOCITY) {
            stopFling()
            return false
        }
        val scale = viewport.scale.takeIf { it > 0f } ?: 1f
        // Bounded like every other scroll; when the clamp stops the offset moving, the glide is over.
        if (!scrollPageBy((flingX / scale).toInt(), (flingY / scale).toInt())) {
            stopFling()
            return false
        }
        flingX *= FLING_DECAY
        flingY *= FLING_DECAY
        return true
    }

    /** Pinch-zoom from the car surface, mapped to the WebView's own zoom. */
    fun scaleBy(factor: Float) = runOnMain {
        if (overlay != Overlay.NONE) return@runOnMain
        if (!factor.isFinite() || factor <= 0f) return@runOnMain
        webView?.zoomBy(factor.coerceIn(0.8f, 1.25f))
    }

    // ------------------------------------------------------------------ overlays

    fun openDrawer() = runOnMain {
        drawerScroll = 0f
        overlay = Overlay.DRAWER
        rebuildDrawer()
        visibility.setDrawerOpen(SystemClock.uptimeMillis(), true)
        trace(ViewportDebug.Event.DRAWER, "open=true")
    }

    fun closeDrawer() = runOnMain {
        if (overlay == Overlay.NONE) return@runOnMain
        overlay = Overlay.NONE
        visibility.setDrawerOpen(SystemClock.uptimeMillis(), false)
        trace(ViewportDebug.Event.DRAWER, "open=false")
    }

    fun openTabSwitcher() = runOnMain {
        saveActiveTabState()
        overlay = Overlay.TABS
        visibility.setDrawerOpen(SystemClock.uptimeMillis(), true)
    }

    private fun rebuildDrawer() {
        drawer = BrowserDrawerModel.create(
            sizes,
            viewport,
            BrowserDrawerModel.sectionsFor(
                tabCount = tabCount,
                isDesktop = BrowserUserAgentStore.mode(appContext) == BrowserUserAgentMode.DESKTOP,
            ),
            drawerScroll
        )
    }

    private fun performDrawerAction(action: DrawerAction) {
        val target = host
        when (action) {
            DrawerAction.NEW_TAB -> openNewTab()
            DrawerAction.TABS -> openTabSwitcher()
            DrawerAction.HOME -> goHome()
            DrawerAction.BOOKMARKS -> target?.openBookmarks()
            DrawerAction.HISTORY -> target?.openHistory()
            DrawerAction.DOWNLOADS -> target?.openDownloads()
            DrawerAction.MEDIA_CENTER -> target?.openMediaCenter()
            DrawerAction.NOW_PLAYING -> target?.openNowPlaying()
            DrawerAction.MEDIA_LIBRARY -> target?.openMediaLibrary()
            DrawerAction.ADDRESS_KEYBOARD -> target?.openAddressInput()
            DrawerAction.FIND_IN_PAGE -> target?.openFindInPage()
            DrawerAction.AGENT -> target?.openAgent()
            DrawerAction.COPY_URL ->
                target?.showMessage(if (copyUrl()) "คัดลอก URL แล้ว" else "คัดลอกไม่สำเร็จ")
            DrawerAction.PASTE_AND_GO -> clipboardText()?.let { load(it) }
                // Covers both an empty clipboard and the platform refusing the read because the
                // app is not focused on the phone; the user gets a reason either way.
                ?: target?.showMessage("อ่านคลิปบอร์ดไม่ได้ หรือคลิปบอร์ดว่าง")
            DrawerAction.BOOKMARK_PAGE -> {
                val added = dev.autobridge.entertainment.WebBookmarkStore.add(
                    appContext, title?.takeIf { it.isNotBlank() } ?: url, url
                )
                target?.showMessage(if (added) "บันทึกบุ๊กมาร์กแล้ว" else "บันทึกไม่สำเร็จ")
            }
            DrawerAction.TOGGLE_DESKTOP -> toggleDesktopMode(appContext)
            DrawerAction.ZOOM_IN -> zoomIn()
            DrawerAction.ZOOM_OUT -> zoomOut()
            DrawerAction.RELOAD -> reload()
            DrawerAction.OPEN_EXTERNAL -> target?.openExternal(url)
            DrawerAction.SETTINGS -> target?.openSettings()
            DrawerAction.CLEAR_DATA -> {
                clearBrowsingData()
                target?.showMessage("ล้างข้อมูลการท่องเว็บแล้ว")
            }
            DrawerAction.DIAGNOSTICS -> target?.openDiagnostics()
        }
        host?.onBrowserStateChanged()
    }

    private fun handleTabOverlayClick(x: Float, y: Float) {
        val cards = tabCardLayout()
        cards.forEach { (tab, box, closeBox) ->
            if (closeBox.contains(x, y)) {
                if (tab != null) closeTab(tab.id)
                return
            }
            if (box.contains(x, y)) {
                if (tab == null) openNewTab() else {
                    activateTab(tab.id)
                    overlay = Overlay.NONE
                    visibility.setDrawerOpen(SystemClock.uptimeMillis(), false)
                }
                return
            }
        }
        overlay = Overlay.NONE
        visibility.setDrawerOpen(SystemClock.uptimeMillis(), false)
    }

    /** Tab cards laid out in a row; the trailing card (null tab) is "new tab". */
    private fun tabCardLayout(): List<Triple<BrowserTab?, Box, Box>> {
        val entries = tabs.tabs + listOf(null)
        val columns = entries.size.coerceAtMost(4)
        if (columns == 0) return emptyList()
        val gap = sizes.contentGap * 2f
        val top = viewport.top + sizes.toolbarHeight(viewport.height) + gap
        val available = viewport.width - gap * (columns + 1)
        val cardWidth = available / columns
        val rows = (entries.size + columns - 1) / columns
        val verticalSpace = viewport.top + viewport.height - top - gap
        val cardHeight = (verticalSpace / rows - gap).coerceAtLeast(sizes.touchTarget * 1.5f)
        val closeSize = sizes.touchTarget * 0.7f

        return entries.mapIndexed { index, tab ->
            val column = index % columns
            val row = index / columns
            val left = viewport.left + gap + column * (cardWidth + gap)
            val cardTop = top + row * (cardHeight + gap)
            val box = Box(left, cardTop, left + cardWidth, cardTop + cardHeight)
            val closeBox = if (tab == null) Box(0f, 0f, 0f, 0f) else Box(
                box.right - closeSize, box.top, box.right, box.top + closeSize
            )
            Triple(tab, box, closeBox)
        }
    }

    // ------------------------------------------------------------------ WebView

    private fun createWebView(): ScrollableWebView = ScrollableWebView(appContext).apply {
        BrowserDefaults.configure(appContext, settings)
        setDownloadListener(
            BrowserDownloads.listener(appContext) { message ->
                mainHandler.post { host?.showMessage(message) }
            }
        )
        webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                loadingProgress = newProgress.coerceIn(0, 100)
                onPageChanged?.invoke(view.url ?: currentUrl, view.title)
            }

            /**
             * `target="_blank"` and `window.open()` become a real new tab instead of being silently
             * dropped. The href is carried by the transport message rather than read from the
             * request, which is the only reliable way to get it for a deferred window.
             */
            override fun onCreateWindow(
                view: WebView,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: Message,
            ): Boolean {
                if (!isUserGesture) return false
                val transport = resultMsg.obj as? WebView.WebViewTransport ?: return false
                val probe = WebView(appContext).apply {
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(
                            probeView: WebView,
                            request: WebResourceRequest,
                        ): Boolean {
                            val target = request.url.toString()
                            mainHandler.post {
                                ContentAddress.https(target)?.let { openNewTab(it) }
                                probeView.destroy()
                            }
                            return true
                        }
                    }
                }
                transport.webView = probe
                resultMsg.sendToTarget()
                return true
            }
        }
        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val target = request.url.toString()
                if (BrowserDefaults.isExternalSignInHost(target)) {
                    onExternalSignInRequired?.invoke(target)
                    return true
                }
                // Only allow HTTPS navigation; block custom schemes/intents on the car surface.
                return ContentAddress.https(target) == null
            }

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                loadError = null
            }

            override fun onPageFinished(view: WebView, url: String) {
                currentUrl = url
                BrowserDefaults.remember(appContext, url)
                currentTitle = view.title
                loadingProgress = 100
                tabs = tabs.updateActive(url, view.title)
                BrowserTabStore.save(appContext, tabs)
                tracePostLoad()
                if (loadError == null) WebHistoryStore.record(appContext, view.title, url)
                onPageChanged?.invoke(url, view.title)
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (!request.isForMainFrame) return
                loadError = "โหลดหน้านี้ไม่สำเร็จ"
                onPageChanged?.invoke(currentUrl, currentTitle)
            }
        }
        setFindListener { activeMatchOrdinal, numberOfMatches, _ ->
            // WebView reports a 0-based ordinal; expose it 1-based for display ("2/8").
            findMatchCount = numberOfMatches
            findActiveMatch = if (numberOfMatches > 0) activeMatchOrdinal + 1 else 0
            onFindResult?.invoke(findActiveMatch, findMatchCount)
        }
    }

    /**
     * The single place the page is measured. Called only for real geometry changes — never for a
     * toolbar, drawer or fullscreen transition.
     */
    private fun layoutWebView(width: Int, height: Int, event: String) {
        val view = webView ?: return
        if (width <= 0 || height <= 0) return
        val area = stableArea
        sizes = AutoUiSizes.forCarSurface(surfaceDpi)
        val next = BrowserViewport.create(
            width, height, webViewDensity, sizes.density,
            area?.left ?: 0, area?.top ?: 0, area?.right ?: width, area?.bottom ?: height
        )
        val geometryUnchanged = next == viewport && view.width == next.webWidth
        viewport = next
        chrome = BrowserChromeLayout.create(sizes, viewport)
        if (overlay == Overlay.DRAWER) rebuildDrawer()
        if (geometryUnchanged) {
            trace(event, "reflow=skipped")
            return
        }

        view.layoutParams = ViewGroup.LayoutParams(viewport.webWidth, viewport.webHeight)
        view.measure(
            View.MeasureSpec.makeMeasureSpec(viewport.webWidth, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(viewport.webHeight, View.MeasureSpec.EXACTLY)
        )
        view.layout(0, 0, viewport.webWidth, viewport.webHeight)
        trace(event, "reflow=applied")
    }

    /** WebView-side geometry, so a mismatch between what is laid out and what gets painted shows up. */
    @Suppress("DEPRECATION")
    private fun webViewMetrics(): String {
        val view = webView ?: return "view=none"
        return "view=${view.width}x${view.height}" +
            " viewScroll=${view.scrollX},${view.scrollY}" +
            " pageScale=" + String.format("%.3f", view.scale) +
            " contentH=${view.contentHeight}"
    }

    private fun trace(event: String, extra: String = "") {
        ViewportDebug.log(
            event = event,
            viewport = viewport,
            surfaceWidth = surfaceWidth,
            surfaceHeight = surfaceHeight,
            surfaceDpi = surfaceDpi,
            webViewDensity = webViewDensity,
            stableArea = stableArea,
            chromeVisible = visibility.isShown,
            fullscreen = visibility.fullscreen,
            drawerOpen = overlay != Overlay.NONE,
            extra = if (extra.isBlank()) webViewMetrics() else "$extra ${webViewMetrics()}"
        )
    }

    /** Emits one trace after the page settles, when the painted result can be compared to layout. */
    private fun tracePostLoad() {
        mainHandler.postDelayed({ if (running) trace("post_load") }, 1_500L)
    }

    // ------------------------------------------------------------------ drawing

    private fun drawFrame(nowMs: Long) {
        val activeSurface = surface ?: return
        val view = webView ?: return
        if (!activeSurface.isValid || surfaceWidth <= 0 || surfaceHeight <= 0) return
        val canvas: Canvas = runCatching { activeSurface.lockCanvas(null) }.getOrNull() ?: return
        try {
            canvas.drawColor(BrowserTheme.background)
            canvas.save()
            canvas.clipRect(
                viewport.left, viewport.top,
                viewport.left + viewport.width, viewport.top + viewport.height
            )
            canvas.translate(viewport.left.toFloat(), viewport.top.toFloat())
            canvas.drawColor(Color.WHITE)
            if (loadError != null) {
                drawErrorOverlay(canvas)
            } else {
                // The page occupies the whole viewport; chrome is composited over it afterwards.
                val scale = viewport.scale
                canvas.save()
                if (scale > 0f && scale != 1f) canvas.scale(scale, scale)
                view.draw(canvas)
                canvas.restore()
            }
            canvas.restore()

            when (overlay) {
                Overlay.DRAWER -> drawDrawer(canvas)
                Overlay.TABS -> drawTabSwitcher(canvas)
                Overlay.NONE -> Unit
            }
            val alpha = visibility.alphaAt(nowMs)
            if (alpha > 0.01f) drawToolbar(canvas, alpha) else drawHandle(canvas)
        } catch (error: RuntimeException) {
            Log.w(TAG, "WebView draw to car surface failed", error)
        } finally {
            runCatching { activeSurface.unlockCanvasAndPost(canvas) }
        }
    }

    /**
     * Android-Auto-style toolbar: `‹ › ↻   page title            ⛶  ☰`
     *
     * Kept as a Canvas draw because the Car App NavigationTemplate map surface cannot host real
     * Views. Every rectangle comes from [chrome], the same object [onSurfaceClick] hit-tests, and
     * every size comes from [AutoUiSizes] rather than a per-call literal — so glyphs stay at their
     * intended dp size on every head unit instead of growing with the panel's pixel width.
     */
    private fun drawToolbar(canvas: Canvas, alpha: Float) {
        val bar = chrome.toolbar
        val opacity = (alpha.coerceIn(0f, 1f) * 255).toInt()
        toolbarPaint.color = BrowserTheme.toolbarBackground
        // Fully opaque at rest (only the fade reduces it). At 94% the page underneath bled through
        // the bar — a search-field "+" from the page appeared on top of the back button.
        toolbarPaint.alpha = opacity
        canvas.drawRect(bar.left, bar.top, bar.right, bar.bottom, toolbarPaint)

        chrome.slots.forEach { slot ->
            val enabled = when (slot.zone) {
                ChromeZone.BACK -> canGoBack
                ChromeZone.FORWARD -> canGoForward
                else -> true
            }
            glyphPaint.color = if (enabled) BrowserTheme.iconEnabled else BrowserTheme.iconDisabled
            glyphPaint.alpha = opacity
            glyphPaint.textSize = slot.iconSize
            val glyph = when (slot.zone) {
                ChromeZone.BACK -> "‹"
                ChromeZone.FORWARD -> "›"
                ChromeZone.RELOAD -> if (isLoading) "×" else "↻"
                ChromeZone.FULLSCREEN -> if (isFullscreen) "⤡" else "⛶"
                ChromeZone.MENU -> "☰"
                else -> ""
            }
            canvas.drawText(
                glyph, slot.bounds.centerX,
                slot.bounds.centerY + slot.iconSize * 0.36f, glyphPaint
            )
        }

        // Address pill: the page's identity, not a second row of controls.
        val pill = chrome.address
        if (pill.width > sizes.touchTarget) {
            addressPaint.color = BrowserTheme.addressPillBackground
            addressPaint.alpha = opacity
            canvas.drawRoundRect(
                RectF(pill.left, pill.top, pill.right, pill.bottom),
                sizes.cornerRadius, sizes.cornerRadius, addressPaint
            )
            val address = runCatching { Uri.parse(url) }.getOrNull()
            val host = address?.host.orEmpty()
            val isHttps = url.startsWith("https://", ignoreCase = true)
            detailPaint.color = if (isHttps) BrowserTheme.secureBadge else BrowserTheme.insecureBadge
            detailPaint.alpha = opacity
            detailPaint.textSize = sizes.iconSmall * 0.8f
            val badgeX = pill.left + sizes.horizontalPadding
            canvas.drawText(if (isHttps) "🔒" else "!", badgeX, pill.centerY + sizes.iconSmall * 0.3f, detailPaint)

            titlePaint.color = BrowserTheme.textPrimary
            titlePaint.alpha = opacity
            titlePaint.textSize = sizes.iconSmall * 0.85f
            val labelX = badgeX + sizes.iconSmall + sizes.contentGap
            val display = title?.takeIf { it.isNotBlank() } ?: host.ifBlank { url }
            val label = TextUtils.ellipsize(
                display, TextPaint(titlePaint),
                (pill.right - labelX - sizes.horizontalPadding).coerceAtLeast(0f),
                TextUtils.TruncateAt.END
            ).toString()
            canvas.drawText(label, labelX, pill.centerY + sizes.iconSmall * 0.3f, titlePaint)
        }

        if (loadingProgress in 0..99) {
            toolbarPaint.color = BrowserTheme.accent
            toolbarPaint.alpha = opacity
            val lineHeight = sizes.dp(2.5f)
            canvas.drawRect(
                bar.left, bar.bottom - lineHeight,
                bar.left + bar.width * (loadingProgress / 100f), bar.bottom, toolbarPaint
            )
        }
        toolbarPaint.alpha = 255
        glyphPaint.alpha = 255
        titlePaint.alpha = 255
        detailPaint.alpha = 255
        addressPaint.alpha = 255
    }

    /** Slim grab handle shown while chrome is hidden, so it can always be recalled. */
    private fun drawHandle(canvas: Canvas) {
        val handle = chrome.handle
        toolbarPaint.color = Color.argb(90, 20, 22, 26)
        canvas.drawRoundRect(
            RectF(handle.left, handle.top, handle.right, handle.bottom),
            sizes.cornerRadius, sizes.cornerRadius, toolbarPaint
        )
        addressPaint.color = Color.argb(170, 210, 214, 220)
        val barWidth = handle.width * 0.3f
        val barHeight = sizes.dp(3f)
        canvas.drawRoundRect(
            RectF(
                handle.centerX - barWidth / 2f, handle.centerY - barHeight / 2f,
                handle.centerX + barWidth / 2f, handle.centerY + barHeight / 2f
            ),
            barHeight, barHeight, addressPaint
        )
        if (loadingProgress in 0..99) {
            toolbarPaint.color = BrowserTheme.accent
            canvas.drawRect(
                viewport.left.toFloat(), viewport.top.toFloat(),
                viewport.left + viewport.width * (loadingProgress / 100f),
                viewport.top + sizes.dp(2.5f), toolbarPaint
            )
        }
    }

    private fun drawDrawer(canvas: Canvas) {
        val model = drawer ?: return
        // Scrim over the page so the drawer reads as a layer, without moving anything beneath it.
        toolbarPaint.color = BrowserTheme.scrim
        canvas.drawRect(
            viewport.left.toFloat(), viewport.top.toFloat(),
            (viewport.left + viewport.width).toFloat(), (viewport.top + viewport.height).toFloat(),
            toolbarPaint
        )
        val panel = model.panel
        toolbarPaint.color = BrowserTheme.drawerBackground
        canvas.drawRect(panel.left, panel.top, panel.right, panel.bottom, toolbarPaint)

        canvas.save()
        canvas.clipRect(panel.left, model.headerBottom, panel.right, panel.bottom)
        model.rows.forEach { row ->
            row.sectionTitle?.let { section ->
                detailPaint.color = BrowserTheme.textSecondary
                detailPaint.textSize = sizes.iconSmall * 0.7f
                canvas.drawText(
                    section.uppercase(),
                    panel.left + sizes.horizontalPadding * 1.5f,
                    row.bounds.top - sizes.contentGap, detailPaint
                )
            }
            glyphPaint.color = BrowserTheme.iconEnabled
            glyphPaint.textSize = sizes.iconMedium * 0.85f
            canvas.drawText(
                row.item.glyph,
                panel.left + sizes.horizontalPadding * 1.5f + sizes.iconMedium / 2f,
                row.bounds.centerY + sizes.iconMedium * 0.3f, glyphPaint
            )
            titlePaint.color = BrowserTheme.textPrimary
            titlePaint.textSize = sizes.iconSmall * 0.95f
            canvas.drawText(
                row.item.label,
                panel.left + sizes.horizontalPadding * 2f + sizes.iconMedium * 1.6f,
                row.bounds.centerY + sizes.iconSmall * 0.33f, titlePaint
            )
            if (row.item.value.isNotBlank()) {
                detailPaint.color = BrowserTheme.accent
                detailPaint.textSize = sizes.iconSmall * 0.85f
                val width = detailPaint.measureText(row.item.value)
                canvas.drawText(
                    row.item.value,
                    panel.right - sizes.horizontalPadding * 1.5f - width,
                    row.bounds.centerY + sizes.iconSmall * 0.3f, detailPaint
                )
            }
        }
        canvas.restore()

        // Drawer header sits flush with the toolbar so the two read as one chrome layer.
        toolbarPaint.color = BrowserTheme.toolbarBackground
        canvas.drawRect(panel.left, panel.top, panel.right, model.headerBottom, toolbarPaint)
        titlePaint.color = BrowserTheme.textPrimary
        titlePaint.textSize = sizes.iconMedium * 0.8f
        canvas.drawText(
            "AutoBridge", panel.left + sizes.horizontalPadding * 1.5f,
            (panel.top + model.headerBottom) / 2f + sizes.iconMedium * 0.28f, titlePaint
        )
    }

    private fun drawTabSwitcher(canvas: Canvas) {
        toolbarPaint.color = BrowserTheme.drawerBackground
        canvas.drawRect(
            viewport.left.toFloat(), viewport.top.toFloat(),
            (viewport.left + viewport.width).toFloat(), (viewport.top + viewport.height).toFloat(),
            toolbarPaint
        )
        titlePaint.color = BrowserTheme.textPrimary
        titlePaint.textSize = sizes.iconMedium * 0.8f
        canvas.drawText(
            "Tabs  ${tabs.count}/${BrowserTabsState.MAX_TABS}",
            viewport.left + sizes.horizontalPadding * 2f,
            viewport.top + sizes.toolbarHeight(viewport.height) * 0.65f, titlePaint
        )

        tabCardLayout().forEach { (tab, box, closeBox) ->
            toolbarPaint.color =
                if (tab != null && tab.id == tabs.activeId) BrowserTheme.accent else BrowserTheme.addressPillBackground
            canvas.drawRoundRect(
                RectF(box.left, box.top, box.right, box.bottom),
                sizes.cornerRadius, sizes.cornerRadius, toolbarPaint
            )
            if (tab == null) {
                glyphPaint.color = BrowserTheme.textPrimary
                glyphPaint.textSize = sizes.iconLarge
                canvas.drawText("+", box.centerX, box.centerY + sizes.iconLarge * 0.35f, glyphPaint)
                return@forEach
            }
            val inset = sizes.dp(3f)
            val thumbBottom = box.bottom - sizes.touchTarget * 0.85f
            val thumbnail = tabThumbnails[tab.id]
            val thumbRect = RectF(box.left + inset, box.top + inset, box.right - inset, thumbBottom)
            if (thumbnail != null && !thumbnail.isRecycled) {
                canvas.save()
                canvas.clipRect(thumbRect)
                canvas.drawBitmap(thumbnail, null, thumbRect, null)
                canvas.restore()
            } else {
                toolbarPaint.color = BrowserTheme.background
                canvas.drawRect(thumbRect, toolbarPaint)
            }
            titlePaint.color = BrowserTheme.textPrimary
            titlePaint.textSize = sizes.iconSmall * 0.85f
            val label = TextUtils.ellipsize(
                tab.displayTitle, TextPaint(titlePaint),
                box.width - sizes.horizontalPadding * 2f, TextUtils.TruncateAt.END
            ).toString()
            canvas.drawText(label, box.left + sizes.horizontalPadding, thumbBottom + sizes.iconSmall, titlePaint)
            detailPaint.color = BrowserTheme.textSecondary
            detailPaint.textSize = sizes.iconSmall * 0.7f
            canvas.drawText(
                tab.host, box.left + sizes.horizontalPadding,
                thumbBottom + sizes.iconSmall * 2f, detailPaint
            )
            glyphPaint.color = BrowserTheme.textSecondary
            glyphPaint.textSize = sizes.iconSmall
            canvas.drawText(
                "×", closeBox.centerX, closeBox.centerY + sizes.iconSmall * 0.35f, glyphPaint
            )
        }
    }

    /** Shown in place of the page when the main frame fails to load; tapping content area retries. */
    private fun drawErrorOverlay(canvas: Canvas) {
        val w = viewport.width.toFloat()
        val h = viewport.height.toFloat()
        canvas.drawColor(BrowserTheme.errorBackground)
        titlePaint.color = BrowserTheme.errorAccent
        titlePaint.textSize = sizes.iconMedium
        val message = loadError.orEmpty()
        canvas.drawText(message, (w - titlePaint.measureText(message)) / 2f, h / 2f - sizes.contentGap, titlePaint)
        detailPaint.color = BrowserTheme.textSecondary
        detailPaint.textSize = sizes.iconSmall * 0.9f
        val hint = "แตะเพื่อลองใหม่"
        canvas.drawText(hint, (w - detailPaint.measureText(hint)) / 2f, h / 2f + sizes.iconMedium, detailPaint)
    }

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block()
        else mainHandler.post(block)
    }
}
