package dev.autobridge.obd

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import dev.autobridge.R

/**
 * Holds the OBD connection's process in the foreground while an adapter is connected, so Android
 * does not kill the app (and the link) when the screen is off or another app is on top. It owns no
 * connection itself: it starts with [ObdManager.connect] and ends when the manager disconnects.
 */
class ObdService : Service() {
    private val listener = object : ObdManager.Listener {
        override fun onStatus(status: ObdManager.Status) {
            if (status.state == ObdManager.State.DISCONNECTED) stopSelf() else update(status)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.obd_title), NotificationManager.IMPORTANCE_LOW))
        startForeground(NOTIFICATION_ID, build(ObdManager.status), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        ObdManager.addListener(listener)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_DISCONNECT) ObdManager.disconnect()
        // START_NOT_STICKY: a process that died took the connection with it, so nothing to restart.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        ObdManager.removeListener(listener)
        super.onDestroy()
    }

    private fun update(status: ObdManager.Status) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, build(status))
    }

    private fun build(status: ObdManager.Status): Notification {
        val open = PendingIntent.getActivity(this, 0, ObdActivity.intent(this), PendingIntent.FLAG_IMMUTABLE)
        val disconnect = PendingIntent.getService(
            this, 1, Intent(this, ObdService::class.java).setAction(ACTION_DISCONNECT), PendingIntent.FLAG_IMMUTABLE
        )
        val text = if (status.state == ObdManager.State.CONNECTED) getString(R.string.obd_status_connected, status.adapterName ?: "")
        else getString(R.string.obd_status_connecting, status.adapterName ?: "")
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_car_obd)
            .setContentTitle(getString(R.string.obd_title))
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, getString(R.string.obd_disconnect), disconnect).build())
            .build()
    }

    private companion object {
        const val CHANNEL = "obd_connection"
        const val NOTIFICATION_ID = 4108
        const val ACTION_DISCONNECT = "dev.autobridge.obd.DISCONNECT"
    }
}
