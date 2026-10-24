package dev.autobridge.projection.desktop

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dev.autobridge.R
import dev.autobridge.desktop.DesktopLog
import dev.autobridge.desktop.keyboard.TypingTarget

/**
 * The car's "Phone" keyboard choice: a notification on the phone that opens
 * [DesktopPhoneKeyboardActivity], where the phone's own keyboard types into Chrome on the car.
 * An activity cannot be started from the background, so the driver (parked) taps it.
 */
object DesktopPhoneKeyboard {
    const val EXTRA_TARGET = "target"
    private const val CHANNEL = "desktop_keyboard"
    private const val NOTIFICATION_ID = 0x4b42
    private const val TIMEOUT_MS = 120_000L

    /**
     * Posts the notification. False when typing is not allowed now ([KeyboardTypingGate]) or the
     * phone does not show AutoBridge notifications (off, or the channel blocked).
     */
    fun request(context: Context, target: TypingTarget): Boolean {
        if (!KeyboardTypingGate.allowed()) return false
        val app = context.applicationContext
        val nm = NotificationManagerCompat.from(app)
        if (!nm.areNotificationsEnabled()) return false
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, app.getString(R.string.desktop_keyboard_channel), NotificationManager.IMPORTANCE_HIGH)
            )
        }
        if (nm.getNotificationChannel(CHANNEL)?.importance == NotificationManager.IMPORTANCE_NONE) return false
        val intent = Intent(app, DesktopPhoneKeyboardActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(EXTRA_TARGET, keyOf(target))
        val open = PendingIntent.getActivity(app, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = NotificationCompat.Builder(app, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_edit)
            .setContentTitle(app.getString(R.string.desktop_keyboard_companion_title))
            .setContentText(app.getString(R.string.desktop_keyboard_notification_text))
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
            DesktopLog.i("phone keyboard notification not allowed: ${DesktopLog.describe(e)}")
            false
        }
    }

    /** Removes the notification once the companion is open. */
    fun cancel(context: Context) = NotificationManagerCompat.from(context.applicationContext).cancel(NOTIFICATION_ID)

    fun keyOf(target: TypingTarget): String = if (target == TypingTarget.ADDRESS) "address" else "field"

    /** `address` → the address bar; anything else → the page field. */
    fun targetOf(key: String?): TypingTarget = if (key == "address") TypingTarget.ADDRESS else TypingTarget.FIELD
}
