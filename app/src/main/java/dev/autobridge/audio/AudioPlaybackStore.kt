package dev.autobridge.audio

import android.content.Context
import androidx.core.content.edit

/**
 * User preferences for how playback reacts to the system's audio focus.
 *
 * This exists for one concrete car behaviour: selecting reverse gear plays a short rear-camera /
 * parking chime through the car's audio, and the system delivers that to us as a media focus loss.
 * The default focus policy pauses on a loss, so the music died the instant the driver put the car
 * in R. Some drivers would rather the music simply carry on through that chime.
 *
 * The toggle is honest about its cost: "keep playing through focus loss" means playback ignores
 * *every* focus loss, so a phone call or a navigation prompt will no longer pause or quiet it
 * either — they reach the app through the same signal. It is off by default so the standard,
 * well-behaved audio etiquette is what a fresh install gets.
 */
object AudioPlaybackStore {
    private const val PREFS_NAME = "autobridge_audio"
    private const val KEY_KEEP_PLAYING_THROUGH_FOCUS_LOSS = "keep_playing_through_focus_loss"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun keepPlayingThroughFocusLoss(context: Context): Boolean =
        prefs(context).getBoolean(KEY_KEEP_PLAYING_THROUGH_FOCUS_LOSS, false)

    fun setKeepPlayingThroughFocusLoss(context: Context, enabled: Boolean) {
        prefs(context).edit { putBoolean(KEY_KEEP_PLAYING_THROUGH_FOCUS_LOSS, enabled) }
    }
}
