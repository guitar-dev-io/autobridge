package dev.autobridge.car

/**
 * Which player the car home's Now Playing card shows, when more than one has something.
 *
 * Three things can be playing: a page in the car browser (its audio reaches the media session
 * through [dev.autobridge.media.WebMediaHub]), a source the bridge opened (Send to car, the queue),
 * and anything started straight on the media session — TV, radio, playlists, local audio. The card
 * used to read only the first two, so with the radio on it showed nothing, or an old bridge item
 * whose buttons did not touch the radio, while the steering wheel and Android Auto's media card
 * controlled the radio correctly. The session is now a source too.
 *
 * Order: whatever is audibly the browser's page, then the bridge's live session, then the media
 * session, and only then the bridge's resumable snapshot ("Continue watching"), which is history
 * rather than something playing. Pure, so the order is a JVM test.
 */
object NowPlayingChoice {
    enum class Source { WEB, BRIDGE_LIVE, SESSION, BRIDGE_SNAPSHOT, NONE }

    fun choose(webPlaying: Boolean, bridgeLive: Boolean, sessionActive: Boolean, hasSnapshot: Boolean): Source = when {
        webPlaying -> Source.WEB
        bridgeLive -> Source.BRIDGE_LIVE
        sessionActive -> Source.SESSION
        hasSnapshot -> Source.BRIDGE_SNAPSHOT
        else -> Source.NONE
    }

    /**
     * Whether the media session has something worth a card: playing, or loaded and paused. Idle
     * and ended mean nothing is there to resume from the card. States are `Player.STATE_*`.
     */
    fun sessionActive(isPlaying: Boolean, itemCount: Int, playbackState: Int): Boolean =
        itemCount > 0 && (isPlaying || playbackState == STATE_BUFFERING || playbackState == STATE_READY)

    /** Mirrors of `androidx.media3.common.Player.STATE_*`, kept here so this stays free of Media3. */
    const val STATE_BUFFERING = 2
    const val STATE_READY = 3
}
