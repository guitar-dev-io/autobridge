package dev.autobridge.rear.wifi

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import dev.autobridge.R
import dev.autobridge.logging.StructuredLog
import java.util.concurrent.Executors

/**
 * Sends to the paired rear-screen tablet, and keeps the phone's notification for it.
 *
 * The tablet's address is the last one seen; when that no longer answers (a new hotspot address,
 * a tablet restarted) the tablet is looked up again on the Wi-Fi by its id for a few seconds, and
 * the send is retried once at the new address.
 */
object RearSender {
    private const val TAG = "REAR_WIFI_TX"
    private const val CHANNEL_ID = "rear_screen"
    private const val NOTIFICATION_ID = 4319
    private const val LOOKUP_MS = 5_000L
    const val ACTION_TOGGLE = "dev.autobridge.rear.wifi.TOGGLE"
    const val ACTION_MUTE = "dev.autobridge.rear.wifi.MUTE"
    const val ACTION_STOP = "dev.autobridge.rear.wifi.STOP"

    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    /** Whether there is a paired tablet to send to. */
    fun hasTablet(context: Context): Boolean = RearTablets.active(context) != null

    /** Plays [command] on the paired tablet; [done] gets true once the tablet took it. Main thread. */
    fun send(context: Context, command: RearProtocol.Play, done: (Boolean) -> Unit) {
        val app = context.applicationContext
        val tablet = RearTablets.active(app) ?: return done(false)
        io.execute {
            val first = RearClient.play(tablet, command)
            if (first?.ok == true) {
                main.post { finished(app, tablet, command, done) }
                return@execute
            }
            if (first != null) {
                // It answered but refused: not paired any more (the tablet forgot it).
                StructuredLog.w(TAG, "tablet refused play: ${first.code}")
                main.post { done(false) }
                return@execute
            }
            main.post { lookUpAndRetry(app, tablet, command, done) }
        }
    }

    private fun lookUpAndRetry(app: Context, tablet: RearTablets.Tablet, command: RearProtocol.Play, done: (Boolean) -> Unit) {
        var settled = false
        lateinit var finder: RearFinder
        val giveUp = Runnable {
            if (settled) return@Runnable
            settled = true
            finder.stop()
            StructuredLog.w(TAG, "tablet ${tablet.name} not found on the Wi-Fi")
            done(false)
        }
        finder = RearFinder(app) { found ->
            if (settled || found.info.id != tablet.id) return@RearFinder
            settled = true
            finder.stop()
            main.removeCallbacks(giveUp)
            RearTablets.moved(app, tablet.id, found.host, found.port)
            val moved = tablet.copy(host = found.host, port = found.port)
            io.execute {
                val ok = RearClient.play(moved, command)?.ok == true
                main.post { if (ok) finished(app, moved, command, done) else done(false) }
            }
        }
        finder.start()
        main.postDelayed(giveUp, LOOKUP_MS)
    }

    private fun finished(app: Context, tablet: RearTablets.Tablet, command: RearProtocol.Play, done: (Boolean) -> Unit) {
        StructuredLog.i(TAG, "sent to ${tablet.name} muted=${command.muted}")
        notify(app, tablet, RearProtocol.Status(playing = true, muted = command.muted, title = command.title))
        done(true)
    }

    /** Runs a notification button against the tablet, then redraws the notification from its answer. */
    fun control(context: Context, action: String) {
        val app = context.applicationContext
        val tablet = RearTablets.active(app) ?: return
        val path = when (action) {
            ACTION_TOGGLE -> "/toggle"
            ACTION_MUTE -> "/mute"
            ACTION_STOP -> "/stop"
            else -> return
        }
        io.execute {
            RearClient.command(tablet, path)
            if (action == ACTION_STOP) {
                main.post { cancel(app) }
                return@execute
            }
            // Give the tablet's player a moment to change state before asking.
            Thread.sleep(300)
            val status = RearClient.status(tablet)
            main.post { if (status != null) notify(app, tablet, status) else cancel(app) }
        }
    }

    fun cancel(context: Context) {
        context.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
    }

    private fun notify(app: Context, tablet: RearTablets.Tablet, status: RearProtocol.Status) {
        val manager = app.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, app.getString(R.string.rear_notification_channel), NotificationManager.IMPORTANCE_LOW)
        )
        fun button(label: Int, action: String, code: Int) = Notification.Action.Builder(
            null, app.getString(label),
            PendingIntent.getBroadcast(
                app, code, Intent(app, ControlReceiver::class.java).setAction(action),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        ).build()
        val text = if (status.muted) app.getString(R.string.rear_notification_muted, status.title) else status.title
        val notification = Notification.Builder(app, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_tile_tv)
            .setContentTitle(app.getString(R.string.rear_wifi_notification_title, tablet.name))
            .setContentText(text)
            .addAction(button(if (status.playing) R.string.rear_notification_pause else R.string.rear_notification_play, ACTION_TOGGLE, 11))
            .addAction(button(if (status.muted) R.string.rear_notification_unmute else R.string.rear_notification_mute, ACTION_MUTE, 12))
            .addAction(button(R.string.rear_notification_stop, ACTION_STOP, 13))
            .setOngoing(true)
            .build()
        runCatching { manager.notify(NOTIFICATION_ID, notification) }
    }

    /** The notification's buttons; declared in the manifest, not exported. */
    class ControlReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            control(context, intent.action ?: return)
        }
    }
}
