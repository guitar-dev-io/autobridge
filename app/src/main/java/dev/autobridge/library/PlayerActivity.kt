package dev.autobridge.library

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import dev.autobridge.core.model.Feature
import dev.autobridge.core.policy.FeaturePolicy
import dev.autobridge.entertainment.ContentKind
import dev.autobridge.media.MediaPlaybackClient
import dev.autobridge.safety.ParkingStateStore
import dev.autobridge.ui.AutoBridgeDesign
import dev.autobridge.ui.AutoBridgeDesign.dp
import dev.autobridge.ui.ImageLoader

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
 */
class PlayerActivity : Activity() {
    companion object {
        const val EXTRA_URL = "dev.autobridge.extra.PLAYER_URL"
        const val EXTRA_TITLE = "dev.autobridge.extra.PLAYER_TITLE"
        const val EXTRA_SUBTITLE = "dev.autobridge.extra.PLAYER_SUBTITLE"
        const val EXTRA_ARTWORK = "dev.autobridge.extra.PLAYER_ARTWORK"
        const val EXTRA_KIND = "dev.autobridge.extra.PLAYER_KIND"
        /** Queue for Next/Previous, e.g. every track in the folder that was opened. */
        const val EXTRA_QUEUE = "dev.autobridge.extra.PLAYER_QUEUE"
        const val EXTRA_QUEUE_INDEX = "dev.autobridge.extra.PLAYER_QUEUE_INDEX"

        fun intent(
            context: Context,
            url: String,
            title: String,
            subtitle: String = "",
            artwork: String = "",
            video: Boolean = true,
            queue: List<String> = emptyList(),
            queueIndex: Int = 0
        ): Intent = Intent(context, PlayerActivity::class.java)
            .putExtra(EXTRA_URL, url)
            .putExtra(EXTRA_TITLE, title)
            .putExtra(EXTRA_SUBTITLE, subtitle)
            .putExtra(EXTRA_ARTWORK, artwork)
            .putExtra(EXTRA_KIND, (if (video) ContentKind.VIDEO else ContentKind.AUDIO).name)
            .putStringArrayListExtra(EXTRA_QUEUE, ArrayList(queue))
            .putExtra(EXTRA_QUEUE_INDEX, queueIndex)

        private const val SEEK_STEP_MS = 10_000L
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
    private val queueIndex by lazy { intent.getIntExtra(EXTRA_QUEUE_INDEX, 0) }

    private lateinit var playback: MediaPlaybackClient
    private lateinit var stage: FrameLayout
    private lateinit var video: SurfaceView
    private lateinit var artworkPanel: View
    private lateinit var artworkImage: ImageView
    private lateinit var artworkGlyph: TextView
    private lateinit var notice: TextView
    private lateinit var toggle: TextView
    private lateinit var scrubber: SeekBar
    private lateinit var elapsed: TextView
    private lateinit var remaining: TextView
    private lateinit var liveBadge: View

    private var started = false

    /** Set when the session reports a playback error; cleared when playback actually starts. */
    private var failure: String? = null
    private var scrubbing = false
    private var surfaceAttached = false

    private val accent get() = if (kind == ContentKind.AUDIO) {
        AutoBridgeDesign.ACCENT_RADIO
    } else {
        AutoBridgeDesign.ACCENT_TV
    }

    private val ticker = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            render()
            ticker.postDelayed(this, 500L)
        }
    }
    private val parkingListener: (ParkingStateStore.State) -> Unit = { runOnUiThread { render() } }
    private val playerListener = object : Player.Listener {
        override fun onPlayerError(error: PlaybackException) {
            runOnUiThread {
                // The notice has to survive render(), which runs twice a second from the ticker
                // and used to restore the (black, empty) video view over the message instantly.
                // A dead IPTV channel therefore looked identical to one that simply had not
                // started yet.
                failure = "Playback failed: ${error.errorCodeName}. Tap play to retry."
                render()
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) = runOnUiThread {
            if (isPlaying) failure = null
            render()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
    }

    // ----- UI -----

    private fun buildUi(): View {
        val header = AutoBridgeDesign.header(
            context = this,
            title = title,
            subtitle = subtitleText.ifBlank { if (kind == ContentKind.AUDIO) "Audio" else "Video" },
            onBack = { finish() }
        )

        stage = FrameLayout(this).apply {
            background = AutoBridgeDesign.surface(
                this@PlayerActivity, AutoBridgeDesign.SURFACE, 20, AutoBridgeDesign.HAIRLINE
            )
            clipToOutline = true
        }
        video = SurfaceView(this).apply { visibility = View.GONE }
        artworkPanel = buildArtworkPanel()
        notice = TextView(this).apply {
            textSize = 14f
            setTextColor(AutoBridgeDesign.TEXT_MUTED)
            gravity = Gravity.CENTER
            visibility = View.GONE
            setPadding(dp(24), dp(24), dp(24), dp(24))
        }
        stage.addView(artworkPanel, FrameLayout.LayoutParams(-1, -1))
        stage.addView(video, FrameLayout.LayoutParams(-1, -1))
        stage.addView(notice, FrameLayout.LayoutParams(-1, -1))

        val body = AutoBridgeDesign.body(this)
        body.addView(stage, LinearLayout.LayoutParams(-1, dp(220)).apply { topMargin = dp(4) })
        body.addView(buildProgress(), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(18) })
        body.addView(buildTransport(), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })

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
            textSize = 12f
            setTextColor(AutoBridgeDesign.TEXT_MUTED)
        }
        remaining = TextView(this).apply {
            textSize = 12f
            setTextColor(AutoBridgeDesign.TEXT_MUTED)
            gravity = Gravity.END
        }
        liveBadge = TextView(this).apply {
            text = "LIVE"
            textSize = 11f
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
                AutoBridgeDesign.glyphButton(this, glyph, size) { action() },
                LinearLayout.LayoutParams(dp(diameter), dp(diameter)).apply {
                    marginStart = dp(8)
                    marginEnd = dp(8)
                }
            )
        }
        control("⏮", 16f, 48) { playback.previous() }
        control("−10", 13f, 48) { seekBy(-SEEK_STEP_MS) }
        toggle = AutoBridgeDesign.glyphButton(this, "▶", 22f, filled = true) {
            // After an error the player needs a new prepare(); resume() alone would do nothing,
            // leaving the only visible control dead for the rest of the screen's life.
            if (failure != null) retry() else if (playback.isPlaying) playback.pause() else playback.resume()
            render()
        }
        row.addView(toggle, LinearLayout.LayoutParams(dp(64), dp(64)).apply {
            marginStart = dp(10)
            marginEnd = dp(10)
        })
        control("+10", 13f, 48) { seekBy(SEEK_STEP_MS) }
        control("⏭", 16f, 48) { playback.next() }
        return row
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
        if (queue.size > 1 && kind == ContentKind.AUDIO) {
            playback.playPlaylist(queue, queueIndex)
        } else {
            playback.play(url, title)
        }
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
     * Video is parked-only, so the surface is attached and detached from policy rather than from
     * the Activity lifecycle alone. Audio keeps playing in either case.
     */
    private fun render() {
        val player = playback.player
        val videoAllowed = kind == ContentKind.VIDEO &&
            FeaturePolicy.app.isAvailable(Feature.VIDEO) &&
            ParkingStateStore.isParked

        val failed = failure
        if (failed != null) {
            detachSurface()
            showNotice(failed)
        } else if (kind == ContentKind.VIDEO && !videoAllowed) {
            detachSurface()
            video.visibility = View.GONE
            artworkPanel.visibility = View.GONE
            showNotice(FeaturePolicy.app.denialMessage(Feature.VIDEO))
        } else if (kind == ContentKind.VIDEO) {
            notice.visibility = View.GONE
            artworkPanel.visibility = View.GONE
            video.visibility = View.VISIBLE
            if (!surfaceAttached && player != null) {
                player.setVideoSurfaceView(video)
                surfaceAttached = true
            }
            if (!started) startPlayback()
        } else {
            video.visibility = View.GONE
            artworkPanel.visibility = View.VISIBLE
            if (notice.visibility == View.VISIBLE && started) notice.visibility = View.GONE
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
        remaining.text = if (live) "" else "-" + formatTime((duration - position).coerceAtLeast(0L))
        if (!scrubbing) {
            scrubber.progress = if (live) 0 else (scrubber.max * position / duration).toInt()
        }
    }

    private fun detachSurface() {
        val player = playback.player ?: return
        if (!surfaceAttached) return
        if (player.isCommandAvailable(Player.COMMAND_SET_VIDEO_SURFACE)) {
            player.clearVideoSurfaceView(video)
        }
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

    override fun onResume() {
        super.onResume()
        ticker.removeCallbacks(tick)
        ticker.post(tick)
    }

    override fun onPause() {
        super.onPause()
        ticker.removeCallbacks(tick)
        // Video output is exclusive to a visible surface; audio deliberately keeps going so a
        // radio stream survives leaving the screen, matching the car behaviour.
        if (kind == ContentKind.VIDEO) {
            playback.pause()
            detachSurface()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        ticker.removeCallbacks(tick)
        ParkingStateStore.removeListener(parkingListener)
        detachSurface()
        playback.player?.removeListener(playerListener)
        playback.disconnect()
    }
}
