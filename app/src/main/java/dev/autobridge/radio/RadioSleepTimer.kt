package dev.autobridge.radio

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import dev.autobridge.logging.StructuredLog
import dev.autobridge.media.MediaPlaybackClient

/**
 * The radio's sleep timer: pauses the shared session after a set time, for falling asleep to a
 * station or for a drive that ends before the programme does.
 *
 * One timer for the process, because there is one session. It lives here rather than in a screen
 * so leaving the player does not cancel it; the media service keeps the process alive while it
 * plays. When it fires it connects a client of its own just long enough to pause.
 */
object RadioSleepTimer {
    /** The choices offered, in minutes. */
    val OPTIONS_MINUTES = listOf(15, 30, 45, 60, 90)

    private const val TAG = "RadioSleepTimer"
    private const val MINUTE_MS = 60_000L

    private val handler by lazy { Handler(Looper.getMainLooper()) }
    private var appContext: Context? = null

    /** When it fires, on [SystemClock.elapsedRealtime]; 0 while no timer is set. */
    @Volatile
    private var endsAt = 0L

    private val fire = Runnable {
        endsAt = 0L
        val context = appContext ?: return@Runnable
        StructuredLog.i(TAG, "Sleep timer ran out; pausing")
        val client = MediaPlaybackClient(context)
        client.connect(
            onConnected = {
                client.pause()
                client.disconnect()
            },
            onError = { client.disconnect() }
        )
    }

    val isSet: Boolean get() = endsAt > 0L

    fun start(context: Context, minutes: Int) {
        appContext = context.applicationContext
        handler.removeCallbacks(fire)
        val delay = minutes.coerceAtLeast(1) * MINUTE_MS
        endsAt = SystemClock.elapsedRealtime() + delay
        handler.postDelayed(fire, delay)
        StructuredLog.i(TAG, "Sleep timer set for $minutes min")
    }

    fun cancel() {
        handler.removeCallbacks(fire)
        endsAt = 0L
    }

    /** Whole minutes left, rounded up so the last minute reads 1 rather than 0; 0 when not set. */
    fun remainingMinutes(): Int = remainingMinutes(endsAt, SystemClock.elapsedRealtime())

    /** The pure arithmetic behind [remainingMinutes], for a JVM test. */
    fun remainingMinutes(endsAt: Long, now: Long): Int =
        if (endsAt <= 0L || endsAt <= now) 0 else ((endsAt - now + MINUTE_MS - 1) / MINUTE_MS).toInt()
}
