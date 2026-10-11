package dev.autobridge.media

import android.content.Context
import android.os.Handler
import androidx.core.content.edit
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import dev.autobridge.logging.StructuredLog
import dev.autobridge.settings.VideoSettings

/**
 * The decisions behind "Remember playback position", kept free of a player and a Context so they
 * are unit-tested on their own.
 *
 * Only something with an end is remembered: a live channel has no position worth returning to,
 * and a short clip is quicker to restart than to resume. The first few seconds and the last
 * stretch are treated as "not started" and "finished", so a video someone opened by mistake does
 * not come back halfway, and one watched to the credits starts again from the top.
 */
object PlaybackPositionPolicy {
    /** Shorter than this and a clip simply restarts. */
    const val MIN_DURATION_MS = 2 * 60_000L

    /** A position before this is "not really started" and is forgotten rather than saved. */
    const val MIN_POSITION_MS = 10_000L

    /** Within this of the end counts as finished. */
    const val END_MARGIN_MS = 30_000L

    /** Resuming steps back this far, so the first words after a cut are not lost. */
    const val REWIND_MS = 3_000L

    /** How many items are remembered; the least recently saved go first. */
    const val MAX_ENTRIES = 200

    fun isRememberable(durationMs: Long, isLive: Boolean, isSeekable: Boolean): Boolean =
        !isLive && isSeekable && durationMs != C.TIME_UNSET && durationMs >= MIN_DURATION_MS

    /** The position to store, or null when the stored one should be forgotten instead. */
    fun positionToSave(positionMs: Long, durationMs: Long): Long? {
        if (positionMs < MIN_POSITION_MS) return null
        if (positionMs >= durationMs - END_MARGIN_MS) return null
        return positionMs
    }

    /** Where to resume, or null when the saved position no longer fits this item. */
    fun resumeAt(savedMs: Long, durationMs: Long): Long? {
        if (positionToSave(savedMs, durationMs) == null) return null
        return (savedMs - REWIND_MS).coerceAtLeast(0L)
    }

    /** The keys to drop so at most [MAX_ENTRIES] remain, oldest saved first. */
    fun overflow(savedAt: Map<String, Long>): List<String> {
        val excess = savedAt.size - MAX_ENTRIES
        if (excess <= 0) return emptyList()
        return savedAt.entries.sortedBy { it.value }.take(excess).map { it.key }
    }

    /** Stored as "position:savedAt"; anything else reads as nothing stored. */
    fun encode(positionMs: Long, savedAtMs: Long): String = "$positionMs:$savedAtMs"

    fun decode(value: String?): Pair<Long, Long>? {
        val parts = value?.split(':') ?: return null
        if (parts.size != 2) return null
        val position = parts[0].toLongOrNull() ?: return null
        val savedAt = parts[1].toLongOrNull() ?: return null
        return position to savedAt
    }
}

/** Saved positions, keyed by the item's URI. */
object PlaybackPositionStore {
    private const val PREFS_NAME = "autobridge_playback_positions"

    fun get(context: Context, key: String): Long? =
        PlaybackPositionPolicy.decode(prefs(context).getString(key, null))?.first

    fun put(context: Context, key: String, positionMs: Long) {
        val prefs = prefs(context)
        prefs.edit { putString(key, PlaybackPositionPolicy.encode(positionMs, System.currentTimeMillis())) }
        val savedAt = prefs.all.mapNotNull { (k, v) ->
            PlaybackPositionPolicy.decode(v as? String)?.let { k to it.second }
        }.toMap()
        val drop = PlaybackPositionPolicy.overflow(savedAt)
        if (drop.isNotEmpty()) prefs.edit { drop.forEach { remove(it) } }
    }

    fun remove(context: Context, key: String) = prefs(context).edit { remove(key) }

    fun clear(context: Context) = prefs(context).edit { clear() }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}

/**
 * Saves where each finite item was left and returns to it the next time that item comes up.
 *
 * Attached to the engine itself (ExoPlayer or the VLC facade) in [MediaPlaybackService], so it
 * works the same whichever screen started playback — the phone player, the car card or a
 * steering-wheel skip. Saving happens on pause, on leaving an item, every few seconds while
 * playing (so a killed process loses at most that much), and when the service is torn down.
 */
