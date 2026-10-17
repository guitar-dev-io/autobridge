package dev.autobridge.ui

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import dev.autobridge.media.MediaPlaybackClient
import dev.autobridge.ui.AutoBridgeDesign.dp

/**
 * The bottom now-playing bar shared by the home screen and the library pages.
 *
 * It reads the live MediaSession through [MediaPlaybackClient] rather than holding state of its
 * own, so whatever started the audio — a radio channel, a local track, or the car — is what the
 * bar shows. It hides itself whenever the session has nothing loaded, and polls on a one-second
 * tick, which is cheap and avoids wiring a listener through every screen that embeds it.
 */
class MiniPlayer(
    private val context: Context,
    private val playback: MediaPlaybackClient,
    private val onOpen: () -> Unit
) {
    val view: View = LinearLayout(context).apply {
        gravity = Gravity.CENTER_VERTICAL
        visibility = View.GONE
        background = AutoBridgeDesign.surface(
            context, AutoBridgeDesign.SURFACE_RAISED, 18, AutoBridgeDesign.HAIRLINE
        )
        isClickable = true
        isFocusable = true
        setPadding(context.dp(10), context.dp(10), context.dp(8), context.dp(10))
        setOnClickListener { onOpen() }
    }

    private val artworkFrame = FrameLayout(context).apply {
        background = AutoBridgeDesign.surface(
            context,
            AutoBridgeDesign.tint(AutoBridgeDesign.ACCENT_SOFT, 0.16f),
            12,
            AutoBridgeDesign.tint(AutoBridgeDesign.ACCENT_SOFT, 0.3f)
        )
    }
    private val artwork = ImageView(context).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private val glyph = TextView(context).apply {
        text = "♪"
        textSize = 18f
        gravity = Gravity.CENTER
        setTextColor(AutoBridgeDesign.ACCENT_SOFT)
    }
    private val title = TextView(context).apply {
        textSize = 14f
        setTextColor(AutoBridgeDesign.TEXT)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }
    private val subtitle = TextView(context).apply {
        textSize = 12f
        setTextColor(AutoBridgeDesign.TEXT_MUTED)
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }
    private val toggle = AutoBridgeDesign.glyphButton(context, PLAY, 16f, filled = true) {
        val wasPlaying = playback.isPlaying
        if (wasPlaying) playback.pause() else playback.resume()
        // Reflect the tap immediately: the session still reports the old state for a moment, so
        // the glyph shows what was asked for, and the next tick confirms it against the session.
        render()
        showPlaying(!wasPlaying)
    }

    private fun showPlaying(playing: Boolean) {
        toggle.text = if (playing) PAUSE else PLAY
        toggle.contentDescription = if (playing) "Pause" else "Play"
    }

    private val ticker = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            render()
            ticker.postDelayed(this, 1_000L)
        }
    }

    init {
        val row = view as LinearLayout
        artworkFrame.addView(glyph, FrameLayout.LayoutParams(-1, -1))
        artworkFrame.addView(artwork, FrameLayout.LayoutParams(-1, -1))
        row.addView(artworkFrame, LinearLayout.LayoutParams(context.dp(40), context.dp(40)))

        val text = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(context.dp(12), 0, context.dp(8), 0)
        }
        text.addView(title)
        text.addView(subtitle)
        row.addView(text, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(
            AutoBridgeDesign.glyphButton(context, "⏭", 15f) { playback.next() },
            LinearLayout.LayoutParams(context.dp(38), context.dp(38))
        )
        row.addView(toggle, LinearLayout.LayoutParams(context.dp(40), context.dp(40)))
        // Pausing leaves the channel loaded and this bar up; closing unloads it, and the bar hides.
        row.addView(
            AutoBridgeDesign.glyphButton(context, "✕", 15f) {
                playback.close()
                render()
            }.apply { contentDescription = context.getString(dev.autobridge.R.string.player_close) },
            LinearLayout.LayoutParams(context.dp(38), context.dp(38))
        )
    }

    /** Starts the refresh tick. Callers pair this with [stop] in their lifecycle callbacks. */
    fun start() {
        ticker.removeCallbacks(tick)
        ticker.post(tick)
    }

    fun stop() = ticker.removeCallbacks(tick)

    private fun render() {
        val name = playback.currentTitle
        if (!playback.isConnected || name.isNullOrBlank()) {
            view.visibility = View.GONE
            return
        }
        view.visibility = View.VISIBLE
        // A radio stream that names its song shows the song, with the artist and the station
        // under it; anything else reads exactly as before (its title, then its artist).
        val now = dev.autobridge.radio.RadioNowPlaying.of(playback.currentItemTitle, name, playback.currentArtist)
        title.text = now.song ?: name
        subtitle.text = listOfNotNull(
            now.artist,
            now.station.takeIf { now.song != null },
            if (playback.isPlaying) "Playing" else "Paused"
        ).joinToString(" • ")
        showPlaying(playback.isPlaying)

        val art = playback.currentArtworkData
        if (art != null && art.isNotEmpty()) {
            val bitmap = runCatching { BitmapFactory.decodeByteArray(art, 0, art.size) }.getOrNull()
            if (bitmap != null) {
                artwork.setImageBitmap(bitmap)
                artwork.visibility = View.VISIBLE
                glyph.visibility = View.GONE
                return
            }
        }
        // No embedded art: the address the item was queued with (a station logo), fetched once.
        val address = playback.currentArtworkUri
        if (address != null) {
            if (address != loadedArtwork) {
                loadedArtwork = address
                artwork.setImageDrawable(null)
                artwork.visibility = View.GONE
                glyph.visibility = View.VISIBLE
                ImageLoader.load(context, address, artwork) {
                    artwork.visibility = View.VISIBLE
                    glyph.visibility = View.GONE
                }
            }
            return
        }
        if (loadedArtwork != null) {
            // The station changed to one without a logo: a fetch for the old one must not land.
            loadedArtwork = null
            artwork.setImageDrawable(null)
            artwork.setTag(dev.autobridge.R.id.autobridge_image_tag, null)
        }
        artwork.visibility = View.GONE
        glyph.visibility = View.VISIBLE
    }

    /** The artwork address last asked of [ImageLoader], so the one-second tick does not refetch it. */
    private var loadedArtwork: String? = null

    private companion object {
        const val PLAY = "▶"
        const val PAUSE = "❚❚"
    }
}
