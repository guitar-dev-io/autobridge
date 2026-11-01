package dev.autobridge.obd

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.content.ContextCompat
import dev.autobridge.R
import dev.autobridge.logging.StructuredLog

/**
 * Keeps the process, and so the adapter link, alive while the gauges are being read.
 *
 * It holds no connection itself; [ObdConnection] starts it with the connection and stops it with
 * the connection. If Android refuses a foreground service at that moment (started from the
 * background without an exemption, or the Bluetooth permission is missing) the service ends and
 * the connection carries on for as long as the process lives.
 */
class ObdService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            ObdConnection.stop()
            stopSelf()
            return START_NOT_STICKY
        }
        val started = runCatching {
            createChannel()
            startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        }.onFailure { StructuredLog.w(TAG, "Foreground service refused: $it") }.isSuccess
        if (!started) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_NOT_STICKY
    }

    private fun createChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.obd_notification_channel), NotificationManager.IMPORTANCE_LOW)
        )
    }

    private fun notification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, ObdGaugesActivity.intent(this), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, ObdService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_tile_gauge)
            .setContentTitle(getString(R.string.obd_notification_title))
            .setContentText(getString(R.string.obd_notification_text))
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, getString(R.string.obd_notification_stop), stop).build())
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "ObdService"
        private const val CHANNEL_ID = "obd_gauges"
        private const val NOTIFICATION_ID = 4217
        private const val ACTION_STOP = "dev.autobridge.obd.STOP"

        fun start(context: Context) {
            runCatching {
                ContextCompat.startForegroundService(context, Intent(context, ObdService::class.java))
            }.onFailure { StructuredLog.w(TAG, "Could not start the service: $it") }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, ObdService::class.java)) }
        }
    }
}
