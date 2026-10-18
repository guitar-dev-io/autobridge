package dev.autobridge.library

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import dev.autobridge.R
import dev.autobridge.i18n.AppLocale
import dev.autobridge.iptv.IptvHistoryStore
import dev.autobridge.media.MediaPlaybackClient
import dev.autobridge.radio.RadioNowPlaying
import dev.autobridge.radio.RadioRepository
import dev.autobridge.radio.RadioSleepTimer
import dev.autobridge.radio.RadioSourceType
import dev.autobridge.radio.RadioStation
import dev.autobridge.ui.AutoBridgeDesign
import dev.autobridge.ui.AutoBridgeDesign.dp
import dev.autobridge.ui.ImageLoader
import dev.autobridge.ui.SystemBack

/**
 * The radio player on the phone: the station's logo large, "Live", the song and artist when the
 * stream names them and the station either way, previous / play-pause / next, and the three
 * things a listener reaches for — the equalizer ([AudioEffectsSheet]), the favourite, all stations.
 *
 * It holds no playback state of its own: everything is read from the shared session through
 * [MediaPlaybackClient] on a one-second tick, the same way [dev.autobridge.ui.MiniPlayer] does, so
 * a change made on the car shows here too. Previous and next step through the queue the station
 * was started with (its category, or the favourites).
 */
class RadioPlayerActivity : Activity() {
    companion object {
        fun intent(context: Context): Intent = Intent(context, RadioPlayerActivity::class.java)

        // The radio design's warm palette.
        private val LIVE_RED = 0xFFFF5C5C.toInt()
        private val WARM_SURFACE = 0xFF2A2116.toInt()
        private val WARM_TEXT = 0xFFFFC985.toInt()
        private val WARM_INK = 0xFF2A1A05.toInt()
        private val SOFT_TEXT = 0xFFC9CDD3.toInt()
        private val HINT_TEXT = 0xFF8A919B.toInt()

        /** Never a real address: forces the logo to be placed again on the next update. */
        private const val LOGO_UNSET = "\u0000"
    }

    private lateinit var playback: MediaPlaybackClient
    private var releaseBack: () -> Unit = {}

    private lateinit var cover: FrameLayout
    private lateinit var logo: ImageView
    private lateinit var badge: ImageView
    private lateinit var initial: TextView
    private lateinit var song: TextView
    private lateinit var artist: TextView
    private lateinit var stationLine: TextView
    private lateinit var playToggle: TextView
    private lateinit var favorite: ActionButton
    private lateinit var sleep: FrameLayout
    private lateinit var sleepIcon: View
    private lateinit var sleepMinutes: TextView
    private var shownLogo: String? = null
    /** The cover bytes last looked at, and the ones actually shown (null when they did not decode). */
    private var seenCover: ByteArray? = null
    private var shownCover: ByteArray? = null

