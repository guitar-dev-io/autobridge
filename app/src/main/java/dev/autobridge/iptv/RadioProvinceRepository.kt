package dev.autobridge.iptv

import android.content.Context
import dev.autobridge.core.crypto.SecretPrefs

/**
 * The user's chosen Thai province for the Local radio category, persisted so a selection made on the
 * phone survives to the car and back. Its own prefs file keeps the concern isolated and independently
 * clearable. Province selection is manual — no [ACCESS_*_LOCATION] permission, no geolocation API.
 *
 * Written through [SecretPrefs] to match the app's at-rest storage policy, exactly as
 * [RadioPreferencesRepository] does; the stored value is the radio-browser `state` spelling. The
 * one validity rule lives in [ThaiProvinces.isKnown]: an unknown value is never written, and an
 * unknown stored value reads back as "none chosen".
 */
object RadioProvinceRepository {
    private const val PREFS_NAME = "autobridge_radio_province"
    private const val KEY_SELECTED_STATE = "selected_state"

    /** The chosen province's radio-browser `state`, or "" when none is set or the stored one is unknown. */
    fun selected(context: Context): String {
        val raw = SecretPrefs.read(prefs(context), PREFS_NAME, KEY_SELECTED_STATE)
        return if (ThaiProvinces.isKnown(raw)) raw else ""
    }

    /** Persists [state] only if it is a known Thai province; a blank/unknown value is ignored. */
    fun setSelected(context: Context, state: String) {
        if (!ThaiProvinces.isKnown(state)) return
        SecretPrefs.write(prefs(context), PREFS_NAME, KEY_SELECTED_STATE, state)
    }

    /** Forgets the chosen province. */
    fun clear(context: Context) =
        SecretPrefs.write(prefs(context), PREFS_NAME, KEY_SELECTED_STATE, "")

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
