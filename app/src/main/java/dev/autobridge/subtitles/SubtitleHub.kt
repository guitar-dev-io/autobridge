package dev.autobridge.subtitles

import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Carries the current subtitle line from the player to whatever is drawing it.
 *
 * The same process-wide store shape as [dev.autobridge.media.WebMediaHub] and
 * [dev.autobridge.media.VideoOutputGeometry], and for a sharper reason here: cues are not part of
 * a MediaSession's shared state, so a [androidx.media3.session.MediaController] never sees them.
 * The ExoPlayer that decodes the text track lives in
 * [dev.autobridge.media.MediaPlaybackService]; the screen that has room for a subtitle lives in
 * [dev.autobridge.library.PlayerActivity]. Both are in this process, neither owns the other, and
 * this is the one thing between them.
 *
 * Main thread only. The service publishes from its main handler and the player reads while laying
 * out, so a line never has to be made thread-safe on either side.
 */
object SubtitleHub {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<(SubtitleLine) -> Unit>()

    /** The line that should be on screen now, or [SubtitleLine.NONE] between lines. */
    var current: SubtitleLine = SubtitleLine.NONE
        private set

    fun publish(line: SubtitleLine) {
        runOnMain {
            current = line
            listeners.forEach { it(line) }
        }
    }

    /** Adds [listener] and hands it the current line, so a screen opened mid-line is not empty. */
    fun addListener(listener: (SubtitleLine) -> Unit) {
        runOnMain {
            listeners.add(listener)
            listener(current)
        }
    }

    fun removeListener(listener: (SubtitleLine) -> Unit) {
        runOnMain { listeners.remove(listener) }
    }

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post(block)
    }
}
