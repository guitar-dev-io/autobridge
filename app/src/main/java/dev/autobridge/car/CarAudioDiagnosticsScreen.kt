package dev.autobridge.car

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.Header
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import dev.autobridge.R
import dev.autobridge.audio.AudioEnvironment
import dev.autobridge.audio.AudioFocusState
import dev.autobridge.browser.CarBrowserRuntime
import dev.autobridge.media.MediaPlaybackClient

/**
 * Live audio diagnostics, so a problem can be attributed to the WebView, the MediaSession or the
 * Android audio system rather than guessed at.
 *
 * Every value comes from public API. Where Android does not tell an app something — the mixer's
 * real route, the car's own volume curve — the row says so instead of showing a plausible number.
 */
class CarAudioDiagnosticsScreen(carContext: CarContext) : Screen(carContext) {
    private val environment = AudioEnvironment(carContext)
    private val mediaClient = MediaPlaybackClient(carContext)

    /** Filled asynchronously by the WebView; null until the page has answered once. */
    private var webStatusLine: String? = null

    init {
        // Without connecting, the client always reports INACTIVE and the row would say the session
        // is down when it is simply unobserved.
        lifecycle.addObserver(object : androidx.lifecycle.DefaultLifecycleObserver {
            override fun onStart(owner: androidx.lifecycle.LifecycleOwner) {
                mediaClient.connect(onConnected = { carContext.mainExecutor.execute { invalidate() } })
            }

            override fun onStop(owner: androidx.lifecycle.LifecycleOwner) {
                mediaClient.disconnect()
            }
        })
    }

    override fun onGetTemplate(): Template {
        val renderer = CarBrowserRuntime.rendererOrNull()
        val focus = renderer?.audioFocusState ?: AudioFocusState.NONE
        val snapshot = environment.snapshot(focus)

        // Ask the page each time the screen is built; the answer lands on the next invalidate.
        renderer?.readWebMediaStatus { status ->
            val playState = carContext.getString(
                if (status.playing) R.string.car_audio_playing else R.string.car_audio_paused
            )
            val line = if (status.hasMetadata) {
                carContext.getString(
                    R.string.car_audio_web_line, playState, status.title, status.artist
                )
            } else {
                carContext.getString(R.string.car_audio_web_no_metadata, playState)
            }
            if (line != webStatusLine) {
                webStatusLine = line
                carContext.mainExecutor.execute { invalidate() }
            }
        }

        val list = ItemList.Builder()
            .addItem(row(carContext.getString(R.string.car_audio_focus), snapshot.focus.name))
            .addItem(row(carContext.getString(R.string.car_audio_device), snapshot.deviceName))
            .addItem(row(carContext.getString(R.string.car_audio_route), snapshot.route.label))
            .addItem(row(carContext.getString(R.string.car_audio_usage), snapshot.usage))
            .addItem(row(carContext.getString(R.string.car_audio_content), snapshot.contentType))
            .addItem(row(carContext.getString(R.string.car_audio_state), playbackStateLabel()))
            .addItem(row(carContext.getString(R.string.car_audio_volume), volumeLabel(snapshot)))
            .addItem(
                row(
                    carContext.getString(R.string.car_audio_session),
                    carContext.getString(
                        if (mediaClient.isConnected) R.string.car_audio_active
                        else R.string.car_audio_inactive
                    )
                )
            )
            .addItem(
                row(
                    carContext.getString(R.string.car_audio_bluetooth),
                    carContext.getString(
                        if (snapshot.bluetoothA2dpOn) R.string.car_audio_a2dp_on
                        else R.string.car_audio_a2dp_off
                    )
                )
            )
            .addItem(
                row(
                    carContext.getString(R.string.car_audio_latency),
                    snapshot.outputLatencyHintMs
                        ?.let { carContext.getString(R.string.car_audio_latency_hint, it) }
                        ?: carContext.getString(R.string.car_audio_not_reported)
                )
            )
            .addItem(
                row(
                    carContext.getString(R.string.car_audio_web),
                    webStatusLine ?: carContext.getString(R.string.car_audio_reading)
                )
            )
            .addItem(row(carContext.getString(R.string.car_audio_outputs), outputsLabel()))
            .build()

        return ListTemplate.Builder()
            .setHeader(
                Header.Builder()
                    .setTitle(carContext.getString(R.string.car_audio_title))
                    .setStartHeaderAction(Action.BACK)
                    .build()
            )
            .setSingleList(list)
            .build()
    }

    private fun playbackStateLabel(): String = carContext.getString(
        when {
            mediaClient.isPlaying -> R.string.car_audio_playing
            mediaClient.isConnected -> R.string.car_audio_paused
            else -> R.string.car_audio_stopped
        }
    )

    /**
     * A head unit usually owns the volume curve and reports the stream as fixed; saying so is more
     * useful than a percentage the app cannot change.
     */
    private fun volumeLabel(snapshot: dev.autobridge.audio.AudioSnapshot): String =
        if (snapshot.fixedVolume) {
            carContext.getString(R.string.car_audio_volume_fixed, snapshot.volumePercent)
        } else {
            carContext.getString(
                R.string.car_audio_volume_index,
                snapshot.volumePercent, snapshot.volumeIndex, snapshot.volumeMax
            )
        }

    private fun outputsLabel(): String {
        val devices = environment.outputDevices()
        if (devices.isEmpty()) return carContext.getString(R.string.car_audio_no_outputs)
        return devices.joinToString("  •  ") { (kind, name) ->
            if (name.isBlank()) kind.label else "${kind.label} ($name)"
        }.take(120)
    }

    private fun row(title: String, value: String): Row =
        Row.Builder().setTitle(title).addText(value).build()
}
