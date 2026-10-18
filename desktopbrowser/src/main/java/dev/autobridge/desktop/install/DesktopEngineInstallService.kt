package dev.autobridge.desktop.install

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import dev.autobridge.desktop.DesktopEngine
import dev.autobridge.desktop.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Keeps the app alive while the engine downloads or installs, and shows what it is doing. Stops
 * itself as soon as the engine has no queued operation left.
 */
class DesktopEngineInstallService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.desktop_engine_notification_channel), NotificationManager.IMPORTANCE_LOW)
        )
        startInForeground(build(getString(R.string.desktop_engine_working), null))
        DesktopEngine.init(this)
        scope.launch {
            combine(DesktopEngine.busy, DesktopEngine.runtimeState, DesktopEngine.browserStates) { busy, runtime, browsers ->
                Triple(busy, runtime, browsers)
            }.collect { (busy, runtime, browsers) ->
                if (!busy) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                    return@collect
                }
                val active = (listOf(runtime) + browsers.values).firstOrNull { it.state.busy }
                val text = active?.let { describe(it) } ?: getString(R.string.desktop_engine_working)
                manager.notify(ID, build(text, active?.progress))
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun describe(state: ComponentState): String {
        val step = when (state.state) {
            InstallState.DOWNLOADING -> getString(R.string.desktop_state_downloading)
            InstallState.VERIFYING -> getString(R.string.desktop_state_verifying)
            InstallState.EXTRACTING -> getString(R.string.desktop_state_extracting)
            InstallState.CONFIGURING -> getString(R.string.desktop_state_configuring)
            else -> getString(R.string.desktop_engine_working)
        }
        return listOfNotNull(step, state.detail?.take(60)).joinToString(" · ")
    }

    private fun build(text: String, progress: Float?): Notification =
        NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(getString(R.string.desktop_engine_title))
            .setContentText(text)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .apply {
                if (progress == null) setProgress(0, 0, true) else setProgress(1000, (progress * 1000).toInt(), false)
            }
            .build()

    private fun startInForeground(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(ID, notification)
        }
    }

    private companion object {
        const val CHANNEL = "desktop_engine"
        const val ID = 0x0de5
    }
}
