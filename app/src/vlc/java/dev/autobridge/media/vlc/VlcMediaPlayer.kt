package dev.autobridge.media.vlc

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Looper
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.TextureView
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import dev.autobridge.logging.StructuredLog
import dev.autobridge.settings.AspectRatio

/**
 * libVLC exposed as an `androidx.media3.common.Player`, so the whole app — the MediaSession, the
 * car media card, the `PlayerView`, the surface arbiter, the parking gate and the settings
 * listeners — reaches it through the one interface it already speaks, without a parallel playback
 * path. The decode engine behind the facade is libVLC (via the [VlcBackend] seam); everything a
 * Media3 consumer needs is mapped onto that in [getState] and the `handleX` overrides.
 *
 * What is NOT reproduced here (Media3-only, by design): GL video enhancement, the subtitle-cue
 * pipeline to SubtitleController, and `BEHIND_LIVE_WINDOW` live-edge recovery. Aspect ratio is
 * mapped to libVLC's own aspect/scale. Audio focus, which ExoPlayer handles for the Media3 branch,
 * is owned here.
 *
 * The queue, parked-gate and live/VOD logic are driven purely over [VlcBackend], so they are
 * unit-tested with a fake backend (see VlcMediaPlayerTest) without a device or a real libVLC `.so`.
 */
