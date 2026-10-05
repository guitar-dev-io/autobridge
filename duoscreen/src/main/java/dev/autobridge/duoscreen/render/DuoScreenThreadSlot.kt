package dev.autobridge.duoscreen.render

/**
 * Holds at most one worker thread and hands out a *fresh* one on every [start].
 *
 * A `java.lang.Thread` (and so a HandlerThread) can only be started once; starting it again throws
 * IllegalThreadStateException. The car host delivers its Surface more than once per Screen
 * (destroyed/re-created, or delivered again without a destroy), so the compositor's GL thread has
 * to be recreated for each session rather than kept as a single field.
 *
 * Kept free of android types so the lifecycle can be unit-tested on the JVM.
 */
internal class DuoScreenThreadSlot<T : Any>(
    private val create: () -> T,
    private val shutdown: (T) -> Unit
) {
    var current: T? = null
        private set

    /** Shuts down any thread still held, then creates and returns a new one. */
    fun start(): T {
        stop()
        return create().also { current = it }
    }

    /** Shuts down the held thread, if any. Safe to call more than once. */
    fun stop() {
        val thread = current ?: return
        current = null
        shutdown(thread)
    }
}
