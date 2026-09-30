package dev.autobridge.entertainment

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Bundle
import android.speech.RecognizerIntent
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.*
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import dev.autobridge.browser.BrowserDefaults
import dev.autobridge.browser.FullscreenVideoController
import dev.autobridge.core.policy.FeaturePolicy
import dev.autobridge.media.MediaPlaybackClient
import dev.autobridge.safety.ParkingStateStore

/** Phone-hosted browser/audio/video content projected through the consent-based mirror engine. */
class EntertainmentActivity : Activity() {
    companion object {
        const val EXTRA_BROWSER_MODE = "dev.autobridge.extra.BROWSER_MODE"

        /**
         * Direct playback request from the library/IPTV screens. Unlike the `intent.data` path,
         * these accept http, file and content URIs, because IPTV portals and on-device media are
         * routinely not https. The kind is the name of a [ContentKind]; it is required so a radio
         * stream is never promoted to the video surface just because of its file extension.
         */
        const val EXTRA_SOURCE_URL = "dev.autobridge.extra.SOURCE_URL"
        const val EXTRA_SOURCE_KIND = "dev.autobridge.extra.SOURCE_KIND"
        const val EXTRA_SOURCE_TITLE = "dev.autobridge.extra.SOURCE_TITLE"
    }

    private val browserMode by lazy { intent.getBooleanExtra(EXTRA_BROWSER_MODE, false) }
    private val prefs by lazy { getSharedPreferences("entertainment", MODE_PRIVATE) }
    private val audioManager by lazy { getSystemService(AUDIO_SERVICE) as AudioManager }
    private var audioFocusRequest: AudioFocusRequest? = null
    private lateinit var client: MediaPlaybackClient
    private lateinit var browser: WebView
    private lateinit var video: SurfaceView
    private lateinit var fullscreenController: FullscreenVideoController
    private lateinit var emptyState: TextView

    /** Holds the WebView/video stack; the IME inset is applied here, never to the whole root. */
    private var contentContainer: android.widget.FrameLayout? = null
    private lateinit var status: TextView
    private lateinit var address: EditText
    private var source = ""
    private var contentKind = ContentKind.WEB
    private var resumed = false
    private var wasAllowed = false
    private var pendingSource: Pair<String, ContentKind>? = null

