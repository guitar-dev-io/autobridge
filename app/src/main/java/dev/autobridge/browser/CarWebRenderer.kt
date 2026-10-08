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
import android.graphics.drawable.Drawable
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
import androidx.annotation.VisibleForTesting
import androidx.core.content.ContextCompat
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import dev.autobridge.R
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
 * ## The WebView still needs a window
 *
 * Off-screen is not the same as windowless, and Chromium charges dearly for the difference. Its
 * tile budget comes from `BrowserViewRenderer::ComputeTileRectAndUpdateMemoryPolicy`, which opens
 * `if (!hardware_enabled_) { compositor_->SetMemoryPolicy(0u); return; }`, and `hardware_enabled_`
 * is set only by `OnDrawHardware()` — the draw pass a real window performs. A WebView in no window
 * therefore rasters on **zero bytes** of tile memory: `cc` forces through the little a draw
 * strictly requires, drops the rest, and logs `tile memory limits exceeded, some content may not
 * draw` for every frame. A light page survives it; a heavy one composites down to its root
 * background colour.
 *
 * That is the bug this cost: m.youtube.com came out as a flat black panel on the head unit (dark
 * theme, so the background it fell back to was near-black) while ordinary pages showed as blank
 * white bands. The two symptoms look unrelated and both were previously chased as raster *speed*.
 *
 * [OffscreenWebViewWindow] fixes it by hosting the same WebView in a window no one can see, while
 * everything below carries on drawing it to the car surface exactly as before.
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
 * fully hardware-accelerated composited layers (some DRM video/WebGL). Hosting the view in a
 * window ([OffscreenWebViewWindow]) restores its tile budget, which is what makes ordinary page
 * content appear; it does not make this a hardware *presentation* path, so that limit stands.
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

        /** How soon after a page play a permanent focus loss counts as the WebView's own request. */
        const val SELF_FOCUS_WINDOW_MS = 1_500L
        const val FRAME_INTERVAL_MS = 33L // ~30fps repaint pump while something is moving

        /**
         * Pump interval while nothing is known to be moving (~4fps). Still ticking, so auto-hide
         * timers advance and page changes the renderer cannot observe (title, a carousel, a page
         * repaint in legacy mode) still reach the car within a quarter second.
         */
        const val IDLE_FRAME_INTERVAL_MS = 250L

        /** Full-rate window after any call into the renderer (touch, scroll, command, settings). */
        const val ACTIVE_WINDOW_MS = 1_000L

        /**
         * Legacy mode paints the page itself, so playing media only moves as fast as the pump. The
         * page's media state is re-read this often while idle, to switch back to full rate.
         */
        const val LEGACY_MEDIA_POLL_MS = 2_000L

        /** [WebViewTimerGate] owner tag for the car browser. */
        const val TIMER_GATE_OWNER = "car-browser"

        /** M3 FAB corner: 16dp on a 56dp button. */
        const val FAB_CORNER_FRACTION = 16f / 56f

        /**
         * Immersive fullscreen: the floating button stays this long after the last touch, then
         * fades out over [FULLSCREEN_FAB_FADE_MS]. Any tap or scroll brings it back.
         */
        const val FULLSCREEN_FAB_IDLE_MS = 3_000L
        const val FULLSCREEN_FAB_FADE_MS = 250L
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
         * Empty band between split panes; the window background shows through as the divider.
         * Wide enough to read as a channel the seam control sits in, rather than a hairline.
         */
        const val SPLIT_GAP_DP = 10f

        /** Narrowest pane a split may produce; below this the surface stays single. */
        const val SPLIT_MIN_PANE_DP = 180f

        /**
         * How far around the seam control's grip a tap still grabs it: the grip is a slim capsule,
         * so the grab target is a finger's width around it.
         */
        const val SPLIT_GRAB_DP = 24f

        /** How long after a tap on the page its focused field is read; see [offerFieldInput]. */
        const val FIELD_FOCUS_DELAY_MS = 300L

        /**
         * The seam control: a slim capsule on the divider, [SEAM_CAPSULE_THICK_DP] across and
         * [SEAM_CAPSULE_LONG_DP] along it. Its first [SEAM_CLOSE_DP] is the ✕; the rest is the
         * grip that is dragged.
         */
        const val SEAM_CAPSULE_THICK_DP = 22f
        const val SEAM_CAPSULE_LONG_DP = 92f
        const val SEAM_CLOSE_DP = 32f

        /** How often a divider drag is allowed to re-measure the panes. See `dragDivider`. */
        const val DIVIDER_LAYOUT_INTERVAL_MS = 80L

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

        /**
         * A text field on the page took focus. The host offers a way to type into it, seeded with
         * [current], and sends the result back through [submitText].
         */
        fun openFieldInput(current: String)
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

        /** Shows the phone's screen (Bridge Mirror) in place of the browser. */
        fun openMirror()

        /** Lets the driver pick a split layout; the renderer re-reads it on return. */
        fun openSplitChooser()
        fun openExternal(url: String)

        /**
         * Starts turn-by-turn navigation to [destination] in the car's navigation app. Called when
         * the split's Google Maps page asks for the Maps app, which the mobile web needs for any
         * actual navigation; see [MapsHandoff].
         */
        fun startNavigation(destination: String)
        fun showMessage(text: String)
        /** Leaves the browser entirely and returns to AutoBridge's main dashboard. */
        fun openAppHome()

        /** Browser state changed in a way the car template should reflect. */
        fun onBrowserStateChanged()
    }

    /** Which full-surface overlay is currently composited over the page. */
    private enum class Overlay { NONE, DRAWER, DRAWER_MORE, DRAWER_SPLIT, TABS }

    /** Any page of the menu sheet: the main list, More, or the split page. */
    private val drawerOpen: Boolean
        get() = overlay == Overlay.DRAWER || overlay == Overlay.DRAWER_MORE || overlay == Overlay.DRAWER_SPLIT

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

    /**
     * The off-screen window that gives [webView] a non-zero tile budget. See
     * [OffscreenWebViewWindow] — without it Chromium rasters almost nothing for a view that is in
     * no window, and a heavy page comes out as its background colour and nothing else.
     */
    private var rasterHost: OffscreenWebViewWindow? = null

    /**
     * Whether hosting has already been tried. A platform that refuses once will refuse again, and
     * retrying on every geometry change would log the same warning for the life of the session.
     */
    private var rasterHostAttempted = false

    /**
     * Set when [stop] released the raster host because nothing was playing, so [start] knows to
     * host the view again. Kept apart from [rasterHostAttempted]: a platform that refused hosting
     * must not be retried, but a host this class released on purpose must come back.
     */
    private var rasterHostReleasedOnStop = false

    /**
     * Requested render path. [CarBrowserRenderMode.HARDWARE] unless the rollback preference says
     * otherwise; [activeMode] drops to legacy for the session if the hardware window cannot be made.
     */
    private val requestedMode: CarBrowserRenderMode = CarBrowserRenderMode.current(appContext)
    private var activeMode: CarBrowserRenderMode = requestedMode

    /** The real window on a VirtualDisplay backed by the car surface. Null in legacy mode. */
    private var hardwareWindow: CarHardwareWebWindow? = null

    private val hardwareMode: Boolean get() = activeMode == CarBrowserRenderMode.HARDWARE

    /** True while a page video (or any element) is in Chromium fullscreen on the car display. */
    val isVideoFullscreen: Boolean get() = hardwareWindow?.fullscreen?.isShowing == true
    private var surface: Surface? = null
    private var surfaceWidth = 0
    private var surfaceHeight = 0
    private var surfaceDpi = 0
    private var running = false

    /**
     * A page requested while no surface was attached, applied by the next [start].
     *
     * Home tiles and the streaming list call [load] *before* pushing the browser screen, so the
     * request lands while the surface is detached and the WebView is paused (and its timers may be
     * gated off process-wide by [WebViewTimerGate]). `loadUrl` on a WebView in that state does not
     * reliably navigate, and [start] then takes its "keeping current page" branch because nothing
     * told it a different page was wanted — so tapping TikTok while YouTube was left open re-opened
     * YouTube. Holding the target here makes the hand-off explicit instead of depending on what a
     * detached WebView does with a queued navigation.
     */
    private var pendingStartUrl: String? = null
    /** Bumped on every start()/stop(), so a late async check from an older stop() is dropped. */
    private var surfaceGeneration = 0
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

    /**
     * The card: the whole area the browser owns. Chrome (toolbar, drawer, tab switcher, FAB) is laid
     * out against this. Without a split it is also where the main page sits.
     */
    private var viewport: BrowserViewport = BrowserViewport.create(1, 1, 1f)

    /** Where the main page ([webView]) sits. Equal to [viewport] unless the surface is split. */
    private var mainViewport: BrowserViewport = viewport

    // ------------------------------------------------------------------ split (side pane)

    /**
     * The optional second page shown beside the main one ([BrowserSplitLayout]). It is a plain
     * page — no tabs of its own — and exists only while a split layout is active, so a single-page
     * session pays nothing for it. HARDWARE mode only: the legacy canvas path draws one view.
     */
    private var sideView: ScrollableWebView? = null
    private var sidePageDensity: PageDensityDisplay? = null
    private var sideViewport: BrowserViewport? = null
    private var sideLastPageScalePercent = 0
    private var sideUrl: String = BrowserSplitStore.sideUrl(appContext)
    private var sideTitle: String? = null
    private var sideProgress = 100

    /** Applied split preferences, so [applyControlSettings] only re-lays out when they move. */
    private var splitLayout: BrowserSplitLayout = BrowserSplitStore.layout(appContext)
    private var splitSideOnRight: Boolean = BrowserSplitStore.sideOnRight(appContext)

    /** The ratio a dragged divider left behind, or null while the preset's own shape applies. */
    private var splitFraction: Float? = BrowserSplitStore.sideFraction(appContext)

    /** The panes as last measured, which is what a divider drag moves. Null without a split. */
    private var splitPanes: SplitPanes? = null

    /**
     * Whether the divider is being dragged. Grabbed by tapping it and let go by tapping anywhere
     * else, because the host reports a scroll as a bare distance with no position (see
     * SurfaceCallback) — the tap is the only part of the gesture that says *what* is being dragged.
     */
    private var dividerGrabbed = false

    /** Whether a divider drag has a re-layout queued; see [dragDivider]. */
    private var dividerLayoutPending = false

    /**
     * Which pane navigation, the address bar, scrolling and zoom act on. Set by tapping a pane; the
     * toolbar shows the focused pane's page. Tabs, find and the error overlay stay with the main page.
     */
    private var sideFocused = false

    private val isSplit: Boolean get() = sideView != null && sideViewport != null
    private val sideActive: Boolean get() = sideFocused && isSplit

    /** The WebView user commands go to: the side page while it has focus, else the main page. */
    private val focusedView: ScrollableWebView? get() = if (sideActive) sideView else webView
    /** Where the host leaves room for our controls; null until it reports a stable area. */
    private var chromeBounds: Box? = null
    private var chrome: BrowserChromeLayout = BrowserChromeLayout.create(sizes, viewport, showMenuButton = true)
    // The browser opens fullscreen by default; a fresh session starts pinned, exactly as if the
    // user had just tapped the fullscreen toggle. [ChromeVisibility] itself stays neutral
    // (defaults off) since it is a general chrome-visibility state machine asserted on its own in
    // ChromeVisibilityTest — this is CarWebRenderer's own session-start policy, applied once here
    // rather than on every [start] (which runs again each time the screen is reattached and must
    // not stomp on a choice the user already made during the session).
    private val visibility = ChromeVisibility().apply { setFullscreen(0L, true) }
    private var overlay: Overlay = Overlay.NONE
    private var drawer: BrowserDrawerModel? = null
    private var drawerScroll = 0f

    private var tabs: BrowserTabsState = BrowserTabsState()
    private val tabStates = HashMap<Long, Bundle>()
    private val tabThumbnails = HashMap<Long, Bitmap>()
    private var nextTabId = 1L

    private var flingX = 0f
    private var flingY = 0f

    /** Last value handed to `setInitialScale`, so an unchanged scale costs nothing. */
    private var lastPageScalePercent = 0

    /**
     * Reused by [drawFrame] for the page clip. Both used to be allocated inside the draw loop, so
     * every frame handed the collector a Path and a RectF - garbage produced at frame rate, in the
     * one code path whose whole problem is that it cannot keep up with a scroll.
     */
    private val clipRect = RectF()
    private val clipPath = android.graphics.Path()


    /**
     * The density the WebView scales by: [PageDensityDisplay]'s when it exists, else the phone's.
     * **Traced only.** It no longer sizes anything.
     *
     * It used to multiply the page's CSS width to get the view's pixel width, which is what made
     * the off-screen WebView many times larger than the surface it is drawn to. Re-basing the
     * WebView's Context to the panel's density was tried first and does not work: the device scale
     * factor comes from the application, so only the layout shrank and Chromium kept painting at
     * 3x into a third of the space. [BrowserViewport.pageScalePercent] reaches the same CSS width
     * through the page zoom instead, which is a knob the WebView does expose, so the view can stay
     * the size of the surface. Kept here because a trace that prints it makes the difference
     * between "the scale is pinned" and "the WebView fell back to its own" readable at a glance.
     */
    private val webViewDensity: Float
        get() = (pageDensity?.densityDpi?.div(160f)) ?: appContext.resources.displayMetrics.density

    /** Carries the car density into the WebView's Context. Null on API < 30 or if refused. */
    private var pageDensity: PageDensityDisplay? = null

    /**
     * Page audio under native control.
     *
     * This surface held no audio focus at all, so a page's video kept playing over a navigation
     * prompt and over another app's music: nothing here ever told the system it was making sound.
     */
    private val webAudio = dev.autobridge.audio.WebAudioBridge { webView }

    /** The side page's sound follows the same focus decisions, so a call pauses both panes. */
    private val sideAudio = dev.autobridge.audio.WebAudioBridge { sideView }

    /** The same YouTube add-ons the phone browser applies, so both surfaces behave alike. */
    private val youtube by lazy { dev.autobridge.youtube.YouTubeEnhancer(appContext) }
    private val audioEnvironment by lazy { dev.autobridge.audio.AudioEnvironment(appContext) }
    private val audioFocus by lazy {
        dev.autobridge.audio.AudioFocusController(
            context = appContext,
            environment = audioEnvironment,
            onAction = { applyFocusAction(it) },
            keepPlayingThroughFocusLoss =
                dev.autobridge.audio.AudioPlaybackStore.keepPlayingThroughFocusLoss(appContext)
        )
    }

    /**
     * Applies a focus decision to both panes, except the one self-inflicted case.
     *
     * When a page starts playing, the WebView's Chromium requests audio focus itself. It is the
     * same app, so the system moves focus to Chromium and reports a permanent loss to
     * [audioFocus] — and pausing on that loss stopped the page a few milliseconds after the user
     * pressed play. A permanent loss that lands within [SELF_FOCUS_WINDOW_MS] of a play on a pane
     * is that hand-over, so the pane keeps playing; Chromium now holds focus for it and pauses it
     * itself if another app takes over. Any other loss is applied as before.
     */
    private fun applyFocusAction(action: dev.autobridge.audio.AudioFocusAction) {
        dev.autobridge.logging.StructuredLog.i("CARWEB", "audio focus action=$action state=${audioFocus.state}")
        val selfHandOverPossible = action == dev.autobridge.audio.AudioFocusAction.PAUSE &&
            audioFocus.state == dev.autobridge.audio.AudioFocusState.PERMANENT_LOSS
        if (!selfHandOverPossible) {
            webAudio.apply(action)
            sideAudio.apply(action)
            return
        }
        listOf("main" to webAudio, "side" to sideAudio).forEach { (pane, bridge) ->
            bridge.msSinceLastPlay { ms ->
                if (ms <= SELF_FOCUS_WINDOW_MS) {
                    Log.i(TAG, "Focus loss right after a $pane-pane play (${ms}ms): WebView took focus; not pausing")
                } else {
                    bridge.apply(action)
                }
            }
        }
    }

    /** Current audio focus state, for the diagnostics screen. */
    val audioFocusState: dev.autobridge.audio.AudioFocusState get() = audioFocus.state

    /** A snapshot of the system audio state this surface plays into. */
    fun audioSnapshot(): dev.autobridge.audio.AudioSnapshot = audioEnvironment.snapshot(audioFocus.state)

    /** Reads what the page is playing, for the media session and diagnostics. */
    fun readWebMediaStatus(onResult: (dev.autobridge.audio.WebMediaStatus) -> Unit) =
        webAudio.readState(onResult)

    /**
     * The main page's audio as seen by the media session, so the Android Auto media card and the
     * steering-wheel buttons follow the browser too. Registered while the renderer exists, not
     * only while its surface is shown: the card matters most when the driver has gone back to the
     * dashboard and the page keeps playing behind it.
     */
    private val mediaSource = object : dev.autobridge.media.WebMediaSource {
        override fun readMediaStatus(onResult: (dev.autobridge.audio.WebMediaStatus) -> Unit) =
            webAudio.readState(onResult)

        override val pageUrl: String? get() = webView?.url ?: currentUrl

        override fun play() {
            // A page whose surface went away silent was paused; it cannot play until resumed.
            webView?.onResume()
            webAudio.userPlay()
        }

        override fun pause() = webAudio.userPause()

        override fun seekTo(positionMs: Long) = webAudio.seekTo(positionMs)

        /**
         * Takes the next page off [BrowserPlayQueue] and loads it over the finished one.
         *
         * Loading is all it takes: the new page starts its own playback, and because
         * `mediaPlaybackRequiresUserGesture` is off for this browser ([BrowserDefaults.configure])
         * it does not need a tap to begin. Navigating also leaves whatever the site would have
         * auto-played next unreached, which is the point of having a queue of our own.
         */
        override fun skipToNext(): Boolean {
            val next = BrowserPlayQueue.takeNext(appContext) ?: return false
            dev.autobridge.logging.StructuredLog.i("MEDIA", "play queue -> ${next.url}")
            load(next.url)
            return true
        }
    }

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

    /** Live copies of [BrowserControlsStore], refreshed by [applyControlSettings]. */
    private var alwaysShowFab: Boolean = true
    /**
     * When the toolbar is pinned on screen the page is inset below it, so a pinned address bar no
     * longer paints over the top of the page (the sign-in header half-hidden under the bar). The
     * auto-hiding default keeps its full-surface page: an inset there would leave a permanent band
     * for a bar that is usually gone.
     */
    private var alwaysShowUrlBar: Boolean = false
    /**
     * Toolbar removed entirely: never drawn, never hit-tested, never recalled by an edge/handle
     * gesture. The floating button is the only chrome left, and stays the way to reach the menu.
     */
    private var hideUrlBar: Boolean = false
    private var fabAction: FloatingButtonAction = FloatingButtonAction.MENU
    private var fabOnLeft: Boolean = false

    private val frameRunnable = object : Runnable {
        override fun run() {
            if (!running) return
            val now = SystemClock.uptimeMillis()
            // Idle timing and fling stepping advance every frame, and every frame repaints (a
            // detached WebView only rasterises while it is being drawn). What changes is how often:
            // full rate while something moves, a slow tick otherwise, so a page left alone on the
            // car screen stops costing 30 redraws a second.
            visibility.tick(now)
            val flinging = stepFling()
            drawFrame(now)
            pollLegacyMedia(now)
            val busy = flinging || needsFullRate(now)
            pumpIdle = !busy
            mainHandler.postDelayed(this, if (busy) FRAME_INTERVAL_MS else IDLE_FRAME_INTERVAL_MS)
        }
    }

    /** Uptime until which the pump stays at full rate; pushed forward by [requestFullRate]. */
    private var activeUntilMs = 0L

    /** True while the next pump frame is scheduled at the idle interval. */
    private var pumpIdle = false

    private var lastLegacyMediaPollMs = 0L

    /** Whether anything on the surface is animating or may change from frame to frame. */
    private fun needsFullRate(nowMs: Long): Boolean {
        if (nowMs < activeUntilMs) return true
        if (visibility.isAnimating(nowMs)) return true
        if (fullscreenFabFading(nowMs)) return true
        if (loadingProgress < 100 || (sideView != null && sideProgress < 100)) return true
        // HARDWARE: the WebView renders itself, so playback needs nothing from the pump. Legacy
        // paints the page on every frame, so a playing video would drop to the idle rate.
        val playing = dev.autobridge.audio.WebAudioState.PLAYING
        if (!hardwareMode && (webAudio.state == playing || sideAudio.state == playing)) return true
        return false
    }

    /**
     * The immersive FAB fade is time-driven, not input-driven. The window opens one idle interval
     * early so the fade's first frame is not skipped by a slow tick.
     */
    private fun fullscreenFabFading(nowMs: Long): Boolean {
        if (!visibility.fullscreen) return false
        val idle = nowMs - lastInputMs
        return idle in (FULLSCREEN_FAB_IDLE_MS - IDLE_FRAME_INTERVAL_MS)..(FULLSCREEN_FAB_IDLE_MS + FULLSCREEN_FAB_FADE_MS)
    }

    /** Legacy only: refreshes the pages' playing state so [needsFullRate] can see a video start. */
    private fun pollLegacyMedia(nowMs: Long) {
        if (hardwareMode || nowMs - lastLegacyMediaPollMs < LEGACY_MEDIA_POLL_MS) return
        lastLegacyMediaPollMs = nowMs
        webAudio.readState { }
        if (sideView != null) sideAudio.readState { }
    }

    /**
     * Puts the pump back on full rate for [ACTIVE_WINDOW_MS]. Called after every [runOnMain]
     * block, which is the entry point of every touch, scroll, command and settings change, so
     * individual call sites do not have to remember to wake the surface.
     */
    private fun requestFullRate() {
        activeUntilMs = SystemClock.uptimeMillis() + ACTIVE_WINDOW_MS
        if (running && pumpIdle) {
            pumpIdle = false
            mainHandler.removeCallbacks(frameRunnable)
            mainHandler.post(frameRunnable)
        }
    }

    // All of these describe the focused pane, because that is the page the toolbar, the drawer and
    // remote commands act on. Without a split the focused pane is always the main page.
    val canGoBack: Boolean get() = isVideoFullscreen || focusedView?.canGoBack() == true
    val canGoForward: Boolean get() = focusedView?.canGoForward() == true
    val url: String get() = if (sideActive) sideView?.url ?: sideUrl else webView?.url ?: currentUrl

    /**
     * The page a live WebView is actually showing, or null when there is none yet.
     *
     * Unlike [url] this never falls back to the remembered URL of a previous session, which is the
     * distinction [BrowserSiteEntry.resumes] turns on: a remembered address is not a page anyone can
     * be sent back to.
     */
    val livePageUrl: String? get() = if (sideActive) sideView?.url else webView?.url
    val title: String? get() = if (sideActive) sideView?.title ?: sideTitle else webView?.title
    val isLoading: Boolean get() = (if (sideActive) sideProgress else loadingProgress) < 100

    /** The active split layout, for the drawer and settings. */
    val splitLayoutInUse: BrowserSplitLayout get() = if (isSplit) splitLayout else BrowserSplitLayout.SINGLE
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
        // Persist even when the state is unchanged is pointless, but persist the user's choice so a
        // later session starts the way they left it; the stored default only applies until the
        // first toggle. See [BrowserControlsStore.startFullscreen].
        BrowserControlsStore.setStartFullscreen(appContext, enabled)
        if (visibility.fullscreen == enabled) return@runOnMain
        val now = SystemClock.uptimeMillis()
        // Show the floating button for one idle window on entry, so the way out is visible
        // before it fades.
        lastInputMs = now
        visibility.setFullscreen(now, enabled)
        trace(ViewportDebug.Event.FULLSCREEN, "enabled=$enabled")
        host?.onBrowserStateChanged()
    }

    fun toggleFullscreen() = setFullscreen(!visibility.fullscreen)

    /** Drawer shortcut: steps 100 → 50/50 → 40/60 → portrait + landscape → 100. */
    fun cycleSplitLayout() = runOnMain {
        val next = splitLayout.next()
        // The new preset decides the shape, so whatever was being dragged is no longer being
        // dragged — and setLayout drops the dragged ratio with it.
        dividerGrabbed = false
        BrowserSplitStore.setLayout(appContext, next)
        applyControlSettings()
        val label = next.label(appContext)
        val message = when {
            next == BrowserSplitLayout.SINGLE || isSplit -> label
            !hardwareMode -> appContext.getString(R.string.car_split_unavailable_legacy, label)
            else -> appContext.getString(R.string.car_split_too_narrow, label)
        }
        host?.showMessage(message)
    }

    /**
     * The split switch (the drawer's Split screen tile, the side pane's ✕): one tap closes a
     * split, the next brings back the layout it had. See [BrowserSplitPrefs.toggle].
     */
    fun toggleSplit() = runOnMain {
        dividerGrabbed = false
        val next = BrowserSplitStore.toggle(appContext)
        applyControlSettings()
        val label = next.label(appContext)
        host?.showMessage(
            when {
                next == BrowserSplitLayout.SINGLE -> appContext.getString(R.string.split_closed)
                isSplit -> label
                !hardwareMode -> appContext.getString(R.string.car_split_unavailable_legacy, label)
                else -> appContext.getString(R.string.car_split_too_narrow, label)
            }
        )
    }

    /** Closes the split from the ✕ on the divider. */
    fun closeSplit() = runOnMain {
        if (splitLayout != BrowserSplitLayout.SINGLE) toggleSplit()
    }

    /**
     * Drawer: shows the page the main pane is on in the side pane as well — the way to put a video
     * or a site beside the map without typing its address again. Splits 50/50 first when the
     * surface is not split.
     */
    fun showMainPageOnSide() = runOnMain {
        val target = webView?.url?.let(ContentAddress::https)
        if (target == null) {
            host?.showMessage(appContext.getString(R.string.car_side_show_failed))
            return@runOnMain
        }
        val hadSide = sideView != null
        sideUrl = target
        BrowserSplitStore.setSideUrl(appContext, target)
        if (splitLayout == BrowserSplitLayout.SINGLE) {
            dividerGrabbed = false
            BrowserSplitStore.setLayout(appContext, BrowserSplitLayout.HALF)
            applyControlSettings()
        }
        // A side pane created just now already opened [sideUrl].
        if (hadSide) sideView?.loadUrl(target)
        val label = splitLayout.label(appContext)
        host?.showMessage(
            when {
                isSplit -> appContext.getString(R.string.car_side_shown)
                !hardwareMode -> appContext.getString(R.string.car_split_unavailable_legacy, label)
                else -> appContext.getString(R.string.car_split_too_narrow, label)
            }
        )
    }

    /**
     * Drawer: starts real navigation to what the split's map is showing (a route's end, a place or
     * a search), or failing that what the main page is showing.
     */
    fun navigateInMaps() = runOnMain {
        val destination = MapsHandoff.destinationFromPage(sideView?.url)
            ?: MapsHandoff.destinationFromPage(webView?.url)
        if (destination == null) {
            host?.showMessage(appContext.getString(R.string.car_maps_no_destination))
        } else {
            host?.startNavigation(destination)
        }
    }

    /**
     * When [target] is a link asking for the Google Maps app, starts navigation to its destination
     * (or to what [pageUrl], the map page that asked, is showing) and returns true; the mobile web
     * map has no navigation of its own. Says why when there is nowhere to navigate to, rather than
     * leaving the tap to do nothing. False for every other link. Callable from any thread.
     */
    private fun handOffToMaps(target: String, pageUrl: String?): Boolean {
        if (!MapsHandoff.isMapsAppLink(target) && MapsHandoff.destinationFromLink(target) == null) return false
        val destination = MapsHandoff.handoffDestination(target, pageUrl)
        mainHandler.post {
            if (destination != null) {
                host?.startNavigation(destination)
            } else {
                host?.showMessage(appContext.getString(R.string.car_maps_no_destination))
            }
        }
        return true
    }

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
            dev.autobridge.media.WebMediaHub.register(appContext, mediaSource)
            webView?.let { WebViewTimerGate.hold(TIMER_GATE_OWNER, it) }
            webView?.onResume()
            sideView?.onResume()
            AutoBridgeVideoLog.surface("available", surface, width, height, dpi)
            if (hardwareMode) attachHardwareWindow(surface, width, height)
            layoutWebView(width, height, ViewportDebug.Event.SURFACE_AVAILABLE)
            reattachRasterHost()
            running = true
            pumpIdle = false
            dev.autobridge.logging.StructuredLog.i("CARWEB", "surface back ${width}x$height")
            // Give the user the full idle window from the moment the browser appears.
            val startNow = SystemClock.uptimeMillis()
            visibility.onInteraction(startNow)
            applyControlSettings()
            // Seed fullscreen from the stored preference on the first start of the session only —
            // never on a surface re-attach/resize, which would force fullscreen back on after the
            // user had left it. Applied through [ChromeVisibility] directly so seeding does not
            // re-write the preference it just read. See [BrowserControlsStore.startFullscreen].
            if (firstStart) {
                visibility.setFullscreen(startNow, BrowserControlsStore.startFullscreen(appContext))
            }
            // Only load on the very first start (or when an explicit URL is requested). A surface
            // recreation/resize must re-fit the existing page, never reload and lose it. The
            // User-Agent is applied when the WebView is created and when the user changes it, not
            // on every re-attach — doing it here used to risk a reload on each foreground cycle.
            // Taken before the branch below so a deferred request cannot survive into a later
            // start() and reload a page the user has since navigated away from.
            val deferred = pendingStartUrl
            pendingStartUrl = null
            when {
                startUrl != null -> load(startUrl)
                deferred != null -> load(deferred)
                firstStart -> load(tabs.active?.url ?: currentUrl)
                // The mode was changed while this surface was away (phone browser, agent command,
                // a screen pushed on top): the kept page still has the old identity and width.
                identityKey() != appliedIdentity -> {
                    Log.i(TAG, "Browser identity changed while detached; re-applying")
                    applyUserAgentAndReload()
                }
                else -> Log.i(TAG, "Surface re-attached ${width}x$height, keeping current page")
            }
            // Hold focus while this surface is live, so page audio participates in ducking and
            // pauses for calls instead of talking over them. Re-read the "keep playing" preference
            // on each start so toggling it in settings takes effect on the next playback without
            // rebuilding the controller.
            audioFocus.keepPlayingThroughFocusLoss =
                dev.autobridge.audio.AudioPlaybackStore.keepPlayingThroughFocusLoss(appContext)
            val generation = ++surfaceGeneration
            audioFocus.isPlaying = true
            // A page that kept playing while the car showed another app (Maps, the dashboard)
            // is being played under Chromium's own audio focus. Requesting focus here took it
            // from Chromium, whose AudioFocusDelegate pauses the page on that LOSS and abandons
            // its request, so the music stopped the moment the driver came back. While anything
            // is still audible, leave focus with Chromium and only clear the resume tags.
            webAudio.isAnyPlaying { mainPlaying ->
                sideAudio.isAnyPlaying { sidePlaying ->
                    // A newer start()/stop() has taken over; its own decision stands.
                    if (generation != surfaceGeneration || !running) return@isAnyPlaying
                    if (mainPlaying || sidePlaying) {
                        Log.i(TAG, "Page still playing on re-attach; leaving audio focus with the WebView")
                        webAudio.resumeMarked()
                        sideAudio.resumeMarked()
                        return@isAnyPlaying
                    }
                    audioFocus.request()
                    // Bring back what was playing when the surface went away (rear camera on
                    // reverse, a pushed template). The page may have paused itself when it was
                    // hidden, and a granted request only resumes media when focus was actually
                    // re-acquired. Skipped while a call or prompt holds focus: the focus GAIN
                    // resumes it once that ends.
                    when (audioFocus.state) {
                        dev.autobridge.audio.AudioFocusState.GAINED,
                        dev.autobridge.audio.AudioFocusState.DUCKED -> {
                            webAudio.resumeMarked()
                            sideAudio.resumeMarked()
                        }
                        else -> Unit
                    }
                }
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
            AutoBridgeVideoLog.surface("resized", surface, width, height, dpi)
            val activeSurface = surface
            if (hardwareMode && activeSurface != null) attachHardwareWindow(activeSurface, width, height)
            layoutWebView(width, height, ViewportDebug.Event.SURFACE_RESIZED)
        }
    }

    fun stop() {
        runOnMain {
            running = false
            // Focus is deliberately NOT dropped here. The surface detaches whenever a car template
            // is pushed over the browser — the drawer's own screens, settings, diagnostics — while
            // the page keeps playing. Abandoning focus then would leave the app audible with no
            // claim to the output, so it would neither duck for a prompt nor pause for a call.
            // It is released when nothing is playing, and unconditionally in destroy().
            //
            // The playing check must run BEFORE the WebView is paused. It used to be an async
            // read queued ahead of onPause(), so its answer often came back after the hidden page
            // had already stopped its media: the check then saw "not playing", dropped focus, and
            // nothing ever restarted the music when the surface came back — exactly what happened
            // when the rear camera took the screen on reverse. Now playing media is tagged for
            // resume first, and a page that is playing is not paused at all, so its audio keeps
            // running while the car shows something else.
            val generation = ++surfaceGeneration
            val main = webView
            val side = sideView
            webAudio.markPlayingForResume { mainPlaying ->
                sideAudio.markPlayingForResume { sidePlaying ->
                    // A newer start()/stop() has taken over; its own decision stands.
                    if (generation != surfaceGeneration || running) return@markPlayingForResume
                    // In the Send log (the video log below goes to Logcat only): what the page was doing
                    // when the car took the screen, which decides whether its sound carries on.
                    dev.autobridge.logging.StructuredLog.i(
                        "CARWEB",
                        "surface lost: mainPlaying=$mainPlaying sidePlaying=$sidePlaying focus=${audioFocus.state}"
                    )
                    if (!mainPlaying && !sidePlaying) {
                        audioFocus.isPlaying = false
                        audioFocus.abandon()
                    }
                    if (!mainPlaying) main?.takeIf { it === webView }?.onPause()
                    if (!sidePlaying) side?.takeIf { it === sideView }?.onPause()
                    if (!mainPlaying && !sidePlaying) suspendWhileHidden(main)
                }
            }
            mainHandler.removeCallbacks(frameRunnable)
            // A divider let go of by driving away rather than by a second tap still keeps its
            // ratio; the queued re-measure is dropped with the surface it would have measured.
            mainHandler.removeCallbacks(applyDividerLayout)
            dividerLayoutPending = false
            dividerGrabbed = false
            saveSplitFraction()
            AutoBridgeVideoLog.surface("destroyed", surface, surfaceWidth, surfaceHeight, surfaceDpi)
            // The host is taking its surface back. The display is pointed at nothing rather than
            // released, so the window and the WebView in it (page, playback, fullscreen) survive
            // until the next surface arrives — a pushed template or a DHU reconnect.
            hardwareWindow?.setSurface(null)
            surface = null
            saveActiveTabState()
            BrowserTabStore.save(appContext, tabs)
            // WebView.onPause() is applied in the playing check above, only to a pane that is
            // silent: pausing a playing page hides it, and most sites stop their media then.
            side?.url?.let { BrowserSplitStore.setSideUrl(appContext, it) }
            // stop() is the teardown that runs whenever the car surface detaches — including on
            // unplug, which is exactly when the process is most likely to be killed outright.
            // Cookies not yet written to disk would go with it, and the cost of losing them is
            // paid on a screen where typing a password back in is painful.
            CookieManager.getInstance().flush()
            trace(ViewportDebug.Event.SURFACE_DESTROYED)
        }
    }

    /**
     * Runs from [stop]'s playing check once it has found both panes silent: the surface is gone and
     * nothing needs to keep going until it comes back.
     *
     * - JavaScript timers are paused (through [WebViewTimerGate], since the pause is process-wide
     *   and the phone browser may still be in use). `onPause()` alone leaves them running.
     * - Legacy only: the off-screen raster host is released. Its hidden display keeps rastering
     *   and its sink thread keeps draining for as long as it exists, with nobody looking.
     *   [reattachRasterHost] brings it back on the next [start].
     */
    private fun suspendWhileHidden(main: WebView?) {
        main?.takeIf { it === webView }?.let { WebViewTimerGate.release(TIMER_GATE_OWNER, it) }
        val host = rasterHost ?: return
        host.release()
        rasterHost = null
        rasterHostReleasedOnStop = true
        Log.i(TAG, "Raster host released while the car surface is away")
    }

    /**
     * Re-hosts the legacy WebView after [suspendWhileHidden] released its window. Done here rather
     * than left to [layoutWebView], which skips everything when the geometry is unchanged — exactly
     * the case for a template pushed and popped over the browser.
     */
    private fun reattachRasterHost() {
        if (!rasterHostReleasedOnStop) return
        rasterHostReleasedOnStop = false
        if (hardwareMode || hardwareWindow != null || rasterHost != null) return
        val view = webView ?: return
        rasterHost = OffscreenWebViewWindow.attach(
            appContext, view, viewport.webWidth, viewport.webHeight,
            appContext.resources.configuration.densityDpi
        )
    }

    fun destroy() {
        runOnMain {
            running = false
            // A request that never reached a surface dies with the renderer; the next one restores
            // its tabs rather than opening a page asked for in a previous session.
            pendingStartUrl = null
            dev.autobridge.media.WebMediaHub.unregister(mediaSource)
            audioFocus.isPlaying = false
            audioFocus.abandon()
            mainHandler.removeCallbacks(frameRunnable)
            mainHandler.removeCallbacks(applyStableArea)
            surface = null
            saveActiveTabState()
            BrowserTabStore.save(appContext, tabs)
            // Released before the WebView, so the view is out of the window before it is destroyed.
            rasterHost?.release()
            rasterHost = null
            rasterHostAttempted = false
            hardwareWindow?.release()
            hardwareWindow = null
            releaseSidePane()
            rasterHostReleasedOnStop = false
            webView?.apply {
                stopLoading()
                // Released while the view is still alive: pausing timers goes through a WebView.
                WebViewTimerGate.release(TIMER_GATE_OWNER, this)
                destroy()
            }
            webView = null
            pageDensity?.release()
            pageDensity = null
            youtube.release()
            tabThumbnails.values.forEach { it.recycle() }
            tabThumbnails.clear()
            host = null
        }
    }

    // ------------------------------------------------------------------ navigation

    /** Loads a validated HTTPS URL or turns free text into a Google search. */
    fun load(input: String) {
        runOnMain {
            // The non-DRM MP4 test page (TEST 3 of the video matrix), reachable from the car's
            // address input by typing "videodiag".
            if (WebVideoDiagnostics.isSentinel(input)) {
                focusedView?.let(WebVideoDiagnostics::loadTestPage)
                return@runOnMain
            }
            val target = BrowserDefaults.resolve(input)
            val side = sideView
            if (sideActive && side != null) {
                // The side pane has no tabs: an address typed while it has focus replaces its page.
                sideUrl = target
                BrowserSplitStore.setSideUrl(appContext, target)
                BrowserDefaults.applyIdentity(appContext, side, target)
                side.loadUrl(target)
                return@runOnMain
            }
            currentUrl = target
            loadError = null
            if (tabs.tabs.isEmpty()) {
                tabs = BrowserTabsState.single(target, id = nextTabId++)
            }
            if (!running) {
                // No live surface: defer to start() rather than navigate a paused WebView. The
                // tab/currentUrl bookkeeping above still applies, so the rest of the app already
                // reports the page the user asked for.
                pendingStartUrl = target
                return@runOnMain
            }
            syncUserAgentFor(target)
            webView?.loadUrl(target)
        }
    }

    /** Re-requests the page that failed to load (tapped from the error overlay). */
    fun retry() = runOnMain {
        loadError = null
        webView?.reload()
    }

    /** Back leaves video fullscreen first, exactly like the phone browser, then walks history. */
    fun goBack() = runOnMain {
        if (hardwareWindow?.fullscreen?.onBackPressed() == true) {
            AutoBridgeVideoLog.i("fullscreen exit via back")
            return@runOnMain
        }
        focusedView?.let { if (it.canGoBack()) it.goBack() }
    }
    fun goForward() = runOnMain { focusedView?.let { if (it.canGoForward()) it.goForward() } }
    fun reload() = runOnMain { focusedView?.reload() }
    fun stopLoading() = runOnMain {
        focusedView?.stopLoading()
        if (sideActive) sideProgress = 100 else loadingProgress = 100
    }
    fun goHome() = runOnMain { load(DEFAULT_HOME) }

    /**
     * Applies the persisted browser identity (Mobile / Desktop / Custom) to the live page. Every
     * entry point — the drawer switch, the settings screen, remote and agent commands — goes through
     * here, so none of them can apply only part of desktop mode.
     *
     *  1. Re-layout: desktop mode pins the CSS viewport to [BrowserViewport.DESKTOP_CONTENT_WIDTH_DP]
     *     through `setInitialScale`, so the page has to be re-measured, not just reloaded.
     *  2. UA string, client hints and the page-side script ([BrowserDefaults.applyIdentity]). The
     *     hints used to be set only when the WebView was created, so they kept saying "mobile"
     *     after a toggle.
     *  3. Always reload. The new initial scale and document-start script only apply to a fresh
     *     load, and the UA string can be unchanged (sign-in host, Custom) while the scale moved.
     */
    fun applyUserAgentAndReload() = runOnMain {
        val view = webView ?: return@runOnMain
        layoutWebView(surfaceWidth, surfaceHeight, ViewportDebug.Event.STABLE_AREA)
        BrowserDefaults.applyIdentity(appContext, view, view.url ?: currentUrl)
        appliedIdentity = identityKey()
        view.reload()
        sideView?.let { side ->
            BrowserDefaults.applyIdentity(appContext, side, side.url ?: sideUrl)
            side.reload()
        }
    }

    /** The identity [applyUserAgentAndReload] last pushed to the WebView. */
    private var appliedIdentity: String = ""

    private fun identityKey(): String =
        BrowserUserAgentStore.mode(appContext).name + "|" + BrowserUserAgentStore.custom(appContext)

    /**
     * Aligns the WebView's User-Agent with [url] before it loads. Sign-in origins force the mobile
     * UA (see [BrowserUserAgentStore.resolveForUrl]); everything else uses the user's chosen mode.
     * Called for every navigation so a redirect into accounts.google.com from a desktop-mode page
     * still gets the UA Google accepts, without a visible reload.
     */
    private fun syncUserAgentFor(url: String) {
        val view = webView ?: return
        BrowserDefaults.applyIdentity(appContext, view, url)
    }

    // ------------------------------------------------------------------ tabs

    /**
     * Switches tabs by saving and restoring state on the **same** WebView. A second WebView per tab
     * would multiply the per-tab cost on a head unit and would break the rule that no UI state ever
     * recreates the renderer's WebView.
     */
    fun activateTab(id: Long) = runOnMain {
        // Tabs live in the main pane, so picking one hands focus back to it.
        sideFocused = false
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
        sideFocused = false
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

    fun zoomIn() = runOnMain { focusedView?.let { PageZoom.zoomBy(it, ZOOM_STEP) } }
    fun zoomOut() = runOnMain { focusedView?.let { PageZoom.zoomBy(it, 1f / ZOOM_STEP) } }

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
            sideView?.clearFormData()
            sideView?.clearHistory()
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
        val view = focusedView
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
        if (drawerOpen) {
            val model = drawer
            if (model == null) {
                closeDrawer()
                return@runOnMain
            }
            // Checked before the rows: the header sits above them and owns its own tap.
            if (model.hitsClose(x, y)) {
                closeDrawer()
                return@runOnMain
            }
            val row = model.rowAt(x, y)
            // A layout picture on the split page: apply it and stay, so the choice shows at once.
            row?.layout?.let { layout ->
                pickSplitLayout(layout)
                rebuildDrawer()
                return@runOnMain
            }
            // On the split page the switch and the side swap show their result there.
            if (row != null && overlay == Overlay.DRAWER_SPLIT &&
                (row.item.action == DrawerAction.SPLIT_LAYOUT || row.item.action == DrawerAction.SWAP_SPLIT_SIDES)
            ) {
                performDrawerAction(row.item.action)
                rebuildDrawer()
                return@runOnMain
            }
            if (row != null) {
                when (row.item.action) {
                    // These two switch which list is drawn instead of closing the sheet, so it
                    // stays open across the "More" round trip.
                    DrawerAction.MORE -> openDrawerMore()
                    DrawerAction.BACK_TO_MENU -> openDrawer()
                    // Split screen is one row on the main list; it opens the split page.
                    DrawerAction.SPLIT_CHOOSE -> openDrawerSplit()
                    // A switch reports state, so the sheet stays open and redraws it. Closing on
                    // the way out would hide the one thing the tap was for.
                    DrawerAction.TOGGLE_DESKTOP -> {
                        toggleDesktopMode(appContext)
                        rebuildDrawer()
                        host?.onBrowserStateChanged()
                    }
                    // Zoom, pin toolbar and Save are pressed again or show their new state: act,
                    // keep the sheet up and redraw it.
                    else -> if (BrowserDrawerModel.keepsMenuOpen(row.item.action)) {
                        performDrawerAction(row.item.action)
                        rebuildDrawer()
                    } else {
                        closeDrawer()
                        performDrawerAction(row.item.action)
                    }
                }
                return@runOnMain
            }
            // Anything else inside the sheet is its background and is swallowed; outside dismisses.
            if (!model.panel.contains(x, y)) closeDrawer()
            return@runOnMain
        }

        // The *target* state, not the animated opacity: once the user has asked for the toolbar,
        // its buttons must be hittable immediately. Testing the fade alpha made every tap during
        // the 180ms fade-in fall through to the edge-reveal band instead of the button under it.
        // A hidden bar is never visible and never recalled, so hit testing is told the chrome is
        // down and the edge/handle bands are treated as page taps below.
        val chromeVisible = !hideUrlBar && visibility.isShown
        val zone = chrome.hitTest(x, y, chromeVisible, null, fabVisible(now))
        // Recorded after the hit test, so a tap where a faded fullscreen FAB used to be reaches
        // the page and only wakes the button, instead of pressing it unseen.
        lastInputMs = now
        // A tap chrome takes is also a tap that is not on the divider, so it lets go of one:
        // reaching for the toolbar mid-drag must not leave the next scroll still moving the panes.
        // Page taps go through pageTap, which grabs and releases on its own.
        if (dividerGrabbed && zone != ChromeZone.NONE) releaseDivider()
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
            ChromeZone.FAB -> { visibility.onInteraction(now); performFloatingAction() }
            ChromeZone.HANDLE, ChromeZone.EDGE_REVEAL -> {
                if (hideUrlBar) {
                    // The bar is hidden for good: the recall bands do not exist, so a tap there is
                    // just a tap on the page underneath.
                    visibility.onInteraction(now)
                    pageTap(x, y)
                } else {
                    // Recalling chrome must not also leave fullscreen: the page keeps every pixel it
                    // has, the toolbar simply fades back in over it.
                    visibility.show(now)
                }
            }
            ChromeZone.DRAWER_SCRIM -> closeDrawer()
            ChromeZone.TOOLBAR_BACKGROUND -> {
                // Consumed: keeps the chrome awake without letting the tap through to the page.
                visibility.onInteraction(now)
            }
            ChromeZone.NONE -> {
                visibility.onInteraction(now)
                pageTap(x, y)
            }
        }
    }

    /**
     * A tap that reached the page layer. In a split it first moves focus to the pane under the
     * finger, so the toolbar, scrolling and the address bar follow what the user last touched. The
     * error overlay belongs to the main page only, so a failed main page never blocks the side one.
     */
    private fun pageTap(x: Float, y: Float) {
        if (hardwareWindow?.fullscreen?.container == null) {
            if (tapSideClose(x, y)) return
            if (grabDivider(x, y)) return
            val side = sideViewport
            val tappedSide = isSplit && side != null && side.contains(x, y)
            val tappedMain = mainViewport.contains(x, y)
            if (isSplit && (tappedSide || tappedMain) && tappedSide != sideFocused) {
                sideFocused = tappedSide
                stopFling()
                trace(ViewportDebug.Event.SPLIT_LAYOUT, "focus=${if (tappedSide) "side" else "main"}")
                onPageChanged?.invoke(url, title)
                host?.onBrowserStateChanged()
            }
            if (!tappedSide && loadError != null) {
                retry()
                return
            }
        }
        dispatchPageTap(x, y)
    }

    /**
     * Takes hold of the divider when the tap lands on it, and lets go when it lands anywhere else.
     * Returns true when the tap was the grab itself, so it never also reaches a page.
     *
     * Two steps rather than one drag because the host's scroll callback carries no position: the
     * tap is the only part of the gesture that can say the divider — rather than a page — is what
     * the drags that follow should move.
     */
    private fun grabDivider(x: Float, y: Float): Boolean {
        val panes = splitPanes?.takeIf { isSplit && !isVideoFullscreen }
        val hit = panes != null && dividerGrabBox(panes).contains(x, y)
        val wasGrabbed = dividerGrabbed
        dividerGrabbed = hit
        if (hit) {
            stopFling()
            requestFullRate()
            trace(ViewportDebug.Event.SPLIT_LAYOUT, "divider=grabbed")
            return true
        }
        if (wasGrabbed) releaseDivider()
        return false
    }

    /** Lands the dragged ratio: the pending re-layout runs now, and the ratio is remembered. */
    private fun releaseDivider() {
        dividerGrabbed = false
        if (dividerLayoutPending) {
            dividerLayoutPending = false
            mainHandler.removeCallbacks(applyDividerLayout)
            layoutWebView(surfaceWidth, surfaceHeight, ViewportDebug.Event.SPLIT_LAYOUT)
        }
        saveSplitFraction()
        trace(ViewportDebug.Event.SPLIT_LAYOUT, "divider=released")
    }

    private fun saveSplitFraction() {
        splitFraction?.let { BrowserSplitStore.setSideFraction(appContext, it) }
    }

    /** The middle of the gap between the panes, which is the divider the user sees. */
    private fun dividerCentreX(panes: SplitPanes): Float =
        if (splitSideOnRight) {
            (panes.main.right + panes.side.left) / 2f
        } else {
            (panes.side.right + panes.main.left) / 2f
        }

    /** [dividerCentreX] for stacked panes: the middle of the horizontal gap between them. */
    private fun dividerCentreY(panes: SplitPanes): Float =
        if (splitSideOnRight) {
            (panes.main.bottom + panes.side.top) / 2f
        } else {
            (panes.side.bottom + panes.main.top) / 2f
        }

    /**
     * The seam control as drawn: a capsule centred on the divider, lying along it. See
     * [SEAM_CAPSULE_LONG_DP].
     */
    private fun seamCapsule(panes: SplitPanes): Box {
        val thick = sizes.dp(SEAM_CAPSULE_THICK_DP) / 2f
        val long = sizes.dp(SEAM_CAPSULE_LONG_DP) / 2f
        return if (panes.stacked) {
            val cx = panes.side.left + panes.side.width / 2f
            val cy = dividerCentreY(panes)
            Box(cx - long, cy - thick, cx + long, cy + thick)
        } else {
            val cx = dividerCentreX(panes)
            val cy = panes.side.top + panes.side.height / 2f
            Box(cx - thick, cy - long, cx + thick, cy + long)
        }
    }

    /** The capsule's grip: everything past the ✕. This is what a tap grabs and a drag moves. */
    private fun dividerHandleBox(panes: SplitPanes): Box {
        val capsule = seamCapsule(panes)
        val close = sizes.dp(SEAM_CLOSE_DP)
        return if (panes.stacked) Box(capsule.left + close, capsule.top, capsule.right, capsule.bottom)
        else Box(capsule.left, capsule.top + close, capsule.right, capsule.bottom)
    }

    /** The capsule's ✕ end: the top of it, or its left end when the panes are stacked. */
    private fun sideCloseBox(panes: SplitPanes): Box {
        val capsule = seamCapsule(panes)
        val close = sizes.dp(SEAM_CLOSE_DP)
        return if (panes.stacked) Box(capsule.left, capsule.top, capsule.left + close, capsule.bottom)
        else Box(capsule.left, capsule.top, capsule.right, capsule.top + close)
    }

    /** A tap on the split's ✕ closes the split. Returns true when the tap was that. */
    private fun tapSideClose(x: Float, y: Float): Boolean {
        val panes = splitPanes?.takeIf { isSplit && !isVideoFullscreen } ?: return false
        // The ✕ is drawn small; a tap a little outside it still counts.
        val close = sideCloseBox(panes)
        val slop = sizes.dp(8f)
        // Grown on every side but the one the grip is on.
        val target = if (panes.stacked) Box(close.left - slop, close.top - slop, close.right, close.bottom + slop)
        else Box(close.left - slop, close.top - slop, close.right + slop, close.bottom)
        if (!target.contains(x, y)) return false
        trace(ViewportDebug.Event.SPLIT_LAYOUT, "close")
        closeSplit()
        return true
    }

    /**
     * What counts as a tap on the divider: the handle plus a finger's width around it. Only around
     * the handle, not down the whole seam — a full-height grab strip would swallow every tap on
     * the inner edge of either page.
     */
    private fun dividerGrabBox(panes: SplitPanes): Box {
        val handle = dividerHandleBox(panes)
        val margin = sizes.dp(SPLIT_GRAB_DP)
        return Box(
            handle.left - margin, handle.top - margin,
            handle.right + margin, handle.bottom + margin
        )
    }

    /**
     * Moves a grabbed divider by one scroll's worth. Returns true whenever the drag was consumed,
     * including the no-op cases, so a grabbed divider never also scrolls the page under it.
     *
     * The re-layout is coalesced: every step of the drag changes the ratio, but reflowing two live
     * WebViews thirty times a second is what a drag on a head unit cannot afford. The pending pass
     * is flushed on release, so where the finger stops is exactly where the panes land.
     */
    private fun dragDivider(distanceX: Float, distanceY: Float): Boolean {
        val panes = splitPanes?.takeIf { isSplit }
        if (panes == null) {
            dividerGrabbed = false
            return false
        }
        // The host reports the distance *scrolled*, which is the negative of the way the finger
        // went; the divider follows the finger. Stacked panes are divided by a horizontal seam,
        // which moves with the vertical part of the drag.
        val delta = -(if (panes.stacked) distanceY else distanceX).roundToInt()
        if (delta == 0) return true
        val next = BrowserSplitGeometry.dragSideFraction(
            panes,
            delta,
            sideOnRight = splitSideOnRight,
            minPanePx = sizes.dp(SPLIT_MIN_PANE_DP).roundToInt(),
        )
        if (next == splitFraction) return true
        splitFraction = next
        if (!dividerLayoutPending) {
            dividerLayoutPending = true
            mainHandler.postDelayed(applyDividerLayout, DIVIDER_LAYOUT_INTERVAL_MS)
        }
        return true
    }

    private val applyDividerLayout = Runnable {
        dividerLayoutPending = false
        layoutWebView(surfaceWidth, surfaceHeight, ViewportDebug.Event.SPLIT_LAYOUT)
    }

    private fun dispatchPageTap(x: Float, y: Float) {
        val now = SystemClock.uptimeMillis()
        // While Chromium is fullscreen the page content lives in the custom view, which covers the
        // whole display at (0,0), so surface coordinates are already its coordinates.
        val fullscreenView = hardwareWindow?.fullscreen?.container
        if (fullscreenView != null) {
            val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0)
            val up = MotionEvent.obtain(now, now + 20, MotionEvent.ACTION_UP, x, y, 0)
            fullscreenView.dispatchTouchEvent(down)
            fullscreenView.dispatchTouchEvent(up)
            down.recycle()
            up.recycle()
            return
        }
        // Each pane maps the tap with its own viewport, which subtracts that pane's offset.
        val side = sideViewport
        val sidePage = sideView
        val (view, pane) = when {
            side != null && sidePage != null && side.contains(x, y) -> sidePage to side
            mainViewport.contains(x, y) -> (webView ?: return) to mainViewport
            else -> return // the divider, or outside both panes
        }
        val webX = pane.toWebX(x)
        val webY = pane.toWebY(y)
        val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, webX, webY, 0)
        val up = MotionEvent.obtain(now, now + 20, MotionEvent.ACTION_UP, webX, webY, 0)
        view.dispatchTouchEvent(down)
        view.dispatchTouchEvent(up)
        down.recycle()
        up.recycle()
        // The car surface has no system keyboard: a tap that focused one of the page's text
        // fields asks the host for a way to type into it.
        mainHandler.postDelayed({ offerFieldInput(view) }, FIELD_FOCUS_DELAY_MS)
    }

    /** When [page] has a text field focused, hands its text to [Host.openFieldInput]. */
    private fun offerFieldInput(page: WebView) {
        if (page !== webView && page !== sideView) return
        if (overlay != Overlay.NONE) return
        page.evaluateJavascript(CarKeyboardPanel.FOCUSED_FIELD_SCRIPT.trimIndent()) { raw ->
            val parsed = runCatching { org.json.JSONArray("[${raw ?: "null"}]") }.getOrNull()
            val field = if (parsed == null || parsed.isNull(0)) null else parsed.optString(0)
            if (field != null && overlay == Overlay.NONE) host?.openFieldInput(field)
        }
    }

    /**
     * Scrolls the page by a device-pixel delta, clamped to the content. Returns true when the
     * offset actually moved, which is what tells a fling it has reached the end.
     */
    private fun scrollPageBy(dx: Int, dy: Int): Boolean {
        // The host's scroll and fling callbacks carry no position, so they go to the focused pane.
        val view = focusedView ?: return false
        // The page underneath a fullscreen video is not on screen; scrolling it would only move
        // the position the user returns to.
        if (isVideoFullscreen) return false
        val nextX = PageScroll.clamp(view.scrollX, dx, view.maxScrollX)
        val nextY = PageScroll.clamp(view.scrollY, dy, view.maxScrollY)
        if (nextX == view.scrollX && nextY == view.scrollY) return false
        view.scrollTo(nextX, nextY)
        return true
    }

    /**
     * The live page offset and its bounds, for instrumented tests.
     *
     * The clamp arithmetic is covered by `PageScrollTest` on the JVM; what cannot be checked there
     * is the wiring — that [scrollPageBy] reads the WebView's real extents and that no scroll path
     * can leave the offset outside them. Reading that back needs the actual WebView, so this is the
     * one seam the on-device test uses. Null until a surface has started the renderer.
     */
    @VisibleForTesting
    internal fun pageScrollState(): PageScrollState? = webView?.let {
        PageScrollState(x = it.scrollX, y = it.scrollY, maxX = it.maxScrollX, maxY = it.maxScrollY)
    }

    /** Snapshot of [pageScrollState]. */
    @VisibleForTesting
    internal data class PageScrollState(val x: Int, val y: Int, val maxX: Int, val maxY: Int)

    /** Scrolls the page content, or the open overlay, by the given car-surface delta. */
    fun scrollBy(distanceX: Float, distanceY: Float) = runOnMain {
        val now = SystemClock.uptimeMillis()
        lastInputMs = now
        visibility.onInteraction(now)
        if (drawerOpen) {
            val model = drawer ?: return@runOnMain
            drawerScroll = (drawerScroll + distanceY).coerceIn(0f, model.maxScroll)
            rebuildDrawer()
            return@runOnMain
        }
        if (overlay == Overlay.TABS) return@runOnMain
        // A grabbed divider owns the drag; the page under it must not scroll with it.
        if (dividerGrabbed && dragDivider(distanceX, distanceY)) return@runOnMain
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
        lastInputMs = SystemClock.uptimeMillis()
        visibility.onInteraction(lastInputMs)
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
        lastInputMs = SystemClock.uptimeMillis()
        focusedView?.let { PageZoom.zoomBy(it, factor.coerceIn(0.8f, 1.25f)) }
    }

    // ------------------------------------------------------------------ overlays

    /**
     * Re-reads [BrowserControlsStore] and applies it to the live surface. Called when the renderer
     * starts and again whenever the settings screen reports a change, so a toggle takes effect on
     * the panel without the browser being restarted.
     */
    fun applyControlSettings() = runOnMain {
        val now = SystemClock.uptimeMillis()
        // While the surface is split the bar would run across both pages, over the top of each,
        // and the menu already carries the address, Back, Forward and Reload; so a split has no
        // bar and the floating button is the way to the menu.
        val hideBar = BrowserControlsStore.hideUrlBar(appContext) ||
            BrowserSplitStore.layout(appContext) != BrowserSplitLayout.SINGLE
        // "Hide the bar" wins over "pin the bar": the two are contradictory and the hide is the
        // more explicit "I never use it". A hidden bar also gets no page inset — there is no bar to
        // make room for — so the page keeps the whole surface.
        val pinToolbar = !hideBar && BrowserControlsStore.alwaysShowUrlBar(appContext)
        // Auto-hide stays on while the bar is hidden so any bar accidentally shown still fades; it
        // simply is never drawn or recalled. Pinned keeps it up; otherwise it fades as before.
        visibility.setAutoHide(now, !pinToolbar)
        if (hideBar) visibility.hide(now)
        alwaysShowFab = BrowserControlsStore.alwaysShowFloatingButton(appContext)
        fabAction = BrowserControlsStore.floatingButtonAction(appContext)
        fabOnLeft = BrowserControlsStore.floatingButtonOnLeft(appContext)
        // A change to whether a bar reserves space (pinned) or the surface is whole (hidden/auto)
        // changes how much of the surface the page may use, so re-measure the page when it flips.
        val insetChanged = pinToolbar != alwaysShowUrlBar || hideBar != hideUrlBar
        alwaysShowUrlBar = pinToolbar
        hideUrlBar = hideBar
        // The split layout and side are geometry too: a change re-measures both panes.
        val nextSplit = BrowserSplitStore.layout(appContext)
        val nextSideOnRight = BrowserSplitStore.sideOnRight(appContext)
        // A dragged ratio is dropped when a preset is chosen (BrowserSplitStore.setLayout clears
        // it), so this is also how the panes snap back to the preset's own shape.
        val nextFraction = BrowserSplitStore.sideFraction(appContext)
        val splitChanged = nextSplit != splitLayout || nextSideOnRight != splitSideOnRight ||
            nextFraction != splitFraction
        splitLayout = nextSplit
        splitSideOnRight = nextSideOnRight
        splitFraction = nextFraction
        if (splitChanged) {
            layoutWebView(surfaceWidth, surfaceHeight, ViewportDebug.Event.SPLIT_LAYOUT)
        } else if (insetChanged) {
            layoutWebView(surfaceWidth, surfaceHeight, ViewportDebug.Event.STABLE_AREA)
        } else {
            // The toolbar's own ☰ only appears once the floating button stops being the menu's fixed
            // entry point, so a change made in settings has to be reflected in the chrome immediately.
            chrome = BrowserChromeLayout.create(sizes, viewport, showToolbarMenuButton(), chromeBounds, fabOnLeft)
        }
    }

    /** Whether the floating button is drawn right now; hit testing asks the same question. */
    private fun fabVisible(nowMs: Long): Boolean =
        overlay == Overlay.NONE && fabAlpha(nowMs) > 0.01f

    /**
     * The floating button's opacity. Outside fullscreen it is always lit (the default) or follows
     * the toolbar fade. In fullscreen it is immersive: lit right after any touch, then fading out
     * after [FULLSCREEN_FAB_IDLE_MS], so a video or page is left with nothing over it. The tap that
     * brings it back goes to the page as usual — the button only takes taps while it is drawn.
     */
    private fun fabAlpha(nowMs: Long): Float {
        if (visibility.fullscreen) {
            val idle = nowMs - lastInputMs
            if (idle < FULLSCREEN_FAB_IDLE_MS) return 1f
            return (1f - (idle - FULLSCREEN_FAB_IDLE_MS).toFloat() / FULLSCREEN_FAB_FADE_MS).coerceIn(0f, 1f)
        }
        // With no bar to recall, the floating button is the only way to the menu: keep it up.
        return if (alwaysShowFab || hideUrlBar) 1f else visibility.alphaAt(nowMs)
    }

    /** Uptime of the last tap/scroll/fling/pinch on the car surface; drives the fullscreen FAB. */
    private var lastInputMs: Long = 0L

    /**
     * Whether the toolbar draws its own ☰ button, alongside the floating button.
     *
     * The two used to always coexist: an identical hamburger glyph on the auto-hiding toolbar and
     * on the always-present floating button, both opening the same drawer. Nothing distinguished
     * them, so the reported confusion was "which one do I press?" rather than either control being
     * broken. As long as the floating button is bound to [FloatingButtonAction.MENU] — the default
     * — it is the single, fixed way to open the menu and the toolbar does not repeat it. Rebinding
     * the floating button to a different action (tabs, new tab, home...) hands the toolbar button
     * back, since the menu then needs a way in of its own again.
     */
    private fun showToolbarMenuButton(): Boolean = fabAction != FloatingButtonAction.MENU

    private fun performFloatingAction() {
        when (fabAction) {
            FloatingButtonAction.MENU -> openDrawer()
            FloatingButtonAction.TABS -> openTabSwitcher()
            FloatingButtonAction.NEW_TAB -> openNewTab()
            FloatingButtonAction.HOME -> goHome()
            FloatingButtonAction.ADDRESS -> host?.openAddressInput()
            FloatingButtonAction.FULLSCREEN -> toggleFullscreen()
        }
    }

    fun openDrawer() = runOnMain {
        drawerScroll = 0f
        overlay = Overlay.DRAWER
        rebuildDrawer()
        visibility.setDrawerOpen(SystemClock.uptimeMillis(), true)
        trace(ViewportDebug.Event.DRAWER, "open=true")
    }

    /** Switches the open drawer to the secondary "More" list without closing it. */
    private fun openDrawerMore() {
        drawerScroll = 0f
        overlay = Overlay.DRAWER_MORE
        rebuildDrawer()
        trace(ViewportDebug.Event.DRAWER, "open=true more=true")
    }

    /** Switches the open drawer to the split page: the switch and the layouts as pictures. */
    private fun openDrawerSplit() {
        drawerScroll = 0f
        overlay = Overlay.DRAWER_SPLIT
        rebuildDrawer()
        trace(ViewportDebug.Event.DRAWER, "open=true split=true")
    }

    /** A layout picked on the split page: the same path as cycling to it, without the cycle. */
    private fun pickSplitLayout(layout: BrowserSplitLayout) {
        dividerGrabbed = false
        BrowserSplitStore.setLayout(appContext, layout)
        applyControlSettings()
        val label = layout.label(appContext)
        host?.showMessage(
            when {
                isSplit -> label
                !hardwareMode -> appContext.getString(R.string.car_split_unavailable_legacy, label)
                else -> appContext.getString(R.string.car_split_too_narrow, label)
            }
        )
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

    /**
     * The live state the sheet reports. Read fresh on every rebuild rather than cached, because the
     * sheet stays open across a desktop-site toggle and must redraw the switch it just moved.
     */
    private fun menuState(): BrowserMenuState = BrowserMenuState(
        appName = "AutoBridge",
        pageTitle = title?.takeIf { it.isNotBlank() } ?: BrowserDisplayUrl.compact(url),
        url = url,
        tabCount = tabCount,
        isDesktop = BrowserUserAgentStore.mode(appContext) == BrowserUserAgentMode.DESKTOP,
        fullscreen = visibility.fullscreen,
        canGoBack = canGoBack,
        canGoForward = canGoForward,
        version = "v${dev.autobridge.BuildConfig.VERSION_NAME}",
        pinnedToolbar = alwaysShowUrlBar,
        splitActive = isSplit,
        zoomPercent = PageZoom.percent(focusedView),
        splitLayout = splitLayout,
        splitSideOnRight = splitSideOnRight,
    )

    private fun rebuildDrawer() {
        drawer = BrowserDrawerModel.create(
            sizes, viewport, menuState(),
            more = overlay == Overlay.DRAWER_MORE,
            scrollOffset = drawerScroll,
            moreTitle = appContext.getString(R.string.drawer_more),
            split = overlay == Overlay.DRAWER_SPLIT,
            splitTitle = appContext.getString(R.string.drawer_split),
            layoutLabels = BrowserSplitLayout.entries.associateWith { it.label(appContext) },
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
            DrawerAction.NAV_BACK -> goBack()
            DrawerAction.NAV_FORWARD -> goForward()
            // Both routes land on the same editor; "clear" is the one that starts it empty, which
            // the car's address screen does by ignoring the current URL it was not given.
            DrawerAction.ADDRESS_KEYBOARD, DrawerAction.ADDRESS_CLEAR -> target?.openAddressInput()
            DrawerAction.FIND_IN_PAGE -> target?.openFindInPage()
            DrawerAction.AGENT -> target?.openAgent()
            DrawerAction.COPY_URL ->
                target?.showMessage(
                    appContext.getString(
                        if (copyUrl()) R.string.car_url_copied else R.string.car_copy_failed
                    )
                )
            DrawerAction.PASTE_AND_GO -> clipboardText()?.let { load(it) }
                // Covers both an empty clipboard and the platform refusing the read because the
                // app is not focused on the phone; the user gets a reason either way.
                ?: target?.showMessage(appContext.getString(R.string.car_clipboard_unreadable))
            DrawerAction.BOOKMARK_PAGE -> {
                val added = dev.autobridge.entertainment.WebBookmarkStore.add(
                    appContext, title?.takeIf { it.isNotBlank() } ?: url, url
                )
                target?.showMessage(
                    appContext.getString(
                        if (added) R.string.car_bookmark_saved else R.string.car_bookmark_failed
                    )
                )
            }
            DrawerAction.TOGGLE_DESKTOP -> toggleDesktopMode(appContext)
            DrawerAction.TOGGLE_FULLSCREEN -> toggleFullscreen()
            DrawerAction.SPLIT_LAYOUT -> toggleSplit()
            DrawerAction.SPLIT_CHOOSE -> target?.openSplitChooser()
            DrawerAction.SIDE_SHOW_PAGE -> showMainPageOnSide()
            DrawerAction.NAVIGATE_MAPS -> navigateInMaps()
            DrawerAction.ZOOM_IN -> zoomIn()
            DrawerAction.ZOOM_OUT -> zoomOut()
            DrawerAction.RELOAD -> reload()
            DrawerAction.APP_HOME -> target?.openAppHome()
            DrawerAction.OPEN_EXTERNAL -> target?.openExternal(url)
            DrawerAction.SETTINGS -> target?.openSettings()
            DrawerAction.CLEAR_DATA -> {
                clearBrowsingData()
                target?.showMessage(appContext.getString(R.string.car_browsing_data_cleared))
            }
            DrawerAction.DIAGNOSTICS -> target?.openDiagnostics()
            // The same stores the browser's Settings screen writes; applyControlSettings re-reads
            // them and re-lays out the page when the bar's inset or the split's side moved.
            DrawerAction.PIN_TOOLBAR -> {
                BrowserControlsStore.setAlwaysShowUrlBar(appContext, !alwaysShowUrlBar)
                if (!alwaysShowUrlBar) BrowserControlsStore.setHideUrlBar(appContext, false)
                applyControlSettings()
            }
            DrawerAction.SWAP_SPLIT_SIDES -> if (isSplit) {
                BrowserSplitStore.setSideOnRight(appContext, !BrowserSplitStore.sideOnRight(appContext))
                applyControlSettings()
            }
            DrawerAction.MIRROR_PHONE -> target?.openMirror()
            // Handled directly in onSurfaceClick before performDrawerAction is called, since these
            // change what the sheet shows rather than performing a browser action.
            DrawerAction.MORE, DrawerAction.BACK_TO_MENU, DrawerAction.CLOSE_SHEET -> Unit
            // Phone-sheet entries. [BrowserDrawerModel.moreItems] never offers them on the car
            // surface — the car *is* the other end of both — so reaching here means a caller
            // bypassed the model, and doing nothing is the correct response.
            DrawerAction.SEND_TO_CAR, DrawerAction.RECEIVE_FROM_CAR -> Unit
            // Phone-sheet "About & support" links. Offered only on the phone More sheet; the car
            // surface never lists them, so reaching here means a caller bypassed the model.
            DrawerAction.SUPPORT, DrawerAction.LICENSES, DrawerAction.GITHUB -> Unit
            // Phone-only quick-menu entries; the car reaches the same queue/ad-block controls
            // through its own existing rows ([DrawerAction.MEDIA_CENTER], Settings).
            DrawerAction.ADD_TO_QUEUE, DrawerAction.TOGGLE_AD_BLOCK -> Unit
        }
        host?.onBrowserStateChanged()
    }

    private fun handleTabOverlayClick(x: Float, y: Float) {
        // Checked first, matching the drawer: a fixed, always-in-the-same-corner way to leave the
        // switcher without picking a tab, instead of relying on "tap the background" which is easy
        // to miss in a moving car.
        if (tabSwitcherCloseButton().contains(x, y)) {
            overlay = Overlay.NONE
            visibility.setDrawerOpen(SystemClock.uptimeMillis(), false)
            return
        }
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

    /** Same corner and size as the drawer's own close button, so the two overlays feel consistent. */
    private fun tabSwitcherCloseButton(): Box {
        val headerHeight = sizes.toolbarHeight(viewport.height)
        val top = viewport.top.toFloat()
        val closeSide = sizes.touchTarget.coerceAtMost(headerHeight - sizes.contentGap)
        val centerY = top + headerHeight / 2f
        val right = viewport.left + viewport.width - sizes.horizontalPadding
        return Box(right - closeSide, centerY - closeSide / 2f, right, centerY + closeSide / 2f)
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

    /**
     * Built on [PageDensityDisplay]'s context so Chromium scales the page by the car density rather
     * than the phone's; see that class. Falls back to the application context if it cannot exist.
     */
    private fun createWebView(): ScrollableWebView {
        if (pageDensity == null) {
            pageDensity = PageDensityDisplay.create(
                appContext, surfaceWidth, surfaceHeight, pageDensityDpiFor(surfaceWidth, surfaceHeight)
            )
        }
        return newWebView(pageDensity?.context ?: appContext)
    }

    private fun newWebView(context: Context): ScrollableWebView = ScrollableWebView(context).apply {
        // The car WebView is off-screen and drawn to a Surface, so it is the one presentation that
        // cannot be inspected by looking at it. Debug builds expose it over chrome://inspect for the
        // same reason the phone activities do; release builds are untouched.
        BrowserDefaults.configureDebugTools()
        BrowserDefaults.configure(appContext, this)
        appliedIdentity = identityKey()
        // Half of the tile-budget fix; [rasterHost] is the other half, and neither works alone.
        // With the WebView hosted in a window, Chromium sizes its tile budget from `interest_rect`
        // — which is the view's on-screen visible rect unless this is on, and that rect is empty
        // for a view nobody can see. On, it becomes the view's own size, which is the quantity
        // that actually describes what this renderer rasterises. Not set in
        // [BrowserDefaults.configure] because the phone window's WebView is on screen, where it
        // would only cost memory.
        // Legacy only: in HARDWARE mode the WebView is on a real (car) display, its visible rect is
        // the true interest rect, and pre-rastering off-screen content would only cost memory.
        settings.setOffscreenPreRaster(!hardwareMode)
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
             * navigator.geolocation. Left to the default, the request was never answered and map
             * pages had no GPS. This surface has no Activity for a dialog, so it uses the app's
             * existing location permission; see [BrowserGeolocation.answerForCar].
             */
            override fun onGeolocationPermissionsShowPrompt(
                origin: String,
                callback: GeolocationPermissions.Callback,
            ) = BrowserGeolocation.answerForCar(
                appContext, origin, callback,
                onMissingPermission = {
                    mainHandler.post {
                        host?.showMessage(
                            appContext.getString(R.string.car_needs_location_permission)
                        )
                    }
                },
                onLocationOff = {
                    mainHandler.post { host?.showMessage(appContext.getString(R.string.geo_location_off)) }
                },
            )

            /**
             * HTML5 fullscreen (YouTube's fullscreen button, `requestFullscreen()` on a video).
             * Hosted by the same [FullscreenVideoController] the phone browser uses, inside the
             * car window, so the fullscreen video is composited by the system like the inline one.
             * The legacy canvas path has no window to put it in and declines immediately, which
             * tells Chromium to stay inline instead of waiting on a view nobody shows.
             */
            override fun onShowCustomView(view: View, callback: CustomViewCallback) =
                hostFullscreen(view, callback)

            override fun onHideCustomView() {
                hardwareWindow?.fullscreen?.hide()
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
                // The probe carries the same identity as the main WebView — clean UA, no
                // X-Requested-With header, third-party cookies on. Without configure() a popup
                // opened by window.open() (which is how YouTube launches Google sign-in) reaches
                // accounts.google.com as a raw, detectable WebView and Google refuses it with
                // "this browser or app may not be secure". Configuring it first means the very
                // first request the popup makes already looks like a real browser.
                val probe = WebView(appContext).apply {
                    BrowserDefaults.configure(appContext, this)
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(
                            probeView: WebView,
                            request: WebResourceRequest,
                        ): Boolean {
                            val target = request.url.toString()
                            // "Open in the Maps app" can arrive as a popup rather than a link.
                            if (handOffToMaps(target, webView?.url)) {
                                mainHandler.post { probeView.destroy() }
                                return true
                            }
                            mainHandler.post {
                                ContentAddress.https(target)?.let { url ->
                                    // The car surface can only show one WebView, so a popup cannot
                                    // be a visible second window. A Google sign-in popup is loaded
                                    // into the current, visible tab instead of a new one: the user
                                    // sees the sign-in, cookies land in the configured main
                                    // WebView, and returning to the video is a Back away. An
                                    // ordinary target="_blank" still opens a new tab.
                                    if (BrowserDefaults.isSignInPopupHost(url)) load(url) else openNewTab(url)
                                }
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
            /**
             * Drops advertising and tracking subresources when the user has turned blocking on.
             * Returns null — "fetch it as usual" — for everything else, which is the whole of the
             * web whenever the preference is off.
             */
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest,
            ): WebResourceResponse? = BrowserAdBlock.intercept(appContext, request)

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val target = request.url.toString()
                if (BrowserDefaults.isExternalSignInHost(target)) {
                    onExternalSignInRequired?.invoke(target)
                    return true
                }
                // A map opened in the main page asks for the Maps app the same way the side one does.
                if (handOffToMaps(target, view.url)) return true
                // Only allow HTTPS navigation; block custom schemes/intents on the car surface.
                if (ContentAddress.https(target) == null) return true
                // Set the identity before the request leaves; onPageStarted is too late for the
                // main-frame request of a link tap.
                if (request.isForMainFrame) syncUserAgentFor(target)
                return false
            }

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                PageZoom.reset(view)
                loadError = null
                // A redirect into a sign-in origin (e.g. desktop-mode YouTube handing off to
                // accounts.google.com) must switch to the mobile UA Google accepts. Doing it here
                // catches redirects that never went through load()/shouldOverrideUrlLoading.
                syncUserAgentFor(url)
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
                youtube.onPageChanged(view, url)
                webAudio.installPlayTracking()
                onPageChanged?.invoke(url, view.title)
                logDisplays("page-finished")
            }

            /** YouTube switches videos with `pushState`; onPageFinished never fires for those. */
            override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
                currentUrl = url
                youtube.onPageChanged(view, url)
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (!request.isForMainFrame) return
                loadError = appContext.getString(R.string.car_page_load_failed)
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
        // The page rect is inset from the surface by [AutoUiSizes.cardMargin] and drawn with
        // [AutoUiSizes.cardCornerRadius] in drawFrame(). Both are currently 0 — the inset card was
        // tried and dropped because it ringed the page in black on the head unit — so this reduces
        // to "the page is the surface". It stays expressed as an inset rather than assuming zero
        // because shrinking `viewport` is what makes every consumer of it — hit testing, touch
        // mapping, the drawer — follow the page automatically, with no second "card rect" to keep
        // in step.
        val margin = sizes.cardMargin.roundToInt()
        // The page takes the whole surface. It used to be shrunk to the host's stable area, which
        // is the region the host guarantees it will never cover - the intersection of every state
        // its own chrome can be in, and so always the smallest of them. Sizing the page to it meant
        // the panel was permanently laid out for the host's worst case, and the difference was
        // simply black: on one observed head unit an 800x400 surface reported 752x300 stable, a
        // quarter of the height given away for chrome that is not on screen most of the time.
        // Chrome still respects the stable area (see chromeBounds); only the page grew.
        val cardLeft = margin
        val cardRight = (width - margin).coerceAtLeast(cardLeft + 1)
        // A pinned toolbar occupies the top of the surface for good, so the page starts below it
        // instead of being drawn under it (the sign-in header half-hidden under the bar). The page
        // is inset down to the bar's real bottom edge — the bar is drawn at the top of the chrome
        // bounds, which is the stable-area top the host guarantees, so the inset is measured from
        // there rather than from the surface top. With the auto-hiding default the inset is zero
        // and the page keeps the whole surface, exactly as before.
        val toolbarTop = area?.top?.coerceIn(margin, height - margin) ?: margin
        val toolbarBottom = if (alwaysShowUrlBar) {
            val chromeHeight = area?.let { (it.bottom - it.top).toFloat() } ?: (height - 2 * margin).toFloat()
            toolbarTop + sizes.toolbarHeight(chromeHeight.roundToInt()).roundToInt()
        } else margin
        val cardTop = toolbarBottom.coerceAtMost(height - margin - 1)
        val cardBottom = (height - margin).coerceAtLeast(cardTop + 1)
        val desktop = BrowserUserAgentStore.isDesktopIdentity(appContext)
        val next = BrowserViewport.create(
            width, height, sizes.density,
            cardLeft, cardTop, cardRight, cardBottom,
            desktop = desktop,
        )
        // Split: the card is divided into the main page and the side page. Each gets its own
        // viewport (touch mapping, CSS width, page zoom); chrome keeps using the whole card. Only the
        // HARDWARE path can host two views, so legacy stays single.
        val panes = if (hardwareMode) {
            BrowserSplitGeometry.panes(
                splitLayout, cardLeft, cardTop, cardRight, cardBottom,
                sideOnRight = splitSideOnRight,
                gapPx = sizes.dp(SPLIT_GAP_DP).roundToInt(),
                minPanePx = sizes.dp(SPLIT_MIN_PANE_DP).roundToInt(),
                sideFraction = splitFraction,
            )
        } else null
        splitPanes = panes
        // Nothing left to hold on to once the split is gone (a narrower surface, or SINGLE).
        if (panes == null) dividerGrabbed = false
        val nextMain = panes?.main?.let { paneViewport(width, height, it, desktop) } ?: next
        val nextSide = panes?.side?.let { paneViewport(width, height, it, desktop) }
        val geometryUnchanged = next == viewport && nextMain == mainViewport &&
            nextSide == sideViewport && view.width == nextMain.webWidth &&
            (nextSide == null || sideView?.width == nextSide.webWidth)
        viewport = next
        mainViewport = nextMain
        if (nextSide == null) releaseSidePane() else sideViewport = nextSide
        // Chrome is confined to the host's stable area. Its top is clamped to the surface (not to
        // the page's cardTop): when the toolbar is pinned it is drawn in the reserved band *above*
        // the page, so clamping it down into the page would put the bar below the content it labels.
        val chromeTopBound = toolbarTop
        chromeBounds = when {
            area != null -> Box(
                area.left.coerceIn(cardLeft, cardRight).toFloat(),
                area.top.coerceIn(chromeTopBound, cardBottom).toFloat(),
                area.right.coerceIn(cardLeft, cardRight).toFloat(),
                area.bottom.coerceIn(chromeTopBound, cardBottom).toFloat()
            )
            // No stable area, but the toolbar is pinned: chrome must still start in the reserved
            // band above the page rather than defaulting to the page rect (which now starts below
            // the inset), or the pinned bar would paint over the top of the content again.
            alwaysShowUrlBar -> Box(
                cardLeft.toFloat(), chromeTopBound.toFloat(),
                cardRight.toFloat(), cardBottom.toFloat()
            )
            else -> null
        }
        chrome = BrowserChromeLayout.create(sizes, viewport, showToolbarMenuButton(), chromeBounds, fabOnLeft)
        if (drawerOpen) rebuildDrawer()
        if (geometryUnchanged) {
            trace(event, "reflow=skipped")
            return
        }

        // The zoom that turns a surface-sized view into a [contentWidthDp]-wide page. Applied
        // before measure/layout so the reflow this triggers already lays out at the right width,
        // and re-applied on every geometry change because the ratio moves with the surface.
        pageDensity?.update(mainViewport.webWidth, mainViewport.webHeight, mainViewport.pageDensityDpi)
        lastPageScalePercent = applyPageScale(view, mainViewport, lastPageScalePercent)
        // Sized here rather than on surface attach because this is the one place the page's pixel
        // size is decided, and the host window has to be the same size as the view it rasters.
        // The host is kept across surface attach/detach: detaching the view would release the
        // hardware draw state and put the tile budget back to zero.
        var hardware = hardwareWindow
        val activeSurface = surface
        // The window's density follows the whole surface, not a pane: with two panes of different
        // widths there is no single page density, and keying the window to one pane would rebuild
        // it on every split change. Per-page CSS width comes from each WebView's PageDensityDisplay.
        // Without a split this is the same value the page viewport yields.
        if (hardware != null && activeSurface != null &&
            !hardware.matches(
                hardware.width, hardware.height,
                CarHardwareWebWindow.coerceDpi(pageDensityDpiFor(hardware.width, hardware.height))
            )
        ) {
            // The CSS width moved (desktop toggle) without the surface moving. The density lives on
            // the display, and a Presentation cannot survive its display's metrics changing, so the
            // window is rebuilt around the same WebView rather than resized in place.
            attachHardwareWindow(activeSurface, hardware.width, hardware.height)
            hardware = hardwareWindow
        }
        if (hardware != null) {
            // A real window lays the view out itself; it only needs to know where the page goes.
            hardware.placePage(view, mainViewport.left, mainViewport.top, mainViewport.webWidth, mainViewport.webHeight)
            nextSide?.let { placeSidePane(hardware, it) }
            trace(event, "reflow=applied split=${splitLayoutInUse}")
            return
        }
        val host = rasterHost
        if (host != null) {
            // Hosted: the window owns the view's layout params, because its parent measures it.
            host.resize(
                viewport.webWidth, viewport.webHeight,
                appContext.resources.configuration.densityDpi
            )
        } else {
            view.layoutParams = ViewGroup.LayoutParams(viewport.webWidth, viewport.webHeight)
            if (!rasterHostAttempted) {
                rasterHostAttempted = true
                rasterHost = OffscreenWebViewWindow.attach(
                    appContext, view, viewport.webWidth, viewport.webHeight,
                    appContext.resources.configuration.densityDpi
                )
            }
        }
        view.measure(
            View.MeasureSpec.makeMeasureSpec(viewport.webWidth, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(viewport.webHeight, View.MeasureSpec.EXACTLY)
        )
        view.layout(0, 0, viewport.webWidth, viewport.webHeight)
        trace(event, "reflow=applied")
    }

    /**
     * Pins the WebView's page zoom to [BrowserViewport.pageScalePercent].
     *
     * `setInitialScale` is what decouples "how wide the page thinks it is" from "how many pixels we
     * rasterise". Without it the WebView scales by the phone's density, so the only way to reach a
     * car-appropriate CSS width was to inflate the view by that factor — which is what drove the
     * off-screen surface to 3840x1920 and made Chromium drop tiles it could not afford.
     *
     * It takes effect on the next load, so a change that must be visible immediately (the desktop
     * toggle) reloads afterwards. [lastPageScalePercent] keeps that reload out of the ordinary
     * resize path, where the scale usually has not moved at all.
     */
    private fun applyPageScale(view: WebView, pane: BrowserViewport, lastPercent: Int): Int {
        val percent = pane.pageScalePercent
        if (percent == lastPercent) return percent
        // `setInitialScale` alone is not enough, and the first attempt at this shipped without
        // these two lines: the trace came back `scale=1.000` (the raster was 1:1, as intended) but
        // `pageScale=3.000`, so the page had been laid out at 800/3 = 267 CSS px and blown up three
        // times. With useWideViewPort on, Chromium takes the layout width and the scale from the
        // page's own `<meta name="viewport">` and the requested initial scale is simply discarded.
        // Turning both off is what hands the decision back: the viewport is then the view's own
        // width at the scale set here. It is set on the car WebView rather than in
        // BrowserDefaults.configure because the phone window wants the opposite - there the
        // WebView's density *is* the display's, so the meta viewport already resolves correctly.
        view.settings.useWideViewPort = false
        view.settings.loadWithOverviewMode = false
        view.setInitialScale(percent)
        return percent
    }

    /** A pane's own viewport: same rules as the card, so CSS width follows the pane's width. */
    private fun paneViewport(width: Int, height: Int, pane: PaneRect, desktop: Boolean): BrowserViewport =
        BrowserViewport.create(
            width, height, sizes.density,
            pane.left, pane.top, pane.right, pane.bottom,
            desktop = desktop,
        )

    /**
     * Hosts the side page at [pane], creating it the first time a split needs it. Its
     * [PageDensityDisplay] is made before the WebView because Chromium reads the density from the
     * construction Context; a pane narrower than the surface needs its own.
     */
    private fun placeSidePane(hardware: CarHardwareWebWindow, pane: BrowserViewport) {
        var side = sideView
        val created = side == null
        if (side == null) {
            sidePageDensity = PageDensityDisplay.create(appContext, pane.webWidth, pane.webHeight, pane.pageDensityDpi)
            side = newSideWebView(sidePageDensity?.context ?: appContext)
            sideView = side
            sideLastPageScalePercent = 0
        }
        sidePageDensity?.update(pane.webWidth, pane.webHeight, pane.pageDensityDpi)
        sideLastPageScalePercent = applyPageScale(side, pane, sideLastPageScalePercent)
        hardware.placePage(side, pane.left, pane.top, pane.webWidth, pane.webHeight)
        if (created) {
            BrowserDefaults.applyIdentity(appContext, side, sideUrl)
            side.loadUrl(sideUrl)
            if (running) side.onResume()
            AutoBridgeVideoLog.i("split side pane created ${pane.webWidth}x${pane.webHeight} url=$sideUrl")
        }
    }

    /**
     * Drops the side page when the surface goes back to a single page, so a single-page session
     * carries no second WebView. Its URL is kept, so the next split reopens the same page.
     */
    private fun releaseSidePane() {
        sideViewport = null
        sideFocused = false
        val side = sideView ?: return
        side.url?.let {
            sideUrl = it
            BrowserSplitStore.setSideUrl(appContext, it)
        }
        (side.parent as? ViewGroup)?.removeView(side)
        side.stopLoading()
        side.destroy()
        sideView = null
        sidePageDensity?.release()
        sidePageDensity = null
        sideTitle = null
        sideProgress = 100
        sideLastPageScalePercent = 0
    }

    /**
     * The side page: the same identity, HTTPS-only rule and fullscreen/geolocation handling as the
     * main page, but none of the tab, history-list or find wiring — it is one page, not a browser.
     */
    private fun newSideWebView(context: Context): ScrollableWebView = ScrollableWebView(context).apply {
        BrowserDefaults.configureDebugTools()
        BrowserDefaults.configure(appContext, this)
        // The side page must never take audio focus off the main page; see [SidePaneAudio].
        SidePaneAudio.install(this)
        // Split only exists in HARDWARE mode, where the view is on a real display (see newWebView).
        settings.setOffscreenPreRaster(false)
        setDownloadListener(
            BrowserDownloads.listener(appContext) { message ->
                mainHandler.post { host?.showMessage(message) }
            }
        )
        webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                sideProgress = newProgress.coerceIn(0, 100)
                if (sideActive) onPageChanged?.invoke(view.url ?: sideUrl, view.title)
            }

            override fun onGeolocationPermissionsShowPrompt(
                origin: String,
                callback: GeolocationPermissions.Callback,
            ) = BrowserGeolocation.answerForCar(
                appContext, origin, callback,
                onMissingPermission = {
                    mainHandler.post {
                        host?.showMessage(
                            appContext.getString(R.string.car_needs_location_permission)
                        )
                    }
                },
                onLocationOff = {
                    mainHandler.post { host?.showMessage(appContext.getString(R.string.geo_location_off)) }
                },
            )

            override fun onShowCustomView(view: View, callback: CustomViewCallback) =
                hostFullscreen(view, callback)

            override fun onHideCustomView() {
                hardwareWindow?.fullscreen?.hide()
            }

            /** A popup from the side page replaces the side page; it has no tabs to open it in. */
            override fun onCreateWindow(
                view: WebView,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: Message,
            ): Boolean {
                if (!isUserGesture) return false
                val transport = resultMsg.obj as? WebView.WebViewTransport ?: return false
                val probe = WebView(appContext).apply {
                    BrowserDefaults.configure(appContext, this)
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(probeView: WebView, request: WebResourceRequest): Boolean {
                            val target = request.url.toString()
                            if (handOffToMaps(target, sideView?.url)) {
                                mainHandler.post { probeView.destroy() }
                                return true
                            }
                            mainHandler.post {
                                ContentAddress.https(target)?.let { url -> sideView?.loadUrl(url) }
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
            /**
             * Drops advertising and tracking subresources when the user has turned blocking on.
             * Returns null — "fetch it as usual" — for everything else, which is the whole of the
             * web whenever the preference is off.
             */
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest,
            ): WebResourceResponse? = BrowserAdBlock.intercept(appContext, request)

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val target = request.url.toString()
                if (BrowserDefaults.isExternalSignInHost(target)) {
                    onExternalSignInRequired?.invoke(target)
                    return true
                }
                // The map's "Start" / "Open app" link: the mobile web cannot navigate, the Maps
                // app can. Handed over rather than dropped like every other non-web link.
                if (handOffToMaps(target, view.url)) return true
                if (ContentAddress.https(target) == null) return true
                if (request.isForMainFrame) BrowserDefaults.applyIdentity(appContext, view, target)
                return false
            }

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                PageZoom.reset(view)
                BrowserDefaults.applyIdentity(appContext, view, url)
            }

            override fun onPageFinished(view: WebView, url: String) {
                sideUrl = url
                sideTitle = view.title
                sideProgress = 100
                BrowserSplitStore.setSideUrl(appContext, url)
                WebHistoryStore.record(appContext, view.title, url)
                sideAudio.installPlayTracking()
                if (sideActive) onPageChanged?.invoke(url, view.title)
            }

            override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
                sideUrl = url
            }
        }
    }

    /**
     * Shows Chromium fullscreen (from either pane) in the car window. It covers the whole display,
     * so a fullscreen video from the side pane takes the full surface, not just its pane.
     */
    private fun hostFullscreen(view: View, callback: WebChromeClient.CustomViewCallback) {
        val window = hardwareWindow
        if (window == null) {
            AutoBridgeVideoLog.w("fullscreen declined mode=$activeMode (no hardware window)")
            callback.onCustomViewHidden()
            return
        }
        window.fullscreen.show(view, callback) { entered ->
            AutoBridgeVideoLog.i("fullscreen ${if (entered) "enter" else "exit"} url=${focusedView?.url}")
            setFullscreen(entered)
        }
        logDisplays("fullscreen-shown")
    }

    /** WebView-side geometry, so a mismatch between what is laid out and what gets painted shows up. */
    @Suppress("DEPRECATION")
    private fun webViewMetrics(): String {
        val view = webView ?: return "view=none"
        return "view=${view.width}x${view.height}" +
            " viewScroll=${view.scrollX},${view.scrollY}" +
            " pageScale=" + String.format("%.3f", view.scale) +
            " contentH=${view.contentHeight}" +
            " rasterHost=" + (if (rasterHost != null) "window" else "none") +
            " pageDpi=" + (hardwareWindow?.densityDpi ?: viewport.pageDensityDpi)
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

    /**
     * Creates (or re-points) the hardware window for [surface]. Returns false and drops this session
     * to [CarBrowserRenderMode.LEGACY_CANVAS] if the platform refuses, with the exact failure logged.
     */
    private fun attachHardwareWindow(surface: Surface, width: Int, height: Int): Boolean {
        val view = webView ?: return false
        val dpi = pageDensityDpiFor(width, height)
        val existing = hardwareWindow
        if (existing != null && existing.matches(width, height, dpi)) {
            existing.setSurface(surface)
            logDisplays("hardware-surface-reattached")
            return true
        }
        if (existing != null) {
            AutoBridgeVideoLog.i("car surface size changed ${existing.width}x${existing.height} -> ${width}x$height; recreating window")
            existing.release()
            hardwareWindow = null
        }
        return try {
            val window = CarHardwareWebWindow.create(
                context = appContext,
                surface = surface,
                width = width,
                height = height,
                densityDpi = dpi,
                drawChrome = ::drawChromeLayer,
                onUnexpectedDismiss = { mainHandler.post(::recoverHardwareWindow) },
            )
            hardwareWindow = window
            window.placePage(view, mainViewport.left, mainViewport.top, mainViewport.webWidth, mainViewport.webHeight)
            // A rebuilt window starts empty; the side page moves across with the main one.
            val side = sideView
            val sidePane = sideViewport
            if (side != null && sidePane != null) {
                window.placePage(side, sidePane.left, sidePane.top, sidePane.webWidth, sidePane.webHeight)
            }
            logDisplays("hardware-window-attached")
            true
        } catch (error: RuntimeException) {
            AutoBridgeVideoLog.w(
                "HARDWARE render path refused (sdk=${android.os.Build.VERSION.SDK_INT} " +
                    "surface=${width}x$height valid=${surface.isValid}); falling back to LEGACY_CANVAS",
                error
            )
            activeMode = CarBrowserRenderMode.LEGACY_CANVAS
            view.settings.setOffscreenPreRaster(true)
            false
        }
    }

    /**
     * Density of the display the page is hosted on, chosen so the page's CSS width is
     * [BrowserViewport.contentWidthDp] (see [BrowserViewport.pageDensityDpi]). Computed from the
     * same inputs [layoutWebView] uses, because the window is created before the first layout.
     */
    private fun pageDensityDpiFor(width: Int, height: Int): Int =
        BrowserViewport.create(
            width, height, AutoUiSizes.forCarSurface(surfaceDpi).density,
            desktop = BrowserUserAgentStore.isDesktopIdentity(appContext),
        ).pageDensityDpi

    /** The platform dismissed the window (e.g. display metrics changed); rebuild it on the live surface. */
    private fun recoverHardwareWindow() {
        val dismissed = hardwareWindow ?: return
        dismissed.release()
        hardwareWindow = null
        val activeSurface = surface
        if (running && activeSurface != null && activeSurface.isValid) {
            attachHardwareWindow(activeSurface, surfaceWidth, surfaceHeight)
            layoutWebView(surfaceWidth, surfaceHeight, ViewportDebug.Event.SURFACE_RESIZED)
        }
    }

    private fun logDisplays(stage: String) {
        val window = hardwareWindow
        AutoBridgeVideoLog.displays(
            stage, activeMode, window?.displayId, window?.windowView, webView, webView?.url
        )
    }

    /**
     * HARDWARE mode's chrome pass: runs on the chrome layer's hardware canvas, which covers the
     * whole display in car-surface pixels — the same coordinate space [drawFrame] draws in, so the
     * toolbar, drawer, tab switcher and FAB are drawn by the very same functions and still match
     * [onSurfaceClick]'s hit testing. The page itself is not drawn here; it is a real view below.
     */
    private fun drawChromeLayer(canvas: Canvas) {
        val save = canvas.save()
        clipRect.set(
            viewport.left.toFloat(), viewport.top.toFloat(),
            (viewport.left + viewport.width).toFloat(), (viewport.top + viewport.height).toFloat()
        )
        canvas.clipRect(clipRect)
        if (loadError != null && !isVideoFullscreen) {
            canvas.save()
            canvas.translate(mainViewport.left.toFloat(), mainViewport.top.toFloat())
            canvas.clipRect(0f, 0f, mainViewport.width.toFloat(), mainViewport.height.toFloat())
            drawErrorOverlay(canvas)
            canvas.restore()
        }
        if (isSplit && !isVideoFullscreen) {
            drawSplitFocus(canvas)
            drawSplitDivider(canvas)
        }
        drawChrome(canvas, SystemClock.uptimeMillis())
        canvas.restoreToCount(save)
    }

    private fun drawFrame(nowMs: Long) {
        // HARDWARE: the WebView renders itself through the window; only the chrome layer needs
        // re-recording so fades and overlays advance. Never lockCanvas() here — the virtual
        // display is the surface's producer.
        hardwareWindow?.let {
            it.invalidateChrome()
            return
        }
        if (hardwareMode) return
        val activeSurface = surface ?: return
        val view = webView ?: return
        if (!activeSurface.isValid || surfaceWidth <= 0 || surfaceHeight <= 0) return
        val canvas: Canvas = runCatching { activeSurface.lockCanvas(null) }.getOrNull() ?: return
        try {
            canvas.drawColor(BrowserTheme.dark.background)
            // Everything for this frame — page, drawer/tab overlays, toolbar, handle — is clipped
            // to one rect, so a rounded panel reads as a single surface rather than a rounded page
            // with a square toolbar stitched on top of it. Squared off (the current sizing) that
            // is an ordinary rect clip: clipPath is the slower of the two and forces the canvas off
            // its fast paths, so it is used only when there is actually a corner to round.
            val cardOuterSave = canvas.save()
            val radius = sizes.cardCornerRadius
            clipRect.set(
                viewport.left.toFloat(), viewport.top.toFloat(),
                (viewport.left + viewport.width).toFloat(), (viewport.top + viewport.height).toFloat()
            )
            if (radius > 0f) {
                clipPath.rewind()
                clipPath.addRoundRect(clipRect, radius, radius, android.graphics.Path.Direction.CW)
                canvas.clipPath(clipPath)
            } else {
                canvas.clipRect(clipRect)
            }

            canvas.save()
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
            drawChrome(canvas, nowMs)
            canvas.restoreToCount(cardOuterSave)
        } catch (error: RuntimeException) {
            Log.w(TAG, "WebView draw to car surface failed", error)
        } finally {
            runCatching { activeSurface.unlockCanvasAndPost(canvas) }
        }
    }

    /**
     * Outlines the focused pane in a split, so it is clear which page the toolbar, scrolling and the
     * address bar will act on. The divider itself is the empty gap between the panes.
     */
    private fun drawSplitFocus(canvas: Canvas) {
        val pane = (if (sideFocused) sideViewport else mainViewport) ?: return
        val stroke = sizes.dp(2f)
        toolbarPaint.style = Paint.Style.STROKE
        toolbarPaint.strokeWidth = stroke
        toolbarPaint.color = BrowserTheme.dark.accent
        toolbarPaint.alpha = 200
        canvas.drawRect(
            pane.left + stroke / 2f, pane.top + stroke / 2f,
            pane.left + pane.width - stroke / 2f, pane.top + pane.height - stroke / 2f,
            toolbarPaint
        )
        toolbarPaint.alpha = 255
        toolbarPaint.style = Paint.Style.FILL
    }

    /**
     * The handle on the divider. It is drawn whenever there is a split, faint, because a divider
     * nobody can see is one nobody tries to drag; grabbing it lights it up and widens it, which is
     * the only feedback the driver gets that the next drag moves the panes rather than the page.
     */
    private fun drawSplitDivider(canvas: Canvas) {
        val panes = splitPanes ?: return
        val capsule = seamCapsule(panes)
        val radius = minOf(capsule.width, capsule.height) / 2f
        // The capsule: dark and nearly opaque, so it reads over either page.
        toolbarPaint.style = Paint.Style.FILL
        toolbarPaint.color = BrowserTheme.dark.surfaceContainerHighest
        toolbarPaint.alpha = 235
        canvas.drawRoundRect(capsule.left, capsule.top, capsule.right, capsule.bottom, radius, radius, toolbarPaint)
        toolbarPaint.alpha = 255
        // A small ✕ at its end.
        val close = sideCloseBox(panes)
        drawIcon(canvas, BrowserIcon.CLOSE, close.centerX, close.centerY, sizes.dp(12f), BrowserTheme.dark.iconEnabled)
        // A hairline between the ✕ and the grip.
        val grip = dividerHandleBox(panes)
        toolbarPaint.color = BrowserTheme.dark.outlineVariant
        val hair = sizes.dp(1f)
        if (panes.stacked) {
            canvas.drawRect(grip.left, grip.top + sizes.dp(5f), grip.left + hair, grip.bottom - sizes.dp(5f), toolbarPaint)
        } else {
            canvas.drawRect(grip.left + sizes.dp(5f), grip.top, grip.right - sizes.dp(5f), grip.top + hair, toolbarPaint)
        }
        // The grip: two short bars along the seam, lit in the accent while grabbed.
        toolbarPaint.color = if (dividerGrabbed) BrowserTheme.dark.accent else BrowserTheme.dark.textSecondary
        val bar = sizes.dp(2f)
        val len = sizes.dp(16f) / 2f
        val apart = sizes.dp(3f)
        for (offset in listOf(-apart, apart)) {
            if (panes.stacked) {
                val y = grip.centerY + offset
                canvas.drawRoundRect(grip.centerX - len, y - bar / 2f, grip.centerX + len, y + bar / 2f, bar, bar, toolbarPaint)
            } else {
                val x = grip.centerX + offset
                canvas.drawRoundRect(x - bar / 2f, grip.centerY - len, x + bar / 2f, grip.centerY + len, bar, bar, toolbarPaint)
            }
        }
    }

    /** Toolbar, overlays and FAB — shared by the legacy frame and the hardware chrome layer. */
    private fun drawChrome(canvas: Canvas, nowMs: Long) {
        // No grab-handle graphic while chrome is hidden: the page fills the surface with
        // nothing floating over it. The edge-reveal band ([BrowserChromeLayout.edgeReveal])
        // still recalls the toolbar on a swipe/tap; it is simply never drawn.
        // When the bar is hidden it is never painted, in any state — not even dimmed behind an
        // open drawer. Forcing the alpha to zero here (rather than guarding each drawToolbar
        // call) keeps the single source of "is the bar visible" for both draw branches below.
        val alpha = if (hideUrlBar) 0f else visibility.alphaAt(nowMs)
        val overlayOpen = overlay != Overlay.NONE
        // While an overlay owns the surface the toolbar is painted *first*, so the overlay's
        // scrim dims it. Painted afterwards it stayed fully lit above the sheet: its buttons
        // advertised themselves as live while every tap on them was being swallowed, and the
        // bar covered the sheet's own close button. Dimmed and behind, it reads as what it is
        // — inactive until the sheet is dismissed.
        if (overlayOpen && alpha > 0.01f) drawToolbar(canvas, alpha)
        when (overlay) {
            Overlay.DRAWER, Overlay.DRAWER_MORE, Overlay.DRAWER_SPLIT -> drawDrawer(canvas)
            Overlay.TABS -> drawTabSwitcher(canvas)
            Overlay.NONE -> Unit
        }
        if (!overlayOpen && alpha > 0.01f) drawToolbar(canvas, alpha)
        // Drawn last and, by default, at full opacity regardless of the chrome fade: an
        // always-available control that faded with the toolbar would be the same two-step it
        // replaces. It is hidden while an overlay already owns the surface, and follows the
        // toolbar's fade only when the user has asked for that
        // ([BrowserControlsStore.alwaysShowFloatingButton]).
        if (fabVisible(nowMs)) drawFab(canvas, fabAlpha(nowMs))
    }

    /** The floating control button: one large, always-present target that opens the menu. */
    private fun drawFab(canvas: Canvas, alpha: Float) {
        val box = chrome.fab
        val opacity = alpha.coerceIn(0f, 1f)
        val radius = minOf(box.width, box.height) / 2f
        // M3 FAB: a rounded square (16dp corners on a 56dp button) in primaryContainer, not a
        // circle in the toolbar colour. The hit box is the same square either way.
        val corner = minOf(box.width, box.height) * FAB_CORNER_FRACTION
        toolbarPaint.color = BrowserTheme.dark.fabContainer
        toolbarPaint.alpha = (245 * opacity).toInt()
        canvas.drawRoundRect(box.left, box.top, box.right, box.bottom, corner, corner, toolbarPaint)
        toolbarPaint.alpha = 255
        // The icon says what the button will do, so a button rebound to "new tab" does not keep
        // claiming to be the menu.
        drawIcon(
            canvas, fabAction.icon, box.centerX, box.centerY,
            radius * 0.95f, BrowserTheme.dark.onFabContainer, (255 * opacity).toInt()
        )
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
        toolbarPaint.color = BrowserTheme.dark.toolbarBackground
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
            val color = if (enabled) BrowserTheme.dark.iconEnabled else BrowserTheme.dark.iconDisabled
            val icon = when (slot.zone) {
                ChromeZone.BACK -> BrowserIcon.BACK
                ChromeZone.FORWARD -> BrowserIcon.FORWARD
                ChromeZone.RELOAD -> if (isLoading) BrowserIcon.CLOSE else BrowserIcon.RELOAD
                ChromeZone.FULLSCREEN -> if (isFullscreen) BrowserIcon.FULLSCREEN_EXIT else BrowserIcon.FULLSCREEN_ENTER
                ChromeZone.MENU -> BrowserIcon.MENU
                else -> null
            }
            if (icon != null) {
                drawIcon(
                    canvas, icon, slot.bounds.centerX, slot.bounds.centerY,
                    slot.iconSize, color, opacity
                )
            }
        }

        // Address pill: the page's identity, not a second row of controls.
        val pill = chrome.address
        if (pill.width > sizes.touchTarget) {
            addressPaint.color = BrowserTheme.dark.addressPillBackground
            addressPaint.alpha = opacity
            // Fully rounded, like the M3 search bar.
            canvas.drawRoundRect(
                RectF(pill.left, pill.top, pill.right, pill.bottom),
                pill.height / 2f, pill.height / 2f, addressPaint
            )
            val address = runCatching { Uri.parse(url) }.getOrNull()
            val host = address?.host.orEmpty()
            val isHttps = url.startsWith("https://", ignoreCase = true)
            val badgeColor = if (isHttps) BrowserTheme.dark.secureBadge else BrowserTheme.dark.insecureBadge
            val badgeX = pill.left + sizes.horizontalPadding
            val badgeSize = sizes.iconSmall * 0.8f
            drawIcon(
                canvas, if (isHttps) BrowserIcon.LOCK else BrowserIcon.WARNING,
                badgeX + badgeSize / 2f, pill.centerY, badgeSize, badgeColor, opacity
            )

            titlePaint.color = BrowserTheme.dark.textPrimary
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
            toolbarPaint.color = BrowserTheme.dark.accent
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
        toolbarPaint.color = BrowserTheme.dark.surfaceContainer
        toolbarPaint.alpha = 90
        canvas.drawRoundRect(
            RectF(handle.left, handle.top, handle.right, handle.bottom),
            sizes.cornerRadius, sizes.cornerRadius, toolbarPaint
        )
        toolbarPaint.alpha = 255
        addressPaint.color = BrowserTheme.dark.onSurfaceVariant
        addressPaint.alpha = 170
        val barWidth = handle.width * 0.3f
        val barHeight = sizes.dp(3f)
        canvas.drawRoundRect(
            RectF(
                handle.centerX - barWidth / 2f, handle.centerY - barHeight / 2f,
                handle.centerX + barWidth / 2f, handle.centerY + barHeight / 2f
            ),
            barHeight, barHeight, addressPaint
        )
        addressPaint.alpha = 255
        if (loadingProgress in 0..99) {
            toolbarPaint.color = BrowserTheme.dark.accent
            canvas.drawRect(
                viewport.left.toFloat(), viewport.top.toFloat(),
                viewport.left + viewport.width * (loadingProgress / 100f),
                viewport.top + sizes.dp(2.5f), toolbarPaint
            )
        }
    }

    /**
     * Draws the menu sheet: header, address row, cards of tiles, the desktop switch and the footer.
     *
     * Two passes around one clip. Everything below [BrowserDrawerModel.headerBottom] scrolls and is
     * drawn inside a clip that starts there; the header is drawn afterwards, above it, so a tile
     * scrolled up vanishes under the header rather than over it. That boundary is the same one
     * [BrowserDrawerModel.rowAt] refuses taps above, so what is hidden is also untappable — the two
     * are read from one model instead of being kept in step by hand.
     */
    private fun drawDrawer(canvas: Canvas) {
        val model = drawer ?: return
        // The phone sheet's generous corner, so the two read as the same panel.
        val radius = sizes.dp(AutoUiSizes.SHEET_CORNER_RADIUS_DP)
        // Scrim over the page so the sheet reads as a layer, without moving anything beneath it.
        toolbarPaint.color = BrowserTheme.dark.scrim
        canvas.drawRect(
            viewport.left.toFloat(), viewport.top.toFloat(),
            (viewport.left + viewport.width).toFloat(), (viewport.top + viewport.height).toFloat(),
            toolbarPaint
        )

        val panel = model.panel
        toolbarPaint.color = BrowserTheme.dark.sheetBackground
        canvas.drawRoundRect(panel.left, panel.top, panel.right, panel.bottom, radius, radius, toolbarPaint)

        canvas.save()
        canvas.clipRect(panel.left, model.headerBottom, panel.right, panel.bottom)
        model.address?.let { drawSheetAddress(canvas, it) }
        model.primary?.let { drawSheetPrimary(canvas, it) }
        // The phone sheet separates its groups with faint full-width rules, not filled cards.
        toolbarPaint.color = BrowserTheme.dark.hairline
        model.dividers.forEach { y ->
            canvas.drawRect(model.header.left, y, model.header.right, y + sizes.dp(1f), toolbarPaint)
        }
        model.sectionLabels.forEach { (labelRes, box) -> drawSheetSectionLabel(canvas, labelRes, box) }
        model.plates.forEach { (labelRes, box) -> drawSheetPlate(canvas, labelRes, box) }
        model.zoomReadout?.let { (text, box) -> drawSheetZoomReadout(canvas, text, box) }
        model.tiles.forEach {
            when (it.kind) {
                DrawerKind.LIST -> drawSheetListRow(canvas, it)
                DrawerKind.ROUND -> drawSheetStepButton(canvas, it)
                DrawerKind.LAYOUT -> drawSheetLayoutCard(canvas, it)
                else -> drawSheetTile(canvas, it)
            }
        }
        model.toggles.forEach { drawSheetToggle(canvas, it) }
        canvas.restore()

        drawSheetHeader(canvas, model)
    }

    /**
     * One inflated [Drawable] per [BrowserIcon] resource, so the vector is parsed once and only its
     * tint/alpha/bounds are re-applied per frame — the car redraws many times a second and
     * re-inflating a vector each time would be wasteful. Mirrors
     * [BrowserActivity.buildIconDrawable], the phone's equivalent, so the same action resolves to
     * the same artwork on both surfaces.
     */
    private val iconCache = HashMap<Int, Drawable>()

    /**
     * Draws a [BrowserIcon] as a tinted square centred on ([cx], [cy]), the Canvas-surface
     * counterpart to the phone's `ImageView` icons. [sizePx] is the side of that square; the icon
     * is centred on the point rather than offset to a text baseline, which is why callers pass a
     * box centre where the old glyph draws passed a baseline.
     */
    private fun drawIcon(
        canvas: Canvas,
        icon: BrowserIcon,
        cx: Float,
        cy: Float,
        sizePx: Float,
        color: Int,
        alpha: Int = 255,
    ) {
        val drawable = iconCache.getOrPut(icon.resId) {
            ContextCompat.getDrawable(appContext, icon.resId)!!.mutate()
        }
        val half = sizePx / 2f
        drawable.setBounds(
            (cx - half).roundToInt(), (cy - half).roundToInt(),
            (cx + half).roundToInt(), (cy + half).roundToInt()
        )
        drawable.setTint(color)
        drawable.alpha = alpha.coerceIn(0, 255)
        drawable.draw(canvas)
    }

    /** Draws [text] centred on [cx], restoring the alignment so no later draw inherits it. */
    private fun drawCentered(canvas: Canvas, paint: Paint, text: String, cx: Float, baseline: Float) {
        val previous = paint.textAlign
        paint.textAlign = Paint.Align.CENTER
        canvas.drawText(text, cx, baseline, paint)
        paint.textAlign = previous
    }

    /** Truncates to what [width] can hold, so a long title can never run past its box. */
    private fun fit(text: String, paint: Paint, width: Float): String =
        TextUtils.ellipsize(text, TextPaint(paint), width.coerceAtLeast(0f), TextUtils.TruncateAt.END)
            .toString()

    private fun drawSheetHeader(canvas: Canvas, model: BrowserDrawerModel) {
        val grip = model.grip
        toolbarPaint.color = BrowserTheme.dark.iconDisabled
        canvas.drawRoundRect(
            grip.left, grip.top, grip.right, grip.bottom,
            grip.height / 2f, grip.height / 2f, toolbarPaint
        )

        // Title and page title straight on the sheet, as the phone header draws them: no card.
        val header = model.header
        val firstButton = (model.headerLinks.map { it.bounds.left } + model.closeButton.left)
            .filter { it > model.titleLeft }.minOrNull() ?: header.right
        val titleSize = sizes.iconLarge.coerceAtMost(header.height * 0.42f)
        val subSize = (sizes.iconSmall * 0.82f).coerceAtMost(header.height * 0.26f)
        val textLeft = model.titleLeft
        val textLimit = firstButton - textLeft - sizes.contentGap
        val hasSubtitle = model.subtitle.isNotBlank()
        val blockTop = header.centerY - (titleSize + if (hasSubtitle) subSize * 1.4f else 0f) / 2f
        titlePaint.color = BrowserTheme.dark.textPrimary
        titlePaint.textSize = titleSize
        canvas.drawText(fit(model.title, titlePaint, textLimit), textLeft, blockTop + titleSize * 0.92f, titlePaint)
        if (hasSubtitle) {
            detailPaint.color = BrowserTheme.dark.textSecondary
            detailPaint.textSize = subSize
            canvas.drawText(
                fit(model.subtitle, detailPaint, textLimit), textLeft,
                blockTop + titleSize + subSize * 1.3f, detailPaint
            )
        }

        model.headerLinks.forEach { link ->
            val box = link.bounds
            toolbarPaint.color = BrowserTheme.dark.sheetCardBackground
            canvas.drawRoundRect(
                box.left, box.top, box.right, box.bottom, box.height / 2f, box.height / 2f, toolbarPaint
            )
            if (link.kind == DrawerKind.ROUND) {
                val iconSize = sizes.iconLarge.coerceAtMost(box.height * 0.6f)
                drawIcon(canvas, link.item.icon, box.centerX, box.centerY, iconSize, BrowserTheme.dark.textPrimary)
            } else {
                // A pill: icon on the left, its label to the right, drawn as one centred block.
                val iconSize = (sizes.iconSmall * 0.9f).coerceAtMost(box.height * 0.5f)
                glyphPaint.color = BrowserTheme.dark.textPrimary
                glyphPaint.textSize = (sizes.iconSmall * 0.8f).coerceAtMost(box.height * 0.42f)
                val label = link.item.label(appContext)
                val gap = sizes.contentGap * 0.5f
                val labelWidth = glyphPaint.measureText(label)
                val blockWidth = iconSize + gap + labelWidth
                val iconCx = box.centerX - blockWidth / 2f + iconSize / 2f
                drawIcon(canvas, link.item.icon, iconCx, box.centerY, iconSize, BrowserTheme.dark.textPrimary)
                drawCentered(
                    canvas, glyphPaint, label,
                    iconCx + iconSize / 2f + gap + labelWidth / 2f,
                    box.centerY + glyphPaint.textSize * 0.36f
                )
            }
        }

        // The phone's round ✕: a full touch target in a fixed corner, on a tonal circle.
        val close = model.closeButton
        toolbarPaint.color = BrowserTheme.dark.sheetCardBackground
        canvas.drawCircle(close.centerX, close.centerY, minOf(close.width, close.height) / 2f, toolbarPaint)
        val closeSize = sizes.iconSmall.coerceAtMost(close.height * 0.45f)
        drawIcon(canvas, BrowserIcon.CLOSE, close.centerX, close.centerY, closeSize, BrowserTheme.dark.textSecondary)
    }

    /**
     * The address row. The sheet's own copy of the toolbar pill, because the toolbar auto-hides and
     * the menu is frequently what the user opens *instead* of recalling it — a menu that cannot say
     * which page it is acting on is a menu the user has to close to check.
     */
    private fun drawSheetAddress(canvas: Canvas, address: DrawerAddress) {
        val box = address.bounds
        val radius = box.height / 2f
        addressPaint.color = BrowserTheme.dark.addressPillBackground
        canvas.drawRoundRect(box.left, box.top, box.right, box.bottom, radius, radius, addressPaint)

        val badgeX = box.left + sizes.horizontalPadding
        val badgeColor = if (address.secure) BrowserTheme.dark.secureBadge else BrowserTheme.dark.insecureBadge
        val badgeSize = sizes.iconSmall * 0.9f
        drawIcon(
            canvas, if (address.secure) BrowserIcon.LOCK else BrowserIcon.WARNING,
            badgeX + badgeSize / 2f, box.centerY, badgeSize, badgeColor
        )

        titlePaint.color = BrowserTheme.dark.textPrimary
        titlePaint.textSize = sizes.iconSmall * 0.9f
        val textLeft = badgeX + sizes.iconSmall + sizes.contentGap
        canvas.drawText(
            fit(address.text, titlePaint, address.clear.left - textLeft - sizes.contentGap),
            textLeft, box.centerY + titlePaint.textSize * 0.34f, titlePaint
        )

        val clearSize = sizes.iconSmall.coerceAtMost(address.clear.height * 0.5f)
        drawIcon(
            canvas, BrowserIcon.CLOSE, address.clear.centerX, address.clear.centerY,
            clearSize, BrowserTheme.dark.textSecondary
        )

        // The one filled control on the row, because it is the one that commits: M3 filled button.
        toolbarPaint.color = BrowserTheme.dark.primary
        canvas.drawCircle(
            address.go.centerX, address.go.centerY,
            minOf(address.go.width, address.go.height) / 2f, toolbarPaint
        )
        val goSize = sizes.iconMedium.coerceAtMost(address.go.height * 0.55f)
        drawIcon(
            canvas, BrowserIcon.SEARCH, address.go.centerX, address.go.centerY,
            goSize, BrowserTheme.dark.onPrimary
        )
    }

    /** A "More" group heading: small, muted, left-aligned at the bottom of its band. */
    private fun drawSheetSectionLabel(canvas: Canvas, labelRes: Int, box: Box) {
        detailPaint.color = BrowserTheme.dark.textSecondary
        detailPaint.textSize = (sizes.iconSmall * 0.72f).coerceAtMost(box.height * 0.6f)
        detailPaint.textAlign = Paint.Align.LEFT
        canvas.drawText(
            fit(appContext.getString(labelRes), detailPaint, box.width),
            box.left + sizes.contentGap * 0.5f, box.bottom - box.height * 0.28f, detailPaint
        )
    }

    /** A row that only holds buttons (zoom): the card, a search glyph and its label. */
    private fun drawSheetPlate(canvas: Canvas, labelRes: Int, box: Box) {
        toolbarPaint.color = BrowserTheme.dark.sheetCardBackground
        canvas.drawRoundRect(box.left, box.top, box.right, box.bottom, sizes.cornerRadius, sizes.cornerRadius, toolbarPaint)
        val iconSize = sizes.iconMedium.coerceAtMost(box.height * 0.5f)
        val glyphX = box.left + sizes.horizontalPadding + iconSize / 2f
        drawIcon(canvas, BrowserIcon.SEARCH, glyphX, box.centerY, iconSize, BrowserTheme.dark.textSecondary)
        titlePaint.color = BrowserTheme.dark.textPrimary
        titlePaint.textSize = (sizes.iconSmall * 0.9f).coerceAtMost(box.height * 0.42f)
        canvas.drawText(
            appContext.getString(labelRes), glyphX + iconSize / 2f + sizes.horizontalPadding,
            box.centerY + titlePaint.textSize * 0.34f, titlePaint
        )
    }

    /** The zoom now, centred between the − and + buttons. */
    private fun drawSheetZoomReadout(canvas: Canvas, text: String, box: Box) {
        titlePaint.color = BrowserTheme.dark.textPrimary
        titlePaint.textSize = (sizes.iconSmall * 0.85f).coerceAtMost(box.height * 0.42f)
        val align = titlePaint.textAlign
        titlePaint.textAlign = Paint.Align.CENTER
        canvas.drawText(fit(text, titlePaint, box.width), box.centerX, box.centerY + titlePaint.textSize * 0.34f, titlePaint)
        titlePaint.textAlign = align
    }

    /** The zoom stepper's − / + buttons: a tonal rounded square with the glyph. */
    private fun drawSheetStepButton(canvas: Canvas, row: DrawerRow) {
        val box = row.bounds
        toolbarPaint.color = BrowserTheme.dark.tileBackground
        val radius = box.height * 0.3f
        canvas.drawRoundRect(box.left, box.top, box.right, box.bottom, radius, radius, toolbarPaint)
        val iconSize = sizes.iconMedium.coerceAtMost(box.height * 0.55f)
        drawIcon(
            canvas, row.item.icon, box.centerX, box.centerY, iconSize,
            if (row.item.enabled) BrowserTheme.dark.iconEnabled else BrowserTheme.dark.iconDisabled
        )
    }

    /** A "More" row: tonal rounded bar, icon on the left, the label beside it. */
    private fun drawSheetListRow(canvas: Canvas, row: DrawerRow) {
        val box = row.bounds
        val enabled = row.item.enabled
        val on = enabled && row.item.on
        val radius = sizes.dp(AutoUiSizes.SHEET_CORNER_RADIUS_DP).coerceAtMost(box.height * 0.3f)
        toolbarPaint.color = if (enabled) BrowserTheme.dark.tileBackground else BrowserTheme.dark.tileDisabledBackground
        canvas.drawRoundRect(box.left, box.top, box.right, box.bottom, radius, radius, toolbarPaint)

        val iconSize = sizes.dp(AutoUiSizes.SHEET_ICON_DP).coerceAtMost(box.height * 0.5f)
        val iconCx = box.left + sizes.contentGap + iconSize / 2f
        val iconColor = when {
            on -> BrowserTheme.dark.accent
            enabled -> BrowserTheme.dark.iconEnabled
            else -> BrowserTheme.dark.iconDisabled
        }
        drawIcon(canvas, row.item.icon, iconCx, box.centerY, iconSize, iconColor)

        detailPaint.color = if (enabled) BrowserTheme.dark.textPrimary else BrowserTheme.dark.iconDisabled
        detailPaint.textSize = (sizes.iconSmall * 0.8f).coerceAtMost(box.height * 0.36f)
        detailPaint.textAlign = Paint.Align.LEFT
        val textLeft = iconCx + iconSize / 2f + sizes.contentGap
        val valueText = row.item.value
        val valueWidth = if (valueText.isNotBlank()) detailPaint.measureText(valueText) + sizes.contentGap else 0f
        canvas.drawText(
            fit(row.item.label(appContext), detailPaint, box.right - sizes.contentGap - valueWidth - textLeft),
            textLeft, box.centerY + detailPaint.textSize * 0.35f, detailPaint
        )
        if (valueText.isNotBlank()) {
            detailPaint.color = BrowserTheme.dark.accent
            canvas.drawText(
                valueText, box.right - sizes.contentGap - detailPaint.measureText(valueText),
                box.centerY + detailPaint.textSize * 0.35f, detailPaint
            )
        }
    }

    /**
     * A split layout on the split page: a small picture of the screen with the two panes at that
     * layout's shares (the side pane on the side it is set to), the name below, and an accent
     * outline on the one in use.
     */
    private fun drawSheetLayoutCard(canvas: Canvas, row: DrawerRow) {
        val card = row.bounds
        val layout = row.layout ?: return
        val radius = sizes.dp(AutoUiSizes.SHEET_CORNER_RADIUS_DP).coerceAtMost(card.height * 0.2f)
        toolbarPaint.color = BrowserTheme.dark.tileBackground
        canvas.drawRoundRect(card.left, card.top, card.right, card.bottom, radius, radius, toolbarPaint)
        if (row.item.on) {
            val stroke = sizes.dp(3f)
            val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = stroke
                color = BrowserTheme.dark.accent
            }
            canvas.drawRoundRect(
                card.left + stroke / 2f, card.top + stroke / 2f, card.right - stroke / 2f, card.bottom - stroke / 2f,
                radius, radius, outline
            )
        }

        val pad = sizes.contentGap * 0.8f
        val labelSize = (sizes.iconSmall * 0.72f).coerceAtMost(card.height * 0.16f)
        val picBottom = card.bottom - pad - labelSize * 1.6f
        val picHeight = (picBottom - card.top - pad).coerceAtLeast(1f)
        val picWidth = (picHeight * 16f / 9f).coerceAtMost(card.width - pad * 2f)
        val picLeft = card.centerX - picWidth / 2f
        val picTop = card.top + pad
        val gap = sizes.dp(3f)
        val sideWidth = picWidth * layoutSideShare(layout)
        val mainWidth = picWidth - sideWidth - gap
        val sideRight = menuSideOnRight
        val mainLeft = if (sideRight) picLeft else picLeft + sideWidth + gap
        val sideLeft = if (sideRight) picLeft + mainWidth + gap else picLeft
        val corner = sizes.dp(4f)
        toolbarPaint.color = if (row.item.on) BrowserTheme.dark.accent else BrowserTheme.dark.iconEnabled
        canvas.drawRoundRect(mainLeft, picTop, mainLeft + mainWidth, picTop + picHeight, corner, corner, toolbarPaint)
        toolbarPaint.color = BrowserTheme.dark.iconDisabled
        canvas.drawRoundRect(sideLeft, picTop, sideLeft + sideWidth, picTop + picHeight, corner, corner, toolbarPaint)

        detailPaint.color = BrowserTheme.dark.textPrimary
        detailPaint.textSize = labelSize
        val align = detailPaint.textAlign
        detailPaint.textAlign = Paint.Align.CENTER
        canvas.drawText(fit(row.item.value, detailPaint, card.width - pad), card.centerX, card.bottom - pad, detailPaint)
        detailPaint.textAlign = align
    }

    /** Which side the second page is on, as the split page pictures it. */
    private val menuSideOnRight: Boolean get() = splitSideOnRight

    /** The side page's share of the width in each layout's picture; matches [CarMenuList]. */
    private fun layoutSideShare(layout: BrowserSplitLayout): Float = when (layout) {
        BrowserSplitLayout.SINGLE -> 0f
        BrowserSplitLayout.HALF -> 0.5f
        BrowserSplitLayout.FORTY_SIXTY -> 0.4f
        BrowserSplitLayout.SIXTY_FIVE_THIRTY_FIVE -> 0.35f
        BrowserSplitLayout.PORTRAIT_LANDSCAPE -> 0.3f
    }

    private fun drawSheetTile(canvas: Canvas, row: DrawerRow) {
        val tile = row.bounds
        val enabled = row.item.enabled
        // A switch tile that is on (the split, while one is up) is accent-filled, so the sheet
        // says whether tapping it will close or open.
        val on = enabled && row.item.on
        // The phone sheet's tile: a tonal rounded rectangle, a glyph over a label.
        val radius = sizes.dp(AutoUiSizes.SHEET_CORNER_RADIUS_DP).coerceAtMost(tile.height * 0.3f)
        toolbarPaint.color = when {
            on -> BrowserTheme.dark.accent
            enabled -> BrowserTheme.dark.tileBackground
            else -> BrowserTheme.dark.tileDisabledBackground
        }
        canvas.drawRoundRect(tile.left, tile.top, tile.right, tile.bottom, radius, radius, toolbarPaint)

        val glyphSize = sizes.dp(AutoUiSizes.SHEET_ICON_DP).coerceAtMost(tile.height * 0.36f)
        val labelSize = (sizes.iconSmall * 0.78f).coerceAtMost(tile.height * 0.24f)
        // Glyph and label centred as one block, with the phone's gap between them.
        val spacing = (sizes.contentGap * 0.75f).coerceAtMost(tile.height * 0.1f)
        val blockTop = tile.centerY - (glyphSize + spacing + labelSize) / 2f

        val tileIconColor = when {
            on -> BrowserTheme.dark.onPrimary
            enabled -> BrowserTheme.dark.iconEnabled
            else -> BrowserTheme.dark.iconDisabled
        }
        drawIcon(canvas, row.item.icon, tile.centerX, blockTop + glyphSize / 2f, glyphSize, tileIconColor)

        detailPaint.color = when {
            on -> BrowserTheme.dark.onPrimary
            enabled -> BrowserTheme.dark.textPrimary
            else -> BrowserTheme.dark.iconDisabled
        }
        detailPaint.textSize = labelSize
        drawCentered(
            canvas, detailPaint,
            fit(row.item.label(appContext), detailPaint, tile.width - sizes.contentGap),
            tile.centerX, blockTop + glyphSize + spacing + labelSize * 0.85f
        )

        if (row.item.value.isNotBlank()) {
            detailPaint.color = BrowserTheme.dark.accent
            detailPaint.textSize = sizes.iconSmall * 0.75f
            val width = detailPaint.measureText(row.item.value)
            canvas.drawText(
                row.item.value, tile.right - sizes.contentGap - width,
                tile.top + sizes.contentGap + detailPaint.textSize * 0.8f, detailPaint
            )
        }
    }

    /**
     * The accent-filled primary button, drawn the way the phone draws "Send to car": an icon, a
     * bold title over a subtitle, and a chevron. On the car it carries the open-tab count as well.
     */
    private fun drawSheetPrimary(canvas: Canvas, row: DrawerRow) {
        val box = row.bounds
        val radius = sizes.dp(AutoUiSizes.SHEET_CORNER_RADIUS_DP).coerceAtMost(box.height * 0.36f)
        toolbarPaint.color = BrowserTheme.dark.accent
        canvas.drawRoundRect(box.left, box.top, box.right, box.bottom, radius, radius, toolbarPaint)

        val pad = sizes.horizontalPadding * 1.5f
        val primaryIconSize = (sizes.dp(AutoUiSizes.SHEET_ICON_DP) * 1.1f).coerceAtMost(box.height * 0.5f)
        val iconCx = box.left + pad + primaryIconSize / 2f
        drawIcon(canvas, row.item.icon, iconCx, box.centerY, primaryIconSize, BrowserTheme.dark.onPrimary)

        // Chevron, then the optional count badge, from the trailing edge in.
        val chevronSize = sizes.iconLarge.coerceAtMost(box.height * 0.6f)
        val chevronCx = box.right - pad * 0.8f - chevronSize * 0.25f
        drawIcon(canvas, BrowserIcon.CHEVRON_RIGHT, chevronCx, box.centerY, chevronSize, BrowserTheme.dark.onPrimary)
        var textRight = chevronCx - chevronSize * 0.5f - sizes.contentGap
        if (row.item.value.isNotBlank()) {
            val badge = (sizes.iconMedium * 1.3f).coerceAtMost(box.height * 0.55f)
            val badgeCx = textRight - badge / 2f
            toolbarPaint.color = BrowserTheme.dark.onPrimary
            canvas.drawCircle(badgeCx, box.centerY, badge / 2f, toolbarPaint)
            glyphPaint.color = BrowserTheme.dark.accent
            glyphPaint.textSize = badge * 0.5f
            canvas.drawText(row.item.value, badgeCx, box.centerY + glyphPaint.textSize * 0.36f, glyphPaint)
            textRight = badgeCx - badge / 2f - sizes.contentGap
        }

        val textLeft = iconCx + sizes.dp(AutoUiSizes.SHEET_ICON_DP) * 0.55f + pad * 0.7f
        val limit = textRight - textLeft
        val titleSize = sizes.iconMedium.coerceAtMost(box.height * 0.32f)
        val subSize = (sizes.iconSmall * 0.78f).coerceAtMost(box.height * 0.22f)
        val detail = row.item.detail(appContext)
        val hasDetail = detail.isNotBlank()
        val blockTop = box.centerY - (titleSize + if (hasDetail) subSize * 1.35f else 0f) / 2f
        titlePaint.color = BrowserTheme.dark.onPrimary
        titlePaint.textSize = titleSize
        canvas.drawText(
            fit(row.item.label(appContext), titlePaint, limit), textLeft,
            blockTop + titleSize * 0.9f, titlePaint
        )
        if (hasDetail) {
            detailPaint.color = BrowserTheme.dark.onPrimary
            detailPaint.textSize = subSize
            canvas.drawText(
                fit(detail, detailPaint, limit), textLeft,
                blockTop + titleSize + subSize * 1.2f, detailPaint
            )
        }
    }

    /**
     * The desktop-site switch. A switch rather than a tile because what it reports is a state: a
     * tile labelled "Desktop site / On" asks the user to read two things and work out which one is
     * the button, while a track and a knob say the same thing in one glance.
     */
    private fun drawSheetToggle(canvas: Canvas, row: DrawerRow) {
        val box = row.bounds
        toolbarPaint.color = BrowserTheme.dark.sheetCardBackground
        canvas.drawRoundRect(
            box.left, box.top, box.right, box.bottom,
            sizes.cornerRadius, sizes.cornerRadius, toolbarPaint
        )

        val toggleIconSize = sizes.iconMedium.coerceAtMost(box.height * 0.5f)
        val glyphX = box.left + sizes.horizontalPadding + toggleIconSize / 2f
        drawIcon(canvas, row.item.icon, glyphX, box.centerY, toggleIconSize, BrowserTheme.dark.textSecondary)

        val trackWidth = (sizes.touchTarget * 0.9f).coerceAtMost(box.width * 0.25f)
        val trackHeight = (trackWidth * 0.52f).coerceAtMost(box.height * 0.6f)
        val trackRight = box.right - sizes.horizontalPadding
        val trackLeft = trackRight - trackWidth

        titlePaint.color = BrowserTheme.dark.textPrimary
        titlePaint.textSize = (sizes.iconSmall * 0.9f).coerceAtMost(box.height * 0.42f)
        val labelLeft = glyphX + toggleIconSize / 2f + sizes.horizontalPadding
        canvas.drawText(
            fit(row.item.label(appContext), titlePaint, trackLeft - labelLeft - sizes.contentGap),
            labelLeft, box.centerY + titlePaint.textSize * 0.34f, titlePaint
        )

        toolbarPaint.color =
            if (row.item.on) BrowserTheme.dark.toggleTrackOn else BrowserTheme.dark.toggleTrackOff
        canvas.drawRoundRect(
            trackLeft, box.centerY - trackHeight / 2f, trackRight, box.centerY + trackHeight / 2f,
            trackHeight / 2f, trackHeight / 2f, toolbarPaint
        )
        toolbarPaint.color =
            if (row.item.on) BrowserTheme.dark.toggleKnob else BrowserTheme.dark.toggleKnobOff
        val knobX = if (row.item.on) trackRight - trackHeight / 2f else trackLeft + trackHeight / 2f
        canvas.drawCircle(knobX, box.centerY, trackHeight * 0.4f, toolbarPaint)
    }

    private fun drawTabSwitcher(canvas: Canvas) {
        toolbarPaint.color = BrowserTheme.dark.drawerBackground
        canvas.drawRect(
            viewport.left.toFloat(), viewport.top.toFloat(),
            (viewport.left + viewport.width).toFloat(), (viewport.top + viewport.height).toFloat(),
            toolbarPaint
        )
        titlePaint.color = BrowserTheme.dark.textPrimary
        titlePaint.textSize = sizes.iconMedium * 0.8f
        canvas.drawText(
            "Tabs  ${tabs.count}/${BrowserTabsState.MAX_TABS}",
            viewport.left + sizes.horizontalPadding * 2f,
            viewport.top + sizes.toolbarHeight(viewport.height) * 0.65f, titlePaint
        )
        // Same fixed close target the drawer uses, drawn in the header's opposite corner from the
        // title so the two overlays share one dismissal pattern instead of each inventing its own.
        val close = tabSwitcherCloseButton()
        toolbarPaint.color = BrowserTheme.dark.drawerBackground
        canvas.drawRoundRect(
            close.left, close.top, close.right, close.bottom,
            sizes.cornerRadius, sizes.cornerRadius, toolbarPaint
        )
        val tabCloseSize = minOf(close.width, close.height) * 0.5f
        drawIcon(canvas, BrowserIcon.CLOSE, close.centerX, close.centerY, tabCloseSize, BrowserTheme.dark.iconEnabled)

        tabCardLayout().forEach { (tab, box, closeBox) ->
            val isActive = tab != null && tab.id == tabs.activeId
            // A solid accent fill used to sit behind the thumbnail itself, so the active card's own
            // preview was tinted and harder to read than every other card's. The active state is now
            // a border around the card instead, which marks it without touching what is drawn inside.
            toolbarPaint.color = BrowserTheme.dark.addressPillBackground
            canvas.drawRoundRect(
                RectF(box.left, box.top, box.right, box.bottom),
                sizes.cornerRadius, sizes.cornerRadius, toolbarPaint
            )
            if (isActive) {
                val strokeWidth = sizes.dp(2f)
                toolbarPaint.style = Paint.Style.STROKE
                toolbarPaint.strokeWidth = strokeWidth
                toolbarPaint.color = BrowserTheme.dark.accent
                canvas.drawRoundRect(
                    RectF(
                        box.left + strokeWidth / 2f, box.top + strokeWidth / 2f,
                        box.right - strokeWidth / 2f, box.bottom - strokeWidth / 2f
                    ),
                    sizes.cornerRadius, sizes.cornerRadius, toolbarPaint
                )
                toolbarPaint.style = Paint.Style.FILL
            }
            if (tab == null) {
                drawIcon(canvas, BrowserIcon.ADD, box.centerX, box.centerY, sizes.iconLarge, BrowserTheme.dark.textPrimary)
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
                toolbarPaint.color = BrowserTheme.dark.background
                canvas.drawRect(thumbRect, toolbarPaint)
            }
            titlePaint.color = BrowserTheme.dark.textPrimary
            titlePaint.textSize = sizes.iconSmall * 0.85f
            val label = TextUtils.ellipsize(
                tab.displayTitle, TextPaint(titlePaint),
                box.width - sizes.horizontalPadding * 2f, TextUtils.TruncateAt.END
            ).toString()
            canvas.drawText(label, box.left + sizes.horizontalPadding, thumbBottom + sizes.iconSmall, titlePaint)
            detailPaint.color = BrowserTheme.dark.textSecondary
            detailPaint.textSize = sizes.iconSmall * 0.7f
            canvas.drawText(
                tab.host, box.left + sizes.horizontalPadding,
                thumbBottom + sizes.iconSmall * 2f, detailPaint
            )
            drawIcon(
                canvas, BrowserIcon.CLOSE, closeBox.centerX, closeBox.centerY,
                sizes.iconSmall, BrowserTheme.dark.textSecondary
            )
        }
    }

    /** Shown in place of the page when the main frame fails to load; tapping content area retries. */
    private fun drawErrorOverlay(canvas: Canvas) {
        val w = mainViewport.width.toFloat()
        val h = mainViewport.height.toFloat()
        canvas.drawColor(BrowserTheme.dark.errorBackground)
        titlePaint.color = BrowserTheme.dark.errorAccent
        titlePaint.textSize = sizes.iconMedium
        val message = loadError.orEmpty()
        canvas.drawText(message, (w - titlePaint.measureText(message)) / 2f, h / 2f - sizes.contentGap, titlePaint)
        detailPaint.color = BrowserTheme.dark.textSecondary
        detailPaint.textSize = sizes.iconSmall * 0.9f
        val hint = appContext.getString(R.string.car_tap_to_retry)
        canvas.drawText(hint, (w - detailPaint.measureText(hint)) / 2f, h / 2f + sizes.iconMedium, detailPaint)
    }

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
            requestFullRate()
        } else {
            mainHandler.post {
                block()
                requestFullRate()
            }
        }
    }
}
