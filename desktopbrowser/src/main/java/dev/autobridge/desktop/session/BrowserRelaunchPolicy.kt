package dev.autobridge.desktop.session

/**
 * Whether a browser that exited on its own should be started again.
 *
 * Only a normal exit (0) is a relaunch: that is the browser quitting because its last tab or window
 * was closed. A crash stays a failure. And a browser that quits [maxRelaunches] times within
 * [windowMs] is not closing windows, it is failing to start, so that is a failure too.
 */
class BrowserRelaunchPolicy(
    private val maxRelaunches: Int = 3,
    private val windowMs: Long = 30_000L,
) {
    private val recent = ArrayDeque<Long>()

    @Synchronized
    fun shouldRelaunch(exitCode: Int, nowMs: Long): Boolean {
        if (exitCode != 0) return false
        while (recent.isNotEmpty() && nowMs - recent.first() >= windowMs) recent.removeFirst()
        if (recent.size >= maxRelaunches) return false
        recent.addLast(nowMs)
        return true
    }
}
