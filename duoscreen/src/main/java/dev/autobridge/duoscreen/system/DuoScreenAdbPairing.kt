package dev.autobridge.duoscreen.system

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings
import dev.autobridge.duoscreen.DuoLog
import dev.autobridge.duoscreen.DuoScreenHost
import dev.autobridge.duoscreen.R
import io.github.muntashirakon.adb.android.AdbMdns
import kotlin.concurrent.thread

/**
 * Pairing AutoBridge with this phone's Wireless debugging, on the phone itself.
 *
 * The "Pair device with pairing code" dialog in Settings closes as soon as another app comes to
 * the front, so the code is typed into a notification instead (its reply box opens over the
 * dialog without closing it). Meanwhile the dialog's port is found on the network (mDNS
 * `_adb-tls-pairing._tcp`), so the driver only types the six digits.
 */
object DuoScreenAdbPairing {
    private const val TAG = "AutoBridgeDuoAdbPair"
    private const val CHANNEL_ID = "duo_adb_pairing"
    private const val NOTIFICATION_ID = 4331
    internal const val KEY_CODE = "code"
    internal const val ACTION_PAIR = "dev.autobridge.duoscreen.ADB_PAIR"

    @Volatile private var pairingHost: String? = null
    @Volatile private var pairingPort: Int = -1
    private var discovery: AdbMdns? = null

    /**
     * Starts looking for the pairing dialog, posts the notification to type the code in, and
     * opens Developer options so the driver can go to Wireless debugging › Pair device with
     * pairing code.
     */
    fun begin(context: Context) {
        val app = context.applicationContext
        startDiscovery(app)
        notify(app, app.getString(R.string.duo_screen_adb_pair_text), withReply = true)
        runCatching {
            app.startActivity(
                Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    fun cancel(context: Context) {
        stopDiscovery()
        context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
    }

    @Synchronized
    private fun startDiscovery(context: Context) {
        if (discovery?.isRunning == true) return
        discovery = AdbMdns(context, AdbMdns.SERVICE_TYPE_TLS_PAIRING) { host, port ->
            if (host != null && port > 0) {
                pairingHost = host.hostAddress
                pairingPort = port
                DuoLog.i(TAG, "Pairing dialog found on port $port")
            }
        }.also { it.start() }
    }

    @Synchronized
    private fun stopDiscovery() {
        runCatching { discovery?.stop() }
        discovery = null
    }

    /** The code typed into the notification; pairs on a worker thread and reports back. */
    internal fun onCode(context: Context, code: String, done: () -> Unit) {
        val app = context.applicationContext
        val port = pairingPort
        if (port <= 0) {
            notify(app, app.getString(R.string.duo_screen_adb_pair_no_dialog), withReply = true)
            done()
            return
        }
        notify(app, app.getString(R.string.duo_screen_adb_pair_working), withReply = false)
        thread(name = "DuoAdbPair", isDaemon = true) {
            // The dialog listens on the phone's own Wi-Fi address; loopback works on most phones
            // and is tried first, then the address the dialog announced.
            val hosts = listOfNotNull("127.0.0.1", pairingHost).distinct()
            val paired = hosts.any { host -> DuoScreenAdbOps.pair(app, host, port, code) }
            if (!paired) {
                val reason = DuoScreenAdbOps.lastError.orEmpty()
                notify(app, app.getString(R.string.duo_screen_adb_pair_failed, reason), withReply = true)
                done()
                return@thread
            }
            stopDiscovery()
            notify(app, app.getString(R.string.duo_screen_adb_pair_ok), withReply = false)
            // The broadcast is done once paired; connecting can take longer than it may run.
            done()
            DuoLog.i(TAG, "Paired; connecting")
            val result = DuoScreenAdbOps.start(app)
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                if (result == DuoScreenAdbOps.Result.READY) {
                    DuoScreenHost.onBackendReady()
                    app.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
                }
            }
        }
    }

    private fun notify(context: Context, text: String, withReply: Boolean) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.duo_screen_adb_pair_channel),
                NotificationManager.IMPORTANCE_HIGH,
            )
        )
        val builder = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle(context.getString(R.string.duo_screen_adb_pair_title))
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setOnlyAlertOnce(true)
            .setAutoCancel(false)
        if (withReply) {
            val reply = PendingIntent.getBroadcast(
                context, 0,
                Intent(context, DuoScreenAdbPairReceiver::class.java).setAction(ACTION_PAIR),
                // RemoteInput needs the system to write the typed code into the intent.
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
            val input = RemoteInput.Builder(KEY_CODE)
                .setLabel(context.getString(R.string.duo_screen_adb_pair_hint))
                .build()
            builder.addAction(
                Notification.Action.Builder(null, context.getString(R.string.duo_screen_adb_pair_action), reply)
                    .addRemoteInput(input)
                    .build()
            )
        }
        runCatching { manager.notify(NOTIFICATION_ID, builder.build()) }
            .onFailure { DuoLog.w(TAG, "Could not post the pairing notification", it) }
    }
}

/** Receives the pairing code typed into [DuoScreenAdbPairing]'s notification. */
class DuoScreenAdbPairReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != DuoScreenAdbPairing.ACTION_PAIR) return
        val code = RemoteInput.getResultsFromIntent(intent)
            ?.getCharSequence(DuoScreenAdbPairing.KEY_CODE)
            ?.toString()
            ?.filter { it.isDigit() }
            .orEmpty()
        if (code.isEmpty()) return
        val pending = goAsync()
        DuoScreenAdbPairing.onCode(context, code) { pending.finish() }
    }
}
