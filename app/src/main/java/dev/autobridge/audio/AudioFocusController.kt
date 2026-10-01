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
    /**
     * When true, the player keeps running through any focus loss: the controller still requests
     * and holds focus so the system knows this surface makes sound, but it never pauses or ducks
     * the player in response to losing it.
     *
     * This exists for cars that steal media focus for a short system sound while playback should
     * carry on — most visibly the reverse-gear / rear-camera chime, which otherwise killed the
     * music the moment the driver selected R. The trade-off is deliberate and the caller owns it:
     * with this on, a phone call or a navigation prompt no longer pauses or quiets playback either,
     * because they arrive as the same focus-loss signal.
     */
    var keepPlayingThroughFocusLoss: Boolean = false,
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
            val effective = if (keepPlayingThroughFocusLoss) throughLoss(action) else action
            if (effective != AudioFocusAction.NOTHING) onAction(effective)
        }
    }

    /**
     * Drops the player commands that quiet or stop playback in response to a focus loss, so the
     * music carries on. UNDUCK/PLAY are kept because they only ever restore sound, which is the
     * state this mode wants the player left in anyway.
     */
    private fun throughLoss(action: AudioFocusAction): AudioFocusAction = when (action) {
        AudioFocusAction.PAUSE, AudioFocusAction.DUCK -> AudioFocusAction.NOTHING
        AudioFocusAction.PLAY, AudioFocusAction.UNDUCK, AudioFocusAction.NOTHING -> action
    }

    /** Requests focus for media playback. Returns true when the system granted it. */
    fun request(): Boolean {
        request?.let { existing ->
            when (policy.state) {
                // Still held (or only ducked): nothing to ask for.
                AudioFocusState.GAINED, AudioFocusState.DUCKED -> return true
                // A call or prompt owns the output for now; the listener is still registered and
                // will deliver GAIN (and the resume) when it ends. Re-asking would talk over it.
                AudioFocusState.TRANSIENT_LOSS -> return false
                // A permanent loss (the rear-camera chime on reverse is delivered as one by some
                // head units) unregisters this request for good: the system will never send GAIN
                // to it again. Returning early here is what left the music silent after reverse,
                // so drop the dead request and ask afresh.
                AudioFocusState.PERMANENT_LOSS, AudioFocusState.NONE -> {
                    runCatching { audioManager.abandonAudioFocusRequest(existing) }
                    request = null
                }
            }
        }
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
