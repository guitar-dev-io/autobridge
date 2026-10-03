package dev.autobridge.safety

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import dev.autobridge.R
import dev.autobridge.display.StructuredLog

/**
 * Surfaces [BypassPolicyStore] changes as a status notification, and keeps a quiet standing
 * notification while the bypass is on so the driver always sees that the safety gate is open.
 *
 * Install once from the Application: [install] registers the channel and a listener. The listener
 * posts a fresh notification on every change (so turning the bypass on/off is visible), and while
 * the bypass stays on the notification is ongoing — the car-safety equivalent of a "mic in use"
 * chip. When the bypass is off, a one-shot notice is posted and then auto-cancels.
 *
 * The notification's action button fires the same broadcast the shell uses, so the one-tap toggle
 * and `adb` go through one path ([BypassControlReceiver]).
 */
object BypassNotifier {
    private const val TAG = "BypassNotifier"
    private const val CHANNEL_ID = "autobridge_bypass"
    private const val NOTIFICATION_ID = 0x4259 // "BY"

    @Volatile
    private var installed = false

    private val listener: (BypassPolicyStore.State) -> Unit = { state ->
        appContext?.let { render(it, state) }
    }

    @Volatile
    private var appContext: Context? = null

    fun install(context: Context) {
        val app = context.applicationContext
        appContext = app
        if (installed) return
        installed = true
        createChannel(app)
        BypassPolicyStore.addListener(listener)
        // Reflect whatever the persisted state already is (e.g. bypass left on across a restart).
        render(app, BypassPolicyStore.state)
        StructuredLog.i(TAG, "installed")
    }

    private fun createChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.bypass_channel_name),
                // LOW: visible and persistent, but silent — this is a status chip, not an alert.
                NotificationManager.IMPORTANCE_LOW
            )
        )
    }

    private fun render(context: Context, state: BypassPolicyStore.State) {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (!state.enabled) {
            // Post a short "restored" notice, auto-cancelling, then clear the standing chip.
            manager.notify(NOTIFICATION_ID, buildNotification(context, enabled = false))
            StructuredLog.i(TAG, "notify: bypass OFF")
            return
        }
        manager.notify(NOTIFICATION_ID, buildNotification(context, enabled = true))
        StructuredLog.i(TAG, "notify: bypass ON (scope=${state.scope})")
    }

    private fun buildNotification(context: Context, enabled: Boolean): Notification {
        val toggleAction = if (enabled) BypassControlReceiver.ACTION_OFF else BypassControlReceiver.ACTION_ON
        val actionLabel = context.getString(
            if (enabled) R.string.bypass_notification_action_turn_off
            else R.string.bypass_notification_action_turn_on
        )
        val actionIntent = PendingIntent.getBroadcast(
            context,
            if (enabled) 1 else 0,
            Intent(context, BypassControlReceiver::class.java).setAction(toggleAction),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val title = context.getString(
            if (enabled) R.string.bypass_notification_title_on else R.string.bypass_notification_title_off
        )
        val text = context.getString(
            if (enabled) R.string.bypass_notification_text_on else R.string.bypass_notification_text_off
        )

        return Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(
                if (enabled) android.R.drawable.ic_lock_idle_lock
                else android.R.drawable.ic_lock_lock
            )
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            // On while enabled (cannot be swiped away); auto-cancels the one-shot "off" notice.
            .setOngoing(enabled)
            .setAutoCancel(!enabled)
            .addAction(
                Notification.Action.Builder(null, actionLabel, actionIntent).build()
            )
            .build()
    }
}
