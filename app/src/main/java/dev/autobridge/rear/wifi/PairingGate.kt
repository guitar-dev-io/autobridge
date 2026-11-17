package dev.autobridge.rear.wifi

import java.security.SecureRandom

/**
 * The tablet's side of pairing: a short code on its screen, traded once for a long token.
 *
 * The code is what a person reads off the passengers' screen and types on the phone. It is short,
 * so guessing is limited: after [maxFailures] wrong codes pairing locks for [lockMs], and a fresh
 * code is drawn. The token is random and long, kept by both ends, and is what every later command
 * carries. [tokens] starts from what the tablet saved and [onTokensChanged] saves it again.
 */
class PairingGate(
    tokens: Set<String> = emptySet(),
    private val now: () -> Long = System::currentTimeMillis,
    private val random: SecureRandom = SecureRandom(),
    private val maxFailures: Int = 5,
    private val lockMs: Long = 60_000L,
    private val onTokensChanged: (Set<String>) -> Unit = {},
) {
    private val known = tokens.toMutableSet()
    private var failures = 0
    private var lockedUntil = 0L

    /** The code the tablet shows now. */
    @get:Synchronized
    var code: String = newCode()
        private set

    sealed interface Result {
        data class Paired(val token: String) : Result
        data object WrongCode : Result
        data object Locked : Result
    }

    @Synchronized
    fun pair(attempt: String): Result {
        if (now() < lockedUntil) return Result.Locked
        if (attempt.trim() != code) {
            failures++
            if (failures >= maxFailures) {
                failures = 0
                lockedUntil = now() + lockMs
                code = newCode()
            }
            return Result.WrongCode
        }
        failures = 0
        // A used code is spent: the next phone needs the next code.
        code = newCode()
        val token = ByteArray(24).also(random::nextBytes).joinToString("") { "%02x".format(it) }
        known += token
        onTokensChanged(known.toSet())
        return Result.Paired(token)
    }

    @Synchronized
    fun isAuthorized(token: String?): Boolean = token != null && token in known

    /** Forgets every paired phone, for the tablet's "unpair all". */
    @Synchronized
    fun forgetAll() {
        known.clear()
        onTokensChanged(emptySet())
    }

    private fun newCode(): String = "%04d".format(random.nextInt(10_000))
}
