package dev.autobridge.radio

import android.content.Context

/**
 * The one documented, honest seam for "can this app drive a hardware FM tuner?".
 *
 * The verdict is final and deliberate: **not accessible**. No public, supported Android API lets a
 * normal app tune or read audio from a hardware FM tuner on this target. The FM tuner, where one
 * exists, lives in a head unit the phone app cannot reach through Android Auto, and the on-device
 * paths are all off-limits:
 *
 *  - `android.hardware.radio.RadioManager` / `RadioTuner` are `@SystemApi`, needing a privileged
 *    permission a user app cannot hold.
 *  - `MediaRecorder.AudioSource.RADIO_TUNER` likewise requires a privileged capture permission.
 *
 * This function therefore returns false and makes none of those calls. It is a single seam so the
 * decision is auditable in one place, and so a future privileged/system build could override it
 * without the rest of the domain inventing frequencies in the meantime.
 */
object HardwareFmProbe {
    /** False on this target: there is no app-accessible hardware FM tuner. */
    @Suppress("UNUSED_PARAMETER")
    fun isTunerAccessible(context: Context): Boolean = false
}
