package dev.autobridge.media.vlc

import android.content.Context
import android.net.Uri
import android.view.Surface
import dev.autobridge.logging.StructuredLog
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer

/**
 * The real [VlcBackend], driving `org.videolan.libvlc`. This is the only class in the tree that
 * imports `org.videolan.libvlc.*`, so it only compiles under `-Pautobridge.vlc=true` (it lives in
 * `src/vlc`). All of its playback decisions sit in [VlcMediaPlayer] over the [VlcBackend] seam;
 * this file is the glue to libVLC's `LibVLC` + `MediaPlayer` and nothing more.
 *
 * The event constants, method names and `IVLCVout` surface API were confirmed against the resolved
 * `org.videolan.android:libvlc-all:3.7.7` AAR (see the task context.json pre-implementation
 * checklist). A live stream reports length <= 0, which the facade maps to `C.TIME_UNSET`.
 */
class LibVlcBackend(context: Context) : VlcBackend {

    private val libVlc = LibVLC(context.applicationContext)
    private val player = MediaPlayer(libVlc)

    private var listener: VlcBackend.Listener? = null
    private var loaded = false
    private var currentPhase: VlcBackend.PlaybackPhase = VlcBackend.PlaybackPhase.IDLE
    private var vWidth = 0
    private var vHeight = 0
    private var attachedSurface: Surface? = null

    private data class LibVlcSource(override val uri: String) : VlcBackend.Source

    init {
        player.setEventListener { event ->
            when (event.type) {
                MediaPlayer.Event.Buffering -> updatePhase(VlcBackend.PlaybackPhase.BUFFERING)
                MediaPlayer.Event.Playing -> {
                    updatePhase(VlcBackend.PlaybackPhase.PLAYING)
                    listener?.onTracksChanged()
                }
                MediaPlayer.Event.Paused -> updatePhase(VlcBackend.PlaybackPhase.PAUSED)
                MediaPlayer.Event.EndReached -> updatePhase(VlcBackend.PlaybackPhase.ENDED)
                MediaPlayer.Event.EncounteredError -> updatePhase(VlcBackend.PlaybackPhase.ERROR)
                MediaPlayer.Event.TimeChanged,
                MediaPlayer.Event.LengthChanged -> listener?.onTimeChanged()
                MediaPlayer.Event.ESAdded -> listener?.onTracksChanged()
                MediaPlayer.Event.Vout -> {
                    val vout = event.voutCount
                    if (vout > 0) {
                        vWidth = player.currentVideoTrack?.width ?: vWidth
                        vHeight = player.currentVideoTrack?.height ?: vHeight
                        listener?.onVideoSizeChanged(vWidth, vHeight)
                    }
                    listener?.onTracksChanged()
                }
            }
        }
    }

    private fun updatePhase(phase: VlcBackend.PlaybackPhase) {
        currentPhase = phase
        listener?.onPhaseChanged(phase)
    }

    override val hasMediaLoaded: Boolean get() = loaded

    override val phase: VlcBackend.PlaybackPhase get() = currentPhase

    override val isLive: Boolean get() = loaded && player.length <= 0L

    override val positionMs: Long get() = player.time

    override val durationMs: Long get() = player.length

    override val videoWidth: Int get() = vWidth

    override val videoHeight: Int get() = vHeight

    override fun source(uri: String): VlcBackend.Source = LibVlcSource(uri)

    override fun load(source: VlcBackend.Source) {
        val media = Media(libVlc, Uri.parse(source.uri))
        player.setMedia(media)
        media.release()
        loaded = true
        vWidth = 0
        vHeight = 0
        currentPhase = VlcBackend.PlaybackPhase.BUFFERING
    }

    override fun play() {
        player.play()
    }

    override fun pause() {
        player.pause()
    }

    override fun stop() {
        player.stop()
        loaded = false
        currentPhase = VlcBackend.PlaybackPhase.IDLE
    }

    override fun seekTo(positionMs: Long) {
        player.setTime(positionMs)
    }

    override fun setVideoTrackEnabled(enabled: Boolean): Boolean = runCatching {
        player.setVideoTrackEnabled(enabled)
        true
    }.getOrElse {
        StructuredLog.w("VLC", "setVideoTrackEnabled($enabled) failed: ${it.javaClass.simpleName}")
        false
    }

    override fun setAspectRatio(ratio: String?) {
        player.setAspectRatio(ratio)
    }

    override fun setScale(scale: Float) {
        player.setScale(scale)
    }

    override fun attachSurface(surface: Surface) {
        val vout = player.getVLCVout()
        attachedSurface = surface
        vout.setVideoSurface(surface, null)
        vout.attachViews()
    }

    override fun detachSurface() {
        runCatching { player.getVLCVout().detachViews() }
        attachedSurface = null
    }

    override fun setListener(listener: VlcBackend.Listener?) {
        this.listener = listener
    }

    override fun release() {
        runCatching { player.getVLCVout().detachViews() }
        player.release()
        libVlc.release()
        loaded = false
        listener = null
    }
}
