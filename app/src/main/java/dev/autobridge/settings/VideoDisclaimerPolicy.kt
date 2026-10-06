package dev.autobridge.settings

/**
 * When the video warning's "I understand" still stands. It is not permanent: it lapses after
 * [EXPIRY_MS], and whenever the warning's wording changes ([VERSION]), so the driver is reminded
 * now and then rather than agreeing once and never seeing it again. Pure, so it is tested on the JVM.
 */
object VideoDisclaimerPolicy {
    /** Bump this when the warning's text changes in substance; everyone is asked again. */
    const val VERSION = 2

    /** Three months. A week would nag a daily driver; a year would be forgotten. */
    const val EXPIRY_DAYS = 90
    const val EXPIRY_MS = EXPIRY_DAYS * 24L * 60 * 60 * 1000

    /**
     * True when an acceptance made at [acceptedAtMs] under warning [acceptedVersion] still holds at
     * [nowMs]. No time recorded (0, which is what an acceptance from before this existed leaves),
     * another version, a time in the future (a clock set back) or an expired one all say no.
     */
    fun stands(acceptedAtMs: Long, acceptedVersion: Int, nowMs: Long): Boolean =
        acceptedAtMs > 0 && acceptedVersion == VERSION && nowMs >= acceptedAtMs && nowMs - acceptedAtMs < EXPIRY_MS
}
