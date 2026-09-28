package dev.autobridge.audio

import android.content.Context
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import dev.autobridge.display.StructuredLog

/**
 * Holds audio focus for one playback surface and turns the system's focus callbacks into concrete
 * player commands via [AudioFocusPolicy].
 *
 * The app previously requested focus with an empty listener, which is the shape that looks correct
 * and behaves worst: focus is held, so other apps defer, but nothing ever reacts to losing it. A
 * navigation prompt would talk over the music, and a phone call would leave it playing underneath.
 *
 * `setWillPauseWhenDucked(false)` is deliberate: the app ducks by lowering its own volume and keeps
 * playing, which is what a driver wants when the navigation voice cuts in. Ducking is left to the
 * app rather than the framework so the volume is restored to exactly what it was.
 */
class AudioFocusController(
    context: Context,
    private val environment: AudioEnvironment,
    /** Applies a command to whatever is actually playing. Always called on the main thread. */
    private val onAction: (AudioFocusAction) -> Unit,
    /** Reports every focus state change, for the diagnostics screen and the web bridge. */
    private val onStateChanged: (AudioFocusState) -> Unit = {},
) {
    private val audioManager =
        context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val handler = Handler(Looper.getMainLooper())
    private val policy = AudioFocusPolicy()
    private var request: AudioFocusRequest? = null

    /** True while the player should be treated as producing audio. Kept by the caller. */
    var isPlaying: Boolean = false

    val state: AudioFocusState get() = policy.state

    private val listener = AudioManager.OnAudioFocusChangeListener { change ->
        handler.post {
            val action = policy.onFocusChange(change, isPlaying)
            StructuredLog.i("AUDIO", "focus change=$change state=${policy.state} action=$action")
            onStateChanged(policy.state)
            if (action != AudioFocusAction.NOTHING) onAction(action)
        }
    }

    /** Requests focus for media playback. Returns true when the system granted it. */
    fun request(): Boolean {
        request?.let { return policy.state != AudioFocusState.NONE }
        val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(environment.mediaAttributes)
            .setOnAudioFocusChangeListener(listener, handler)
            .setWillPauseWhenDucked(false)
            .setAcceptsDelayedFocusGain(true)
            .build()
        request = focusRequest
        val result = runCatching { audioManager.requestAudioFocus(focusRequest) }
            .getOrDefault(AudioManager.AUDIOFOCUS_REQUEST_FAILED)
        val action = when (result) {
            AudioManager.AUDIOFOCUS_REQUEST_GRANTED -> policy.onFocusGranted()
            // Delayed grant: the listener will deliver GAIN when the current owner is done.
            AudioManager.AUDIOFOCUS_REQUEST_DELAYED -> AudioFocusAction.NOTHING
            else -> policy.onFocusDenied()
        }
        StructuredLog.i("AUDIO", "focus request result=$result state=${policy.state}")
        onStateChanged(policy.state)
        if (action != AudioFocusAction.NOTHING) onAction(action)
        return result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    fun abandon() {
        request?.let { runCatching { audioManager.abandonAudioFocusRequest(it) } }
        request = null
        policy.onFocusAbandoned()
        onStateChanged(policy.state)
    }
}