class PlaybackPositionMemory(
    private val context: Context,
    private val player: Player
) : Player.Listener {
    private companion object {
        const val SAVE_INTERVAL_MS = 5_000L
    }

    private val handler = Handler(player.applicationLooper)

    /** The item being watched and its last reading; null for anything not worth remembering. */
    private var trackedKey: String? = null
    private var trackedDurationMs = C.TIME_UNSET

    /** Set when an item comes up, cleared once it has been resumed or found to have nothing saved. */
    private var pendingRestoreKey: String? = null

    private val tick = object : Runnable {
        override fun run() {
            saveCurrent()
            if (player.isPlaying) handler.postDelayed(this, SAVE_INTERVAL_MS)
        }
    }

    fun start() {
        player.addListener(this)
        onItemStarted(player.currentMediaItem)
    }

    fun release() {
        saveCurrent()
        handler.removeCallbacks(tick)
        player.removeListener(this)
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        // A repeat of the same item is the end of it, already forgotten in onPositionDiscontinuity.
        onItemStarted(mediaItem)
    }

    override fun onPositionDiscontinuity(
        oldPosition: Player.PositionInfo,
        newPosition: Player.PositionInfo,
        reason: Int
    ) {
        val oldKey = keyOf(oldPosition.mediaItem) ?: return
        if (oldKey != trackedKey) return
        when {
            // Played to the end on its own: next time it starts from the top.
            reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION -> forget(oldKey)
            // Skipped or replaced mid-way: keep the exact position it was left at, which the
            // periodic save may be a few seconds behind.
            oldPosition.mediaItemIndex != newPosition.mediaItemIndex ||
                reason == Player.DISCONTINUITY_REASON_REMOVE -> save(oldKey, oldPosition.positionMs)
        }
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        if (playbackState == Player.STATE_READY) restoreIfPending()
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        handler.removeCallbacks(tick)
        if (isPlaying) handler.postDelayed(tick, SAVE_INTERVAL_MS) else saveCurrent()
    }

    private fun onItemStarted(item: MediaItem?) {
        trackedKey = null
        trackedDurationMs = C.TIME_UNSET
        pendingRestoreKey = keyOf(item)
        if (player.playbackState == Player.STATE_READY) restoreIfPending()
    }

    /**
     * Runs once the duration is known, which is not until the item is prepared. Liveness and
     * seekability are only reliable at the same point, so this is also where an item is accepted
     * for tracking at all.
     */
    private fun restoreIfPending() {
        val key = keyOf(player.currentMediaItem) ?: return
        val duration = player.duration
        if (!PlaybackPositionPolicy.isRememberable(
                duration, player.isCurrentMediaItemLive, player.isCurrentMediaItemSeekable
            )
        ) {
            if (pendingRestoreKey == key) pendingRestoreKey = null
            return
        }
        trackedKey = key
        trackedDurationMs = duration
        if (pendingRestoreKey != key) return
        pendingRestoreKey = null
        if (!VideoSettings.rememberPosition(context)) return
        // Someone already moved the playhead themselves before the item was ready; that wins.
        if (player.currentPosition >= PlaybackPositionPolicy.MIN_POSITION_MS) return
        val saved = PlaybackPositionStore.get(context, key) ?: return
        val target = PlaybackPositionPolicy.resumeAt(saved, duration) ?: return
        StructuredLog.i("MEDIA", "resuming at ${target / 1000}s of ${duration / 1000}s")
        player.seekTo(target)
    }

    private fun saveCurrent() {
        val key = trackedKey ?: return
        if (keyOf(player.currentMediaItem) != key) return
        save(key, player.currentPosition)
    }

    private fun save(key: String, positionMs: Long) {
        if (!VideoSettings.rememberPosition(context)) return
        val duration = trackedDurationMs
        if (duration == C.TIME_UNSET) return
        val position = PlaybackPositionPolicy.positionToSave(positionMs, duration)
        if (position == null) forget(key) else PlaybackPositionStore.put(context, key, position)
    }

    private fun forget(key: String) {
        if (VideoSettings.rememberPosition(context)) PlaybackPositionStore.remove(context, key)
    }

    private fun keyOf(item: MediaItem?): String? =
        item?.localConfiguration?.uri?.toString()?.takeIf { it.isNotBlank() }
}
