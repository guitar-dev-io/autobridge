package dev.autobridge.breakreminder

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.core.app.NotificationCompat
import androidx.core.content.edit
import dev.autobridge.R
import dev.autobridge.logging.StructuredLog

/**
 * "Time for a break" while a car session is up. No location and no sensors: it counts the time the
 * car has been connected and says so every N hours (off by default; the driver picks N in Settings).
 * A toast on the car screen, and a phone notification when notifications are allowed.
 *
 * The timer lives only as long as the car session: when the car disconnects it is cancelled, so the
 * count starts again at the next connection rather than carrying a half-finished drive over.
 */
object BreakReminder {
    private const val TAG = "BREAK"
    private const val PREFS = "autobridge_break_reminder"
    private const val KEY_HOURS = "hours"
    private const val CHANNEL_ID = "autobridge_break"
    private const val NOTIFICATION_ID = 0x4252 // "BR"

    private val main = Handler(Looper.getMainLooper())
    private var tick: Runnable? = null

    /** Hours between reminders, 0 for off. */
    fun hours(context: Context): Int =
        BreakReminderOptions.sanitize(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_HOURS, 0))

    fun setHours(context: Context, hours: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putInt(KEY_HOURS, BreakReminderOptions.sanitize(hours)) }
    }

    /** Starts counting for this car session; a no-op when the reminder is off. */
    fun start(carContext: CarContext) {
        stop()
        val interval = BreakReminderOptions.intervalMs(hours(carContext)) ?: return
        val runnable = object : Runnable {
            override fun run() {
                remind(carContext)
                // The choice may have changed (or been switched off) since: read it again each time.
                BreakReminderOptions.intervalMs(hours(carContext))?.let { main.postDelayed(this, it) }
            }
        }
        tick = runnable
        main.postDelayed(runnable, interval)
        StructuredLog.i(TAG, "break reminder every ${interval / 3_600_000L} h")
    }

    fun stop() {
        tick?.let { main.removeCallbacks(it) }
        tick = null
    }

    private fun remind(carContext: CarContext) {
        val message = carContext.getString(R.string.break_reminder_message)
        runCatching { CarToast.makeText(carContext, message, CarToast.LENGTH_LONG).show() }
        notify(carContext.applicationContext, message)
        StructuredLog.i(TAG, "reminded")
    }

    private fun notify(context: Context, message: String) {
        val allowed = context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        val manager = context.getSystemService(NotificationManager::class.java)
        if (!allowed || !manager.areNotificationsEnabled()) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, context.getString(R.string.break_reminder_title), NotificationManager.IMPORTANCE_DEFAULT)
        )
        manager.notify(
            NOTIFICATION_ID,
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_menu_recent_history)
                .setContentTitle(context.getString(R.string.break_reminder_title))
                .setContentText(message)
                .setAutoCancel(true)
                .build()
        )
    }
}
