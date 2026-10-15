package dev.autobridge.radio

import android.content.Context
import dev.autobridge.media.MediaPlaybackClient

/**
 * The single point that decides which radio source owns the shared Media3 session, over the
 * existing [MediaPlaybackClient]. It never creates a player or a second audio-focus owner: every
 * audio command goes through the one session the car and the notification already observe.
 *
 * Two pieces of state, deliberately separate:
 *
 *  - [activeSource]: who currently owns the session queue and audio. Only
 *    [RadioSourceType.INTERNET_RADIO] or [RadioSourceType.OFFLINE_AUDIO] can be active, because
 *    those are the only sources with a stream to load. [RadioSourceType.HARDWARE_FM] is never
 *    active here.
 *  - [selectedSource]: the user's choice, which may be [RadioSourceType.HARDWARE_FM] even though
 *    this device cannot play it. Persisted by the caller so a phone selection reaches the car.
 *
 * [play] is the only method that loads audio; switching sources never loads a queue on its own.
 */
class RadioPlaybackCoordinator(private val client: MediaPlaybackClient) {

    /** The outcome of a [switchSource]. */
    sealed interface SwitchResult {
        /** The source is now active (or already was) — the user's selection took effect. */
        object Activated : SwitchResult
        /** The source cannot be made active; [reason] is the localized, user-facing explanation. */
        data class Unavailable(val reason: String?) : SwitchResult
    }

    @Volatile
    private var activeSource: RadioSourceType = RadioSourceType.INTERNET_RADIO

    @Volatile
    private var selectedSource: RadioSourceType = RadioSourceType.INTERNET_RADIO

    /** Called whenever selected/active change, so an observer (RadioRepository) can republish. */
    var onStateChanged: (() -> Unit)? = null

    fun activeSource(): RadioSourceType = activeSource
    fun selectedSource(): RadioSourceType = selectedSource

    private fun sourceFor(type: RadioSourceType): RadioSource = when (type) {
        RadioSourceType.INTERNET_RADIO -> InternetRadioSource
        RadioSourceType.HARDWARE_FM -> HardwareFmSource
        RadioSourceType.OFFLINE_AUDIO -> OfflineAudioSource
    }

    /**
     * Flips the user's selection to [type] and, when it is both available and not already active,
     * makes it the active source. This NEVER builds or loads a queue — audio only ever starts
     * through [play].
     *
     *  - Not available (FM here, or unwired offline): the uniform branch. Record the selection,
     *    leave active source and audio untouched, return [SwitchResult.Unavailable].
     *  - Available and already the active source: record the selection, return
     *    [SwitchResult.Activated].
     *  - Available and different: flip active and selected to [type]. The adoption test — whether a
     *    queue is already live for the new active source — reduces to the session simply having a
     *    queue (correction #4): there is no per-source queue tag.
     */
    fun switchSource(context: Context, type: RadioSourceType): SwitchResult {
        val source = sourceFor(type)
        if (!source.isAvailable(context)) {
            selectedSource = type
            onStateChanged?.invoke()
            return SwitchResult.Unavailable(source.unavailableReason(context))
        }
        if (type == activeSource) {
            selectedSource = type
            onStateChanged?.invoke()
            return SwitchResult.Activated
        }
        activeSource = type
        selectedSource = type
        // Adoption test (correction #4): a live queue is simply a non-empty session queue.
        @Suppress("UNUSED_VARIABLE")
        val hasLiveQueue = client.currentQueueUris().isNotEmpty()
        onStateChanged?.invoke()
        return SwitchResult.Activated
    }

    /**
     * The ONLY audio-loading method. Maps a [RadioStation] queue to the shared session's parallel
     * URI/title lists and starts at a coerced [startIndex] (correction #2). The caller must have
     * already excluded hardware-FM and blank-URL stations from [queue]; this method requires every
     * entry be playable. Loading a new queue replaces the old one, which is how switching releases
     * the previous source — no second player.
     */
    fun play(station: RadioStation, queue: List<RadioStation>, startIndex: Int) {
        require(queue.all { it.streamUrl.isNotBlank() }) { "radio queue must be all playable" }
        client.playPlaylist(
            uris = queue.map { it.streamUrl },
            startIndex = startIndex.coerceIn(0, (queue.size - 1).coerceAtLeast(0)),
            titles = queue.map { it.title },
        )
        // Playing a station makes internet radio the active and selected source.
        activeSource = station.sourceType
        selectedSource = station.sourceType
        onStateChanged?.invoke()
    }

    fun pause() = client.pause()
    fun resume() = client.resume()
    fun next() = client.next()
    fun previous() = client.previous()
    fun stop() = client.stop()
}
