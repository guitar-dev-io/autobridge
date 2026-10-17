package dev.autobridge.radio

import android.content.Context
import dev.autobridge.R

/**
 * The hardware FM tuner as a source. Its availability is exactly what [HardwareFmProbe] reports —
 * false on this target — so FM controls are shown unavailable rather than pretending the phone can
 * tune a tuner it cannot reach.
 */
object HardwareFmSource : RadioSource {
    override val type: RadioSourceType = RadioSourceType.HARDWARE_FM

    override fun isAvailable(context: Context): Boolean =
        HardwareFmProbe.isTunerAccessible(context)

    override fun unavailableReason(context: Context): String? =
        if (isAvailable(context)) null
        else context.getString(R.string.radio_fm_unavailable)
}
