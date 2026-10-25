package dev.autobridge.projection.desktop

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dev.autobridge.R
import dev.autobridge.desktop.DesktopLog

/**
 * A notification on the phone that opens the guided Chrome setup, posted from the car's
 * "Chrome needed" screen. A car app cannot start a phone activity itself, so the driver, parked,
 * taps it, the same way the phone keyboard for Chrome is reached ([DesktopPhoneKeyboard]).
 */
object DesktopSetupNotification {
    private const val CHANNEL = "desktop_chrome_setup"
    private const val NOTIFICATION_ID = 0x4353
    private const val TIMEOUT_MS = 10 * 60_000L

    /** False when the phone does not show AutoBridge notifications (off, or the channel blocked). */
    fun post(context: Context, siteTitle: String): Boolean {
        val app = context.applicationContext
        val nm = NotificationManagerCompat.from(app)
        if (!nm.areNotificationsEnabled()) return false
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, app.getString(R.string.chrome_setup_channel), NotificationManager.IMPORTANCE_HIGH)
            )
        }
        if (nm.getNotificationChannel(CHANNEL)?.importance == NotificationManager.IMPORTANCE_NONE) return false
        val intent = DesktopChromeSetupActivity.intent(app).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        val open = PendingIntent.getActivity(app, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = NotificationCompat.Builder(app, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(app.getString(R.string.chrome_setup_title))
            .setContentText(app.getString(R.string.chrome_setup_notification_text, siteTitle))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setTimeoutAfter(TIMEOUT_MS)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        return try {
            nm.notify(NOTIFICATION_ID, notification)
            true
        } catch (e: SecurityException) {
            DesktopLog.i("chrome setup notification not allowed: ${DesktopLog.describe(e)}")
            false
        }
    }

    fun cancel(context: Context) = NotificationManagerCompat.from(context.applicationContext).cancel(NOTIFICATION_ID)
}
