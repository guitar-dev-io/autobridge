package dev.autobridge.bridge

import androidx.annotation.StringRes
import dev.autobridge.R

/**
 * The vocabulary the media bridge is built on: what is being played, how, where it stands, and
 * what went wrong.
 *
 * Everything here is plain Kotlin with no Android dependency beyond `@StringRes`, so the routing
 * and error rules that sit on top of it are covered by ordinary JVM tests rather than by an
 * instrumentation run on a head unit.
 */

/**
 * One thing the bridge can be asked to play, from wherever it was asked.
 *
 * [positionMs] is what makes a handoff a handoff rather than a re-open: the phone writes where it
 * stands into the source, and whichever engine picks it up is responsible for honouring it as far
 * as its medium allows (see [dev.autobridge.browser.BrowserResumePoint] for why that is "as far as
 * it allows" and not "always").
 */
data class BridgeSource(
    val url: String,
    val title: String = "",
    val positionMs: Long = 0L,
    val mimeType: String? = null,
    /** Where the request came from, for logs and for the recents list. */
    val origin: Origin = Origin.PHONE
) {
    enum class Origin { PHONE, SHARE, CAR, QUEUE, RESTORE }

    /** Host without `www.`/`m.`, for a label that fits a car row. */
    val displayHost: String
        get() = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://([^/?#]+)").find(url)
            ?.groupValues?.get(1)
            ?.removePrefix("www.")
            ?.removePrefix("m.")
            ?: url

    /** What to show when the sender gave no title. */
    val displayTitle: String get() = title.ifBlank { displayHost }
}

/** Which engine a source was routed to. [UNSUPPORTED] is a decision, not a failure to decide. */
enum class EngineKind { NATIVE, BROWSER, REMOTE_STREAM, UNSUPPORTED }

/** Where playback stands, uniformly across the three engines. */
enum class BridgePlaybackState { IDLE, LOADING, PLAYING, PAUSED, ENDED, ERROR }

/**
 * The error classes the bridge reports. The point of the enum is that every failure path lands on
 * one of a known set, so the UI can say something useful without ever printing an exception at the
 * user: the raw cause goes to [BridgeLog], the [messageRes] goes to the screen.
 */
enum class BridgeErrorType(@StringRes val messageRes: Int) {
    NETWORK_ERROR(R.string.bridge_error_network),
    UNSUPPORTED_CONTENT(R.string.bridge_error_unsupported),
    PLAYBACK_ERROR(R.string.bridge_error_playback),
    WEB_RENDER_ERROR(R.string.bridge_error_web_render),
    REMOTE_STREAM_ERROR(R.string.bridge_error_remote_stream),
    DRM_PROTECTED_CONTENT(R.string.bridge_error_drm),
    CAR_NOT_CONNECTED(R.string.bridge_error_not_connected)
}

/**
 * A failure with a cause kept apart from the message.
 *
 * [detail] is for the log and the diagnostics screen only. Nothing user-facing reads it, which is
 * what keeps a stack trace or an ExoPlayer error code off the car screen.
 */
data class BridgeError(
    val type: BridgeErrorType,
    val detail: String? = null
) {
    @get:StringRes
    val messageRes: Int get() = type.messageRes
}

/** A snapshot of one engine's progress, polled by the UIs rather than pushed per frame. */
data class EngineState(
    val kind: EngineKind,
    val playback: BridgePlaybackState = BridgePlaybackState.IDLE,
    val source: BridgeSource? = null,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val error: BridgeError? = null
) {
    val isLoading: Boolean get() = playback == BridgePlaybackState.LOADING
    val isPlaying: Boolean get() = playback == BridgePlaybackState.PLAYING
}
