package dev.autobridge.mirror

/**
 * Guards the process-wide Android Auto surface callback against an old car screen clearing a
 * replacement callback during reconnect. Ownership is identity-based rather than equals-based.
 */
object MirrorSurfaceOwnership {
    private var owner: Any? = null

    /** Who holds the surface right now, for a diagnostics row. Never used to make a decision. */
    val ownerLabel: String
        @Synchronized get() = owner?.javaClass?.simpleName ?: "none"

    @Synchronized
    fun claim(candidate: Any) {
        // A handover is worth recording: every screen that loses the surface silently stops
        // painting and stops receiving touch, which reads on the head unit as "it hung".
        val previous = owner
        owner = candidate
        if (previous !== candidate) {
            dev.autobridge.logging.StructuredLog.i(
                "MirrorSurface",
                "claim ${candidate.javaClass.simpleName} (was ${previous?.javaClass?.simpleName ?: "none"})"
            )
        }
    }

    @Synchronized
    fun isOwner(candidate: Any): Boolean = owner === candidate

    @Synchronized
    fun release(candidate: Any): Boolean {
        if (owner !== candidate) return false
        owner = null
        return true
    }
}
