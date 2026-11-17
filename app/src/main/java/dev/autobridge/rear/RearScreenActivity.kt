package dev.autobridge.rear

import android.app.Activity
import android.app.ActivityOptions
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import androidx.core.content.ContextCompat
import dev.autobridge.R
import dev.autobridge.display.RearDisplays
import dev.autobridge.logging.StructuredLog

/**
 * The passengers' player, running on the rear screen itself. See `docs/REAR_SCREEN.md`.
 *
 * It owns its own ExoPlayer rather than the app's MediaSession player: the car keeps whatever it
 * is playing, its media card and steering-wheel buttons keep driving that, and the rear video does
 * not take audio focus, so it never pauses the car's music. One instance at a time
 * (`singleInstance`); a second send replaces what it plays.
 *
 * Sent while the front is playing the same video, it starts muted: both screens show the picture,
 * and the sound comes only from the front, since two copies of one video never run in step and
 * would echo through the one set of car speakers. The notification's mute button turns its own
 * sound on, for a rear screen with speakers or once the front has moved on to something else.
 */
class RearScreenActivity : Activity() {

    companion object {
        private const val TAG = "REAR_SCREEN"
        private const val EXTRA_URL = "dev.autobridge.rear.URL"
        private const val EXTRA_TITLE = "dev.autobridge.rear.TITLE"
        private const val EXTRA_POSITION = "dev.autobridge.rear.POSITION"
        private const val EXTRA_MUTED = "dev.autobridge.rear.MUTED"
        private const val ACTION_MUTE = "dev.autobridge.rear.MUTE"
        private const val ACTION_TOGGLE = "dev.autobridge.rear.TOGGLE"
        private const val ACTION_STOP = "dev.autobridge.rear.STOP"
        private const val CHANNEL_ID = "rear_screen"
        private const val NOTIFICATION_ID = 4318

        /** Whether a rear screen is connected now, for showing the send button. */
        fun isAvailable(context: Context): Boolean = RearDisplays.find(context) != null

        /**
         * Plays [url] on the rear screen, without sound when [muted]. Returns false when no rear
         * screen is connected or Android refuses to open a window there.
         */
        fun send(context: Context, url: String, title: String, positionMs: Long, muted: Boolean = false): Boolean {
            val display = RearDisplays.find(context) ?: return false
            val intent = Intent(context, RearScreenActivity::class.java)
                .putExtra(EXTRA_URL, url)
                .putExtra(EXTRA_TITLE, title)
                .putExtra(EXTRA_POSITION, positionMs)
                .putExtra(EXTRA_MUTED, muted)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val options = ActivityOptions.makeBasic().setLaunchDisplayId(display.displayId)
            return runCatching { context.startActivity(intent, options.toBundle()) }
                .onSuccess {
                    StructuredLog.i(TAG, "sent to display ${display.displayId} \"${display.name}\"")
                }
                .onFailure { StructuredLog.w(TAG, "launch on display ${display.displayId} refused: $it") }
                .isSuccess
        }

        /** The open rear player, for the on-device test. */
        @androidx.annotation.VisibleForTesting
        @Volatile
        internal var shown: RearScreenActivity? = null

        /** Turns the rear screen's own sound on or off. */
        fun toggleMute(context: Context) {
            context.sendBroadcast(Intent(ACTION_MUTE).setPackage(context.packageName))
        }

        /** Stops the rear screen's video, if one is playing. */
        fun stop(context: Context) {
            context.sendBroadcast(Intent(ACTION_STOP).setPackage(context.packageName))
        }
    }

    private var rear: RearPlayer? = null

    /** For the on-device test: the display this is on, and whether the video is playing. */
    internal val displayIdForTest: Int get() = shownOn
    internal val isPlayingForTest: Boolean get() = rear?.isPlaying == true
    internal val isMutedForTest: Boolean get() = rear?.muted == true
    /** The display this window is on. `Context.getDisplay()` needs API 30, and the app supports 29. */
    @Suppress("DEPRECATION")
    private val shownOn: Int by lazy { windowManager.defaultDisplay.displayId }
    private val main = Handler(Looper.getMainLooper())

    private val controls = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_TOGGLE -> rear?.toggle()
                ACTION_MUTE -> rear?.let { it.setMuted(!it.muted) }
                ACTION_STOP -> finish()
            }
        }
    }

    /** The rear screen was unplugged: Android moves this window to the phone, which nobody wants. */
    private val displays = object : DisplayManager.DisplayListener {
        override fun onDisplayRemoved(displayId: Int) {
            if (displayId == shownOn) finish()
        }
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) = Unit
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // A locked phone would otherwise cover the passengers' video with its keyguard.
        setShowWhenLocked(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val player = RearPlayer(this) { showNotification() }
        rear = player
        setContentView(player.view(this))

        ContextCompat.registerReceiver(
            this, controls,
            IntentFilter().apply { addAction(ACTION_TOGGLE); addAction(ACTION_MUTE); addAction(ACTION_STOP) },
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        getSystemService(DisplayManager::class.java)?.registerDisplayListener(displays, main)
        play(intent)
        shown = this
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        play(intent)
    }

    private fun play(intent: Intent) {
        val url = intent.getStringExtra(EXTRA_URL)?.takeIf { it.isNotBlank() } ?: return
        val position = intent.getLongExtra(EXTRA_POSITION, 0L)
        val muted = intent.getBooleanExtra(EXTRA_MUTED, false)
        rear?.play(url, intent.getStringExtra(EXTRA_TITLE).orEmpty(), position, muted)
        StructuredLog.i(TAG, "playing on display $shownOn from ${position}ms muted=$muted")
    }

    /** Pause/play, sound and stop on the phone, for a rear screen that has no touch. */
    private fun showNotification() {
        if (isFinishing) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.rear_notification_channel), NotificationManager.IMPORTANCE_LOW)
        )
        fun broadcast(action: String, code: Int) = PendingIntent.getBroadcast(
            this, code, Intent(action).setPackage(packageName),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val rear = rear ?: return
        val playing = rear.isPlaying
        val muted = rear.muted
        val title = rear.title
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_tile_tv)
            .setContentTitle(getString(R.string.rear_notification_title))
            .setContentText(if (muted) getString(R.string.rear_notification_muted, title) else title)
            .addAction(
                Notification.Action.Builder(
                    null,
                    getString(if (playing) R.string.rear_notification_pause else R.string.rear_notification_play),
                    broadcast(ACTION_TOGGLE, 1)
                ).build()
            )
            .addAction(
                Notification.Action.Builder(
                    null,
                    getString(if (muted) R.string.rear_notification_unmute else R.string.rear_notification_mute),
                    broadcast(ACTION_MUTE, 3)
                ).build()
            )
            .addAction(Notification.Action.Builder(null, getString(R.string.rear_notification_stop), broadcast(ACTION_STOP, 2)).build())
            .setOngoing(true)
            .build()
        // Notifications may be turned off; the rear screen still plays, it just has no phone controls.
        runCatching { manager.notify(NOTIFICATION_ID, notification) }
    }

    override fun onDestroy() {
        if (shown === this) shown = null
        runCatching { unregisterReceiver(controls) }
        getSystemService(DisplayManager::class.java)?.unregisterDisplayListener(displays)
        getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
        rear?.release()
        rear = null
        StructuredLog.i(TAG, "closed")
        super.onDestroy()
    }
}
