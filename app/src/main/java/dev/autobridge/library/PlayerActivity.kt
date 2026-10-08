package dev.autobridge.library

import android.annotation.SuppressLint
import android.app.Activity
import android.app.PictureInPictureParams
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Rational
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import dev.autobridge.settings.AspectRatio
import dev.autobridge.R
import dev.autobridge.core.model.Feature
import dev.autobridge.core.policy.FeaturePolicy
import dev.autobridge.entertainment.ContentKind
import dev.autobridge.i18n.AppLocale
import dev.autobridge.media.MediaPlaybackClient
import dev.autobridge.media.VideoAspect
import dev.autobridge.safety.ParkingStateStore
import dev.autobridge.safety.SafetyEnforcement
import dev.autobridge.settings.ChannelGesture
import dev.autobridge.settings.PlayerDpi
import dev.autobridge.settings.SplitLayout
import dev.autobridge.settings.VideoSettings
import dev.autobridge.subtitles.SubtitleHub
import dev.autobridge.subtitles.SubtitleSettings
import dev.autobridge.subtitles.SubtitleLine
import dev.autobridge.ui.AutoBridgeDesign
import dev.autobridge.ui.AutoBridgeDesign.dp
import dev.autobridge.ui.ImageLoader
import kotlin.math.abs

/**
 * The phone playback screen for everything the library and IPTV sections open.
 *
 * It drives the same shared MediaSession as the car ([MediaPlaybackClient]), so pausing here
 * pauses on the head unit and vice versa, and it enforces the same [FeaturePolicy] gate: video is
 * a parked-only feature, audio is not. When policy turns video off mid-playback the surface is
 * released and the screen says why rather than continuing to draw frames.
 *
 * Live streams report no duration, so the transport switches to a LIVE badge and hides seeking
 * instead of showing a scrubber that cannot move.
 *
 * How it behaves — background playback, picture-in-picture, the channel gesture, the queue panel,
 * the aspect ratio and the size of the controls — is read from [VideoSettings] rather than fixed
 * here. The settings that change the layout are re-read in [onResume] and rebuild the screen,
 * because the settings screen is opened from this one's header and a value that only took effect
 * on the next channel would look broken.
 */
// PlayerView's buffering, resize-mode, button-visibility and fullscreen-listener setters are
// marked @UnstableApi in Media3; the opt-in is deliberate and covers this screen only.
@androidx.annotation.OptIn(UnstableApi::class)
class PlayerActivity : Activity() {
    companion object {
        const val EXTRA_URL = "dev.autobridge.extra.PLAYER_URL"
        const val EXTRA_TITLE = "dev.autobridge.extra.PLAYER_TITLE"
        const val EXTRA_SUBTITLE = "dev.autobridge.extra.PLAYER_SUBTITLE"
        const val EXTRA_ARTWORK = "dev.autobridge.extra.PLAYER_ARTWORK"
        const val EXTRA_KIND = "dev.autobridge.extra.PLAYER_KIND"
        /** Queue for Next/Previous, e.g. every track in the folder that was opened. */
        const val EXTRA_QUEUE = "dev.autobridge.extra.PLAYER_QUEUE"
        /** Names for [EXTRA_QUEUE], in the same order, for the queue panel and the channel toast. */
        const val EXTRA_QUEUE_TITLES = "dev.autobridge.extra.PLAYER_QUEUE_TITLES"
        const val EXTRA_QUEUE_INDEX = "dev.autobridge.extra.PLAYER_QUEUE_INDEX"

        fun intent(
            context: Context,
            url: String,
            title: String,
            subtitle: String = "",
            artwork: String = "",
            video: Boolean = true,
            queue: List<String> = emptyList(),
            queueTitles: List<String> = emptyList(),
            queueIndex: Int = 0
        ): Intent = Intent(context, PlayerActivity::class.java)
            .putExtra(EXTRA_URL, url)
            .putExtra(EXTRA_TITLE, title)
            .putExtra(EXTRA_SUBTITLE, subtitle)
            .putExtra(EXTRA_ARTWORK, artwork)
            .putExtra(EXTRA_KIND, (if (video) ContentKind.VIDEO else ContentKind.AUDIO).name)
            .putStringArrayListExtra(EXTRA_QUEUE, ArrayList(queue))
            .putStringArrayListExtra(EXTRA_QUEUE_TITLES, ArrayList(queueTitles))
            .putExtra(EXTRA_QUEUE_INDEX, queueIndex)

        private const val SEEK_STEP_MS = 10_000L
        /** A failed channel is left on screen this long before "Auto next channel" moves on. */
        private const val AUTO_NEXT_DELAY_MS = 1_500L
        /** Rows in the queue panel; a country playlist would otherwise put thousands in a column. */
        private const val MAX_QUEUE_ROWS = 50
        /** The window a fling has to cross before it counts as a channel change rather than a tap. */
        private const val SWIPE_DISTANCE_DP = 48
    }

    private val url by lazy { intent.getStringExtra(EXTRA_URL)?.trim().orEmpty() }
    private val title by lazy { intent.getStringExtra(EXTRA_TITLE).orEmpty().ifBlank { "Playing" } }
    private val subtitleText by lazy { intent.getStringExtra(EXTRA_SUBTITLE).orEmpty() }
    private val artworkUrl by lazy { intent.getStringExtra(EXTRA_ARTWORK).orEmpty() }
    private val kind by lazy {
        ContentKind.entries.firstOrNull { it.name == intent.getStringExtra(EXTRA_KIND) }
            ?: ContentKind.VIDEO
    }
    private val queue by lazy { intent.getStringArrayListExtra(EXTRA_QUEUE).orEmpty() }
    private val queueTitles by lazy { intent.getStringArrayListExtra(EXTRA_QUEUE_TITLES).orEmpty() }
    private val queueIndex by lazy { intent.getIntExtra(EXTRA_QUEUE_INDEX, 0) }