    /** Display title for a source handed in by the library screens; cleared once playback starts. */
    private var pendingTitle: String? = null
    private val parkingListener: (ParkingStateStore.State) -> Unit = { runOnUiThread { enforceParking() } }
    private val playerListener = object : Player.Listener {
        override fun onPlayerError(error: PlaybackException) {
            status.text = "เล่นไม่ได้: ${error.errorCodeName} — ตรวจ URL หรือเลือกแหล่งอื่น"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (browserMode) {
            startActivity(Intent(this, dev.autobridge.browser.BrowserActivity::class.java).apply {
                data = intent.data
            })
            finish()
            return
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        client = MediaPlaybackClient(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 16, 16, 8)
            setBackgroundColor(Color.rgb(16, 19, 25))
        }
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.ime())
            // System bars pad the root; the IME pads only the content container below. Folding the
            // IME inset into the root's bottom padding made the keyboard shrink the WebView (it has
            // weight 1), so the page reflowed and jumped every time the keyboard opened.
            view.setPadding(
                bars.left + 12.dp(),
                bars.top + 12.dp(),
                bars.right + 12.dp(),
                bars.bottom + 8.dp()
            )
            contentContainer?.setPadding(0, 0, 0, ime.bottom)
            insets
        }
        status = TextView(this).apply { setTextColor(Color.WHITE); textSize = 15f }
        root.addView(status)
        address = EditText(this).apply {
            hint = if (browserMode) "ค้นหาเว็บบนจอรถ หรือวาง HTTPS URL" else "ค้นหา YouTube หรือวาง HTTPS URL"
            setTextColor(Color.WHITE); setHintTextColor(Color.LTGRAY)
            setSingleLine()
        }
        root.addView(address)
        fun row(vararg actions: Pair<String, () -> Unit>) {
            val buttons = LinearLayout(this)
            actions.forEach { (title, action) ->
                buttons.addView(Button(this).apply {
                    text = title; isAllCaps = false; textSize = 13f
                    setSingleLine(); setPadding(4.dp(), 0, 4.dp(), 0)
                    setOnClickListener { action() }
                }, LinearLayout.LayoutParams(0, 48.dp(), 1f))
            }
            root.addView(buttons)
        }
        row(
            "YouTube" to { open(ContentAddress.youtubeSearch(address.text.toString()), ContentKind.WEB) },
            "เว็บ" to { openAddress(ContentKind.WEB) },
            "TV / วิดีโอ" to { openAddress(ContentKind.VIDEO) },
            "เสียง" to { voiceSearch() }
        )
        row("เปิด Browser" to { openExternalBrowser() })
        row(
            "โปรด" to { favorites() },
            "บันทึก" to { saveFavorite() },
            "เล่นต่อ" to { restoreLast() },
            "ไฟล์" to { chooseFile() }
        )
        row(
            "ย้อนกลับ" to { if (contentKind == ContentKind.WEB && browser.canGoBack()) browser.goBack() else finish() },
            "ก่อนหน้า" to { if (contentKind == ContentKind.AUDIO || contentKind == ContentKind.VIDEO) client.previous() },
            "เล่น / พัก" to {
                if (contentKind == ContentKind.AUDIO || contentKind == ContentKind.VIDEO) {
                    if (client.isPlaying) client.pause() else client.resume()
                }
            },
            "ถัดไป" to { if (contentKind == ContentKind.AUDIO || contentKind == ContentKind.VIDEO) client.next() }
        )
        root.addView(CheckBox(this).apply {
            text = "เปิดรายการล่าสุดเมื่อกลับมาจอด (ขณะหน้านี้เปิดอยู่)"
            setTextColor(Color.WHITE)
            isChecked = prefs.getBoolean("autoResume", false)
            setOnCheckedChangeListener { _, checked -> prefs.edit().putBoolean("autoResume", checked).apply() }
        })
        browser = WebView(this).apply {
            // Shares the same UA-stripping and X-Requested-With allow-list as the other two
            // WebView surfaces (BrowserActivity, CarWebRenderer) so a Google sign-in started from
            // this player (e.g. from a YouTube page) is not flagged as an embedded WebView here
            // either. This call also sets javaScriptEnabled/domStorageEnabled/allowFileAccess and
            // the third-party-cookie policy, so the settings block below only adds what
            // BrowserDefaults intentionally leaves out for this player surface.
            BrowserDefaults.configure(this@EntertainmentActivity, this)
            // Allow tapping play directly from the car screen without a second gesture on the phone.
            settings.mediaPlaybackRequiresUserGesture = false
            BrowserDefaults.configureDebugTools()
            webChromeClient = object : WebChromeClient() {
                // Lets a page's own navigator.requestMediaKeySystemAccess() (Widevine EME) reach
                // Android's normal MediaDrm stack; only the protected-media resource is granted.
                override fun onPermissionRequest(request: PermissionRequest) =
                    BrowserDefaults.grantProtectedMediaPermission(request)

                override fun onShowCustomView(view: View, callback: CustomViewCallback) =
                    fullscreenController.show(view, callback) {}

                override fun onHideCustomView() = fullscreenController.hide()
            }
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                    !FeaturePolicy.app.isAvailable(ContentKind.WEB.requiredFeature) ||
                        ContentAddress.https(request.url.toString()) == null

                override fun onPageFinished(view: WebView, url: String) {
                    if (contentKind == ContentKind.WEB && ContentAddress.https(url) != null &&
                        FeaturePolicy.app.isAvailable(ContentKind.WEB.requiredFeature)
                    ) {
                        source = url
                        persist()
                    }
                }
            }
        }
        video = SurfaceView(this)
        emptyState = TextView(this).apply {
            text = if (browserMode) {
                """AutoBridge Car Browser

วาง HTTPS URL หรือค้นหาเว็บจากปุ่มด้านบน

หน้าจอนี้จะถูก mirror ไปยัง Android Auto เมื่อเริ่มแชร์จอ"""
            } else {
                """AutoBridge

YouTube · TV · Web

เชื่อมต่อ Android Auto และเปิดแชร์จอ
เมื่อรถจอดแล้ว เลือกเนื้อหาจากปุ่มด้านบน"""
            }
            textSize = 18f
            gravity = android.view.Gravity.CENTER
            setTextColor(Color.rgb(160, 178, 205))
        }
        val content = FrameLayout(this).apply {
            addView(emptyState, FrameLayout.LayoutParams(-1, -1))
            addView(browser, FrameLayout.LayoutParams(-1, -1))
            addView(video, FrameLayout.LayoutParams(-1, -1))
        }
        contentContainer = content
        fullscreenController = FullscreenVideoController(this, content)
        root.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)

        source = savedInstanceState?.getString("source").orEmpty()
        contentKind = decodeKind(
            savedInstanceState?.getString("kind"),
            savedInstanceState?.getBoolean("stream") == true
        )
        // A car screen can hand us a URL to open directly (intent data). Validate it and queue it
        // as the pending source so it opens as soon as the surface/player is ready.
        if (savedInstanceState == null) {
            val requested = intent?.getStringExtra(EXTRA_SOURCE_URL)?.trim().orEmpty()
            if (requested.isNotEmpty()) {
                pendingTitle = intent?.getStringExtra(EXTRA_SOURCE_TITLE)?.takeIf { it.isNotBlank() }
                address.setText(requested)
                val kind = decodeKind(intent?.getStringExtra(EXTRA_SOURCE_KIND), false)
                pendingSource = requested to kind
            } else intent?.data?.toString()?.let { incoming ->
                val validated = ContentAddress.https(incoming)
                if (validated != null) {
                    address.setText(validated)
                    val kind = ContentKindResolver.classify(validated) ?: ContentKind.WEB
                    pendingSource = validated to kind
                }
            }
        }
        client.connect(onConnected = {
            client.player?.addListener(playerListener)
            if (resumed) {
                pendingSource?.let { (url, kind) -> pendingSource = null; open(url, kind) }
                if (!client.isPlaying && prefs.getBoolean("autoResume", false) && source.isEmpty()) {
                    restoreLast()
                }
            }
            enforceParking()
        }, onError = {
            status.text = "เชื่อมต่อเครื่องเล่นไม่สำเร็จ ลองเปิดหน้านี้ใหม่"
            enforceParking()
        })
        ParkingStateStore.addListener(parkingListener)
        enforceParking()
    }

    private fun Int.dp() = (this * resources.displayMetrics.density).toInt()
    private fun message(text: String) { status.text = text }

    private fun openAddress(kind: ContentKind) {
        val url = ContentAddress.https(address.text.toString())
        if (url == null) {
            message("ใช้ HTTPS URL ที่ถูกต้อง")
        } else {
            open(url, kind)
        }
    }

    private fun openExternalBrowser() {
        if (!resumed) {
            message("รอหน้า Entertainment พร้อมใช้งาน")
            return
        }
        if (!FeaturePolicy.app.isAvailable(ContentKind.WEB.requiredFeature)) {
            message(FeaturePolicy.app.denialMessage(ContentKind.WEB.requiredFeature))
            return
        }
        val url = ContentAddress.https(address.text.toString())
            ?: source.takeIf { it.isNotBlank() && contentKind == ContentKind.WEB }
        if (url == null) {
            message("กรอก HTTPS URL ก่อนเปิด Browser")
            return
        }
        if (!BrowserLauncher.openUrl(this, url)) {
            message("เปิด Browser ไม่สำเร็จ — ตรวจว่ามี Browser และใช้ HTTPS URL")
        }
    }

    private fun open(url: String, kind: ContentKind, position: Long = 0L) {
        val feature = kind.requiredFeature
        if (!resumed || !FeaturePolicy.app.isAvailable(feature)) {
            message(FeaturePolicy.app.denialMessage(feature))
            return
        }
        val needsPlayer = kind == ContentKind.AUDIO || kind == ContentKind.VIDEO
        if (needsPlayer && client.player == null) {
            message("กำลังเชื่อมต่อเครื่องเล่น ลองอีกครั้ง")
            return
        }
        persistPosition()
        browser.stopLoading()
        browser.loadUrl("about:blank")
        client.pause()
        source = url
        contentKind = kind
        address.setText(url)
        persist()
        enforceParking()
        if (needsPlayer) {
            client.play(url, pendingTitle)
            pendingTitle = null
            // Only "เล่นต่อ" and the auto-resume path carry a position. An item the user tapped
            // starts at the beginning, so it must not be seeked at all: the saved offset belongs
            // to the last session, and seeking a freshly prepared item races that prepare().
            if (position > 0L) client.player?.seekTo(position)
        } else {
            browser.loadUrl(url)
        }
    }

    private fun enforceParking() {
        val feature = contentKind.requiredFeature
        val allowed = resumed && FeaturePolicy.app.isAvailable(feature)
        val becameAllowed = allowed && !wasAllowed
        wasAllowed = allowed
        emptyState.visibility = if (!allowed || source.isEmpty() || contentKind == ContentKind.AUDIO) View.VISIBLE else View.GONE
        browser.visibility = if (allowed && contentKind == ContentKind.WEB && source.isNotEmpty()) View.VISIBLE else View.GONE
        val showVideo = allowed && contentKind == ContentKind.VIDEO && source.isNotEmpty()
        video.visibility = if (showVideo) View.VISIBLE else View.GONE
        if (showVideo) client.player?.setVideoSurfaceView(video)
        else client.player?.clearVideoSurfaceView(video)
        if (!allowed) {
            persistPosition()
            browser.onPause()
            browser.stopLoading()
            browser.loadUrl("about:blank")
            message(FeaturePolicy.app.denialMessage(feature))
            emptyState.text = "โหมดเสียง\n\nควบคุมเล่น / พัก ผ่านการแจ้งเตือนหรือปุ่มสื่อ\nวิดีโอและเว็บเปิดได้เมื่อโหมดและรถอนุญาต"
        } else {
            when (contentKind) {
                ContentKind.WEB -> {
                    emptyState.text = "AutoBridge\n\nYouTube · TV · Web\n\nจอดแล้ว เลือกเนื้อหาจากปุ่มด้านบน"
                    browser.onResume()
                    message("AutoBridge • YouTube / Web • จอดแล้ว")
                }
                ContentKind.VIDEO -> {
                    emptyState.text = "AutoBridge Video\n\nจอดแล้ว วิดีโอพร้อมเล่น"
                    browser.onPause()
                    message("AutoBridge • Video • จอดแล้ว")
                }
                ContentKind.AUDIO -> {
                    emptyState.text = "AutoBridge Audio\n\nเสียงยังเล่นต่อได้ แม้ฟีเจอร์วิดีโอจะถูกล็อก"
                    browser.onPause()
                    message(if (client.isConnected) "AutoBridge • Audio" else "กำลังเชื่อมต่อ MediaSession…")
                }
            }
            if (becameAllowed && !client.isPlaying && pendingSource == null &&
                prefs.getBoolean("autoResume", false)
            ) {
                val last = prefs.getString("last", null)
                if (last != null) restoreLast()
            }
        }
    }

    private fun persist() {
        if (source.isEmpty()) return
        prefs.edit()
            .putString("last", source)
            .putString("kind", contentKind.name)
            .putBoolean("stream", contentKind == ContentKind.VIDEO)
            .apply()
    }

    private fun persistPosition() {
        if (contentKind == ContentKind.AUDIO || contentKind == ContentKind.VIDEO) {
            if (source.isNotEmpty()) client.player?.let {
                prefs.edit().putLong("position:$source", it.currentPosition.coerceAtLeast(0L)).apply()
            }
        }
    }

    private fun restoreLast() {
        val last = prefs.getString("last", null) ?: return message("ยังไม่มีรายการล่าสุด")
        val kind = decodeKind(prefs.getString("kind", null), prefs.getBoolean("stream", false))
        open(last, kind, prefs.getLong("position:$last", 0L))
    }

    private fun saveFavorite() {
        if (source.isEmpty()) return message("เปิดเนื้อหาก่อนบันทึก")
        val items = prefs.getStringSet("favorites", emptySet()).orEmpty().toMutableSet()
        items.add("${contentKind.name}|$source")
        prefs.edit().putStringSet("favorites", items).apply()
        message("บันทึกรายการโปรดแล้ว")
    }

    private fun favorites() {
        val items = prefs.getStringSet("favorites", emptySet()).orEmpty().sorted()
        if (items.isEmpty()) return message("ยังไม่มีรายการโปรด")
        AlertDialog.Builder(this).setTitle("รายการโปรด")
            .setItems(items.toTypedArray()) { _, index ->
                val item = items[index]
                val separator = item.indexOf('|')
                val prefix = if (separator > 0) item.substring(0, separator) else "WEB"
                val url = if (separator > 0) item.substring(separator + 1) else item
                val kind = when (prefix) {
                    "TV" -> ContentKind.VIDEO // legacy favorites
                    "WEB" -> ContentKind.WEB // legacy favorites
                    else -> decodeKind(prefix, false)
                }
                open(url, kind)
            }.setNegativeButton("ปิด", null).show()
    }

    private fun voiceSearch() {
        if (!FeaturePolicy.app.isAvailable(ContentKind.WEB.requiredFeature)) {
            return message(FeaturePolicy.app.denialMessage(ContentKind.WEB.requiredFeature))
        }
        runCatching {
            startActivityForResult(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PROMPT, "ค้นหา YouTube")
            }, 3101)
        }.onFailure { message("ไม่พบบริการค้นหาด้วยเสียงบนโทรศัพท์") }
    }

    private fun chooseFile() {
        if (!FeaturePolicy.app.isAvailable(ContentKind.VIDEO.requiredFeature) &&
            !FeaturePolicy.app.isAvailable(ContentKind.AUDIO.requiredFeature)
        ) {
            return message(FeaturePolicy.app.denialMessage(ContentKind.VIDEO.requiredFeature))
        }
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            type = "*/*"
            addCategory(Intent.CATEGORY_OPENABLE)
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("video/*", "audio/*"))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }, 3102)
    }

    private fun decodeKind(name: String?, legacyVideo: Boolean): ContentKind =
        name?.let { value -> ContentKind.entries.firstOrNull { it.name == value } }
            ?: if (legacyVideo) ContentKind.VIDEO else ContentKind.WEB

    @Deprecated("Activity result API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        if (requestCode == 3101) {
            val query = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull() ?: return
            address.setText(query)
            pendingSource = ContentAddress.youtubeSearch(query) to ContentKind.WEB
        } else if (requestCode == 3102) {
            val uri = data?.data ?: return
            runCatching {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val kind = ContentKindResolver.classify(uri.toString(), contentResolver.getType(uri))
            if (kind == null || kind == ContentKind.WEB) {
                message("เลือกไฟล์เสียงหรือวิดีโอที่รองรับ")
            } else {
                pendingSource = uri.toString() to kind
            }
        }
    }

    private fun requestAudioFocus() {
        if (audioFocusRequest != null) return
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                    .build()
            )
            .setOnAudioFocusChangeListener { }
            .build()
        audioFocusRequest = request
        audioManager.requestAudioFocus(request)
    }

    private fun abandonAudioFocus() {
        audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        audioFocusRequest = null
    }

    override fun onResume() {
        super.onResume()
        if (!::browser.isInitialized) return
        resumed = true
        requestAudioFocus()
        if (::browser.isInitialized) enforceParking()
        pendingSource?.let { (url, kind) ->
            if ((kind == ContentKind.WEB) || client.player != null) {
                pendingSource = null
                open(url, kind)
            }
        }
    }

    override fun onPause() {
        resumed = false
        abandonAudioFocus()
        if (::browser.isInitialized) enforceParking()
        super.onPause()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (::fullscreenController.isInitialized && fullscreenController.onBackPressed()) return
        super.onBackPressed()
    }

    override fun onSaveInstanceState(out: Bundle) {
        out.putString("source", source)
        out.putString("kind", contentKind.name)
        out.putBoolean("stream", contentKind == ContentKind.VIDEO)
        super.onSaveInstanceState(out)
    }

    override fun onDestroy() {
        if (!::browser.isInitialized) { super.onDestroy(); return }
        abandonAudioFocus()
        ParkingStateStore.removeListener(parkingListener)
        client.player?.removeListener(playerListener)
        client.player?.clearVideoSurfaceView(video)
        browser.destroy()
        client.disconnect()
        super.onDestroy()
    }
}
