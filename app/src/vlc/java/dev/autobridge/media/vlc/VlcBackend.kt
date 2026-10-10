package dev.autobridge.media.vlc

import android.view.Surface

/**
 * A thin seam over the pieces of `org.videolan.libvlc` the facade drives, so [VlcMediaPlayer]'s
 * queue, parked-gate and live/VOD logic are plain Kotlin that a JVM test can exercise with a fake
 * — no device, no real libVLC `.so`, no `android.net.Uri`. Everything that would touch the native
 * engine goes through here; everything that is a Media3 decision stays in [VlcMediaPlayer].
 *
 * Positions are milliseconds. `null`/`C.TIME_UNSET` handling (live streams report no length) is
 * [VlcMediaPlayer]'s job; this interface just reports what libVLC says.
 */
interface VlcBackend {

    /** What libVLC's event stream last told us the player is doing. */
    enum class PlaybackPhase { IDLE, BUFFERING, PLAYING, PAUSED, ENDED, ERROR }

    /** A playable source, as handed to libVLC. The URI string is opaque to the facade's logic. */
    interface Source {
        val uri: String
    }

    /** True once a [Source] has been loaded via [load] and not yet [stop]ped. */
    val hasMediaLoaded: Boolean

    /** The current phase, driven by libVLC's event listener. */
    val phase: PlaybackPhase

    /** Whether the currently loaded media is a live stream (no finite length). */
    val isLive: Boolean

    /** Current position in ms, or a negative value when unknown. */
    val positionMs: Long

    /** Total length in ms, or a negative value when unknown / live. */
    val durationMs: Long

    /** Natural video width/height in px, or 0 before the first frame is laid out. */
    val videoWidth: Int
    val videoHeight: Int

    /** Build a backend [Source] from a URI string without loading it yet. */
    fun source(uri: String): Source

    /** Load (replace) the current media. Resets position; does not auto-play. */
    fun load(source: Source)

    fun play()
    fun pause()
    fun stop()
    fun seekTo(positionMs: Long)

    /**
     * Enable or disable the video track. Returns true on success. Used by the parked gate: a
     * `false` return while a media is prepared is treated as a safety failure by [VlcMediaPlayer].
     */
    fun setVideoTrackEnabled(enabled: Boolean): Boolean

    /** libVLC aspect-ratio string (e.g. "16:9") or null to clear; and the fit scale. */
    fun setAspectRatio(ratio: String?)
    fun setScale(scale: Float)

    /** Attach / detach the render surface (phone `PlayerView` hands one over via the session). */
    fun attachSurface(surface: Surface)
    fun detachSurface()

    /** Observe phase/track/size transitions; the facade calls invalidateState() from here. */
    fun setListener(listener: Listener?)

    fun release()

    interface Listener {
        /** A phase transition (play/pause/end/error/buffering). */
        fun onPhaseChanged(phase: PlaybackPhase)

        /** Position or length moved. */
        fun onTimeChanged()

        /** A track became available or the video layout changed — the gate re-apply point. */
        fun onTracksChanged()

        /** Natural video size became known or changed. */
        fun onVideoSizeChanged(width: Int, height: Int)
    }
}
