package dev.autobridge.browser

import android.annotation.SuppressLint
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
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
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
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.*
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import dev.autobridge.R
import dev.autobridge.core.model.Feature
import dev.autobridge.core.policy.FeaturePolicy
import dev.autobridge.entertainment.BrowserLauncher
import dev.autobridge.entertainment.ContentAddress
import dev.autobridge.entertainment.WebBookmarkStore
import dev.autobridge.entertainment.WebHistoryStore
import dev.autobridge.i18n.AppLocale
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

    /**
     * Reads and pauses this page's `<audio>`/`<video>` elements, for the resume point "Send to car"
     * carries over; see [sendWithResumePoint]. The car surface keeps its own bridge
     * ([CarWebRenderer]) because each presentation has its own WebView. Null-safe on [web] so a read
     * that races the activity's setup asks nothing rather than throwing.
     */
    private val webAudio by lazy {
        dev.autobridge.audio.WebAudioBridge { if (::web.isInitialized) web else null }
    }

    /** Per-site location prompt plus the runtime location permission; see [BrowserGeolocation]. */
    private val geolocation by lazy { BrowserGeolocation.Prompter(this) }
    private lateinit var address: EditText
    private lateinit var toolbar: LinearLayout
    private lateinit var chromeBar: FrameLayout
    private lateinit var handle: TextView
    private lateinit var menuButton: ImageView
    private lateinit var toolbarMenuButton: ImageButton
    private lateinit var toolbarHomeButton: ImageButton
    private lateinit var backButton: ImageButton
    private lateinit var progress: ProgressBar
    private lateinit var blocked: TextView
    private lateinit var loadError: LinearLayout
    private lateinit var errorMessage: TextView
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var startPage: View
    private var showingStartPage = false
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
        // Three re-bases, for three unrelated reasons: the density one pins the page's CSS width,
        // the appearance one puts the window in the night mode the user picked so the WebView
        // built from this context reports the matching prefers-color-scheme, and the locale one
        // applies the Settings > Language choice on API 29-32. Order does not matter: each
        // overrides a different Configuration field.
        super.attachBaseContext(
            AppLocale.rebase(BrowserAppearanceStore.rebase(CarDisplayScaling.rebase(newBase)))
        )
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
        // Both before anything is built: the chrome below reads BrowserTheme as it constructs its
        // views and drawables, and the window theme has to be set before the first view exists.
        BrowserAppearanceStore.syncChrome(this)
        setTheme(BrowserAppearanceStore.activityTheme(this))
        super.onCreate(savedInstanceState)
        releaseBack = dev.autobridge.ui.SystemBack.register(this) { goBack() }
        root = FrameLayout(this)
        applyStartPageBackground()

        content = FrameLayout(this)
        web = createWebView()
        swipeRefresh = SwipeRefreshLayout(this).apply {
            setColorSchemeColors(BrowserTheme.accent)
            setProgressBackgroundColorSchemeColor(BrowserTheme.toolbarBackground)
            setOnRefreshListener { web.reload() }
            addView(web, FrameLayout.LayoutParams(-1, -1))
        }
        blocked = TextView(this).apply {
            gravity = Gravity.CENTER; setTextColor(BrowserTheme.textPrimary)
        }
        errorMessage = TextView(this).apply {
            gravity = Gravity.CENTER; setTextColor(BrowserTheme.errorAccent)
        }
        loadError = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(BrowserTheme.errorBackground)
            visibility = View.GONE
            addView(
                errorMessage,
                LinearLayout.LayoutParams(-2, -2).apply {
                    bottomMargin = sizes.dpInt(AutoUiSizes.CONTENT_GAP_DP)
                }
            )
            addView(
                Button(this@BrowserActivity).apply {
                    text = getString(R.string.action_retry)
                    setTextColor(BrowserTheme.iconEnabled)
                    setOnClickListener { loadError.visibility = View.GONE; web.reload() }
                }
            )
        }
        content.addView(swipeRefresh, FrameLayout.LayoutParams(-1, -1))
        content.addView(blocked, FrameLayout.LayoutParams(-1, -1))
        content.addView(loadError, FrameLayout.LayoutParams(-1, -1))
        startPage = BrowserStartPage.build(this, sizes) { navigate(it) }.apply { visibility = View.GONE }
        content.addView(startPage, FrameLayout.LayoutParams(-1, -1))

        menuButton = ImageView(this).apply {
            setImageResource(BrowserControlsStore.floatingButtonAction(this@BrowserActivity).icon.resId)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            val inset = sizes.dpInt(AutoUiSizes.CONTENT_GAP_DP)
            setPadding(inset, inset, inset, inset)
            setColorFilter(BrowserTheme.iconEnabled)
            contentDescription = getString(R.string.browser_fab_description)
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
        // A saved instance (rotation, process restore) keeps whatever the user had on screen; a
        // cold start uses the stored fullscreen preference, which defaults to on for a fresh
        // install and remembers a later toggle-off. See [BrowserControlsStore.startFullscreen].
        setFullscreen(
            savedInstanceState?.getBoolean("fullscreen")
                ?: BrowserControlsStore.startFullscreen(this)
        )
        ParkingStateStore.addListener(parkingListener)
        // Settings > Browser launches us with this extra so the settings sheet opens straight away.
        if (savedInstanceState == null) maybeOpenSettingsSheet(intent)
    }

    /** Opens the browser settings sheet when launched with [EXTRA_OPEN_SETTINGS]. */
    private fun maybeOpenSettingsSheet(intent: Intent) {
        if (intent.getBooleanExtra(EXTRA_OPEN_SETTINGS, false)) {
            intent.removeExtra(EXTRA_OPEN_SETTINGS)
            root.post { showBrowserSettings() }
        }
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
        fun control(icon: BrowserIcon, description: String, action: () -> Unit): ImageButton = ImageButton(this).apply {
            setImageResource(icon.resId)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            contentDescription = description
            setColorFilter(BrowserTheme.iconEnabled)
            background = null
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
        backButton = control(BrowserIcon.BACK, "Back") { if (web.canGoBack()) web.goBack() }
        // Forward and Reload left the toolbar with the phase-4 redesign: the mockup's bar is Back,
        // address, menu and nothing else. Both still exist, one level in, under All actions ▸
        // Navigate — see [MoreActionsSheet]'s `more_section_navigate` rows.
        address = buildAddressField()
        toolbar.addView(
            address,
            // Compact pill: shorter than a full touch target and vertically centred in the toolbar,
            // so it reads as a slim address chip rather than a text box filling the bar's height.
            LinearLayout.LayoutParams(0, sizes.dpInt(ADDRESS_PILL_HEIGHT_DP), 1f).apply {
                marginStart = sizes.dpInt(AutoUiSizes.CONTENT_GAP_DP)
                marginEnd = sizes.dpInt(AutoUiSizes.CONTENT_GAP_DP)
            }
        )
        // Fullscreen moved into the hamburger menu next to the Desktop-mode switch (see
        // BrowserMenuSheet.fullscreenToggle) — it no longer has its own toolbar button.
        // Same ☰ as the floating button. Both live on screen only when the floating button has
        // been rebound away from MENU; while it is still the default, the toolbar button would be
        // a second, identical-looking way to do the one thing the floating one already does.
        toolbarMenuButton = control(BrowserIcon.MENU, "Browser menu") { showMenu() }
        // Occupies the same slot as the ☰ button above, with the opposite visibility (see
        // applyFloatingButtonPreference). While the floating button still is the fixed way to open
        // the menu, that slot would otherwise sit empty, so it carries the one direct, no-menu way
        // back to the app's own Home instead of leaving that reachable only through the drawer's
        // footer "Exit" link — matching the always-visible Home action the car surface keeps on its
        // action strip for the same reason.
        toolbarHomeButton = control(BrowserIcon.APP_HOME, "Home") { runMenuAction(DrawerAction.APP_HOME) }

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

    /** The address text the field restores to when editing is cancelled, captured on focus. */
    private var addressBeforeEdit: String = ""

    /**
     * The compact omnibox that stays in the top bar at all times.
     *
     * Idle it shows only the hostname (via [displayUrl]) on a slim dark rounded pill, deliberately
     * shorter than the toolbar buttons so it reads as a chip rather than a text box. Tapping it
     * turns the same field into a full editable omnibox in place — the real URL, selected, with the
     * keyboard up and a clear button — without opening a dialog, sheet or separate screen. Entering
     * loads through the shared [BrowserInputResolver] (URL, bare host, or search); Back cancels and
     * restores the previous address without touching the page.
     */
    private fun buildAddressField(): EditText = EditText(this).apply {
        setHint(HINT_IDLE)
        setTextColor(BrowserTheme.textPrimary)
        setHintTextColor(BrowserTheme.textSecondary)
        textSize = iconSp(AutoUiSizes.ICON_SMALL_DP * 0.85f)
        setSingleLine()
        setSelectAllOnFocus(true)
        // A slim pill, not a boxed text field: no multi-line growth, caret centred vertically.
        gravity = Gravity.CENTER_VERTICAL
        inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
        imeOptions = EditorInfo.IME_ACTION_GO
        setPadding(
            sizes.dpInt(AutoUiSizes.HORIZONTAL_PADDING_DP),
            0,
            sizes.dpInt(AutoUiSizes.HORIZONTAL_PADDING_DP),
            0
        )
        background = GradientDrawable().apply {
            setColor(BrowserTheme.addressPillBackground)
            // Fully rounded ends keep the compact chip look at the shorter pill height.
            cornerRadius = sizes.dp(ADDRESS_PILL_HEIGHT_DP) / 2f
        }
        setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                // Remember what to fall back to, then switch inline into edit mode: full URL,
                // search-or-address hint, all text selected, clear button, keyboard up. On the
                // start page there is no URL to edit yet, so this starts blank rather than at
                // whatever page was showing before Home was opened.
                addressBeforeEdit = if (showingStartPage) "" else displayUrl(web.url.orEmpty())
                hint = HINT_EDITING
                setText(if (showingStartPage) "" else web.url.orEmpty())
                setSelection(0, text.length)
                updateAddressDecorations()
                (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
                    .showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
            } else {
                // Leaving edit mode collapses back to the compact hostname with no clear button.
                setHint(HINT_IDLE)
                setText(if (showingStartPage) "" else displayUrl(web.url.orEmpty()))
                updateAddressDecorations()
            }
        }
        // Keep the clear (X) button in step with the text while editing.
        addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun afterTextChanged(s: android.text.Editable?) {
                if (hasFocus()) updateAddressDecorations()
            }
        })
        // Tap on the trailing X clears the field; the rest of the field behaves normally.
        setOnTouchListener { _, event ->
            if (event.action == android.view.MotionEvent.ACTION_UP && isClearButtonHit(event)) {
                setText("")
                updateAddressDecorations()
                performClick()
                true
            } else false
        }
        setOnEditorActionListener { _, action, event ->
            if (action == EditorInfo.IME_ACTION_GO ||
                (event?.keyCode == android.view.KeyEvent.KEYCODE_ENTER && event.action == android.view.KeyEvent.ACTION_UP)
            ) {
                navigate(text.toString())
                collapseAddressEditing(hideKeyboard = true)
                true
            } else false
        }
        // Android Back while editing cancels: restore the previous address and keep the page as it
        // is. Handled on the field's own key events so it never falls through to WebView back/exit.
        setOnKeyListener { _, keyCode, event ->
            if (keyCode == android.view.KeyEvent.KEYCODE_BACK &&
                event.action == android.view.KeyEvent.ACTION_UP && hasFocus()
            ) {
                cancelAddressEditing()
                true
            } else false
        }
    }

    /** The horizontal span, from the field's right edge, treated as the clear-button hit area. */
    private fun EditText.isClearButtonHit(event: android.view.MotionEvent): Boolean {
        val drawable = compoundDrawablesRelative[2] ?: return false
        val hit = drawable.bounds.width() + paddingEnd + sizes.dpInt(AutoUiSizes.CONTENT_GAP_DP)
        return event.x >= width - hit
    }

    /**
     * Keeps the pill's two glyphs in step with its state: a leading lock while idle on an https
     * page (never while editing, where the real URL in the field already answers that question),
     * and a trailing clear (X) only while editing with non-empty text.
     */
    private fun updateAddressDecorations() {
        val editing = address.hasFocus()
        val secure = !editing && !showingStartPage && web.url.orEmpty().startsWith("https://", ignoreCase = true)
        val hasText = editing && address.text.isNotEmpty()
        address.setCompoundDrawablesRelative(
            if (secure) lockGlyphDrawable else null,
            null,
            if (hasText) clearGlyphDrawable else null,
            null
        )
    }

    /** A [BrowserIcon] sized and tinted for use as a compound drawable on the address field. */
    private fun buildIconDrawable(icon: BrowserIcon, color: Int): android.graphics.drawable.Drawable {
        val size = sizes.dpInt(AutoUiSizes.ICON_SMALL_DP)
        return ContextCompat.getDrawable(this, icon.resId)!!.mutate().apply {
            setTint(color)
            setBounds(0, 0, size, size)
        }
    }

    /** The clear-text icon used as the inline clear button, built once and tinted to the chrome icons. */
    private val clearGlyphDrawable by lazy { buildIconDrawable(BrowserIcon.CLOSE, BrowserTheme.iconEnabled) }

    /** The lock icon shown at the start of the pill for an https page; see [updateAddressDecorations]. */
    private val lockGlyphDrawable by lazy { buildIconDrawable(BrowserIcon.LOCK, BrowserTheme.secureBadge) }

    /** Enter/Go path: drop focus and (optionally) the keyboard, collapsing back to compact display. */
    private fun collapseAddressEditing(hideKeyboard: Boolean) {
        address.clearFocus()
        if (hideKeyboard) {
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
                .hideSoftInputFromWindow(address.windowToken, 0)
        }
    }

    /**
     * Cancels an in-progress edit: restores the address shown before focus, hides the keyboard and
     * leaves the page untouched. The focus-change listener resets the text to the live page too, so
     * [addressBeforeEdit] only matters while the page URL is briefly out of step mid-edit.
     */
    private fun cancelAddressEditing() {
        address.setText(addressBeforeEdit)
        collapseAddressEditing(hideKeyboard = true)
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
            /**
             * Drops advertising and tracking subresources when the user has turned blocking on.
             * Returns null — "fetch it as usual" — for everything else, which is the whole of the
             * web whenever the preference is off.
             */
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest,
            ): WebResourceResponse? = BrowserAdBlock.intercept(this@BrowserActivity, request)

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val target = request.url.toString()
                if (allowed() && BrowserDefaults.isExternalSignInHost(target)) {
                    promptExternalSignIn(target)
                    return true
                }
                if (allowed() && handOffToMaps(target, view.url)) return true
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
                swipeRefresh.isRefreshing = false
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
                swipeRefresh.isRefreshing = false
                errorMessage.text = pageLoadErrorMessage(error.errorCode)
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
        if (input == BrowserStartupStore.START_PAGE) {
            showStartPage()
            return
        }
        if (input.isNotBlank()) {
            // One shared resolver for the whole browser: a valid http/https URL (or a bare host
            // like youtube.com, normalised to https://youtube.com) opens as-is; free text runs on
            // the user's current/default search engine. See BrowserInputResolver.
            val url = BrowserInputResolver.resolveBrowserInput(input, SearchEngineStore.engine(this))
            hideStartPage()
            // Collapse straight to the compact hostname; focus is cleared by the caller on Enter.
            address.setText(if (address.hasFocus()) url else displayUrl(url))
            web.loadUrl(url)
        }
    }

    /** Shows the native start page in place of the page; see [BrowserStartupStore.START_PAGE]. */
    private fun showStartPage() {
        showingStartPage = true
        startPage.visibility = View.VISIBLE
        updateNavigation()
    }

    /** Leaves the start page. A no-op once it is already hidden. */
    private fun hideStartPage() {
        if (!showingStartPage) return
        showingStartPage = false
        startPage.visibility = View.GONE
    }

    private fun updateNavigation() {
        backButton.isEnabled = web.canGoBack()
        backButton.alpha = if (backButton.isEnabled) 1f else DISABLED_NAV_ALPHA
        if (!address.hasFocus()) {
            if (showingStartPage) {
                address.setText("")
                address.setCompoundDrawablesRelative(null, null, null, null)
            } else {
                address.setText(displayUrl(web.url.orEmpty()))
                updateAddressDecorations()
            }
        }
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

    /**
     * Picks which of the three reasons a failed main-frame load gets shown. Order matters: a dead
     * radio answers every lookup with [WebViewClient.ERROR_HOST_LOOKUP] on some devices, so
     * connectivity is checked first and only a real DNS failure with a network present is reported
     * as "site not found".
     */
    private fun pageLoadErrorMessage(errorCode: Int): String = when {
        !hasInternetConnection() -> getString(R.string.browser_error_no_internet)
        errorCode == WebViewClient.ERROR_HOST_LOOKUP -> getString(R.string.browser_error_host_not_found)
        else -> getString(R.string.browser_error_blocked)
    }

    private fun hasInternetConnection(): Boolean {
        val manager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = manager.activeNetwork ?: return false
        val capabilities = manager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun setFullscreen(enabled: Boolean) {
        fullscreen = enabled
        // Remember the choice so a later launch starts the way the user left it; the default only
        // applies until the first toggle writes a value. See [BrowserControlsStore.startFullscreen].
        BrowserControlsStore.setStartFullscreen(this, enabled)
        applyFloatingButtonPreference()
        chromeBar.visibility = if (enabled) View.GONE else View.VISIBLE
        handle.visibility = if (enabled) View.VISIBLE else View.GONE
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
        menuButton.setImageResource(action.icon.resId)
        // The toolbar's own ☰ only appears once the floating button stops being the fixed way to
        // reach the menu; otherwise the two sat side by side doing the same thing. While the
        // floating button keeps that job, its slot carries Home instead rather than sitting empty.
        val menuHandledByFab = action == FloatingButtonAction.MENU
        toolbarMenuButton.visibility = if (menuHandledByFab) View.GONE else View.VISIBLE
        toolbarHomeButton.visibility = if (menuHandledByFab) View.VISIBLE else View.GONE
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
        pageTitle = if (showingStartPage) {
            getString(R.string.browser_start_page_label)
        } else {
            web.title?.takeIf { it.isNotBlank() } ?: displayUrl(web.url.orEmpty())
        },
        url = if (showingStartPage) "" else web.url.orEmpty(),
        isDesktop = BrowserUserAgentStore.mode(this) == BrowserUserAgentMode.DESKTOP,
        fullscreen = fullscreen,
        adBlockEnabled = BrowserAdBlock.enabled(this),
        canGoBack = web.canGoBack(),
        canGoForward = web.canGoForward(),
        version = "v${dev.autobridge.BuildConfig.VERSION_NAME}",
        // On a car display (a Duo Screen pane) this is a car browser and gets the car menu, less
        // what a single-page window cannot do; see [CAR_DISPLAY_UNSUPPORTED].
        surface = if (onCarDisplay) MenuSurface.CAR else MenuSurface.PHONE,
        unsupported = if (onCarDisplay) CAR_DISPLAY_UNSUPPORTED else emptySet(),
    )

    /**
     * The car-menu entries this activity cannot offer when it runs on a car display. It holds one
     * WebView (no tabs, no split, so nothing to swap or hand to Maps from a split), its toolbar
     * does not auto-hide (nothing to pin), and the media, agent and mirror entries open Android
     * Auto screens that a Duo Screen pane cannot show. Exit is left to Duo Screen's own controls:
     * finishing here would only leave an empty pane.
     */
    private val CAR_DISPLAY_UNSUPPORTED = setOf(
        DrawerAction.TABS, DrawerAction.NEW_TAB, DrawerAction.SPLIT_LAYOUT,
        DrawerAction.SIDE_SHOW_PAGE, DrawerAction.SWAP_SPLIT_SIDES, DrawerAction.SPLIT_CHOOSE, DrawerAction.NAVIGATE_MAPS,
        DrawerAction.MEDIA_CENTER, DrawerAction.NOW_PLAYING, DrawerAction.MEDIA_LIBRARY,
        DrawerAction.AGENT, DrawerAction.MIRROR_PHONE, DrawerAction.PIN_TOOLBAR,
        DrawerAction.APP_HOME,
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
        if (onCarDisplay) return showCarMenu()
        BrowserMenuSheet(
            activity = this,
            sizes = sizes,
            state = { menuState() },
            onAction = { runMenuAction(it) },
            onSendToCar = { openSendToCar() },
            onMore = { openMoreActions() },
        ).show()
    }

    /**
     * The car browser menu, when this activity is on a car display: the same entries, order and
     * names as the Android Auto browser and Bridge Web ([CarMenuList]), instead of the phone's
     * sheet with its Send to car / Get from car / support links that make no sense on the car.
     */
    private fun showCarMenu() {
        val shell = BrowserSheetShell(this, sizes)
        val column = shell.contentColumn()
        val style = CarMenuList.Style(
            text = BrowserTheme.textPrimary,
            textSecondary = BrowserTheme.textSecondary,
            rowFill = BrowserTheme.sheetCardBackground,
            accent = BrowserTheme.accent,
            onAccent = BrowserTheme.onPrimary,
        )
        // A ✕ and drag-down to close: the grid can fill a car display, leaving no outside to tap.
        val header = CarMenuList.header(this, getString(R.string.car_browser_title), style) { shell.dismiss() }
        column.addView(header)
        CarMenuList.dragToClose(header, column) { shell.dismiss() }
        CarMenuList.build(
            context = this,
            state = menuState(),
            style = style,
        ) { action ->
            shell.dismiss()
            runMenuAction(action)
        }.forEach(column::addView)
        shell.show(column)
    }

    /** "About" on a car display; the phone's menu has no such entry. */
    private fun showAbout() {
        android.app.AlertDialog.Builder(this)
            .setTitle(R.string.drawer_about)
            .setMessage(CarBrowserAbout.lines(this).joinToString("\n") { (label, value) -> "$label: $value" })
            .setPositiveButton(android.R.string.ok, null)
            .show()
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
            isConnected = { dev.autobridge.bridge.AutoBridgeSessionManager.current.connected },
            currentUrl = { currentPageUrl() },
            currentTitle = { web.title?.takeIf { it.isNotBlank() } },
            engine = { SearchEngineStore.engine(this) },
            onEngineChange = { SearchEngineStore.setEngine(this, it) },
            onSend = { input, selectedEngine ->
                sendToCar(BrowserInputResolver.resolveBrowserInput(input, selectedEngine))
            },
            onSendUrl = { url -> sendToCar(url) },
            queue = playQueueAccess(),
        ).show()
    }

    /**
     * The sheet's window onto [BrowserPlayQueue].
     *
     * Resolving lives here rather than in the sheet so "a URL, a bare host, or a search" has the one
     * answer it has everywhere else ([BrowserInputResolver]), and so the only place that talks to the
     * store is the activity. A queued page carries no resume position on purpose: sending a page is
     * "carry on from here", queueing one is "play this next", which starts at the beginning.
     */
    private fun playQueueAccess() = object : SendToCarSheet.QueueAccess {
        override fun items(): List<BrowserPlayQueue.Item> = BrowserPlayQueue.items(this@BrowserActivity)

        override fun add(input: String?, engine: SearchEngine): Int? {
            val url = if (input == null) {
                currentPageUrl()
            } else {
                BrowserInputResolver.resolveBrowserInput(input, engine)
            }
            if (url == null) {
                toast(getString(R.string.browser_nothing_to_queue))
                return null
            }
            val title = if (input == null) web.title.orEmpty() else ""
            val size = BrowserPlayQueue.add(this@BrowserActivity, url, title)
            toast(
            if (size == null) getString(R.string.browser_already_queued)
            // A quantity, so a plural rather than a format string: English needs "1 item" and
            // "2 items", and a language with more grammatical numbers needs its own forms.
            else resources.getQuantityString(R.plurals.browser_queued, size, size)
        )
            return size
        }

        override fun remove(url: String) = BrowserPlayQueue.remove(this@BrowserActivity, url)

        override fun clear() {
            BrowserPlayQueue.clear(this@BrowserActivity)
            toast(getString(R.string.browser_queue_cleared))
        }
    }

    /** The page this activity is showing, as the one validated HTTPS form the car is given. */
    private fun currentPageUrl(): String? = web.url?.let(ContentAddress::https)

    /**
     * Hands one resolved HTTPS URL to the browser running on the car surface, opening a fresh car
     * browser if none is on top. The single choke point every "send" path funnels through, so the
     * not-connected and empty-input cases are handled once.
     *
     * Sending the page the phone is *already playing* takes one detour: the position lives in this
     * WebView's `<video>` element and nothing but the URL crosses to the car, so it is read out and
     * written into the URL first ([BrowserResumePoint]). That read is a round trip into the page, so
     * the send finishes a frame or two after this returns — the result below is about whether a car
     * browser was reachable, which is all the sheet needs in order to close.
     *
     * @return true when the URL was accepted by a car browser, so the caller (the sheet) can close
     *   itself only on success and leave itself open with the input intact otherwise.
     */
    private fun sendToCar(url: String?): Boolean {
        if (!allowed()) return false
        if (url.isNullOrBlank()) { toast(getString(R.string.browser_nothing_to_send)); return false }
        val target = CarScreenController.requireBrowser()
        if (target == null) { toast(getString(R.string.browser_not_connected)); return false }
        if (url == currentPageUrl() && BrowserResumePoint.supports(url)) {
            sendWithResumePoint(url, target)
        } else {
            target.openUrl(url)
            toast(getString(R.string.browser_sent_to_car))
        }
        return true
    }

    /**
     * Reads where this page's player stands, then sends the URL with that position written in.
     *
     * The send is latched so it happens exactly once. A page that never answers the read — a wedged
     * renderer, a WebView torn down mid-gesture — must not swallow the handoff, since the user has
     * already been told it went through, so a short fallback sends the plain URL instead. Both paths
     * run on the main thread, so the flag needs no synchronisation.
     */
    private fun sendWithResumePoint(url: String, target: CarScreenController.BrowserTarget) {
        var sent = false
        val send = { resolved: String, positionMs: Long ->
            if (!sent) {
                sent = true
                target.openUrl(resolved)
                // Naming the time is the whole confirmation: "it started over" was the complaint this
                // path answers, and the user is looking at the phone, not yet at the car.
                toast(
                    if (resolved == url) getString(R.string.browser_sent_to_car)
                    else getString(
                        R.string.browser_sent_to_car_resumed,
                        BrowserResumePoint.clock(positionMs)
                    )
                )
            }
        }
        webAudio.readState { status ->
            send(
                BrowserResumePoint.withResumeAt(url, status.positionMs, status.durationMs),
                status.positionMs,
            )
            // One video playing on two screens is worse than none: the phone stops at the point the
            // car picks up from. Only what was actually playing is touched.
            if (status.playing) webAudio.userPause()
        }
        web.postDelayed({ send(url, 0L) }, RESUME_READ_TIMEOUT_MS)
    }

    /** Pulls whatever the car surface is showing back onto the phone. Unchanged behaviour. */
    private fun receiveFromCar() {
        val url = CarScreenController.activeBrowser?.currentUrl
        if (url.isNullOrBlank()) toast(getString(R.string.browser_open_car_browser_first))
        else navigate(url)
    }

    /**
     * Opens "All actions": every action this surface offers, grouped into sections. Each row
     * dispatches straight back through [runMenuAction], so no behaviour is duplicated here.
     */
    private fun openMoreActions() {
        MoreActionsSheet(
            activity = this,
            sizes = sizes,
            state = { menuState() },
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
                if (!BrowserLauncher.openUrl(this, web.url.orEmpty())) {
                toast(getString(R.string.browser_cannot_open_browser))
            }
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
                toast(getString(R.string.browser_url_copied))
            }
            DrawerAction.PASTE_AND_GO -> clipboardText()?.let { navigate(it) } ?: toast(getString(R.string.browser_clipboard_empty))
            DrawerAction.ZOOM_IN -> web.zoomBy(1.25f)
            DrawerAction.ZOOM_OUT -> web.zoomBy(0.8f)
            DrawerAction.CLEAR_DATA -> confirmClearBrowsingData()
            // Opens the dedicated "Send to car" sheet rather than firing immediately: the sheet
            // lets the user send the current page, a different URL, or a search query, and pick
            // the engine. One-tap "send current page" still lives there as the default.
            DrawerAction.SEND_TO_CAR -> openSendToCar()
            DrawerAction.RECEIVE_FROM_CAR -> receiveFromCar()
            // Queues the page on screen — the input-less path through the same queue the "Send to
            // car" sheet's own Add-to-queue button uses, so a quick tap and the full sheet agree.
            DrawerAction.ADD_TO_QUEUE -> playQueueAccess().add(null, SearchEngineStore.engine(this))
            DrawerAction.TOGGLE_AD_BLOCK -> {
                BrowserAdBlock.setEnabled(this, !BrowserAdBlock.enabled(this))
                web.reload()
            }
            // Phone equivalents of the car's own AutoBridge-section rows: this surface has no Media
            // Center or in-browser Agent chat, but Library and "what's on the car" are both just a
            // normal Activity away.
            DrawerAction.MEDIA_LIBRARY ->
                startActivity(dev.autobridge.library.LibraryActivity.intent(this, dev.autobridge.library.LibraryActivity.Section.TV))
            DrawerAction.NOW_PLAYING ->
                startActivity(dev.autobridge.MainActivityScreens.intent(this, dev.autobridge.ui.PhoneNav.Route.CONTROL))
            DrawerAction.AGENT ->
                startActivity(dev.autobridge.MainActivityScreens.intent(this, dev.autobridge.ui.PhoneNav.Route.AGENT_COMMANDS))
            DrawerAction.SUPPORT ->
                if (!BrowserLauncher.openUrl(this, "https://buymeacoffee.com/guitar.story")) toast("เปิดเบราว์เซอร์ไม่ได้")
            DrawerAction.LICENSES -> showOpenSourceLicenses()
            DrawerAction.GITHUB ->
                if (!BrowserLauncher.openUrl(this, "https://github.com/guitar-dev-io/autobridge")) toast("เปิดเบราว์เซอร์ไม่ได้")
            // The sheet's own footer pair: leaving the browser is this activity finishing.
            DrawerAction.APP_HOME -> finish()
            DrawerAction.TOGGLE_FULLSCREEN -> setFullscreen(!fullscreen)
            // Handled inside the sheet, which owns a real text field and does not need the activity
            // to open a keyboard screen the way the car surface does.
            DrawerAction.ADDRESS_KEYBOARD, DrawerAction.ADDRESS_CLEAR -> Unit
            // Car-only concepts the phone genuinely does not have — one WebView, one pane, so
            // multiple tabs and split layout have nothing to switch between here. [MenuSurface.PHONE]
            // never lists these; see [MoreActionsSheet] for the phone's actual AUTOBRIDGE section.
            DrawerAction.DIAGNOSTICS -> showAbout()
            // Not offered here: see [CAR_DISPLAY_UNSUPPORTED] (car display) and
            // [MenuSurface.PHONE] (phone), neither of which lists them.
            DrawerAction.NEW_TAB, DrawerAction.TABS, DrawerAction.MEDIA_CENTER,
            DrawerAction.SPLIT_LAYOUT, DrawerAction.SIDE_SHOW_PAGE, DrawerAction.NAVIGATE_MAPS,
            DrawerAction.PIN_TOOLBAR, DrawerAction.SWAP_SPLIT_SIDES, DrawerAction.MIRROR_PHONE,
            DrawerAction.SPLIT_CHOOSE -> Unit
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
            toast(getString(R.string.browser_cannot_open_licenses))
        }
    }

    /** Saves the page on screen, the one action the phone menu could list bookmarks but not add to. */
    private fun bookmarkCurrentPage() {
        val url = web.url.orEmpty()
        if (url.isBlank()) { toast(getString(R.string.browser_nothing_to_bookmark)); return }
        val added = WebBookmarkStore.add(this, web.title?.takeIf { it.isNotBlank() } ?: url, url)
        toast(
            getString(
                if (added) R.string.browser_bookmark_saved else R.string.browser_bookmark_failed
            )
        )
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
                // The chrome's colours are baked into views and drawables, and the page's colour
                // scheme is baked into the WebView's own context, so neither can be re-tinted in
                // place: the activity is rebuilt instead. onSaveInstanceState carries the page
                // and its history across, the same path a rotation already takes.
                BrowserAppearanceStore.apply(this, web)
                recreate()
            },
            onDisplayScaleChanged = { BrowserDisplayScaleStore.apply(this, web) },
            onFloatingButtonChanged = { applyFloatingButtonPreference() },
            onDrmChanged = {
                BrowserDefaults.applyDrmPreference(this)
                web.reload()
            },
            // Blocking is decided per request, so only a fresh fetch of the page applies the new
            // rule to what is already on screen.
            onAdBlockChanged = { web.reload() },
            onStartPageBackgroundChanged = { applyStartPageBackground() },
            onEditHomePage = { showHomePageEditor() },
            onEditUserAgent = { showUserAgentChooser() },
            onResetPermissions = { confirmResetSitePermissions() },
            onDeleteSiteData = { confirmDeleteSiteData() },
            onClearBrowsingData = { confirmClearBrowsingData() },
        ).show()
    }

    /**
     * Edits the configured Home page. A URL field, validated through [BrowserStartupStore];
     * clearing it back to blank resets Home to the native start page rather than failing to
     * validate, since the field is pre-filled blank for that case to begin with.
     */
    private fun showHomePageEditor() {
        val current = BrowserStartupStore.homePage(this@BrowserActivity)
        val field = EditText(this).apply {
            setText(current.takeUnless { it == BrowserStartupStore.START_PAGE }.orEmpty())
            hint = getString(R.string.browser_start_page_label)
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
            .setPositiveButton(R.string.action_save) { _, _ ->
                val input = field.text.toString().trim()
                when {
                    input.isEmpty() -> {
                        BrowserStartupStore.resetHomePage(this)
                        toast(getString(R.string.browser_home_page_set))
                        showBrowserSettings()
                    }
                    BrowserStartupStore.setHomePage(this, input) -> {
                        toast(getString(R.string.browser_home_page_set))
                        showBrowserSettings()
                    }
                    else -> toast(getString(R.string.browser_invalid_url))
                }
            }
            .setNegativeButton(R.string.action_cancel) { _, _ -> showBrowserSettings() }
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
            .setMessage(R.string.browser_reset_permissions_message)
            .setPositiveButton(R.string.action_reset) { _, _ ->
                @Suppress("DEPRECATION")
                android.webkit.GeolocationPermissions.getInstance().clearAll()
                showBrowserSettings()
                toast(getString(R.string.browser_permissions_reset))
            }
            .setNegativeButton(R.string.action_cancel) { _, _ -> showBrowserSettings() }
            .show()
    }

    /** Deletes cookies and local site data, leaving the cache, history and bookmarks in place. */
    private fun confirmDeleteSiteData() {
        AlertDialog.Builder(this)
            .setTitle("Delete cookies and site data")
            .setMessage(R.string.browser_delete_site_data_message)
            .setPositiveButton(R.string.action_delete) { _, _ ->
                CookieManager.getInstance().removeAllCookies(null)
                CookieManager.getInstance().flush()
                WebStorage.getInstance().deleteAllData()
                toast(getString(R.string.browser_site_data_deleted))
                showBrowserSettings()
            }
            .setNegativeButton(R.string.action_cancel) { _, _ -> showBrowserSettings() }
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
        // A preset selected earlier is stored as its own UA string with mode CUSTOM — identical to
        // a hand-typed one except that it happens to match a known preset — so "which row is
        // selected" is this match, not the mode alone.
        val matchedPresetIndex = presets.indexOfFirst { it.userAgent == custom }
        val checkedIndex = when (mode) {
            BrowserUserAgentMode.MOBILE -> 0
            BrowserUserAgentMode.DESKTOP -> 1
            BrowserUserAgentMode.CUSTOM -> if (matchedPresetIndex >= 0) matchedPresetIndex + 3 else 2
        }
        val labels = buildList {
            add("Mobile")
            add("Desktop")
            add(
                if (checkedIndex == 2) {
                    getString(R.string.browser_ua_custom_selected, custom.take(40))
                } else {
                    getString(R.string.browser_ua_custom)
                }
            )
            presets.forEach { preset -> add(preset.label) }
        }.toTypedArray<CharSequence>()
        // A real radio list instead of a "✓ "-prefixed plain list: the dialog draws the selection
        // itself, so there is nothing to keep in sync by hand when the labels above change.
        AlertDialog.Builder(this)
            .setTitle("User-Agent")
            .setSingleChoiceItems(labels, checkedIndex) { dialog, index ->
                dialog.dismiss()
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
            .setNegativeButton(R.string.action_cancel, null)
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
            .setTitle(R.string.browser_ua_custom_title)
            .setView(container)
            .setPositiveButton(R.string.action_save) { _, _ ->
                if (BrowserUserAgentStore.saveCustom(this, field.text.toString())) {
                    applyUserAgentChange()
                } else {
                    toast(getString(R.string.browser_ua_invalid))
                }
            }
            .setNegativeButton(R.string.action_cancel, null)
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

    companion object {
        /**
         * Intent extra: when set to `true`, the activity opens the browser settings sheet as soon
         * as the page is ready, matching the Settings > Browser navigation in the phone launcher.
         */
        const val EXTRA_OPEN_SETTINGS = "open_settings"

        /** [WebViewTimerGate] owner tag for the phone browser. */
        const val TIMER_GATE_OWNER = "phone-browser"

        /**
         * Height of the compact address pill, in dp. Deliberately shorter than the toolbar's
         * [AutoUiSizes.TOUCH_TARGET_DP] buttons so the field reads as a slim chip, not a boxed text
         * field, while still clearing the minimum a finger can hit.
         */
        const val ADDRESS_PILL_HEIGHT_DP = 34f

        /** Opacity of a `‹`/`›` button when the page has nothing to go back/forward to. */
        const val DISABLED_NAV_ALPHA = 0.35f

        /** Hint shown on the compact, unfocused pill. */
        /** The address bar's resting hint; a string id so it follows the UI language. */
        val HINT_IDLE get() = R.string.browser_address_hint_idle

        /** Hint shown once the field is tapped and becomes a full editable omnibox. */
        const val HINT_EDITING = "Search or enter address"

        /**
         * How long [sendWithResumePoint] waits for the page to report its playback position before
         * sending the plain URL instead. A live page answers in single-digit milliseconds, so this is
         * long enough to never cost a resume in practice and short enough to be invisible next to the
         * second or more the car's own page load takes.
         */
        const val RESUME_READ_TIMEOUT_MS = 500L

        /**
         * How often the foreground phone browser checks whether the page finished, while the play
         * queue has something in it. The same cadence the car's media poll uses, and the delay a
         * listener would never notice between one item ending and the next loading.
         */
        const val QUEUE_WATCH_MS = 1_000L

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
        val query = EditText(this).apply {
            hint = getString(R.string.browser_find_title)
            setSingleLine()
        }
        AlertDialog.Builder(this).setTitle(R.string.browser_find_title).setView(query)
            .setPositiveButton(R.string.action_search) { _, _ ->
                if (!allowed()) return@setPositiveButton
                web.findAllAsync(query.text.toString())
                AlertDialog.Builder(this).setTitle(R.string.browser_find_title)
                    .setPositiveButton(R.string.action_next, null)
                    .setNegativeButton(R.string.action_previous, null)
                    .setNeutralButton(R.string.action_close) { _, _ -> web.clearMatches() }
                    .create().also { dialog ->
                        dialog.setOnShowListener {
                            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { web.findNext(true) }
                            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener { web.findNext(false) }
                        }
                        dialog.show()
                    }
            }.setNegativeButton(R.string.action_cancel, null).show()
    }

    /**
     * Clears the same set the car surface clears: cache, cookies, web storage, form data and the
     * visited-page list. Bookmarks are user-curated content and are deliberately left alone.
     */
    private fun confirmClearBrowsingData() {
        AlertDialog.Builder(this)
            .setTitle(R.string.browser_clear_data_title)
            .setMessage(R.string.browser_clear_data_message)
            .setPositiveButton(R.string.action_clear) { _, _ ->
                CookieManager.getInstance().removeAllCookies(null)
                CookieManager.getInstance().flush()
                WebStorage.getInstance().deleteAllData()
                web.clearCache(true)
                web.clearFormData()
                web.clearHistory()
                WebHistoryStore.clear(this)
                toast(getString(R.string.browser_data_cleared))
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun showDownloads() {
        val downloads = BrowserDownloads.list(this)
        if (downloads.isEmpty()) { toast(getString(R.string.browser_no_downloads)); return }
        val labels = downloads.map { entry ->
            val status = BrowserDownloads.status(this, entry.id)
            if (status == null) entry.fileName else "${entry.fileName}  •  $status"
        }
        AlertDialog.Builder(this).setTitle(R.string.browser_downloads_title)
            .setItems(labels.toTypedArray(), null)
            .setNeutralButton(R.string.browser_downloads_clear_list) { _, _ ->
                BrowserDownloads.clear(this)
            }
            .setNegativeButton(R.string.action_close, null)
            .show()
    }

    /** Tap opens the page; long-press removes it, matching the bookmarks long-press-to-remove pattern. */
    private fun showBookmarks() {
        val saved = WebBookmarkStore.list(this).toMutableList()
        if (saved.isEmpty()) { toast(getString(R.string.browser_no_bookmarks)); return }
        val adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, saved.map { it.title }.toMutableList())
        val dialog = AlertDialog.Builder(this).setTitle(R.string.browser_bookmarks_title)
            .setAdapter(adapter) { _, i -> navigate(saved[i].url) }
            .setNegativeButton(R.string.action_close, null)
            .create()
        dialog.show()
        dialog.listView.setOnItemLongClickListener { _, _, i, _ ->
            WebBookmarkStore.remove(this, saved[i].url)
            saved.removeAt(i)
            adapter.remove(adapter.getItem(i))
            toast(getString(R.string.browser_bookmark_removed))
            if (saved.isEmpty()) dialog.dismiss()
            true
        }
    }

    /** Tap opens the page; long-press removes just that entry. */
    private fun showHistory() {
        val visited = WebHistoryStore.list(this).toMutableList()
        if (visited.isEmpty()) { toast(getString(R.string.browser_no_history)); return }
        val adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, visited.map { it.title }.toMutableList())
        val dialog = AlertDialog.Builder(this).setTitle(R.string.browser_history_title)
            .setAdapter(adapter) { _, i -> navigate(visited[i].url) }
            .setNeutralButton(R.string.browser_history_clear_all) { _, _ ->
                WebHistoryStore.clear(this)
                toast(getString(R.string.browser_history_cleared))
            }
            .setNegativeButton(R.string.action_close, null)
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
    /**
     * The mobile Google Maps page has no turn-by-turn of its own: its "Start" and "Open app" buttons
     * only try to launch the Maps app through an `intent://` or `google.navigation:` link, which this
     * browser used to drop, so they did nothing. Starts navigation in the Maps app to the link's
     * destination (or to what the page is showing) and returns true; false for every other link.
     */
    private fun handOffToMaps(target: String, pageUrl: String?): Boolean {
        if (!MapsHandoff.isMapsAppLink(target) && MapsHandoff.destinationFromLink(target) == null) return false
        val destination = MapsHandoff.handoffDestination(target, pageUrl)
        if (destination == null) {
            android.widget.Toast.makeText(this, getString(R.string.car_maps_no_destination), android.widget.Toast.LENGTH_LONG).show()
            return true
        }
        val navigate = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(MapsHandoff.navigationUri(destination)))
            .setPackage(MapsHandoff.MAPS_PACKAGE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val started = runCatching { startActivity(navigate) }.isSuccess
        android.util.Log.i("AutoBridgeBrowser", "maps hand-off started=$started")
        if (!started) {
            android.widget.Toast.makeText(this, getString(R.string.maps_app_missing), android.widget.Toast.LENGTH_LONG).show()
        }
        return true
    }

    private fun promptExternalSignIn(url: String) {
        AlertDialog.Builder(this)
            .setTitle(R.string.browser_signin_title)
            .setMessage(R.string.browser_signin_message)
            .setPositiveButton(R.string.action_sign_in) { _, _ ->
                if (!BrowserLauncher.openSignIn(this, url)) {
                    toast(getString(R.string.browser_cannot_open_browser))
                }
            }
            .setNegativeButton(R.string.action_cancel, null)
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
            pendingUrl?.let { url ->
                pendingUrl = null
                // The phone's Home tiles arrive here as an intent carrying a site root, which is the
                // same gesture as the car's tiles: a tile for the site already open means "show me
                // that", and reloading would throw away the page and restart what is playing on it.
                if (!BrowserSiteEntry.resumes(web.url, url)) navigate(url)
            }
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
        maybeOpenSettingsSheet(intent)
    }

    /**
     * Handled in-process (see the manifest's `configChanges`). Recreating the Activity on rotation
     * would rebuild the WebView and restart the page from a saved state; keeping it alive means a
     * rotation resizes the view and nothing more.
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Appearance "Auto" means the system's night mode, and the system just changed it.
        if (BrowserAppearanceStore.needsRestart(this, newConfig)) {
            recreate()
            return
        }
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

    /**
     * Watches the page for the end of what it is playing, so [BrowserPlayQueue] advances on the
     * phone as it does on the car.
     *
     * The car gets this from [dev.autobridge.media.MediaPlaybackService]'s one-second poll, which
     * exists to feed the media session and only ever reads the car browser
     * ([dev.autobridge.media.WebMediaHub] holds a single source). Registering this activity there
     * instead would hand the car's media card the phone's page, so the phone keeps its own watch.
     *
     * It costs nothing until the queue is used: an empty queue is answered from preferences without
     * asking the page anything. Edge-triggered for the same reason the service is — a finished page
     * keeps saying so until something replaces it, and acting on the level would empty the whole
     * queue into one page load.
     */
    private val queueWatch = object : Runnable {
        override fun run() {
            if (!resumed) return
            if (BrowserPlayQueue.size(this@BrowserActivity) > 0) {
                webAudio.readState { status ->
                    val startedFinishing = status.ended && !pageHadFinished
                    pageHadFinished = status.ended
                    if (startedFinishing) advanceToQueuedPage()
                }
            }
            web.postDelayed(this, QUEUE_WATCH_MS)
        }
    }

    /** Whether the previous reading already said the page had finished; see [queueWatch]. */
    private var pageHadFinished = false

    private fun advanceToQueuedPage() {
        val next = BrowserPlayQueue.takeNext(this) ?: return
        // The next page has nothing playing yet, so its own end must read as a fresh transition.
        pageHadFinished = false
        toast(getString(R.string.browser_resumed_from_queue))
        navigate(next.url)
    }

    override fun onResume() {
        super.onResume(); resumed = true
        web.removeCallbacks(queueWatch)
        web.postDelayed(queueWatch, QUEUE_WATCH_MS)
        // Timers are process-wide; the car browser may have paused them while it was hidden.
        WebViewTimerGate.hold(TIMER_GATE_OWNER, web)
        val previousAgent = web.settings.userAgentString
        BrowserDefaults.configure(this, web)
        enforcePolicy()
        if (allowed() && previousAgent != web.settings.userAgentString) web.reload()
    }

    override fun onPause() {
        resumed = false
        web.removeCallbacks(queueWatch)
        web.onPause()
        WebViewTimerGate.release(TIMER_GATE_OWNER, web)
        // WebView writes cookies to disk lazily, so a session established moments ago can still be
        // memory-only at this point. Flushing on the way to the background is what keeps a fresh
        // sign-in from being lost when the process is killed before Chromium's own periodic flush
        // runs. It blocks on I/O, which is acceptable once per backgrounding.
        CookieManager.getInstance().flush()
        super.onPause()
    }

    /**
     * Back walks out of the page before it walks out of the browser: a fullscreen video, then the
     * browser's own fullscreen, then the web history, and only then the screen.
     */
    private fun goBack() {
        if (fullscreenController.onBackPressed()) return
        if (fullscreen) { setFullscreen(false); return }
        if (showingStartPage) {
            if (allowed() && web.canGoBack()) { hideStartPage(); web.goBack(); return }
            dev.autobridge.ui.SystemBack.finishFromBack(this)
            return
        }
        if (allowed() && web.canGoBack()) { web.goBack(); return }
        dev.autobridge.ui.SystemBack.finishFromBack(this)
    }

    // Pre-33 devices only; everything newer comes through [dev.autobridge.ui.SystemBack].
    @Deprecated("Back is handled by SystemBack on API 33+", ReplaceWith("goBack()"))
    @Suppress("DEPRECATION")
    // The lint check wants this gone, but it is still the only Back a pre-33 device delivers;
    // SystemBack carries the versions that no longer call it.
    @SuppressLint("GestureBackNavigation")
    override fun onBackPressed() = goBack()

    /** Undoes the Back registration; see [dev.autobridge.ui.SystemBack]. */
    private var releaseBack: () -> Unit = {}

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
        releaseBack()
        ParkingStateStore.removeListener(parkingListener)
        youtube.release()
        geolocation.release()
        web.stopLoading(); web.destroy(); super.onDestroy()
    }
}
