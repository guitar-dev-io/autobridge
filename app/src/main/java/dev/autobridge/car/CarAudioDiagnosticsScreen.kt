package dev.autobridge.car

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.Header
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
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
            val line = if (status.hasMetadata) {
                "${if (status.playing) "PLAYING" else "PAUSED"} • ${status.title} — ${status.artist}"
            } else {
                if (status.playing) "PLAYING • no metadata" else "PAUSED • no metadata"
            }
            if (line != webStatusLine) {
                webStatusLine = line
                carContext.mainExecutor.execute { invalidate() }
            }
        }

        val list = ItemList.Builder()
            .addItem(row("Focus", snapshot.focus.name))
            .addItem(row("Device", snapshot.deviceName))
            .addItem(row("Route", snapshot.route.label))
            .addItem(row("Usage", snapshot.usage))
            .addItem(row("Content", snapshot.contentType))
            .addItem(row("State", playbackStateLabel()))
            .addItem(row("Volume", volumeLabel(snapshot)))
            .addItem(row("Session", if (mediaClient.isConnected) "ACTIVE" else "INACTIVE"))
            .addItem(row("Bluetooth", if (snapshot.bluetoothA2dpOn) "A2DP on" else "off"))
            .addItem(row("Output latency", snapshot.outputLatencyHintMs?.let { "~${it}ms (hint)" } ?: "not reported"))
            .addItem(row("Web audio", webStatusLine ?: "reading…"))
            .addItem(row("Outputs seen", outputsLabel()))
            .build()

        return ListTemplate.Builder()
            .setHeader(
                Header.Builder()
                    .setTitle("Audio")
                    .setStartHeaderAction(Action.BACK)
                    .build()
            )
            .setSingleList(list)
            .build()
    }

    private fun playbackStateLabel(): String = when {
        mediaClient.isPlaying -> "PLAYING"
        mediaClient.isConnected -> "PAUSED"
        else -> "STOPPED"
    }

    /**
     * A head unit usually owns the volume curve and reports the stream as fixed; saying so is more
     * useful than a percentage the app cannot change.
     */
    private fun volumeLabel(snapshot: dev.autobridge.audio.AudioSnapshot): String =
        if (snapshot.fixedVolume) {
            "${snapshot.volumePercent}% • fixed by route"
        } else {
            "${snapshot.volumePercent}%  (${snapshot.volumeIndex}/${snapshot.volumeMax})"
        }

    private fun outputsLabel(): String {
        val devices = environment.outputDevices()
        if (devices.isEmpty()) return "none reported"
        return devices.joinToString("  •  ") { (kind, name) ->
            if (name.isBlank()) kind.label else "${kind.label} ($name)"
        }.take(120)
    }

    private fun row(title: String, value: String): Row =
        Row.Builder().setTitle(title).addText(value).build()
}
