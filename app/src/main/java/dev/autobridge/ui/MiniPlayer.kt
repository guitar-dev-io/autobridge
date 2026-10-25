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
 *
 * [radioStyle] turns it into the radio design's bar on the Radio page: warm amber card, a big
 * cover, the station over the song and artist, a large amber play button and a favourite star.
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
            context, AutoBridgeDesign.SURFACE_RAISED, dev.autobridge.design.ThemeHolder.palette.corners.card, AutoBridgeDesign.HAIRLINE
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
        typeface = AppFonts.medium()
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }
    private val subtitle = TextView(context).apply {
        textSize = 12f
        setTextColor(AutoBridgeDesign.TEXT_MUTED)
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }
    /** Radio style: the "on air" bars and the station name above the song. */
    private val stationName = TextView(context).apply {
        textSize = 13f
        setTextColor(WARM_TEXT)
        typeface = AppFonts.bold()
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }
    private val stationLine = LinearLayout(context).apply {
        gravity = Gravity.CENTER_VERTICAL
        visibility = View.GONE
        // Bounces while sound is actually playing; rests while paused or still loading.
        val bars = EqBarsView(context, AMBER, barCount = 3) { playback.isPlaying }
        addView(bars, LinearLayout.LayoutParams(context.dp(14), context.dp(12)).apply { marginEnd = context.dp(6) })
        addView(stationName, LinearLayout.LayoutParams(0, -2, 1f))
    }
    private val favorite = AutoBridgeDesign.glyphButton(context, "☆", 20f, tint = WARM_TEXT) {
        currentStation()?.let { dev.autobridge.radio.RadioRepository.RadioHistory.toggleFavorite(context, it) }
        render()
    }.apply {
        visibility = View.GONE
        contentDescription = context.getString(dev.autobridge.R.string.radio_favorite_add)
    }
    private lateinit var nextButton: View

    /** The radio design's bar, for the Radio page; set before or after [start]. */
    var radioStyle: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            applyStyle()
            render()
        }

    private val toggle = AutoBridgeDesign.glyphButton(context, PLAY, 16f, filled = true) {
        val wasPlaying = playback.isPlayRequested
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
            onTick?.invoke()
            ticker.postDelayed(this, 1_000L)
        }
    }

    init {
        val row = view as LinearLayout
        artworkFrame.addView(glyph, FrameLayout.LayoutParams(-1, -1))
        artworkFrame.addView(artwork, FrameLayout.LayoutParams(-1, -1))
        row.addView(artworkFrame, LinearLayout.LayoutParams(context.dp(44), context.dp(44)))

        val text = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(context.dp(12), 0, context.dp(8), 0)
        }
        text.addView(stationLine)
        text.addView(title)
        text.addView(subtitle)
        row.addView(text, LinearLayout.LayoutParams(0, -2, 1f))
        nextButton = AutoBridgeDesign.glyphButton(context, "⏭", 15f) { playback.next() }
        row.addView(nextButton, LinearLayout.LayoutParams(context.dp(38), context.dp(38)))
        row.addView(toggle, LinearLayout.LayoutParams(context.dp(40), context.dp(40)))
        // A round 44 dp button like the close one beside it, not the tall pill it was.
        row.addView(favorite, LinearLayout.LayoutParams(context.dp(44), context.dp(44)))
        // Pausing leaves the channel loaded and this bar up; closing unloads it, and the bar hides.
        row.addView(
            AutoBridgeDesign.glyphButton(context, "✕", 15f) {
                playback.close()
                render()
            }.apply { contentDescription = context.getString(dev.autobridge.R.string.player_close) },
            LinearLayout.LayoutParams(context.dp(38), context.dp(38))
        )
        applyStyle()
    }

    /** Switches between the shared bar and the radio design's warm one. */
    private fun applyStyle() {
        val row = view as LinearLayout
        if (radioStyle) {
            row.background = AutoBridgeDesign.surface(context, WARM_CARD, 22, WARM_EDGE)
            row.setPadding(context.dp(10), context.dp(10), context.dp(6), context.dp(10))
            artworkFrame.layoutParams = LinearLayout.LayoutParams(context.dp(64), context.dp(64))
            artworkFrame.background = AutoBridgeDesign.surface(context, AutoBridgeDesign.tint(AMBER, 0.16f), 14, android.graphics.Color.TRANSPARENT)
            artworkFrame.clipToOutline = true
            glyph.setTextColor(AMBER)
            title.textSize = 16f
            title.typeface = AppFonts.bold()
            subtitle.textSize = 13f
            subtitle.setTextColor(SOFT_TEXT)
            stationLine.visibility = View.VISIBLE
            nextButton.visibility = View.GONE
            favorite.visibility = View.VISIBLE
            toggle.layoutParams = LinearLayout.LayoutParams(context.dp(56), context.dp(56))
            toggle.background = AutoBridgeDesign.surface(context, WARM_TEXT, 28, android.graphics.Color.TRANSPARENT)
            toggle.setTextColor(ON_AMBER)
        } else {
            row.background = AutoBridgeDesign.surface(context, AutoBridgeDesign.SURFACE_RAISED, cardRadius(), AutoBridgeDesign.HAIRLINE)
            row.setPadding(context.dp(10), context.dp(8), context.dp(8), context.dp(8))
            artworkFrame.layoutParams = LinearLayout.LayoutParams(context.dp(44), context.dp(44))
            artworkFrame.background = AutoBridgeDesign.surface(
                context, AutoBridgeDesign.tint(AutoBridgeDesign.ACCENT_SOFT, 0.16f), 12,
                AutoBridgeDesign.tint(AutoBridgeDesign.ACCENT_SOFT, 0.3f)
            )
            glyph.setTextColor(AutoBridgeDesign.ACCENT_SOFT)
            title.textSize = 14f
            title.typeface = AppFonts.medium()
            subtitle.textSize = 12f
            subtitle.setTextColor(AutoBridgeDesign.TEXT_MUTED)
            stationLine.visibility = View.GONE
            nextButton.visibility = View.VISIBLE
            favorite.visibility = View.GONE
            toggle.layoutParams = LinearLayout.LayoutParams(context.dp(48), context.dp(48))
            toggle.background = AutoBridgeDesign.surface(context, palette.accent, 24, android.graphics.Color.TRANSPARENT)
            toggle.setTextColor(palette.onAccent)
        }
    }

    private val palette get() = dev.autobridge.design.ThemeHolder.palette

    private fun cardRadius(): Int = palette.corners.card

    /** The station playing now as the radio store knows stations, for the favourite star. */
    private fun currentStation(): dev.autobridge.radio.RadioStation? {
        val url = playback.currentUri?.takeIf { it.isNotBlank() } ?: return null
        val name = playback.currentItemTitle ?: playback.currentTitle ?: return null
        return dev.autobridge.radio.RadioStation(
            dev.autobridge.radio.RadioSourceType.INTERNET_RADIO, id = url, title = name, streamUrl = url,
            logo = playback.currentArtworkUri.orEmpty()
        )
    }

    /** Starts the refresh tick. Callers pair this with [stop] in their lifecycle callbacks. */
    fun start() {
        ticker.removeCallbacks(tick)
        ticker.post(tick)
    }

    fun stop() = ticker.removeCallbacks(tick)

    /**
     * A screen that shows what is loaded its own way: while this answers true the bar stays
     * hidden. Home does this for radio, whose card under the connection is then the player.
     */
    var hideWhen: (() -> Boolean)? = null

    /**
     * Home only (the one screen that sets [hideWhen]): the Dashboard layout's hero card is the
     * player there, so the bar would say the same thing twice.
     */
    private fun hiddenByHomeLayout(): Boolean =
        hideWhen != null && !PhoneHomeLayout.showsMiniPlayer(dev.autobridge.settings.AppearanceStore.current().homeLayout)

    /** Called on every one-second tick, so a screen can refresh its own now-playing view with it. */
    var onTick: (() -> Unit)? = null

    private fun render() {
        val name = playback.currentTitle
        if (!playback.isConnected || name.isNullOrBlank() || hideWhen?.invoke() == true || hiddenByHomeLayout()) {
            view.visibility = View.GONE
            return
        }
        view.visibility = View.VISIBLE
        // A radio stream that names its song shows the song, with the artist and the station
        // under it; anything else reads exactly as before (its title, then its artist).
        val now = dev.autobridge.radio.RadioNowPlaying.of(playback.currentItemTitle, name, playback.currentArtist)
        if (radioStyle) {
            stationName.text = now.station ?: playback.currentItemTitle ?: name
            title.text = now.song ?: name
            subtitle.text = now.artist ?: when {
                playback.isPlaying -> "Playing"
                playback.isPlayRequested -> "Loading…"
                else -> "Paused"
            }
            val fav = playback.currentUri?.let { dev.autobridge.iptv.IptvHistoryStore.isFavorite(context, it) } == true
            favorite.text = if (fav) "★" else "☆"
        } else title.text = now.song ?: name
        if (!radioStyle) subtitle.text = listOfNotNull(
            now.artist,
            now.station.takeIf { now.song != null },
            when {
                playback.isPlaying -> "Playing"
                playback.isPlayRequested -> "Loading…"
                else -> "Paused"
            }
        ).joinToString(" • ")
        showPlaying(playback.isPlayRequested)

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
        /** The radio section's hue, as Home's icon chips carry it. */
        val AMBER: Int get() = PhoneHomeLayout.Item.RADIO.hue(dev.autobridge.design.ThemeHolder.palette.isLight)
        const val PLAY = "▶"
        const val PAUSE = "❚❚"
        // The radio bar's controls follow the theme's accent; only the bars and the artwork chip keep
        // the radio section's hue (AMBER). The names stay from the old warm design.
        val ON_AMBER: Int get() = dev.autobridge.design.ThemeHolder.palette.onAccent
        val WARM_CARD: Int get() = dev.autobridge.design.ThemeHolder.palette.raised
        val WARM_EDGE: Int get() = dev.autobridge.design.ThemeHolder.palette.hairline
        val WARM_TEXT: Int get() = dev.autobridge.design.ThemeHolder.palette.accent
        val SOFT_TEXT: Int get() = dev.autobridge.design.ThemeHolder.palette.textSecondary
    }
}
