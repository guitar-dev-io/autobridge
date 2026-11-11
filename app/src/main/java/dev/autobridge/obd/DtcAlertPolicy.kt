package dev.autobridge.obd

/**
 * When the car screen warns about a fault code, kept free of Android so it can be tested.
 *
 * Only stored (confirmed) codes are announced: a pending code is the car's own "maybe", it often
 * clears by itself, and it is still listed on the details screen. A code is announced once, the
 * first answered check that finds it; it stays quiet while it remains stored, and is announced
 * again only if it is cleared and comes back. A check the car did not really answer leaves the
 * remembered codes alone, so a dropped reply is not taken for "everything cleared" and followed by
 * the same warning on the next good one.
 */
object DtcAlertPolicy {

    /** After the link goes live: lets the gauges settle and the car finish its own start-up checks. */
    const val FIRST_CHECK_DELAY_MS = 30_000L

    /** Between checks. Each one pauses the gauges for the two requests, a second or two. */
    const val CHECK_INTERVAL_MS = 5 * 60_000L

    /** [remember] is what to store for the next check, or null to keep what is stored. */
    data class Outcome(val announce: List<String>, val remember: Set<String>?)

    fun evaluate(previous: Set<String>, report: ObdConnection.DtcReport): Outcome {
        if (!report.storedAnswered) return Outcome(emptyList(), null)
        return Outcome(
            announce = report.stored.filter { it !in previous },
            remember = report.stored.toSet(),
        )
    }
}