    private lateinit var playback: MediaPlaybackClient
    private lateinit var bodyView: LinearLayout
    private lateinit var stage: FrameLayout
    private lateinit var video: PlayerView
    private lateinit var artworkPanel: View
    private lateinit var artworkImage: ImageView
    private lateinit var artworkGlyph: TextView
    private lateinit var notice: TextView
    private lateinit var loading: android.widget.ProgressBar
    private lateinit var subtitleView: TextView

    /** Floating "exit fullscreen" button, shown over the picture only while fullscreen. */
    private var exitFullscreenButton: TextView? = null

    /** Our own seek bar and transport; see [ownControlsVisible]. */
    private var progressRow: View? = null
    private var transportRow: View? = null
    private lateinit var toggle: TextView
    private lateinit var scrubber: SeekBar
    private lateinit var elapsed: TextView
    private lateinit var remaining: TextView
    private lateinit var liveBadge: View

    /** Everything that is not the picture, hidden while the window is a picture-in-picture one. */
    private val chrome = mutableListOf<View>()

    private var started = false

    /** Set when the session reports a playback error; cleared when playback actually starts. */
    private var failure: String? = null
    private var scrubbing = false
    private var surfaceAttached = false

    /** Fullscreen moves the stage into a window-wide overlay and goes landscape. */
    private var fullscreen = false
    private var fullscreenOverlay: FrameLayout? = null
    private var stageHomeParent: android.view.ViewGroup? = null
    private var stageHomeIndex = 0
    private var stageHomeParams: android.view.ViewGroup.LayoutParams? = null
    private var orientationBeforeFullscreen = Configuration.ORIENTATION_PORTRAIT

    /** The settings the current view tree was built for; a change rebuilds it. */
    private var builtFor: LayoutSignature? = null

    /** Guards against queueing a second advance while the first one is still pending. */
    private var autoNextPending = false

    private val accent get() = if (kind == ContentKind.AUDIO) {
        AutoBridgeDesign.ACCENT_RADIO
    } else {
        AutoBridgeDesign.ACCENT_TV
    }

    /** The settings that decide the shape of the view tree rather than what it displays. */
    private data class LayoutSignature(
        val dpi: PlayerDpi,
        val split: SplitLayout,
        val orientation: Int
    )