    private val ticker = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            update()
            ticker.postDelayed(this, 1_000L)
        }
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLocale.rebase(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        releaseBack = SystemBack.register(this) { SystemBack.finishFromBack(this) }
        playback = MediaPlaybackClient(this)
        playback.connect(onConnected = { update() })
        setContentView(build())
    }

    override fun onResume() {
        super.onResume()
        ticker.removeCallbacks(tick)
        ticker.post(tick)
    }

    override fun onPause() {
        super.onPause()
        ticker.removeCallbacks(tick)
    }

    override fun onDestroy() {
        super.onDestroy()
        releaseBack()
        ticker.removeCallbacks(tick)
        playback.disconnect()
    }

    private fun build(): View {
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(20), dp(8), dp(20), dp(28))
        }

        // The cover large with the station's logo as a badge on its corner. A stream without a
        // cover of its own puts the logo in the big square, and the badge stays away; until the
        // logo arrives the square shows the station's frequency or initials on its colour.
        val art = FrameLayout(this).apply { clipChildren = false; clipToPadding = false }
        cover = FrameLayout(this).apply {
            background = AutoBridgeDesign.surface(this@RadioPlayerActivity, AutoBridgeDesign.tint(AutoBridgeDesign.ACCENT_RADIO, 0.14f), 40, Color.TRANSPARENT)
            clipToOutline = true
            elevation = dp(18).toFloat()
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                outlineSpotShadowColor = AutoBridgeDesign.ACCENT_RADIO
                outlineAmbientShadowColor = AutoBridgeDesign.ACCENT_RADIO
            }
        }
        initial = TextView(this).apply {
            textSize = 64f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
        }
        logo = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            visibility = View.GONE
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        cover.addView(initial, FrameLayout.LayoutParams(-1, -1))
        cover.addView(logo, FrameLayout.LayoutParams(-1, -1))
        art.addView(cover, FrameLayout.LayoutParams(dp(248), dp(248)).apply { setMargins(dp(10), dp(10), dp(10), dp(10)) })
        badge = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = AutoBridgeDesign.surface(this@RadioPlayerActivity, AutoBridgeDesign.INK, 18, Color.TRANSPARENT)
            setPadding(dp(4), dp(4), dp(4), dp(4))
            clipToOutline = true
            elevation = dp(20).toFloat()
            visibility = View.GONE
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        art.addView(badge, FrameLayout.LayoutParams(dp(80), dp(80), Gravity.END or Gravity.BOTTOM))
        body.addView(art, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(8) })

        // "● Live": the red dot says on air, the pill says radio.
        body.addView(TextView(this).apply {
            text = android.text.SpannableString("●  " + getString(R.string.radio_live)).apply {
                setSpan(android.text.style.ForegroundColorSpan(LIVE_RED), 0, 1, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                setSpan(android.text.style.RelativeSizeSpan(0.8f), 0, 1, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            textSize = 13f
            setTextColor(WARM_TEXT)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            background = AutoBridgeDesign.surface(this@RadioPlayerActivity, WARM_SURFACE, 12, Color.TRANSPARENT)
            setPadding(dp(12), dp(4), dp(12), dp(4))
        }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(18) })

        song = centred(26f, AutoBridgeDesign.TEXT, bold = true, lines = 2)
        artist = centred(16f, SOFT_TEXT, bold = false, lines = 1)
        stationLine = centred(14f, AutoBridgeDesign.TEXT_MUTED, bold = false, lines = 1)
        body.addView(song, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        body.addView(artist, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
        body.addView(stationLine, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })

        // Previous, play/pause, next, spread across the width.
        val transport = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        transport.addView(
            AutoBridgeDesign.glyphButton(this, "⏮", 22f) { playback.previous() }
                .apply { contentDescription = getString(R.string.radio_previous_station) },
            LinearLayout.LayoutParams(dp(64), dp(64))
        )
        transport.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        playToggle = TextView(this).apply {
            textSize = 30f
            gravity = Gravity.CENTER
            setTextColor(WARM_INK)
            isClickable = true
            isFocusable = true
            background = RippleDrawable(
                ColorStateList.valueOf(AutoBridgeDesign.tint(Color.WHITE, 0.3f)),
                GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(AutoBridgeDesign.ACCENT_RADIO) },
                null
            )
            setOnClickListener {
                // The session answers a beat later, so the button shows what was asked for now
                // rather than the state it is leaving; the next tick confirms it.
                val wasPlaying = playback.isPlayRequested
                if (wasPlaying) playback.pause() else playback.resume()
                update()
                showPlaying(!wasPlaying)
            }
        }
        transport.addView(playToggle, LinearLayout.LayoutParams(dp(92), dp(92)))
        transport.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        transport.addView(
            AutoBridgeDesign.glyphButton(this, "⏭", 22f) { playback.next() }
                .apply { contentDescription = getString(R.string.radio_next_station) },
            LinearLayout.LayoutParams(dp(64), dp(64))
        )
        body.addView(transport, LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(28)
            leftMargin = dp(6)
            rightMargin = dp(6)
        })
        body.addView(centred(13f, HINT_TEXT, bold = false, lines = 2).apply {
            text = getString(R.string.radio_player_step_hint)
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })

        // EQ, favourite, all stations, the car: one row, icon over label.
        favorite = action(R.drawable.ic_radio_star, getString(R.string.radio_favorite_add)) { toggleFavorite() }
        val actions = LinearLayout(this)
        listOf(
            action(R.drawable.ic_radio_eq, getString(R.string.eq_short)) { AudioEffectsSheet(this).show() },
            favorite,
            action(R.drawable.ic_radio_list, getString(R.string.radio_all_stations)) {
                startActivity(LibraryActivity.intent(this, LibraryActivity.Section.RADIO))
                finish()
            },
            action(R.drawable.ic_radio_car_screen, getString(R.string.radio_play_on_car)) { sendToCar() }
        ).forEachIndexed { index, button ->
            actions.addView(button.view, LinearLayout.LayoutParams(0, dp(64), 1f).apply {
                if (index > 0) marginStart = dp(8)
            })
        }
        body.addView(actions, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(22) })

        return AutoBridgeDesign.page(this, header = header(), body = body)
    }

    /** Collapse on the left, "Now playing · Radio" in the middle, the sleep timer on the right. */
    private fun header(): View {
        val row = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(6))
        }
        row.addView(roundIcon(R.drawable.ic_radio_collapse, getString(R.string.radio_player_collapse)) { finish() },
            LinearLayout.LayoutParams(dp(44), dp(44)))
        row.addView(TextView(this).apply {
            text = getString(R.string.radio_player_title)
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(SOFT_TEXT)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, -2, 1f))
        sleepIcon = roundIcon(R.drawable.ic_radio_timer, getString(R.string.radio_sleep_timer)) { chooseSleepTimer() }
        sleepMinutes = TextView(this).apply {
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(WARM_INK)
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            background = AutoBridgeDesign.surface(this@RadioPlayerActivity, AutoBridgeDesign.ACCENT_RADIO, 22, Color.TRANSPARENT)
            isClickable = true
            isFocusable = true
            visibility = View.GONE
            setOnClickListener { chooseSleepTimer() }
        }
        sleep = FrameLayout(this).apply {
            addView(sleepIcon, FrameLayout.LayoutParams(-1, -1))
            addView(sleepMinutes, FrameLayout.LayoutParams(-1, -1))
        }
        row.addView(sleep, LinearLayout.LayoutParams(dp(44), dp(44)))
        return row
    }

    private fun roundIcon(icon: Int, description: String, onClick: () -> Unit): View = ImageView(this).apply {
        setImageResource(icon)
        scaleType = ImageView.ScaleType.CENTER
        contentDescription = description
        isClickable = true
        isFocusable = true
        background = RippleDrawable(
            ColorStateList.valueOf(AutoBridgeDesign.tint(Color.WHITE, 0.2f)),
            GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(AutoBridgeDesign.SURFACE) },
            null
        )
        setOnClickListener { onClick() }
    }

    private fun centred(size: Float, color: Int, bold: Boolean, lines: Int): TextView = TextView(this).apply {
        textSize = size
        gravity = Gravity.CENTER
        setTextColor(color)
        if (bold) typeface = Typeface.create("sans-serif", Typeface.BOLD)
        maxLines = lines
        ellipsize = TextUtils.TruncateAt.END
    }

    /** One of the four buttons under the transport: an icon over a short label. */
    private fun action(icon: Int, label: String, onClick: () -> Unit): ActionButton {
        val image = ImageView(this).apply {
            setImageResource(icon)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val text = TextView(this).apply {
            this.text = label
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(AutoBridgeDesign.TEXT)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            contentDescription = label
            background = AutoBridgeDesign.tappable(this@RadioPlayerActivity, AutoBridgeDesign.SURFACE, 16, AutoBridgeDesign.ACCENT_RADIO, Color.TRANSPARENT)
            setOnClickListener { onClick() }
            addView(image, LinearLayout.LayoutParams(dp(20), dp(20)))
            addView(text, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
        }
        return ActionButton(column, image, text)
    }

    private class ActionButton(val view: LinearLayout, val icon: ImageView, val label: TextView)

    /** The station playing now as the radio store knows stations, for the favourite toggle. */
    private fun currentStation(): RadioStation? {
        val url = playback.currentUri?.takeIf { it.isNotBlank() } ?: return null
        val name = playback.currentItemTitle ?: playback.currentTitle ?: return null
        return RadioStation(
            RadioSourceType.INTERNET_RADIO, id = url, title = name, streamUrl = url,
            logo = playback.currentArtworkUri.orEmpty()
        )
    }

    /**
     * Hands the station to the car the way the video player does, through the bridge: it opens
     * there now when the car is connected and waits for it otherwise.
     */
    private fun sendToCar() {
        val url = playback.currentUri
        if (url.isNullOrBlank()) {
            toast(getString(R.string.player_send_car_nothing))
            return
        }
        val title = playback.currentItemTitle ?: playback.currentTitle.orEmpty()
        val result = dev.autobridge.bridge.AutoBridgeSessionManager.sendToCar(
            this,
            dev.autobridge.bridge.BridgeSource(
                url = url,
                title = title,
                origin = dev.autobridge.bridge.BridgeSource.Origin.PHONE
            )
        )
        toast(
            getString(
                when (result) {
                    is dev.autobridge.bridge.AutoBridgeSessionManager.SendResult.Opened -> R.string.player_send_car_opened
                    is dev.autobridge.bridge.AutoBridgeSessionManager.SendResult.Pending -> R.string.player_send_car_pending
                    is dev.autobridge.bridge.AutoBridgeSessionManager.SendResult.Refused -> R.string.player_send_car_refused
                }
            )
        )
    }

    /** The sleep timer's choices, with "turn off" first while one is running. */
    private fun chooseSleepTimer() {
        val minutes = RadioSleepTimer.OPTIONS_MINUTES
        val labels = buildList {
            if (RadioSleepTimer.isSet) add(getString(R.string.radio_sleep_off))
            minutes.forEach { add(getString(R.string.radio_sleep_minutes, it)) }
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.radio_sleep_timer)
            .setItems(labels.toTypedArray()) { _, which ->
                val offset = if (RadioSleepTimer.isSet) 1 else 0
                if (which < offset) {
                    RadioSleepTimer.cancel()
                } else {
                    val chosen = minutes[which - offset]
                    RadioSleepTimer.start(this, chosen)
                    toast(getString(R.string.radio_sleep_set, chosen))
                }
                update()
            }
            .show()
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    private fun toggleFavorite() {
        val station = currentStation() ?: return
        RadioRepository.RadioHistory.toggleFavorite(this, station)
        update()
    }

    private fun showPlaying(playing: Boolean) {
        playToggle.text = if (playing) "❚❚" else "▶"
        playToggle.contentDescription = getString(if (playing) R.string.radio_pause else R.string.radio_play)
    }

    private fun update() {
        if (!::song.isInitialized) return
        val now = RadioNowPlaying.of(playback.currentItemTitle, playback.currentTitle, playback.currentArtist)
        if (now.station.isBlank()) {
            song.text = getString(R.string.radio_nothing_playing)
            artist.text = ""
            stationLine.text = ""
        } else {
            song.text = now.song ?: now.station
            artist.text = now.artist.orEmpty()
            artist.visibility = if (now.artist.isNullOrBlank()) View.GONE else View.VISIBLE
            stationLine.text = if (now.song != null) now.station else ""
            stationLine.visibility = if (now.song != null) View.VISIBLE else View.GONE
        }
        // "101 kiss fm" reads as 101, not as a lone "1"; on the station's own colour.
        initial.text = if (now.station.isBlank()) "" else dev.autobridge.car.CarStationTile.label(now.station)
        (cover.background as? GradientDrawable)?.setColor(
            if (now.station.isBlank()) AutoBridgeDesign.tint(AutoBridgeDesign.ACCENT_RADIO, 0.14f)
            else dev.autobridge.car.CarStationTile.colorFor(now.station)
        )

        showPlaying(playback.isPlayRequested)

        val url = playback.currentUri
        val isFavorite = !url.isNullOrBlank() && IptvHistoryStore.isFavorite(this, url)
        favorite.label.text = getString(if (isFavorite) R.string.radio_favorite_on else R.string.radio_favorite_add)
        favorite.label.setTextColor(if (isFavorite) AutoBridgeDesign.ACCENT_RADIO else AutoBridgeDesign.TEXT)
        favorite.icon.setImageResource(if (isFavorite) R.drawable.ic_radio_star_filled else R.drawable.ic_radio_star)
        favorite.icon.setColorFilter(if (isFavorite) AutoBridgeDesign.ACCENT_RADIO else AutoBridgeDesign.TEXT)
        favorite.view.background = AutoBridgeDesign.tappable(
            this, if (isFavorite) WARM_SURFACE else AutoBridgeDesign.SURFACE, 16, AutoBridgeDesign.ACCENT_RADIO, Color.TRANSPARENT
        )

        // The sleep timer's button turns into the minutes left while one runs.
        val left = RadioSleepTimer.remainingMinutes()
        sleepMinutes.visibility = if (left > 0) View.VISIBLE else View.GONE
        sleepIcon.visibility = if (left > 0) View.GONE else View.VISIBLE
        sleepMinutes.text = left.toString()
        sleep.contentDescription = if (left > 0) getString(R.string.radio_sleep_remaining, left) else getString(R.string.radio_sleep_timer)

        // A cover the stream carries goes in the big square, the logo then becoming the badge.
        val embedded = playback.currentArtworkData?.takeIf { it.isNotEmpty() }
        if (!embedded.contentEquals(seenCover)) {
            seenCover = embedded
            // Whatever the square held belongs to the last item; the logo is placed again below.
            shownLogo = LOGO_UNSET
            val bitmap = embedded?.let { runCatching { android.graphics.BitmapFactory.decodeByteArray(it, 0, it.size) }.getOrNull() }
            shownCover = embedded.takeIf { bitmap != null }
            logo.setTag(R.id.autobridge_image_tag, null)
            if (bitmap != null) {
                logo.setImageBitmap(bitmap)
                logo.visibility = View.VISIBLE
                initial.visibility = View.GONE
            } else {
                logo.setImageDrawable(null)
                logo.visibility = View.GONE
                initial.visibility = View.VISIBLE
            }
        }
        val hasCover = shownCover != null

        // The logo is fetched once per station, not once a second.
        val address = playback.currentArtworkUri
        if (address != shownLogo) {
            shownLogo = address
            // Forget the last station's logo, and the fetch for it that may still be on its way:
            // ImageLoader applies an answer only while the view's tag still names its address.
            val target = if (hasCover) badge else logo
            badge.setImageDrawable(null)
            badge.setTag(R.id.autobridge_image_tag, null)
            badge.visibility = View.GONE
            if (!hasCover) {
                logo.setImageDrawable(null)
                logo.setTag(R.id.autobridge_image_tag, null)
                logo.visibility = View.GONE
                initial.visibility = View.VISIBLE
            }
            if (!address.isNullOrBlank()) {
                ImageLoader.load(this, address, target) {
                    target.visibility = View.VISIBLE
                    if (target === logo) initial.visibility = View.GONE
                }
            }
        }
    }
}
