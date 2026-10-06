package dev.autobridge.maintenance

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.content.edit
import dev.autobridge.R
import dev.autobridge.logging.StructuredLog

/**
 * Tells the driver what is due or close to it. There is no background timer: it runs when the app
 * is opened and when the car connects — the moments the driver is about to drive — and speaks at
 * most once a day, so it is a nudge and not a nag. Nothing happens when notifications are off.
 */
object MaintenanceReminder {
    private const val TAG = "MAINT"
    private const val CHANNEL_ID = "autobridge_maintenance"
    private const val NOTIFICATION_ID = 0x4D41 // "MA"
    private const val PREFS = "autobridge_maintenance_reminder"
    private const val KEY_LAST = "last_notified"
    private const val MIN_GAP_MS = 20 * 60 * 60 * 1000L

    fun check(context: Context, nowMs: Long = System.currentTimeMillis()) {
        val app = context.applicationContext
        val items = MaintenanceStore.all(app)
        if (items.isEmpty()) return
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (nowMs - prefs.getLong(KEY_LAST, 0L) < MIN_GAP_MS) return
        val allowed = app.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        val manager = app.getSystemService(NotificationManager::class.java)
        if (!allowed || !manager.areNotificationsEnabled()) return

        val due = MaintenanceStats.ordered(
            items, MaintenanceStore.currentOdometer(app), nowMs, kmPerDay = MaintenanceStore.kmPerDay(app)
        ).filter { it.state != DueState.OK }
        if (due.isEmpty()) return

        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, app.getString(R.string.maint_title), NotificationManager.IMPORTANCE_DEFAULT)
        )
        val lines = due.map { "${MaintenanceText.badge(it.state)} ${it.item.name}: ${MaintenanceText.standing(app, it)}" }
        val open = PendingIntent.getActivity(
            app, 0,
            MaintenanceActivity.intent(app).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = NotificationCompat.Builder(app, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentTitle(app.resources.getQuantityString(R.plurals.maint_reminder_title, due.size, due.size))
            .setContentText(lines.first())
            .setStyle(NotificationCompat.BigTextStyle().bigText(lines.joinToString("\n")))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        manager.notify(NOTIFICATION_ID, notification)
        prefs.edit { putLong(KEY_LAST, nowMs) }
        StructuredLog.i(TAG, "reminded of ${due.size} item(s)")
    }
}
