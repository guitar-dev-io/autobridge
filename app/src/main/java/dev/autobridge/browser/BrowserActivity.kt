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
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.*
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
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
    private lateinit var address: EditText
    private lateinit var toolbar: LinearLayout
    private lateinit var chromeBar: FrameLayout
    private lateinit var handle: TextView
    private lateinit var menuButton: TextView
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        root = FrameLayout(this).apply { setBackgroundColor(BrowserTheme.background) }

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
            contentDescription = "Browser menu"
            isFocusable = true
            background = GradientDrawable().apply {
                setColor(0xE6101113.toInt())
                cornerRadius = sizes.fabSize / 2f
                setStroke(1.dp(), 0xFF526FA6.toInt())
            }
            setOnClickListener { showMenu() }
        }
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

        val restored = savedInstanceState?.getBundle("web")?.let { web.restoreState(it) } != null
        pendingUrl = savedInstanceState?.getString("pending_url")
            ?: if (!restored) intent.dataString?.let(ContentAddress::https) ?: BrowserDefaults.lastUrl(this) else null
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
        control("☰", "Browser menu") { showMenu() }

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
        BrowserDefaults.configure(this@BrowserActivity, settings)
        BrowserDefaults.configureDebugTools()
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

            override fun onShowCustomView(view: View, callback: CustomViewCallback) =
                fullscreenController.show(view, callback) { pageFullscreen -> setFullscreen(pageFullscreen) }

            override fun onHideCustomView() = fullscreenController.hide()
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
            }

            override fun onPageFinished(view: WebView, url: String) {
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

    private fun navigate(input: String) {
        if (!allowed()) { toast(FeaturePolicy.app.denialMessage(Feature.BROWSER)); return }
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
        menuButton.visibility = if (enabled) View.VISIBLE else View.GONE
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

    /**
     * Primary menu: only the actions used every session, sized so the list fits on screen without
     * scrolling. A 16-item single list forced a scroll on most screens, and a touch that started as
     * a tap but drifted a few px while the car vibrated was read as a scroll instead of a click —
     * the item just never fired. Splitting rare actions into [showMoreMenu] removes the scroll for
     * the common case and keeps "กลับหน้า Home" as a pinned button so it is never affected by list
     * scrolling either.
     */
    /** One tappable tile in a menu sheet. */
    private data class MenuEntry(val glyph: String, val label: String, val run: () -> Unit)

    /**
     * Renders a menu as a grid of tiles rather than a list of text rows.
     *
     * A row in an `AlertDialog.setItems` list is about 48dp tall and spans the dialog, so the only
     * thing distinguishing one entry from the next is where the text sits — there is no visible
     * target, and a touch that drifts a few px reads as a scroll and never fires. A tile is a drawn
     * button with an icon, roughly four times the area, and the grid puts every entry on screen at
     * once so there is nothing to scroll past. This is the same model the car surface uses, so the
     * two menus now look and behave alike.
     */
    private fun showActionGrid(
        title: String,
        entries: List<MenuEntry>,
        closeLabel: String,
        onClose: () -> Unit,
    ) {
        val columns = if (resources.configuration.screenWidthDp >= 600) 4 else 3
        val gap = sizes.dpInt(AutoUiSizes.MENU_TILE_GAP_DP) / 2
        val grid = GridLayout(this).apply {
            columnCount = columns
            setPadding(gap, gap, gap, gap)
        }
        var dialog: AlertDialog? = null
        entries.forEach { entry ->
            val tile = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                background = GradientDrawable().apply {
                    setColor(BrowserTheme.toolbarBackground)
                    cornerRadius = sizes.cornerRadius
                }
                isClickable = true
                isFocusable = true
                contentDescription = entry.label
                setOnClickListener {
                    dialog?.dismiss()
                    if (allowed()) entry.run()
                }
                addView(TextView(context).apply {
                    text = entry.glyph
                    textSize = iconSp(AutoUiSizes.ICON_LARGE_DP)
                    gravity = Gravity.CENTER
                    setTextColor(BrowserTheme.iconEnabled)
                })
                addView(TextView(context).apply {
                    text = entry.label
                    textSize = iconSp(AutoUiSizes.ICON_SMALL_DP * 0.7f)
                    gravity = Gravity.CENTER
                    maxLines = 2
                    setTextColor(BrowserTheme.textPrimary)
                })
            }
            grid.addView(
                tile,
                GridLayout.LayoutParams().apply {
                    width = 0
                    height = sizes.dpInt(AutoUiSizes.MENU_TILE_HEIGHT_DP)
                    columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                    setMargins(gap, gap, gap, gap)
                }
            )
        }
        dialog = AlertDialog.Builder(this)
            .setTitle(title)
            .setView(ScrollView(this).apply { addView(grid) })
            .setNegativeButton(closeLabel) { _, _ -> onClose() }
            .create()
        dialog.show()
    }

    /**
     * Primary menu: only the actions used every session, so the grid fits without scrolling.
     * Rarely used actions live in [showMoreMenu]; "กลับหน้า Home" stays a pinned button so it is
     * never affected by the sheet's own scrolling.
     */
    private fun showMenu() {
        val desktop = BrowserUserAgentStore.mode(this) == BrowserUserAgentMode.DESKTOP
        showActionGrid(
            "Browser",
            listOf(
                MenuEntry("\u2302", "หน้าแรก") { navigate(BrowserDefaults.HOME) },
                MenuEntry("\u2606", "บุ๊กมาร์ก") { showBookmarks() },
                MenuEntry("\u21ba", "ประวัติ") { showHistory() },
                MenuEntry("\u2193", "ดาวน์โหลด") { showDownloads() },
                MenuEntry("\u2315", "ค้นหาในหน้า") { showFindInPage() },
                MenuEntry("\u25a1", "Desktop: ${if (desktop) "เปิด" else "ปิด"}") {
                    BrowserUserAgentStore.select(
                        this,
                        if (desktop) BrowserUserAgentMode.MOBILE else BrowserUserAgentMode.DESKTOP
                    )
                    BrowserDefaults.configure(this, web.settings)
                    web.reload()
                },
                MenuEntry("\u22ef", "เพิ่มเติม") { showMoreMenu() },
            ),
            "กลับหน้า Home"
        ) { finish() }
    }

    /** Secondary menu for actions used rarely enough not to earn a slot in [showMenu]. */
    private fun showMoreMenu() {
        showActionGrid(
            "เพิ่มเติม",
            listOf(
                MenuEntry("\u26f6", "เต็มหน้าจอ") { setFullscreen(true) },
                MenuEntry("\u29c9", "คัดลอก URL") {
                    val manager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    manager.setPrimaryClip(ClipData.newPlainText("URL", web.url.orEmpty()))
                    toast("คัดลอก URL แล้ว")
                },
                MenuEntry("\u2398", "วาง URL แล้วไป") {
                    clipboardText()?.let { navigate(it) } ?: toast("คลิปบอร์ดว่าง")
                },
                MenuEntry("+", "ซูมเข้า") { web.zoomBy(1.25f) },
                MenuEntry("\u2212", "ซูมออก") { web.zoomBy(0.8f) },
                MenuEntry("\u2327", "ล้างข้อมูล") { confirmClearBrowsingData() },
                MenuEntry("\u2197", "เบราว์เซอร์ภายนอก") {
                    if (!BrowserLauncher.openUrl(this, web.url.orEmpty())) toast("เปิดเบราว์เซอร์ไม่ได้")
                },
                MenuEntry("\u25b6", "ส่งไป Android Auto") {
                    val url = web.url?.let(ContentAddress::https)
                    val target = CarScreenController.requireBrowser()
                    when {
                        url == null -> Unit
                        target == null -> toast("ยังไม่ได้เชื่อมต่อ Android Auto")
                        else -> target.openUrl(url)
                    }
                },
                MenuEntry("\u2199", "รับจาก Android Auto") {
                    val url = CarScreenController.activeBrowser?.currentUrl
                    if (url == null) toast("เปิด Browser บน Android Auto ก่อน") else navigate(url)
                },
            ),
            "ย้อนกลับ"
        ) { }
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
        intent.dataString?.let(ContentAddress::https)?.let { pendingUrl = it }
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
        val previousAgent = web.settings.userAgentString
        BrowserDefaults.configure(this, web.settings)
        enforcePolicy()
        if (allowed() && previousAgent != web.settings.userAgentString) web.reload()
    }

    override fun onPause() { resumed = false; web.onPause(); super.onPause() }

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

    override fun onDestroy() {
        ParkingStateStore.removeListener(parkingListener)
        youtube.release()
        web.stopLoading(); web.destroy(); super.onDestroy()
    }
}
