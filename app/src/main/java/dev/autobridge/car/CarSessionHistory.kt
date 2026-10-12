package dev.autobridge.car

import android.content.Context
import androidx.core.content.edit

/**
 * Whether an AutoBridge car session has ever started on this phone.
 *
 * Android Auto lists a sideloaded app only when its installer is the Play Store or its own
 * developer "Unknown sources" setting is on, and the second cannot be read from here. A session
 * that actually started settles it: the car does list the app. Car setup uses this so it stops
 * warning about the installer once the warning is known to be moot.
 */
object CarSessionHistory {
    private const val PREFS = "autobridge_car_session_history"
    private const val KEY_STARTED = "started"

    fun markStarted(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(KEY_STARTED, false)) prefs.edit { putBoolean(KEY_STARTED, true) }
    }

    fun hasStarted(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_STARTED, false)
}
