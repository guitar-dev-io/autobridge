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
 * things a listener reaches for — the equalizer ([RadioEqSheet]), the favourite, all stations.
 *
 * It holds no playback state of its own: everything is read from the shared session through
 * [MediaPlaybackClient] on a one-second tick, the same way [dev.autobridge.ui.MiniPlayer] does, so
 * a change made on the car shows here too. Previous and next step through the queue the station
 * was started with (its category, or the favourites).
 */
class RadioPlayerActivity : Activity() {
    companion object {
        fun intent(context: Context): Intent = Intent(context, RadioPlayerActivity::class.java)
    }

    private lateinit var playback: MediaPlaybackClient
    private var releaseBack: () -> Unit = {}

    private lateinit var logo: ImageView
    private lateinit var initial: TextView
    private lateinit var song: TextView
    private lateinit var artist: TextView
    private lateinit var stationLine: TextView
    private lateinit var playToggle: TextView
    private lateinit var favorite: TextView
    private lateinit var sleep: TextView
    private var shownLogo: String? = null

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
            setPadding(dp(20), dp(8), dp(20), dp(24))
        }

        // The logo, with the station's first letter until (or unless) the image arrives.
        val art = FrameLayout(this).apply {
            background = AutoBridgeDesign.surface(this@RadioPlayerActivity, AutoBridgeDesign.tint(AutoBridgeDesign.ACCENT_RADIO, 0.14f), 40, Color.TRANSPARENT)
            clipToOutline = true
        }
        initial = TextView(this).apply {
            textSize = 72f
            gravity = Gravity.CENTER
            setTextColor(AutoBridgeDesign.ACCENT_RADIO)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        logo = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            visibility = View.GONE
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        art.addView(initial, FrameLayout.LayoutParams(-1, -1))
        art.addView(logo, FrameLayout.LayoutParams(-1, -1))
        body.addView(art, LinearLayout.LayoutParams(dp(248), dp(248)).apply { topMargin = dp(12) })

        body.addView(TextView(this).apply {
            text = getString(R.string.radio_live)
            textSize = 13f
            setTextColor(AutoBridgeDesign.ACCENT_RADIO)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            background = AutoBridgeDesign.surface(this@RadioPlayerActivity, AutoBridgeDesign.tint(AutoBridgeDesign.ACCENT_RADIO, 0.16f), 12, Color.TRANSPARENT)
            setPadding(dp(12), dp(4), dp(12), dp(4))
        }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(22) })

        song = centred(26f, AutoBridgeDesign.TEXT, bold = true, lines = 2)
        artist = centred(16f, AutoBridgeDesign.TEXT, bold = false, lines = 1)
        stationLine = centred(14f, AutoBridgeDesign.TEXT_MUTED, bold = false, lines = 1)
        body.addView(song, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        body.addView(artist, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
        body.addView(stationLine, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })

        // Previous, play/pause, next.
        val transport = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        transport.addView(
            AutoBridgeDesign.glyphButton(this, "⏮", 22f) { playback.previous() }
                .apply { contentDescription = getString(R.string.radio_previous_station) },
            LinearLayout.LayoutParams(dp(64), dp(64))
        )
        playToggle = TextView(this).apply {
            textSize = 30f
            gravity = Gravity.CENTER
            setTextColor(AutoBridgeDesign.INK)
            isClickable = true
            isFocusable = true
            background = RippleDrawable(
                ColorStateList.valueOf(AutoBridgeDesign.tint(Color.WHITE, 0.3f)),
                GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(AutoBridgeDesign.ACCENT_RADIO) },
                null
            )
            setOnClickListener {
                if (playback.isPlaying) playback.pause() else playback.resume()
                update()
            }
        }
        transport.addView(playToggle, LinearLayout.LayoutParams(dp(92), dp(92)).apply {
            marginStart = dp(28)
            marginEnd = dp(28)
        })
        transport.addView(
            AutoBridgeDesign.glyphButton(this, "⏭", 22f) { playback.next() }
                .apply { contentDescription = getString(R.string.radio_next_station) },
            LinearLayout.LayoutParams(dp(64), dp(64))
        )
        body.addView(transport, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(28) })

        // EQ, favourite, all stations; then the car and the sleep timer.
        favorite = action(getString(R.string.radio_favorite_add)) { toggleFavorite() }
        sleep = action(getString(R.string.radio_sleep_timer)) { chooseSleepTimer() }
        val actions = listOf(
            action(getString(R.string.eq_title)) { RadioEqSheet(this).show() },
            favorite,
            action(getString(R.string.radio_all_stations)) {
                startActivity(LibraryActivity.intent(this, LibraryActivity.Section.RADIO))
                finish()
            },
            action(getString(R.string.radio_play_on_car)) { sendToCar() },
            sleep
        )
        AutoBridgeDesign.grid(this, actions, columns = 3).forEachIndexed { index, row ->
            body.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(if (index == 0) 28 else 8) })
        }

        val header = AutoBridgeDesign.header(this, getString(R.string.radio_player_title), onBack = { finish() })
        return AutoBridgeDesign.page(this, header = header, body = body)
    }

    private fun centred(size: Float, color: Int, bold: Boolean, lines: Int): TextView = TextView(this).apply {
        textSize = size
        gravity = Gravity.CENTER
        setTextColor(color)
        if (bold) typeface = Typeface.create("sans-serif", Typeface.BOLD)
        maxLines = lines
        ellipsize = TextUtils.TruncateAt.END
    }

    private fun action(label: String, onClick: () -> Unit): TextView = TextView(this).apply {
        text = label
        textSize = 14f
        gravity = Gravity.CENTER
        setTextColor(AutoBridgeDesign.TEXT)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        isClickable = true
        isFocusable = true
        minHeight = dp(56)
        setPadding(dp(8), dp(12), dp(8), dp(12))
        background = AutoBridgeDesign.tappable(this@RadioPlayerActivity, AutoBridgeDesign.SURFACE, 16, AutoBridgeDesign.ACCENT_RADIO)
        setOnClickListener { onClick() }
    }

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
        initial.text = now.station.trim().take(1).uppercase()

        val playing = playback.isPlaying
        playToggle.text = if (playing) "❚❚" else "▶"
        playToggle.contentDescription = getString(if (playing) R.string.radio_pause else R.string.radio_play)

        val url = playback.currentUri
        val isFavorite = !url.isNullOrBlank() && IptvHistoryStore.isFavorite(this, url)
        favorite.text = getString(if (isFavorite) R.string.radio_favorite_on else R.string.radio_favorite_add)
        favorite.setTextColor(if (isFavorite) AutoBridgeDesign.ACCENT_RADIO else AutoBridgeDesign.TEXT)

        val left = RadioSleepTimer.remainingMinutes()
        sleep.text = if (left > 0) getString(R.string.radio_sleep_remaining, left) else getString(R.string.radio_sleep_timer)
        sleep.setTextColor(if (left > 0) AutoBridgeDesign.ACCENT_RADIO else AutoBridgeDesign.TEXT)

        // The logo is fetched once per station, not once a second.
        val address = playback.currentArtworkUri
        if (address != shownLogo) {
            shownLogo = address
            logo.visibility = View.GONE
            initial.visibility = View.VISIBLE
            if (!address.isNullOrBlank()) {
                ImageLoader.load(this, address, logo) {
                    initial.visibility = View.GONE
                    logo.visibility = View.VISIBLE
                }
            }
        }
    }
}
