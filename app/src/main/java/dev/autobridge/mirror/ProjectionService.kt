package dev.autobridge.mirror

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import dev.autobridge.MainActivity
import dev.autobridge.apps.AppRotationController
import dev.autobridge.apps.QuickAppLauncher
import dev.autobridge.display.MirrorDiagnostics
import dev.autobridge.display.MirrorOrientationController
import dev.autobridge.display.OrientationMonitor
import dev.autobridge.display.ScreenPowerController
import dev.autobridge.display.StructuredLog
import dev.autobridge.input.ShizukuInputBackend
import dev.autobridge.settings.MirrorSettings
import dev.autobridge.settings.SettingsStore

class ProjectionService : Service() {
    enum class SessionState { IDLE, STARTING, READY, ERROR }

    companion object {
        private const val TAG = "AutoBridgeProjection"
        private const val CHANNEL_ID = "autobridge_projection"
        private const val NOTIFICATION_ID = 2107
        private const val ACTION_START = "dev.autobridge.action.START_PROJECTION"
        private const val ACTION_STOP = "dev.autobridge.action.STOP_PROJECTION"
        private const val EXTRA_RESULT_CODE = "result_code"
        private const val EXTRA_RESULT_DATA = "result_data"
        private const val SURFACE_DISCONNECT_GRACE_MS = 2_000L
        private val mainHandler = Handler(Looper.getMainLooper())
        private var pendingDisconnectStop: Runnable? = null

        @Volatile
        var sessionState: SessionState = SessionState.IDLE
            private set

        @Volatile
        var lastErrorMessage: String? = null
            private set

        fun onCarSurfaceConnected() {
            pendingDisconnectStop?.let(mainHandler::removeCallbacks)
            pendingDisconnectStop = null
        }

        fun onCarSurfaceDisconnected(context: Context) {
            pendingDisconnectStop?.let(mainHandler::removeCallbacks)
            pendingDisconnectStop = null
            if (!MirrorSettings.stopOnDisconnect) return

            val appContext = context.applicationContext
            val stopRunnable = Runnable {
                pendingDisconnectStop = null
                if (MirrorSettings.stopOnDisconnect &&
                    !MirrorCoordinator.isCarSurfaceReady &&
                    sessionState != SessionState.IDLE
                ) {
                    StructuredLog.i(TAG, "Android Auto surface disconnected; stopping projection")
                    stop(appContext)
                }
            }
            pendingDisconnectStop = stopRunnable
            mainHandler.postDelayed(stopRunnable, SURFACE_DISCONNECT_GRACE_MS)
        }

        fun refreshScreenPowerPolicy(context: Context) {
            if (sessionState == SessionState.READY && MirrorCoordinator.isProjectionReady) {
                ScreenPowerController.start(
                    context,
                    preventScreenSleep = MirrorSettings.preventScreenSleep,
                    autoDimDelay = MirrorSettings.autoDimDelay
                )
            }
        }

        fun start(context: Context, resultCode: Int, data: Intent): Boolean {
            pendingDisconnectStop?.let(mainHandler::removeCallbacks)
            pendingDisconnectStop = null
            QuickAppLauncher.resetAutoLaunchSession()
            sessionState = SessionState.STARTING
            lastErrorMessage = null
            val intent = Intent(context, ProjectionService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_RESULT_DATA, data)
            }
            return runCatching {
                context.startForegroundService(intent)
                true
            }.getOrElse { error ->
                markError(error)
                false
            }
        }

        fun stop(context: Context) {
            pendingDisconnectStop?.let(mainHandler::removeCallbacks)
            pendingDisconnectStop = null
            ScreenPowerController.stop()
            context.stopService(Intent(context, ProjectionService::class.java))
            sessionState = SessionState.IDLE
            lastErrorMessage = null
        }

        private fun markReady() {
            sessionState = SessionState.READY
            lastErrorMessage = null
        }

        private fun markIdle() {
            sessionState = SessionState.IDLE
            lastErrorMessage = null
        }

        private fun markError(error: Throwable) {
            sessionState = SessionState.ERROR
            lastErrorMessage = error.message ?: error.javaClass.simpleName
        }
    }

    private var projection: MediaProjection? = null

    override fun onCreate() {
        super.onCreate()
        SettingsStore.restore(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                markIdle()
                stopProjectionAndSelf()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                try {
                    startProjection(intent)
                } catch (error: RuntimeException) {
                    markError(error)
                    StructuredLog.e(TAG, "Projection startup failed; fresh consent required: ${error.message}")
                    stopProjectionAndSelf()
                }
            }
            else -> {
                markIdle()
                stopProjectionAndSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun startProjection(intent: Intent) {
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Int.MIN_VALUE)
        @Suppress("DEPRECATION")
        val resultData = intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)

        if (resultCode != Activity.RESULT_OK || resultData == null) {
            val error = IllegalArgumentException("Missing MediaProjection consent result")
            markError(error)
            Log.e(TAG, error.message, error)
            stopProjectionAndSelf()
            return
        }

        MirrorDiagnostics.record("consent_received")
        startAsProjectionForegroundService()

        releaseProjection()

        val manager = getSystemService(MediaProjectionManager::class.java)
        val newProjection = manager.getMediaProjection(resultCode, resultData)
            ?: error("No projection returned for consent")
        projection = newProjection
        MirrorOrientationController.onProjectionStarted(this)

        newProjection.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                if (projection !== newProjection) return
                StructuredLog.i(TAG, "MediaProjection stopped by system/user")
                projection = null
                MirrorCoordinator.stopProjection()
                ShizukuInputBackend.unbind(this@ProjectionService)
                restoreRotationAfterProjectionStop()
                markIdle()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }, null)

        MirrorCoordinator.attachProjection(newProjection)
        if (ShizukuInputBackend.isPermissionGranted) ShizukuInputBackend.bind(this)
        OrientationMonitor.start(this)
        ScreenPowerController.start(
            this,
            preventScreenSleep = MirrorSettings.preventScreenSleep,
            autoDimDelay = MirrorSettings.autoDimDelay
        )
        markReady()
        if (MirrorCoordinator.isCarSurfaceReady) {
            QuickAppLauncher.autoLaunchLastSession(this)
        }
        StructuredLog.i(TAG, "MediaProjection session ready")
    }

    private fun startAsProjectionForegroundService() {
        val notification = buildNotification()
        startForeground(
            NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        )
    }

    private fun buildNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, ProjectionService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("AutoBridge")
            .setContentText("Screen projection session is active")
            .setContentIntent(openIntent)
            .addAction(Notification.Action.Builder(null, "Stop", stopIntent).build())
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "AutoBridge Projection",
                NotificationManager.IMPORTANCE_LOW
            )
        )
    }

    private fun releaseProjection() {
        val old = projection
        projection = null
        ScreenPowerController.stop()
        pendingDisconnectStop?.let(mainHandler::removeCallbacks)
        pendingDisconnectStop = null
        MirrorCoordinator.stopProjection()
        ShizukuInputBackend.unbind(this)
        restoreRotationAfterProjectionStop()
        runCatching { old?.stop() }
    }

    private fun restoreRotationAfterProjectionStop() {
        OrientationMonitor.stop()
        AppRotationController.restore(this)
        MirrorOrientationController.onProjectionStopped(this)
    }

    private fun stopProjectionAndSelf() {
        releaseProjection()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        releaseProjection()
        if (sessionState != SessionState.ERROR) markIdle()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