    private val ticker = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            render()
            ticker.postDelayed(this, 500L)
        }
    }
    private val parkingListener: (ParkingStateStore.State) -> Unit = { runOnUiThread { render() } }

    /**
     * Draws the translated subtitle line published by [SubtitleHub]. The hub calls on the main
     * thread, and the view is only touched here, so no post is needed. A blank line hides the
     * overlay rather than leaving an empty bar on the picture.
     */
    private val subtitleListener: (SubtitleLine) -> Unit = { line ->
        if (::subtitleView.isInitialized) {
            val text = line.displayText
            subtitleView.text = text
            val showing = !(line.isBlank || text.isBlank())
            subtitleView.visibility = if (showing) View.VISIBLE else View.GONE
            // While our translated line is up, PlayerView's own subtitle line would be the same
            // cue a second time; it comes back whenever ours has nothing to show.
            if (::video.isInitialized) {
                video.subtitleView?.visibility = if (showing) View.GONE else View.VISIBLE
            }
        }
    }
    private val playerListener = object : Player.Listener {
        override fun onPlayerError(error: PlaybackException) {
            runOnUiThread {
                // The notice has to survive render(), which runs twice a second from the ticker
                // and used to restore the (black, empty) video view over the message instantly.
                // A dead IPTV channel therefore looked identical to one that simply had not
                // started yet.
                failure = "Playback failed: ${error.errorCodeName}. Tap play to retry."
                render()
                scheduleAutoNext("after ${error.errorCodeName}")
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) = runOnUiThread {
            if (isPlaying) failure = null
            render()
        }

        override fun onPlaybackStateChanged(state: Int) = runOnUiThread {
            if (state == Player.STATE_ENDED) scheduleAutoNext("at the end of the item")
            render()
        }

        /** The chosen aspect ratio can only be applied once the frame's own shape is known. */
        override fun onVideoSizeChanged(videoSize: VideoSize) = runOnUiThread { applyVideoGeometry() }
    }

    /** Applies the Settings &gt; Language choice; see [AppLocale.rebase]. */
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLocale.rebase(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        releaseBack = dev.autobridge.ui.SystemBack.register(this) { goBack() }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        playback = MediaPlaybackClient(this)
        setContentView(buildUi())

        playback.connect(
            onConnected = {
                playback.player?.addListener(playerListener)
                // An empty URL means "show whatever the session is already playing", which is how
                // the now-playing bar opens this screen. Only a silent session is an error.
                if (url.isNotEmpty()) {
                    startPlayback()
                } else if (playback.currentTitle.isNullOrBlank()) {
                    showNotice("Nothing is playing.")
                } else {
                    started = true
                }
                render()
            },
            onError = { showNotice("Could not reach the player service.") }
        )
        ParkingStateStore.addListener(parkingListener)
        dev.autobridge.media.VideoSurfaceArbiter.registerPhone(phoneOutput)
    }

    /** Hands the video output to the car and takes it back when the car is done with it. */
    private val phoneOutput = object : dev.autobridge.media.VideoSurfaceArbiter.PhoneOutput {
        override fun onCarClaimed() {
            // Clear now, before the car re-asserts, so this view's binding cannot blank the car.
            detachSurface()
            if (::notice.isInitialized) render()
        }

        override fun onCarReleased() {
            if (::notice.isInitialized && !isFinishing && !isDestroyed) render()
        }
    }

    // ----- UI -----

    /** Control sizes follow the "Preferred DPI" setting; the picture itself never shrinks. */
    private fun scaled(value: Int): Int = dp((value * VideoSettings.playerDpi(this).scale).toInt())

    private fun scaledText(size: Float): Float = size * VideoSettings.playerDpi(this).scale

    private fun signature(): LayoutSignature = LayoutSignature(
        dpi = VideoSettings.playerDpi(this),
        split = VideoSettings.splitLayout(this),
        orientation = resources.configuration.orientation
    )

    private fun buildUi(): View {
        builtFor = signature()
        chrome.clear()

        val header = AutoBridgeDesign.header(
            context = this,
            title = title,
            subtitle = subtitleText.ifBlank { if (kind == ContentKind.AUDIO) "Audio" else "Video" },
            onBack = { finish() },
            actions = buildList {
                // Send-to-car and fullscreen only make sense for the picture, so audio skips both.
                if (kind == ContentKind.VIDEO) {
                    add(AutoBridgeDesign.HeaderAction("📺", { sendToCar() }))
                    add(AutoBridgeDesign.HeaderAction(if (fullscreen) "🡼" else "⛶", { toggleFullscreen() }))
                }
                // The subtitle screen is only translation settings, so a build without the
                // engines has nothing to show behind this button.
                if (SubtitleSettings.isSupported) {
                    add(AutoBridgeDesign.HeaderAction("💬", { startActivity(SubtitleSettingsActivity.intent(this@PlayerActivity)) }))
                }
                add(AutoBridgeDesign.HeaderAction("⚙", { startActivity(VideoSettingsActivity.intent(this@PlayerActivity)) }))
                // Back keeps audio, and anything the car is showing, playing on purpose; this is
                // how a channel is actually turned off.
                add(AutoBridgeDesign.HeaderAction("✕", { closePlayback() }))
            }
        )
        chrome += header

        stage = FrameLayout(this).apply {
            background = AutoBridgeDesign.surface(
                this@PlayerActivity, AutoBridgeDesign.SURFACE, 20, AutoBridgeDesign.HAIRLINE
            )
            clipToOutline = true
            // The aspect ratio is applied against the stage's measured size, which is not known
            // when the view is built and changes with rotation and with picture-in-picture.
            addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> applyVideoGeometry() }
        }
        // Media3's own PlayerView: it owns the surface lifecycle (re-binding when the surface is
        // recreated), and its controller brings the quality / audio track / subtitle track / speed
        // menu, a seek bar with the buffered range, and a buffering spinner.
        video = PlayerView(this).apply {
            visibility = View.GONE
            setBackgroundColor(0xFF000000.toInt())
            useController = true
            controllerAutoShow = true
            setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
            setShowSubtitleButton(true)
            setShowNextButton(true)
            setShowPreviousButton(true)
            setShowFastForwardButton(true)
            setShowRewindButton(true)
            setFullscreenButtonClickListener { toggleFullscreen() }
        }
        // On the PlayerView itself: it consumes touches, so a listener on the stage behind it would
        // never see a swipe.
        attachChannelGesture(video)
        artworkPanel = buildArtworkPanel()
        notice = TextView(this).apply {
            textSize = scaledText(14f)
            setTextColor(AutoBridgeDesign.TEXT_MUTED)
            gravity = Gravity.CENTER
            visibility = View.GONE
            setPadding(dp(24), dp(24), dp(24), dp(24))
        }
        subtitleView = TextView(this).apply {
            textSize = scaledText(16f)
            setTextColor(0xFFFFFFFF.toInt())
            // A translucent slab behind the text so a white line stays legible over a bright
            // frame; the picture is still visible around it rather than letterboxed by a bar.
            setBackgroundColor(0xA6000000.toInt())
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(6), dp(12), dp(6))
            visibility = View.GONE
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        }
        loading = android.widget.ProgressBar(this).apply {
            isIndeterminate = true
            indeterminateTintList = android.content.res.ColorStateList.valueOf(accent)
            visibility = View.GONE
        }
        stage.addView(artworkPanel, FrameLayout.LayoutParams(-1, -1))
        stage.addView(video, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
        stage.addView(notice, FrameLayout.LayoutParams(-1, -1))
        // Not added to the stage for video: PlayerView draws its own buffering spinner, and two
        // spinners on top of each other look broken. Kept as a view so setLoading stays harmless.
        // Exit-fullscreen affordance, pinned top-right over the picture. Hidden by default and
        // only shown while fullscreen, so there is always a visible way out without relying on the
        // system Back gesture. Tapping the picture toggles it (see attachChannelGesture's host).
        exitFullscreenButton = TextView(this).apply {
            text = "✕"
            textSize = scaledText(20f)
            setTextColor(0xFFFFFFFF.toInt())
            gravity = Gravity.CENTER
            background = AutoBridgeDesign.surface(
                this@PlayerActivity, 0x99000000.toInt(), 24
            )
            val pad = dp(10)
            setPadding(pad, pad, pad, pad)
            visibility = View.GONE
            contentDescription = getString(R.string.player_exit_fullscreen)
            setOnClickListener { toggleFullscreen() }
        }
        stage.addView(
            exitFullscreenButton,
            FrameLayout.LayoutParams(scaled(44), scaled(44), Gravity.TOP or Gravity.END).apply {
                topMargin = dp(10)
                marginEnd = dp(10)
            }
        )
        // On top of the picture, pinned to the bottom with a small inset so it does not touch the
        // rounded corners of the stage.
        stage.addView(
            subtitleView,
            FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
                bottomMargin = dp(12)
                leftMargin = dp(12)
                rightMargin = dp(12)
            }
        )

        val progress = buildProgress()
        val transport = buildTransport()
        chrome += progress
        chrome += transport
        progressRow = progress
        transportRow = transport

        val split = VideoSettings.splitLayout(this)
        val queuePanel = if (split == SplitLayout.MAIN_ONLY) null else buildQueuePanel()
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val sideBySide = split == SplitLayout.SIDE_BY_SIDE && landscape && queuePanel != null

        val body = AutoBridgeDesign.body(this)
        bodyView = body
        val stageHeight = scaled(220)
        if (sideBySide) {
            // Landscape with a queue: the picture keeps the larger share, the list takes the rest.
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(stage, LinearLayout.LayoutParams(0, stageHeight, 1.7f))
            row.addView(queuePanel, LinearLayout.LayoutParams(0, stageHeight, 1f).apply {
                marginStart = dp(10)
            })
            chrome += queuePanel
            body.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
        } else {
            body.addView(stage, LinearLayout.LayoutParams(-1, stageHeight).apply { topMargin = dp(4) })
        }
        body.addView(progress, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(18) })
        body.addView(transport, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        if (queuePanel != null && !sideBySide) {
            chrome += queuePanel
            body.addView(queuePanel, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })
        }

        return AutoBridgeDesign.page(context = this, header = header, body = body)
    }

    private fun buildArtworkPanel(): View {
        val panel = FrameLayout(this)
        artworkGlyph = TextView(this).apply {
            text = if (kind == ContentKind.AUDIO) "♪" else "▶"
            textSize = 54f
            gravity = Gravity.CENTER
            setTextColor(AutoBridgeDesign.tint(accent, 0.55f))
        }
        artworkImage = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            visibility = View.GONE
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        panel.addView(artworkGlyph, FrameLayout.LayoutParams(-1, -1))
        panel.addView(artworkImage, FrameLayout.LayoutParams(-1, -1))
        if (artworkUrl.isNotBlank()) {
            ImageLoader.load(this, artworkUrl, artworkImage) {
                artworkGlyph.visibility = View.GONE
                artworkImage.visibility = View.VISIBLE
            }
        }
        return panel
    }

    private fun buildProgress(): View {
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scrubber = SeekBar(this).apply {
            max = 1000
            progressTintList = android.content.res.ColorStateList.valueOf(accent)
            thumbTintList = android.content.res.ColorStateList.valueOf(accent)
            progressBackgroundTintList =
                android.content.res.ColorStateList.valueOf(AutoBridgeDesign.HAIRLINE)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar, value: Int, fromUser: Boolean) = Unit
                override fun onStartTrackingTouch(bar: SeekBar) {
                    scrubbing = true
                }

                override fun onStopTrackingTouch(bar: SeekBar) {
                    scrubbing = false
                    val duration = playback.player?.duration ?: 0L
                    if (duration > 0) {
                        playback.player?.seekTo(duration * bar.progress / bar.max)
                    }
                }
            })
        }
        column.addView(scrubber, LinearLayout.LayoutParams(-1, -2))

        val times = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        elapsed = TextView(this).apply {
            textSize = scaledText(12f)
            setTextColor(AutoBridgeDesign.TEXT_MUTED)
        }
        remaining = TextView(this).apply {
            textSize = scaledText(12f)
            setTextColor(AutoBridgeDesign.TEXT_MUTED)
            gravity = Gravity.END
        }
        liveBadge = TextView(this).apply {
            text = "LIVE"
            textSize = scaledText(11f)
            gravity = Gravity.CENTER
            setTextColor(AutoBridgeDesign.DANGER)
            letterSpacing = 0.12f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            background = AutoBridgeDesign.surface(
                this@PlayerActivity,
                AutoBridgeDesign.tint(AutoBridgeDesign.DANGER, 0.16f),
                10,
                AutoBridgeDesign.tint(AutoBridgeDesign.DANGER, 0.35f)
            )
            setPadding(dp(8), dp(3), dp(8), dp(3))
            visibility = View.GONE
        }
        times.addView(elapsed, LinearLayout.LayoutParams(0, -2, 1f))
        times.addView(liveBadge, LinearLayout.LayoutParams(-2, -2))
        times.addView(remaining, LinearLayout.LayoutParams(0, -2, 1f))
        column.addView(times, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(2) })
        return column
    }

    private fun buildTransport(): View {
        val row = LinearLayout(this).apply { gravity = Gravity.CENTER }
        fun control(glyph: String, size: Float, diameter: Int, action: () -> Unit) {
            row.addView(
                AutoBridgeDesign.glyphButton(this, glyph, scaledText(size)) { action() },
                LinearLayout.LayoutParams(scaled(diameter), scaled(diameter)).apply {
                    marginStart = dp(8)
                    marginEnd = dp(8)
                }
            )
        }
        control("⏮", 16f, 48) { playback.previous() }
        control("−10", 13f, 48) { seekBy(-SEEK_STEP_MS) }
        toggle = AutoBridgeDesign.glyphButton(this, "▶", scaledText(22f), filled = true) {
            // After an error the player needs a new prepare(); resume() alone would do nothing,
            // leaving the only visible control dead for the rest of the screen's life.
            if (failure != null) retry() else if (playback.isPlaying) playback.pause() else playback.resume()
            render()
        }
        row.addView(toggle, LinearLayout.LayoutParams(scaled(64), scaled(64)).apply {
            marginStart = dp(10)
            marginEnd = dp(10)
        })
        control("+10", 13f, 48) { seekBy(SEEK_STEP_MS) }
        control("⏭", 16f, 48) { playback.next() }
        return row
    }

    /**
     * The "Split screen layout" sub panel: what else is in the queue, and a tap to jump to it.
     *
     * Null when there is nothing to show — a single channel opened on its own has no queue, and an
     * empty panel under the transport would just be a gap.
     */
    private fun buildQueuePanel(): View? {
        if (queue.size <= 1) return null
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = AutoBridgeDesign.surface(this@PlayerActivity, AutoBridgeDesign.SURFACE, 16)
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }
        column.addView(TextView(this).apply {
            text = "UP NEXT"
            textSize = scaledText(11f)
            letterSpacing = 0.14f
            setTextColor(AutoBridgeDesign.TEXT_MUTED)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setPadding(dp(4), dp(2), dp(4), dp(6))
        })
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        queue.take(MAX_QUEUE_ROWS).forEachIndexed { index, item ->
            val name = queueTitles.getOrNull(index)?.takeIf { it.isNotBlank() } ?: item
            list.addView(TextView(this).apply {
                text = name
                textSize = scaledText(13f)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setTextColor(AutoBridgeDesign.TEXT)
                setPadding(dp(6), dp(9), dp(6), dp(9))
                isClickable = true
                isFocusable = true
                background = AutoBridgeDesign.tappable(
                    this@PlayerActivity, AutoBridgeDesign.SURFACE, 12, accent
                )
                setOnClickListener { jumpTo(index, name) }
            }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(4) })
        }
        // Side by side gives the panel the stage's height, so it has to scroll on its own.
        val scroller = android.widget.ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            addView(list, LinearLayout.LayoutParams(-1, -2))
        }
        column.addView(scroller, LinearLayout.LayoutParams(-1, -2))
        return column
    }

    // ----- Channel changes -----

    /** Applies the "Change channel" setting to the picture. */
    private fun attachChannelGesture(target: View) {
        val minimum = dp(SWIPE_DISTANCE_DP)
        val detector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(event: MotionEvent): Boolean = true

            override fun onFling(
                start: MotionEvent?,
                end: MotionEvent,
                velocityX: Float,
                velocityY: Float
            ): Boolean {
                val from = start ?: return false
                val dx = end.x - from.x
                val dy = end.y - from.y
                return when (VideoSettings.channelGesture(this@PlayerActivity)) {
                    ChannelGesture.SWIPE_VERTICAL ->
                        if (abs(dy) > abs(dx) && abs(dy) > minimum) {
                            // Up is "forward", the direction a channel list runs on screen.
                            changeChannel(forward = dy < 0)
                            true
                        } else false
                    ChannelGesture.SWIPE_HORIZONTAL ->
                        if (abs(dx) > abs(dy) && abs(dx) > minimum) {
                            changeChannel(forward = dx < 0)
                            true
                        } else false
                    else -> false
                }
            }

            override fun onDoubleTap(event: MotionEvent): Boolean {
                if (VideoSettings.channelGesture(this@PlayerActivity) != ChannelGesture.DOUBLE_TAP) {
                    return false
                }
                // The middle third is left alone, so a double tap meant for nothing in particular
                // does not change channel.
                val third = target.width / 3f
                return when {
                    event.x < third -> { changeChannel(forward = false); true }
                    event.x > target.width - third -> { changeChannel(forward = true); true }
                    else -> false
                }
            }
        })
        // The detector sees every event, but only a recognised channel gesture is consumed. DOWN and
        // plain taps fall through to the PlayerView, which uses them to show and hide its controls.
        target.setOnTouchListener { _, event ->
            val handled = detector.onTouchEvent(event)
            if (event.actionMasked == MotionEvent.ACTION_DOWN) false else handled
        }
    }

    private fun changeChannel(forward: Boolean) {
        val player = playback.player ?: return
        val available = if (forward) player.hasNextMediaItem() else player.hasPreviousMediaItem()
        if (!available) {
            announce(if (forward) "Last in the queue" else "First in the queue")
            return
        }
        failure = null
        // Read the target before issuing the command: a MediaController applies it on the session
        // thread, so the index here may or may not have moved by the time the toast is built.
        val target = player.currentMediaItemIndex + if (forward) 1 else -1
        if (forward) playback.next() else playback.previous()
        announce(nameAt(target))
        render()
    }

    private fun jumpTo(index: Int, name: String) {
        val player = playback.player ?: return
        if (index !in 0 until player.mediaItemCount) return
        failure = null
        player.seekTo(index, 0L)
        player.play()
        announce(name)
        render()
    }

    /**
     * Moves on from a channel that failed or ended, when "Auto next channel" is on.
     *
     * Delayed rather than immediate: a provider that is down answers instantly, and advancing with
     * no pause would walk the whole playlist in a second and leave the user on an unrelated
     * channel with no idea why.
     */
    private fun scheduleAutoNext(reason: String) {
        if (!VideoSettings.autoNextChannel(this)) return
        if (autoNextPending) return
        if (playback.player?.hasNextMediaItem() != true) return
        autoNextPending = true
        ticker.postDelayed({
            autoNextPending = false
            if (isFinishing || isDestroyed) return@postDelayed
            if (!VideoSettings.autoNextChannel(this)) return@postDelayed
            val player = playback.player ?: return@postDelayed
            if (!player.hasNextMediaItem()) return@postDelayed
            failure = null
            val target = player.currentMediaItemIndex + 1
            playback.next()
            announce("Auto next $reason: " + nameAt(target))
            render()
        }, AUTO_NEXT_DELAY_MS)
    }

    private fun nameAt(index: Int): String =
        queueTitles.getOrNull(index)?.takeIf { it.isNotBlank() }
            ?: queue.getOrNull(index)
            ?: playback.currentTitle
            ?: "Next"

    private fun announce(message: String) {
        android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_SHORT).show()
    }

    // ----- Playback -----

    private fun startPlayback() {
        if (started) return
        // render() runs while the session controller is still connecting, and MediaPlaybackClient
        // drops a play() issued before it arrives. Flipping `started` on that dropped attempt
        // suppressed the real one from onConnected, so every IPTV channel opened a player screen
        // that sat at 00:00 with nothing loaded. Wait for the controller instead.
        if (!playback.isConnected) return
        if (!FeaturePolicy.app.isAvailable(kind.requiredFeature)) {
            showNotice(FeaturePolicy.app.denialMessage(kind.requiredFeature))
            return
        }
        started = true
        failure = null
        // Video queues the list too, now that a channel list is what Next/Previous, the channel
        // gesture and "Auto next channel" all walk.
        if (queue.size > 1) {
            // If the shared session is already playing this exact queue (e.g. it was started on
            // the car and has since advanced several tracks), re-issuing setMediaItems would snap
            // the live index back to the tapped row at 00:00. Adopt the session's current position
            // instead, so the phone follows wherever the car got to.
            if (sessionAlreadyPlayingThisQueue()) return
            playback.playPlaylist(queue, queueIndex, queueTitles)
        } else {
            playback.play(url, title)
        }
    }

    /**
     * True when the live session's queue is the same ordered list of sources this screen was asked
     * to play. Compared through [MediaSourceResolver] so a bare path and its normalized `file://`
     * form match the way they are stored in the session. When true, the caller leaves the session
     * untouched and simply reflects its current track/position.
     */
    private fun sessionAlreadyPlayingThisQueue(): Boolean {
        val live = playback.currentQueueUris()
        if (live.isEmpty() || live.size != queue.size) return false
        val wanted = queue.map {
            dev.autobridge.media.MediaSourceResolver.resolve(it)?.uri ?: it.trim()
        }
        return live == wanted
    }

    /** Re-issues the original request after a failure. */
    private fun retry() {
        if (url.isEmpty()) return
        started = false
        failure = null
        notice.visibility = View.GONE
        startPlayback()
    }

    private fun seekBy(deltaMs: Long) {
        val player = playback.player ?: return
        val duration = player.duration
        val target = (player.currentPosition + deltaMs).coerceAtLeast(0L)
        player.seekTo(if (duration > 0) target.coerceAtMost(duration) else target)
    }

    /**
     * Fullscreen, the way video libraries do it: the stage is lifted out of the page and placed in
     * a black overlay that covers the whole window, then put back exactly where it was on exit.
     *
     * Resizing the stage in place cannot work here: the page body lives in a ScrollView, where
     * MATCH_PARENT and weights just wrap content, and the page root keeps its system-bar padding.
     * Moving the stage recreates the SurfaceView's surface, which the holder callback re-attaches
     * (with a re-render nudge), so the picture comes straight back.
     */
    private fun toggleFullscreen() {
        if (kind != ContentKind.VIDEO) return
        if (fullscreen) exitFullscreen() else enterFullscreen()
    }

    private fun enterFullscreen() {
        val content = findViewById<android.view.ViewGroup>(android.R.id.content) ?: return
        val parent = stage.parent as? android.view.ViewGroup ?: return
        fullscreen = true

        // Remember where the stage lived so exit can restore it exactly.
        stageHomeParent = parent
        stageHomeIndex = parent.indexOfChild(stage)
        stageHomeParams = stage.layoutParams
        orientationBeforeFullscreen = resources.configuration.orientation

        parent.removeView(stage)
        val overlay = FrameLayout(this).apply { setBackgroundColor(0xFF000000.toInt()) }
        overlay.addView(stage, FrameLayout.LayoutParams(-1, -1))
        content.addView(overlay, FrameLayout.LayoutParams(-1, -1))
        fullscreenOverlay = overlay

        stage.background = null
        stage.clipToOutline = false
        exitFullscreenButton?.visibility = View.VISIBLE

        // Draw into the camera cutout too, so landscape has no black strip on the notch side.
        window.attributes = window.attributes.apply {
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        val controller = androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
        controller.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        // Forced landscape, not SENSOR_LANDSCAPE, which stays put when the phone is upright.
        requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        stage.post { applyVideoGeometry() }
    }

    private fun exitFullscreen() {
        fullscreen = false
        val overlay = fullscreenOverlay
        overlay?.removeView(stage)
        (overlay?.parent as? android.view.ViewGroup)?.removeView(overlay)
        fullscreenOverlay = null

        val home = stageHomeParent
        if (home != null) {
            val index = stageHomeIndex.coerceIn(0, home.childCount)
            home.addView(stage, index, stageHomeParams ?: LinearLayout.LayoutParams(-1, scaled(220)))
        }
        stageHomeParent = null
        stageHomeParams = null

        stage.background = AutoBridgeDesign.surface(
            this, AutoBridgeDesign.SURFACE, 20, AutoBridgeDesign.HAIRLINE
        )
        stage.clipToOutline = true
        exitFullscreenButton?.visibility = View.GONE

        window.attributes = window.attributes.apply {
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT
        }
        androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
            .show(androidx.core.view.WindowInsetsCompat.Type.systemBars())

        // Lock back to the orientation the screen had before fullscreen. Handing it back to the
        // sensor (UNSPECIFIED) is what left the page upside down: with the phone flat on a table
        // the sensor reading is ambiguous and the system picked 180 degrees.
        requestedOrientation = if (orientationBeforeFullscreen == Configuration.ORIENTATION_LANDSCAPE) {
            android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        } else {
            android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
        stage.post { applyVideoGeometry() }
    }

    /**
     * Hands whatever is playing to the car through the shared bridge. When Android Auto is
     * connected the car opens it as native video; when it is not, the bridge keeps it pending so
     * it opens as soon as the car connects. Either way the user gets a clear status, never silence.
     */
    private fun sendToCar() {
        val target = (playback.player?.currentMediaItem?.localConfiguration?.uri?.toString())
            ?.takeIf { it.isNotBlank() }
            ?: url.takeIf { it.isNotBlank() }
            ?: run {
                announce(getString(R.string.player_send_car_nothing))
                return
            }
        val sendTitle = playback.currentTitle?.takeIf { it.isNotBlank() } ?: title
        val result = dev.autobridge.bridge.AutoBridgeSessionManager.sendToCar(
            this,
            dev.autobridge.bridge.BridgeSource(
                url = target,
                title = sendTitle,
                origin = dev.autobridge.bridge.BridgeSource.Origin.PHONE
            )
        )
        val message = when (result) {
            is dev.autobridge.bridge.AutoBridgeSessionManager.SendResult.Opened ->
                getString(R.string.player_send_car_opened)
            is dev.autobridge.bridge.AutoBridgeSessionManager.SendResult.Pending ->
                getString(R.string.player_send_car_pending)
            is dev.autobridge.bridge.AutoBridgeSessionManager.SendResult.Refused ->
                getString(R.string.player_send_car_refused)
        }
        announce(message)
    }

    /**
     * Video is parked-only, so the surface is attached and detached from policy rather than from
     * the Activity lifecycle alone. Audio keeps playing in either case.
     */
    private fun render() {
        val player = playback.player
        val videoAllowed = videoAllowed()

        val failed = failure
        if (failed != null) {
            detachSurface()
            showNotice(failed)
            setLoading(false)
        } else if (kind == ContentKind.VIDEO && !videoAllowed) {
            detachSurface()
            video.visibility = View.GONE
            artworkPanel.visibility = View.GONE
            showNotice(FeaturePolicy.app.denialMessage(Feature.VIDEO))
            setLoading(false)
        } else if (kind == ContentKind.VIDEO && dev.autobridge.media.VideoSurfaceArbiter.carActive) {
            // The car holds the one video output. Binding here would steal it and leave the head
            // unit with sound only, so the phone says where the picture went instead.
            detachSurface()
            showNotice(getString(R.string.player_on_car))
            setLoading(false)
        } else if (kind == ContentKind.VIDEO) {
            notice.visibility = View.GONE
            artworkPanel.visibility = View.GONE
            video.visibility = View.VISIBLE
            attachSurface()
            applyVideoGeometry()
            if (!started) startPlayback()
            // Loading while the stream is being fetched/buffered and no frame is on screen yet.
            // The spinner clears itself once the player reaches STATE_READY and starts rendering.
            setLoading(isVideoBuffering())
        } else {
            video.visibility = View.GONE
            artworkPanel.visibility = View.VISIBLE
            if (notice.visibility == View.VISIBLE && started) notice.visibility = View.GONE
            setLoading(false)
        }

        if (!isInPictureInPictureMode) {
            val own = if (ownControlsVisible()) View.VISIBLE else View.GONE
            progressRow?.visibility = own
            transportRow?.visibility = own
        }

        toggle.text = if (playback.isPlaying) "❚❚" else "▶"
        toggle.contentDescription = if (playback.isPlaying) "Pause" else "Play"

        // Session metadata wins over the intent's artwork once the session supplies its own.
        playback.currentArtworkData?.takeIf { it.isNotEmpty() }?.let { bytes ->
            runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }.getOrNull()?.let {
                artworkImage.setImageBitmap(it)
                artworkImage.visibility = View.VISIBLE
                artworkGlyph.visibility = View.GONE
            }
        }

        val duration = player?.duration ?: 0L
        val position = player?.currentPosition ?: 0L
        // A live HLS channel does report a duration — the length of its sliding window — so an
        // unknown duration alone is not what makes a stream live. Thai channel 3HD came through
        // as a 27-second clip with a scrub bar and no LIVE badge. The player knows the difference.
        val live = player?.isCurrentMediaItemLive == true || duration <= 0L
        liveBadge.visibility = if (live) View.VISIBLE else View.GONE
        scrubber.isEnabled = !live
        elapsed.text = if (live) "" else formatTime(position)
        remaining.text = when {
            !live -> "-" + formatTime((duration - position).coerceAtLeast(0L))
            else -> liveDelayText(player)
        }
        if (!scrubbing) {
            scrubber.progress = if (live) 0 else (scrubber.max * position / duration).toInt()
        }
    }

    private fun videoAllowed(): Boolean = kind == ContentKind.VIDEO &&
        FeaturePolicy.app.isAvailable(Feature.VIDEO) &&
        SafetyEnforcement.gateParked(ParkingStateStore.isParked)

    /**
     * For video, PlayerView's controller is the transport, so ours would only duplicate it. Ours
     * stays for audio, and for video while the car holds the picture: the PlayerView has no player
     * then, so without these the phone would have no way to pause or change channel.
     */
    private fun ownControlsVisible(): Boolean =
        kind != ContentKind.VIDEO || dev.autobridge.media.VideoSurfaceArbiter.carActive

    /** Shows or hides the buffering spinner over the stage, if the view tree is built. */
    private fun setLoading(show: Boolean) {
        if (::loading.isInitialized) {
            loading.visibility = if (show) View.VISIBLE else View.GONE
        }
    }

    /**
     * True while a tapped channel is still being fetched/buffered and no frame is up yet: the
     * player is idle/buffering, or it is ready but has not drawn the first frame. Once a frame is
     * on screen the player is playing and reports a video size, so the spinner clears on its own.
     */
    private fun isVideoBuffering(): Boolean {
        val player = playback.player ?: return started
        return when (player.playbackState) {
            Player.STATE_IDLE, Player.STATE_BUFFERING -> true
            Player.STATE_READY ->
                player.playWhenReady && !player.isPlaying
            else -> false
        }
    }

    /**
     * "Show delay": how far behind the live edge the stream is sitting.
     *
     * Only a target-latency stream (a low-latency HLS or DASH channel) reports an offset at all;
     * anything else answers [C.TIME_UNSET], and an empty line is the honest reading rather than a
     * zero that would claim the picture is live to the second.
     */
    private fun liveDelayText(player: Player?): String {
        if (!VideoSettings.showDelay(this)) return ""
        val offset = player?.currentLiveOffset ?: C.TIME_UNSET
        if (offset == C.TIME_UNSET || offset <= 0L) return ""
        return "${offset / 1000}s behind live"
    }

    /**
     * Applies the aspect-ratio setting. Auto, Fill and Stretch map straight onto PlayerView's
     * resize modes with the view filling the stage, so its controls always span the whole box.
     * The forced 16:9 / 4:3 shapes have no resize mode, so for those the view itself is sized to
     * that shape (always inside the box) and the picture is stretched into it.
     */
    private fun applyVideoGeometry() {
        if (!::video.isInitialized || !::stage.isInitialized) return
        val mode = VideoSettings.aspectRatio(this)
        val params = video.layoutParams as? FrameLayout.LayoutParams ?: return
        val (width, height) = when (mode) {
            AspectRatio.AUTO, AspectRatio.FILL, AspectRatio.STRETCH -> -1 to -1
            else -> {
                val size = playback.player?.videoSize
                val target = VideoAspect.layout(
                    mode = mode,
                    videoWidth = size?.width ?: 0,
                    videoHeight = size?.height ?: 0,
                    boxWidth = stage.width,
                    boxHeight = stage.height
                )
                target.width to target.height
            }
        }
        video.resizeMode = when (mode) {
            AspectRatio.AUTO -> AspectRatioFrameLayout.RESIZE_MODE_FIT
            AspectRatio.FILL -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            else -> AspectRatioFrameLayout.RESIZE_MODE_FILL
        }
        if (params.width == width && params.height == height) return
        params.width = width
        params.height = height
        params.gravity = Gravity.CENTER
        video.layoutParams = params
    }

    /**
     * Hands the shared player to the PlayerView, which binds its own surface (and re-binds it
     * whenever that surface is recreated). When the player is already mid-item - returning to the
     * screen, or the car giving the picture back - a zero-delta seek once the surface is up makes
     * the renderer repaint instead of leaving sound with a black frame.
     */
    private fun attachSurface() {
        val player = playback.player ?: return
        if (surfaceAttached) return
        // Never take the output while the car owns it; see VideoSurfaceArbiter.
        if (dev.autobridge.media.VideoSurfaceArbiter.carActive) return
        if (!player.isCommandAvailable(Player.COMMAND_SET_VIDEO_SURFACE)) return
        video.player = player
        surfaceAttached = true
        video.post {
            if (surfaceAttached && video.player === player &&
                player.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM) &&
                player.currentMediaItemIndex >= 0
            ) {
                player.seekTo(player.currentPosition)
            }
        }
        applyVideoGeometry()
    }

    /** Takes the player away from the PlayerView, which clears only the surface it had set. */
    private fun detachSurface() {
        if (!::video.isInitialized || !surfaceAttached) return
        video.player = null
        surfaceAttached = false
    }

    private fun showNotice(message: String) {
        notice.text = message
        notice.visibility = View.VISIBLE
        video.visibility = View.GONE
        artworkPanel.visibility = View.GONE
    }

    private fun formatTime(millis: Long): String {
        val totalSeconds = (millis / 1000).coerceAtLeast(0L)
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds)
        else "%d:%02d".format(minutes, seconds)
    }

    // ----- Picture in picture -----

    private val pictureInPictureSupported: Boolean
        get() = packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)

    /**
     * "Automatic picture in picture": leaving the screen shrinks the picture into a floating
     * window instead of stopping it.
     *
     * Gated on the same parked check as the full screen, because a video window floating over the
     * launcher while the car is moving is exactly what [SafetyEnforcement] exists to prevent.
     */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (!pictureInPictureSupported) return
        if (!VideoSettings.autoPictureInPicture(this)) return
        if (!videoAllowed() || failure != null || !playback.isPlaying) return
        if (isInPictureInPictureMode) return
        val size = playback.player?.videoSize
        val params = PictureInPictureParams.Builder()
            .setAspectRatio(pictureInPictureRatio(size))
            .build()
        // A device can refuse PiP (an OEM policy, or a window that is not eligible right now).
        // Refusing is not a crash, and the screen simply stays as it is.
        runCatching { enterPictureInPictureMode(params) }
    }

    /**
     * Android rejects a picture-in-picture ratio outside roughly 1:2.39..2.39:1, and a stream that
     * has not reported a size yet has none at all, so the window falls back to 16:9.
     */
    private fun pictureInPictureRatio(size: VideoSize?): Rational {
        val width = size?.width ?: 0
        val height = size?.height ?: 0
        if (width <= 0 || height <= 0) return Rational(16, 9)
        val ratio = width.toFloat() / height.toFloat()
        return when {
            ratio > 2.39f -> Rational(239, 100)
            ratio < 1f / 2.39f -> Rational(100, 239)
            else -> Rational(width, height)
        }
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        // The window is a thumbnail: everything that is not the picture would be unreadable, and
        // the controls belong to the system's own PiP affordances there.
        chrome.forEach { it.visibility = if (isInPictureInPictureMode) View.GONE else View.VISIBLE }
        if (::video.isInitialized) video.useController = !isInPictureInPictureMode
        // MATCH_PARENT inside the page's ScrollView would measure against the content rather than
        // the window, so the picture takes the window height the configuration itself reports.
        val params = stage.layoutParams
        params.height = if (isInPictureInPictureMode) dp(newConfig.screenHeightDp) else scaled(220)
        stage.layoutParams = params
        // The page gutter is a quarter of a thumbnail; the window is all picture in that mode.
        if (isInPictureInPictureMode) {
            bodyView.setPadding(0, 0, 0, 0)
        } else {
            bodyView.setPadding(dp(16), 0, dp(16), dp(20))
        }
        render()
    }

    override fun onResume() {
        super.onResume()
        // A layout setting changed on the settings screen this header opens; rebuild rather than
        // leave a stale shape behind. The surface is released first so the new SurfaceView can
        // take the player's output.
        // Not while fullscreen: the forced landscape changes the signature's orientation, and a
        // rebuild would orphan the stage that is currently sitting in the fullscreen overlay.
        if (!fullscreen && builtFor != signature()) {
            detachSurface()
            setContentView(buildUi())
        }
        ticker.removeCallbacks(tick)
        ticker.post(tick)
        // Registered here rather than in onCreate because onResume may have just rebuilt the view
        // tree, replacing subtitleView; addListener hands back the current line immediately, so
        // the fresh view is populated without waiting for the next cue.
        SubtitleHub.addListener(subtitleListener)
    }

    override fun onPause() {
        super.onPause()
        ticker.removeCallbacks(tick)
        SubtitleHub.removeListener(subtitleListener)
        // Video output is exclusive to a visible surface; audio deliberately keeps going so a
        // radio stream survives leaving the screen, matching the car behaviour. "Play in
        // background" extends that to video, and a picture-in-picture window is still on screen,
        // so neither case may pause here.
        // The player is shared with the car: leaving the phone screen must not pause what the car
        // is showing.
        val keepPlaying = kind != ContentKind.VIDEO ||
            dev.autobridge.media.VideoSurfaceArbiter.carActive ||
            VideoSettings.playInBackground(this) ||
            (pictureInPictureSupported && isInPictureInPictureMode)
        if (!keepPlaying) {
            playback.pause()
            detachSurface()
        }
    }

    /** Stops and unloads what is playing, on the phone and the car alike, then leaves. */
    private fun closePlayback() {
        playback.close()
        detachSurface()
        finish()
    }

    /**
     * Back leaves fullscreen first rather than the screen, which is what a user who went
     * fullscreen expects the first Back to do.
     */
    private fun goBack() {
        if (fullscreen) {
            toggleFullscreen()
            return
        }
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

    override fun onDestroy() {
        super.onDestroy()
        releaseBack()
        ticker.removeCallbacksAndMessages(null)
        ParkingStateStore.removeListener(parkingListener)
        dev.autobridge.media.VideoSurfaceArbiter.unregisterPhone(phoneOutput)
        detachSurface()
        playback.player?.removeListener(playerListener)
        playback.disconnect()
    }
}
