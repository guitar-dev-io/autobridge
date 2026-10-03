package dev.autobridge.remotestream

/**
 * What the remote stream receiver is doing, as the car and phone UIs show it.
 *
 * [BUFFERING] is deliberately distinct from [CONNECTING]: a transport that is up but starved of
 * frames looks identical to a dead one on screen unless the two are separated, and they call for
 * opposite reactions from the user (wait vs. check the host).
 */
enum class RemoteStreamStatus { DISCONNECTED, CONNECTING, CONNECTED, BUFFERING, ERROR }

/**
 * How long to wait before the next reconnect attempt.
 *
 * Exponential with a ceiling, because the two failure modes want different things: a host that
 * was restarted comes back in a second or two and should be picked up immediately, while a host
 * that is simply not there must not be hammered for the rest of the drive. The ceiling is what
 * makes the second case cheap, and the small base is what makes the first case feel instant.
 *
 * Pure so the schedule is a unit test rather than a stopwatch held against a car.
 */
object ReconnectPolicy {
    const val BASE_DELAY_MS = 1_000L
    const val MAX_DELAY_MS = 30_000L

    /**
     * Delay before attempt number [attempt], counting the first retry as 1.
     *
     * Attempt 0 is the initial connection and has no delay at all; returning anything else there
     * would put a pause in front of the user's very first play.
     */
    fun delayMs(attempt: Int): Long {
        if (attempt <= 0) return 0L
        val shift = (attempt - 1).coerceAtMost(30)
        val scaled = BASE_DELAY_MS shl shift
        return if (scaled <= 0L || scaled > MAX_DELAY_MS) MAX_DELAY_MS else scaled
    }

    /**
     * Whether a receiver in [status] should try again.
     *
     * A user-initiated stop lands on DISCONNECTED and must stay there — retrying something the
     * driver just closed is how a "stop" button stops meaning anything.
     */
    fun shouldRetry(status: RemoteStreamStatus, userStopped: Boolean): Boolean =
        !userStopped && status != RemoteStreamStatus.CONNECTED
}
