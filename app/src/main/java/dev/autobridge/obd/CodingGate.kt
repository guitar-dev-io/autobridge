package dev.autobridge.obd

import android.content.Context
import android.os.SystemClock
import dev.autobridge.BuildConfig
import dev.autobridge.R
import dev.autobridge.display.SurfaceProfile

/**
 * Coding (writing module configuration) is hidden. It appears only when all three hold: the build
 * is not the `safe` flavor, the active car profile is Ford, and the build number on the About
 * screen has been tapped ten times (tapping ten more times hides it again). Nothing in the normal
 * UI mentions it before then.
 */
object CodingGate {
    private const val PREFS = "obd"
    private const val KEY = "coding_unlocked"
    private const val TAPS = 10
    private const val REVEAL_FROM = 6
    private const val WINDOW_MS = 2_000L

    private var count = 0
    private var lastTap = 0L

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun unlocked(context: Context): Boolean = prefs(context).getBoolean(KEY, false)

    /** Whether the Coding page may be opened right now. */
    fun available(context: Context): Boolean =
        BuildConfig.AUTOBRIDGE_MODE != "SAFE" && unlocked(context) &&
            (SurfaceProfile.active == SurfaceProfile.FORD_NEXT_GEN || ObdSettings.profileId(context).startsWith("ford"))

    /** Counts a tap on the build number; returns a message to show, or null while it is quiet. */
    fun onBuildTap(context: Context): String? {
        if (BuildConfig.AUTOBRIDGE_MODE == "SAFE") return null
        val now = SystemClock.elapsedRealtime()
        count = if (now - lastTap > WINDOW_MS) 1 else count + 1
        lastTap = now
        if (count >= TAPS) {
            count = 0
            val enable = !unlocked(context)
            prefs(context).edit().putBoolean(KEY, enable).apply()
            return context.getString(if (enable) R.string.coding_unlocked else R.string.coding_hidden)
        }
        return if (count >= REVEAL_FROM) context.getString(R.string.coding_taps_left, TAPS - count) else null
    }
}
