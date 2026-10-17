package dev.autobridge.media

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.audiofx.AudioEffect
import android.os.IBinder
import androidx.core.content.ContextCompat
import dev.autobridge.MainActivity
import dev.autobridge.R
import dev.autobridge.logging.StructuredLog

/**
 * Keeps the sound effects on other apps' audio while [EffectScope] reaches past AutoBridge.
 *
 * Effects live only as long as the process that made them, so something has to keep the process up
 * while Spotify or YouTube Music plays — this foreground service, with a quiet notification. It
 * listens for the platform's "open/close effect session" broadcasts that music players send and
 * hands each session to [AudioEq]; under [EffectScope.ALL] the output-mix effects need no
 * broadcast, only the process alive.
 *
 * The broadcasts are implicit, so they reach only a receiver registered at run time: one in the
 * manifest would never hear them on Android 8 and later.
 */
class AudioEffectsService : Service() {

    companion object {
        private const val TAG = "AudioEffects"
        private const val CHANNEL_ID = "audio_effects"
        private const val NOTIFICATION_ID = 4_210
        private const val ACTION_STOP = "dev.autobridge.media.AudioEffectsService.STOP"

        /**
         * Starts or stops the service to match the stored scope. Called from screens in the
         * foreground, where Android allows a foreground service to start; a refusal is logged and
         * the effects stay on AutoBridge's own sound.
         */
        fun sync(context: Context) {
            val wide = AudioEq.settings(context).scope != EffectScope.APP
            val intent = Intent(context, AudioEffectsService::class.java)
            if (wide) {
                runCatching { ContextCompat.startForegroundService(context, intent) }
                    .onFailure { StructuredLog.w(TAG, "Could not start: ${it.message}") }
            } else {
                context.stopService(intent)
            }
        }
    }

    private val sessions = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val session = intent.getIntExtra(AudioEffect.EXTRA_AUDIO_SESSION, 0)
            val pkg = intent.getStringExtra(AudioEffect.EXTRA_PACKAGE_NAME) ?: return
            // Our own player is attached directly by MediaPlaybackService.
            if (pkg == packageName || session <= 0) return
            val owner = EffectTargets.EXTERNAL_PREFIX + pkg
            when (intent.action) {
                AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION -> AudioEq.attach(context, owner, session)
                AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION -> AudioEq.detach(owner)
            }
            StructuredLog.i(TAG, "${intent.action?.substringAfterLast('.')} $pkg session $session")
        }
    }

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.eq_channel_name), NotificationManager.IMPORTANCE_MIN)
        )
        ContextCompat.registerReceiver(
            this,
            sessions,
            IntentFilter().apply {
                addAction(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION)
                addAction(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION)
            },
            ContextCompat.RECEIVER_EXPORTED
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            AudioEq.setScope(this, EffectScope.APP)
            stopSelf()
            return START_NOT_STICKY
        }
        runCatching {
            startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        }.onFailure {
            StructuredLog.w(TAG, "Not allowed in the foreground: ${it.message}")
            stopSelf()
            return START_NOT_STICKY
        }
        // Re-reads the scope, so ALL picks up the output mix straight away.
        AudioEq.setScope(this, AudioEq.settings(this).scope)
        return START_STICKY
    }

    private fun notification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, AudioEffectsService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_silent_mode_off)
            .setContentTitle(getString(R.string.eq_notification_title))
            .setContentText(getString(R.string.eq_notification_text))
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, getString(R.string.eq_notification_stop), stop).build())
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(sessions) }
        AudioEq.detachExternal()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
