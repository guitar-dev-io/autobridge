package dev.autobridge.mirror

/**
 * Guards the process-wide Android Auto surface callback against an old car screen clearing a
 * replacement callback during reconnect. Ownership is identity-based rather than equals-based.
 */
object MirrorSurfaceOwnership {
    private var owner: Any? = null

    @Synchronized
    fun claim(candidate: Any) {
        owner = candidate
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
