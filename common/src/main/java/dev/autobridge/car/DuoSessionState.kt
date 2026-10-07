package dev.autobridge.car

/**
 * Whether a Duo screen session is live in this process. Written by :duoscreen's controller at
 * session start and cleared only at true teardown; read by :app's media layer to decide whether to
 * surface its own media card.
 *
 * Writer is :duoscreen ONLY. :app and :common must never call sessionStarted()/sessionEnded() — in
 * the safe flavor (:duoscreen absent) the flag then stays permanently at its false default, so the
 * media card behaves exactly as today (NFR4/AC8).
 */
object DuoSessionState {
    @Volatile
    private var active: Boolean = false

    /** True while a Duo session owns the car display (including the keep-alive window). */
    val isActive: Boolean get() = active

    /** Called from DuoScreenController.start(): a session is now live. Idempotent. */
    fun sessionStarted() { active = true }

    /**
     * Called from DuoScreenController.stop() on a real teardown only (guarded by the controller's
     * tearingDown flag, so restart()'s internal stop() does not clear it). Reached from both the car
     * route (DuoScreenHost.release()) and the on-phone harness (DuoScreenSpikeActivity.stopSession()).
     * Idempotent.
     */
    fun sessionEnded() { active = false }
}
