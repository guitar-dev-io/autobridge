package dev.autobridge.audio

/** What the app's playback is doing with respect to the system's audio focus. */
enum class AudioFocusState {
    /** No focus held and none requested. */
    NONE,

    /** Focus held; playback may run at full volume. */
    GAINED,

    /** Another app asked to be heard over us; keep playing quietly. */
    DUCKED,

    /** Focus lost for a call or a prompt; paused with the intent to resume. */
    TRANSIENT_LOSS,

    /** Focus lost to something that took over; paused and not coming back on its own. */
    PERMANENT_LOSS,
}

/** What the caller should do to the player in response to a focus change. */
enum class AudioFocusAction { NOTHING, PLAY, PAUSE, DUCK, UNDUCK }

/**
 * The rules for reacting to `AudioManager.OnAudioFocusChangeListener`, kept free of Android types
 * so each transition can be asserted directly.
 *
 * The distinction that matters is between the two kinds of loss. A transient loss — a navigation
 * prompt, the assistant, a ringing phone — is expected to end, so playback pauses and the policy
 * remembers to resume. A permanent loss means another app has taken over; resuming then would
 * fight the user, so it does not. Failing to keep these apart is what makes media either never come
 * back after a navigation prompt or barge back in over whatever the user started instead.
 */
class AudioFocusPolicy {
    var state: AudioFocusState = AudioFocusState.NONE
        private set

    /** True once a loss paused playback that the policy intends to undo. */
    var resumeWhenFocusReturns: Boolean = false
        private set

    /** Records that the app asked for and was given focus. */
    fun onFocusGranted(): AudioFocusAction {
        state = AudioFocusState.GAINED
        resumeWhenFocusReturns = false
        return AudioFocusAction.PLAY
    }

    /** Records that the request was refused; nothing should start. */
    fun onFocusDenied(): AudioFocusAction {
        state = AudioFocusState.NONE
        resumeWhenFocusReturns = false
        return AudioFocusAction.NOTHING
    }

    /** The app gave focus up itself. */
    fun onFocusAbandoned() {
        state = AudioFocusState.NONE
        resumeWhenFocusReturns = false
    }

    /**
     * @param change one of the `AudioManager.AUDIOFOCUS_*` change constants.
     * @param isPlaying whether the player is currently producing audio.
     */
    fun onFocusChange(change: Int, isPlaying: Boolean): AudioFocusAction = when (change) {
        AudioFocusConstants.GAIN -> {
            val previous = state
            state = AudioFocusState.GAINED
            when {
                previous == AudioFocusState.DUCKED -> AudioFocusAction.UNDUCK
                resumeWhenFocusReturns -> {
                    resumeWhenFocusReturns = false
                    AudioFocusAction.PLAY
                }
                else -> AudioFocusAction.NOTHING
            }
        }

        AudioFocusConstants.LOSS -> {
            state = AudioFocusState.PERMANENT_LOSS
            // Deliberately not remembered: something else owns playback now.
            resumeWhenFocusReturns = false
            if (isPlaying) AudioFocusAction.PAUSE else AudioFocusAction.NOTHING
        }

        AudioFocusConstants.LOSS_TRANSIENT -> {
            state = AudioFocusState.TRANSIENT_LOSS
            if (isPlaying) {
                resumeWhenFocusReturns = true
                AudioFocusAction.PAUSE
            } else {
                AudioFocusAction.NOTHING
            }
        }

        AudioFocusConstants.LOSS_TRANSIENT_CAN_DUCK -> {
            state = AudioFocusState.DUCKED
            // Ducking keeps playing, so there is nothing to resume afterwards.
            if (isPlaying) AudioFocusAction.DUCK else AudioFocusAction.NOTHING
        }

        else -> AudioFocusAction.NOTHING
    }
}

/**
 * Mirrors of the `AudioManager.AUDIOFOCUS_*` change constants, so [AudioFocusPolicy] stays a plain
 * Kotlin class that a JVM test can exercise without an Android runtime.
 */
object AudioFocusConstants {
    const val GAIN = 1
    const val LOSS = -1
    const val LOSS_TRANSIENT = -2
    const val LOSS_TRANSIENT_CAN_DUCK = -3
}