@OptIn(UnstableApi::class)
class VlcMediaPlayer(
    looper: Looper,
    private val context: Context,
    private val backend: VlcBackend,
) : SimpleBasePlayer(looper) {

    /**
     * The queue, current index and parked-gate state machine — the device-independent decisions,
     * kept in a plain helper so they are unit-tested over a fake backend without a Looper/Context.
     * libVLC itself only ever holds the current item; the facade surfaces the whole [logic] queue.
     */
    private val logic = VlcPlaybackLogic(backend)
    private val queue get() = logic.queue
    private val currentIndex get() = logic.currentIndex
    private var playWhenReady = false

    private val audioManager: AudioManager by lazy {
        context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }
    private var focusRequest: AudioFocusRequest? = null
    private var noisyReceiver: BroadcastReceiver? = null
    private var hadFocusPauseWhileTransient = false

    init {
        backend.setListener(object : VlcBackend.Listener {
            override fun onPhaseChanged(phase: VlcBackend.PlaybackPhase) {
                if (phase == VlcBackend.PlaybackPhase.PLAYING ||
                    phase == VlcBackend.PlaybackPhase.PAUSED
                ) {
                    // Media is live now; the gate recorded at startup has to actually take hold.
                    enforceGateOrPause()
                }
                invalidateState()
            }

            override fun onTimeChanged() = invalidateState()

            override fun onTracksChanged() {
                enforceGateOrPause()
                invalidateState()
            }

            override fun onVideoSizeChanged(width: Int, height: Int) = invalidateState()
        })
    }

    // -- State ---------------------------------------------------------------------------------

    override fun getState(): State {
        val seekable = logic.canSeekInItem
        val commands = Player.Commands.Builder()
            .addAll(
                Player.COMMAND_PLAY_PAUSE,
                Player.COMMAND_PREPARE,
                Player.COMMAND_STOP,
                Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
                Player.COMMAND_GET_TIMELINE,
                Player.COMMAND_GET_METADATA,
                Player.COMMAND_SET_MEDIA_ITEM,
                Player.COMMAND_CHANGE_MEDIA_ITEMS,
                Player.COMMAND_SET_VIDEO_SURFACE,
                Player.COMMAND_SET_TRACK_SELECTION_PARAMETERS,
                Player.COMMAND_GET_TRACKS,
            )
            // VOD can seek within the item; a live stream cannot, so the command is withheld.
            .apply { if (seekable) add(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM) }
            // Next/Previous stay live at both ends with a multi-item queue (REPEAT_MODE_ALL wraps).
            .apply {
                if (queue.size > 1) {
                    add(Player.COMMAND_SEEK_TO_NEXT)
                    add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
                    add(Player.COMMAND_SEEK_TO_PREVIOUS)
                    add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
                }
            }
            .build()

        val playlist = queue.mapIndexed { index, item ->
            val durationUs = if (index == currentIndex && seekable) {
                backend.durationMs * 1000
            } else {
                C.TIME_UNSET
            }
            MediaItemData.Builder(mediaItemId(item, index))
                .setMediaItem(item)
                .setMediaMetadata(item.mediaMetadata)
                .setIsSeekable(index == currentIndex && seekable)
                .setDurationUs(durationUs)
                .build()
        }

        val playbackState = when (backend.phase) {
            VlcBackend.PlaybackPhase.IDLE -> if (logic.prepared) Player.STATE_BUFFERING else Player.STATE_IDLE
            VlcBackend.PlaybackPhase.BUFFERING -> Player.STATE_BUFFERING
            VlcBackend.PlaybackPhase.PLAYING,
            VlcBackend.PlaybackPhase.PAUSED -> Player.STATE_READY
            VlcBackend.PlaybackPhase.ENDED -> Player.STATE_ENDED
            VlcBackend.PlaybackPhase.ERROR -> Player.STATE_IDLE
        }

        val position = if (backend.phase == VlcBackend.PlaybackPhase.PLAYING) {
            PositionSupplier.getExtrapolating(backend.positionMs.coerceAtLeast(0L), 1f)
        } else {
            PositionSupplier.getConstant(backend.positionMs.coerceAtLeast(0L))
        }

        val builder = State.Builder()
            .setAvailableCommands(commands)
            .setPlaybackState(playbackState)
            .setPlayWhenReady(playWhenReady, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setRepeatMode(Player.REPEAT_MODE_ALL)

        if (playlist.isNotEmpty()) {
            builder.setPlaylist(playlist)
                .setCurrentMediaItemIndex(currentIndex.coerceIn(0, playlist.lastIndex))
                .setContentPositionMs(position)
        }

        if (backend.phase == VlcBackend.PlaybackPhase.ERROR) {
            builder.setPlayerError(
                PlaybackException(
                    "libVLC playback error",
                    null,
                    PlaybackException.ERROR_CODE_UNSPECIFIED
                )
            )
        }

        return builder.build()
    }

    private fun mediaItemId(item: MediaItem, index: Int): String =
        item.mediaId.takeIf { it != MediaItem.DEFAULT_MEDIA_ID } ?: "vlc:$index"

    // -- Queue ---------------------------------------------------------------------------------

    override fun handleSetMediaItems(
        mediaItems: MutableList<MediaItem>,
        startIndex: Int,
        startPositionMs: Long
    ): ListenableFuture<*> {
        logic.setMediaItems(mediaItems, if (startIndex == C.INDEX_UNSET) -1 else startIndex)
        if (startPositionMs != C.TIME_UNSET && startPositionMs > 0) backend.seekTo(startPositionMs)
        if (logic.prepared && playWhenReady) backend.play()
        invalidateState()
        return Futures.immediateVoidFuture()
    }

    override fun handleAddMediaItems(
        index: Int,
        mediaItems: MutableList<MediaItem>
    ): ListenableFuture<*> {
        logic.addMediaItems(index, mediaItems)
        if (logic.prepared && playWhenReady) backend.play()
        invalidateState()
        return Futures.immediateVoidFuture()
    }

    // -- Transport -----------------------------------------------------------------------------

    override fun handlePrepare(): ListenableFuture<*> {
        logic.prepared = true
        // Re-apply the gate recorded before any media existed, now that one may be loading.
        enforceGateOrPause()
        if (playWhenReady && requestAudioFocus()) backend.play()
        invalidateState()
        return Futures.immediateVoidFuture()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        this.playWhenReady = playWhenReady
        if (playWhenReady) {
            if (requestAudioFocus()) {
                registerNoisyReceiver()
                if (logic.prepared) backend.play()
            }
        } else {
            backend.pause()
        }
        invalidateState()
        return Futures.immediateVoidFuture()
    }

    override fun handleStop(): ListenableFuture<*> {
        backend.stop()
        playWhenReady = false
        logic.prepared = false
        abandonAudioFocus()
        invalidateState()
        return Futures.immediateVoidFuture()
    }

    override fun handleSeek(
        mediaItemIndex: Int,
        positionMs: Long,
        seekCommand: Int
    ): ListenableFuture<*> {
        when (seekCommand) {
            Player.COMMAND_SEEK_TO_NEXT,
            Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> loadStep(logic.nextIndex())
            Player.COMMAND_SEEK_TO_PREVIOUS,
            Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> loadStep(logic.previousIndex())
            Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM -> {
                if (positionMs != C.TIME_UNSET) logic.seekInItem(positionMs)
            }
            else -> {
                if (mediaItemIndex != C.INDEX_UNSET && mediaItemIndex in queue.indices) {
                    loadStep(mediaItemIndex)
                    if (positionMs != C.TIME_UNSET && positionMs > 0) logic.seekInItem(positionMs)
                }
            }
        }
        if (logic.prepared && playWhenReady) backend.play()
        invalidateState()
        return Futures.immediateVoidFuture()
    }

    private fun loadStep(index: Int) {
        logic.stepTo(index)
    }

    // -- Surface -------------------------------------------------------------------------------

    override fun handleSetVideoOutput(videoOutput: Any): ListenableFuture<*> {
        val surface: Surface? = when (videoOutput) {
            is Surface -> videoOutput
            is SurfaceHolder -> videoOutput.surface
            is SurfaceView -> videoOutput.holder.surface
            is TextureView -> videoOutput.surfaceTexture?.let(::Surface)
            else -> null
        }
        if (surface != null) backend.attachSurface(surface)
        else StructuredLog.w("VLC", "unsupported video output ${videoOutput.javaClass.simpleName}")
        return Futures.immediateVoidFuture()
    }

    override fun handleClearVideoOutput(videoOutput: Any?): ListenableFuture<*> {
        backend.detachSurface()
        return Futures.immediateVoidFuture()
    }

    // -- Aspect ratio ---------------------------------------------------------------------------

    /** Maps the app's [AspectRatio] setting onto libVLC's own aspect/scale controls. */
    fun applyAspectRatio(aspect: AspectRatio) {
        when (aspect) {
            AspectRatio.AUTO -> {
                backend.setAspectRatio(null)
                backend.setScale(0f)
            }
            AspectRatio.FILL -> {
                backend.setAspectRatio(null)
                backend.setScale(0f)
                // Fill (crop to box) is the renderer's job on the surface; libVLC keeps source
                // shape with scale 0, and the PlayerView resize mode handles the crop.
            }
            AspectRatio.STRETCH -> {
                backend.setAspectRatio(null)
                backend.setScale(1f)
            }
            AspectRatio.WIDE -> {
                backend.setAspectRatio("16:9")
                backend.setScale(0f)
            }
            AspectRatio.CLASSIC -> {
                backend.setAspectRatio("4:3")
                backend.setScale(0f)
            }
        }
    }

    // -- Parked gate ----------------------------------------------------------------------------

    override fun handleSetTrackSelectionParameters(
        trackSelectionParameters: TrackSelectionParameters
    ): ListenableFuture<*> {
        val videoDisabled = trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_VIDEO)
        // With no media loaded this is only a recorded intent — nothing is playing, nothing unsafe
        // to stop — so recordGate returns success and nothing pauses (startup never spuriously
        // fail-closes). With a media prepared it enforces now and a false return fail-closes.
        if (!logic.recordGate(videoDisabled)) failClosed("could not disable video while parked")
        invalidateState()
        return Futures.immediateVoidFuture()
    }

    /** Re-apply the stored gate on prepare / track-available; pauses if the disable fails. */
    private fun enforceGateOrPause() {
        if (!logic.reapplyGate()) failClosed("could not disable video on prepare while parked")
    }

    /** A parked user must never see moving video: if the engine will not stop it, stop playback. */
    private fun failClosed(reason: String) {
        StructuredLog.w("VLC", "$reason; pausing")
        backend.pause()
        playWhenReady = false
    }

    // -- Audio focus ----------------------------------------------------------------------------

    private fun requestAudioFocus(): Boolean {
        focusRequest?.let { return true } // already held
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(attributes)
            .setOnAudioFocusChangeListener { change ->
                when (change) {
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                        hadFocusPauseWhileTransient = playWhenReady
                        backend.pause()
                        playWhenReady = false
                        invalidateState()
                    }
                    AudioManager.AUDIOFOCUS_GAIN -> {
                        if (hadFocusPauseWhileTransient) {
                            hadFocusPauseWhileTransient = false
                            playWhenReady = true
                            if (logic.prepared) backend.play()
                            invalidateState()
                        }
                    }
                    AudioManager.AUDIOFOCUS_LOSS -> {
                        backend.pause()
                        playWhenReady = false
                        abandonAudioFocus()
                        invalidateState()
                    }
                }
            }
            .build()
        focusRequest = request
        val granted = audioManager.requestAudioFocus(request) ==
            AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        if (!granted) focusRequest = null
        return granted
    }

    private fun abandonAudioFocus() {
        focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        focusRequest = null
        hadFocusPauseWhileTransient = false
        unregisterNoisyReceiver()
    }

    private fun registerNoisyReceiver() {
        if (noisyReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                    backend.pause()
                    playWhenReady = false
                    invalidateState()
                }
            }
        }
        context.applicationContext.registerReceiver(
            receiver,
            IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
        )
        noisyReceiver = receiver
    }

    private fun unregisterNoisyReceiver() {
        noisyReceiver?.let { runCatching { context.applicationContext.unregisterReceiver(it) } }
        noisyReceiver = null
    }

    override fun handleRelease(): ListenableFuture<*> {
        abandonAudioFocus()
        backend.release()
        return Futures.immediateVoidFuture()
    }
}
