package dev.autobridge.iptv

import android.content.Context
import dev.autobridge.core.crypto.SecretPrefs
import dev.autobridge.radio.RadioSourceType

/**
 * The user's chosen radio source (Tier B preference), persisted so a selection made on the phone —
 * including a hardware-FM selection the phone cannot itself play — survives to the car.
 *
 * Only the *selected* source is stored here. The *active* source (what owns the shared Media3
 * queue and audio) is implied by the session queue itself, so it is never persisted separately.
 * Written through [SecretPrefs] to match the app's at-rest storage policy, though it holds no
 * credential.
 */
object RadioPreferencesRepository {
    private const val PREFS_NAME = "autobridge_radio_prefs"
    private const val KEY_SELECTED_SOURCE = "selected_source"

    /** The user's selected source, defaulting to internet radio. */
    fun selectedSource(context: Context): RadioSourceType {
        val raw = SecretPrefs.read(prefs(context), PREFS_NAME, KEY_SELECTED_SOURCE)
        return runCatching { RadioSourceType.valueOf(raw) }
            .getOrDefault(RadioSourceType.INTERNET_RADIO)
    }

    /** Remembers [type] as the user's selected source. */
    fun setSelectedSource(context: Context, type: RadioSourceType) {
        SecretPrefs.write(prefs(context), PREFS_NAME, KEY_SELECTED_SOURCE, type.name)
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
