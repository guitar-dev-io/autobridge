package dev.autobridge.radio

import android.content.Context

/**
 * A kind of radio the app can offer, and whether this device can actually use it right now.
 *
 * [isAvailable] is the single, honest gate: a source that returns false is never activated and its
 * controls are shown as unavailable with [unavailableReason]. Nothing here claims a capability the
 * hardware or platform does not expose — see [HardwareFmSource]/[HardwareFmProbe].
 */
interface RadioSource {
    val type: RadioSourceType

    /** Whether this source can be made active on this device right now. */
    fun isAvailable(context: Context): Boolean

    /** A localized reason the source is unavailable, or null when it is available. */
    fun unavailableReason(context: Context): String?
}
