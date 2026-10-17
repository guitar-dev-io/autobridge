package dev.autobridge.radio

import android.content.Context
import dev.autobridge.R

/**
 * Local offline audio as a source. Modelled so the three-type source model is complete, but unwired
 * this pass: [isAvailable] is false and the UI hides the offline segment. It shares the single
 * Media3 session the same way internet radio does — there is no second player — whenever a later
 * pass wires it up.
 */
object OfflineAudioSource : RadioSource {
    override val type: RadioSourceType = RadioSourceType.OFFLINE_AUDIO

    override fun isAvailable(context: Context): Boolean = false

    override fun unavailableReason(context: Context): String? =
        if (isAvailable(context)) null
        else context.getString(R.string.radio_offline_unavailable)
}
