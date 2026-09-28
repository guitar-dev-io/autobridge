package dev.autobridge.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper

/** One reading of what the Android audio system is doing for this app. */
data class AudioSnapshot(
    val focus: AudioFocusState,
    val route: AudioRouteKind,
    val deviceName: String,
    val deviceTypes: List<Int>,
    val usage: String,
    val contentType: String,
    val volumePercent: Int,
    val volumeIndex: Int,
    val volumeMax: Int,
    val fixedVolume: Boolean,
    val bluetoothA2dpOn: Boolean,
    val musicActive: Boolean,
    val outputLatencyHintMs: Int?,
) {
    /** The diagnostics block, one field per line, in the order a reader scans it. */
    fun describe(sessionActive: Boolean, playbackState: String): String = buildString {
        appendLine("Focus: ${focus.name}")
        appendLine("Device: $deviceName")
        appendLine("Route: ${route.label}")
        appendLine("Usage: $usage")
        appendLine("Content: $contentType")
        appendLine("State: $playbackState")
        appendLine("Volume: $volumePercent%  ($volumeIndex/$volumeMax${if (fixedVolume) ", fixed" else ""})")
        appendLine("Session: ${if (sessionActive) "ACTIVE" else "INACTIVE"}")
        appendLine("Bluetooth A2DP: ${if (bluetoothA2dpOn) "on" else "off"}")
        appendLine("Music active: ${if (musicActive) "yes" else "no"}")
        append("Output latency: ${outputLatencyHintMs?.let { "${it}ms" } ?: "not reported"}")
    }
}

/**
 * Reads the parts of the Android audio system a normal app is allowed to see, and reports when they
 * change.
 *
 * Everything here uses public API only. Where the platform refuses an app this information — the
 * exact mixer route, the car's own volume curve — the snapshot says so rather than guessing, so the
 * diagnostics can distinguish "the app does not know" from "the app knows it is the speaker".
 */
class AudioEnvironment(context: Context) {
    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val handler = Handler(Looper.getMainLooper())

    /** Media playback attributes; the same ones the player and the focus request are built with. */
    val mediaAttributes: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()

    private var deviceCallback: AudioDeviceCallback? = null

    /** Notified when the set of output devices changes; the route may or may not have moved. */
    fun observeDevices(onChanged: () -> Unit) {
        if (deviceCallback != null) return
        val callback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) = onChanged()
            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) = onChanged()
        }
        deviceCallback = callback
        audioManager.registerAudioDeviceCallback(callback, handler)
    }

    fun stopObservingDevices() {
        deviceCallback?.let { audioManager.unregisterAudioDeviceCallback(it) }
        deviceCallback = null
    }

    fun snapshot(focus: AudioFocusState): AudioSnapshot {
        val outputs = runCatching { audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS) }
            .getOrNull()
            .orEmpty()
            .toList()
        val types = outputs.map { it.type }
        val route = AudioRoute.preferredOutput(types)
        val device = outputs.firstOrNull { AudioRoute.classify(it.type) == route }
        val max = runCatching { audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC) }.getOrDefault(0)
        val index = runCatching { audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) }.getOrDefault(0)
        return AudioSnapshot(
            focus = focus,
            route = route,
            deviceName = device?.productName?.toString()?.takeIf { it.isNotBlank() } ?: route.label,
            deviceTypes = types,
            usage = "MEDIA",
            contentType = "MUSIC",
            volumePercent = if (max > 0) (index * 100) / max else 0,
            volumeIndex = index,
            volumeMax = max,
            // A car or a fixed-volume dock reports this; adjusting the stream then does nothing.
            fixedVolume = runCatching { audioManager.isVolumeFixed }.getOrDefault(false),
            bluetoothA2dpOn = bluetoothA2dpOn(),
            musicActive = runCatching { audioManager.isMusicActive }.getOrDefault(false),
            outputLatencyHintMs = outputLatencyHintMs(),
        )
    }

    /**
     * Whether audio is going out over Bluetooth A2DP. The getter is deprecated but is still the
     * only public read of this state, so it is isolated here rather than suppressed at the call
     * site inside the snapshot.
     */
    @Suppress("DEPRECATION")
    private fun bluetoothA2dpOn(): Boolean =
        runCatching { audioManager.isBluetoothA2dpOn }.getOrDefault(false)

    /**
     * Best available latency hint. Android exposes the device's preferred frame count and sample
     * rate to apps; the true output latency is not public, so this is a hint, not a measurement.
     */
    private fun outputLatencyHintMs(): Int? {
        val frames = audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)?.toIntOrNull()
        val rate = audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull()
        if (frames == null || rate == null || rate <= 0) return null
        return (frames * 1000) / rate
    }

    /** Current media volume as a percentage, or null when the platform will not say. */
    fun volumePercent(): Int? {
        val max = runCatching { audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC) }.getOrNull() ?: return null
        if (max <= 0) return null
        val index = runCatching { audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) }.getOrNull() ?: return null
        return (index * 100) / max
    }

    /**
     * Asks the platform to change media volume. Returns false when the route has a fixed volume —
     * which is the normal case for a car head unit, where the vehicle owns the volume curve and the
     * phone's stream index is ignored.
     */
    fun setVolumePercent(percent: Int): Boolean {
        if (runCatching { audioManager.isVolumeFixed }.getOrDefault(false)) return false
        val max = runCatching { audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC) }.getOrNull() ?: return false
        if (max <= 0) return false
        val index = (percent.coerceIn(0, 100) * max) / 100
        return runCatching {
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, index, 0)
            true
        }.getOrDefault(false)
    }

    /**
     * Whether this build can ask for a specific output device for media.
     *
     * `setCommunicationDevice` (API 31+) only governs communication use cases such as calls, not
     * media, and there is no public API that redirects a media stream to a chosen sink. Media3 can
     * set a preferred device on its own audio sink, which is the closest an app can get; anything
     * further is the system's routing decision.
     */
    fun canSelectMediaOutputDevice(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P

    /** Output devices the app can see, as `type to name`, for diagnostics. */
    fun outputDevices(): List<Pair<AudioRouteKind, String>> =
        runCatching { audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS) }
            .getOrNull()
            .orEmpty()
            .map { AudioRoute.classify(it.type) to (it.productName?.toString().orEmpty()) }
}
