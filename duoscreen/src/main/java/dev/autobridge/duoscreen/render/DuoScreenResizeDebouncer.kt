package dev.autobridge.duoscreen.render


/**
 * While dragging/pinching, [dev.autobridge.duoscreen.input.DuoScreenInputRouter] updates the GL-composited rect every frame — the
 * image stretches live — but a real `VirtualDisplay.resize()` is comparatively expensive and must
 * not run on every frame. This tracks "has it been quiet for [quietPeriodMs]" per pane so the
 * caller only commits the real resize once the driver's finger has actually stopped.
 *
 * Takes an explicit `nowMs` on every call instead of reading a clock itself, so it runs on the JVM
 * without a device and a test can drive time directly.
 */
class DuoScreenResizeDebouncer(private val quietPeriodMs: Long = 300L) {
    private val lastActivityMs = HashMap<Int, Long>()
    private val pending = HashSet<Int>()

    fun onResizeActivity(paneId: Int, nowMs: Long) {
        lastActivityMs[paneId] = nowMs
        pending.add(paneId)
    }

    /**
     * Call periodically (e.g. once per frame). Returns the ids whose quiet period has elapsed since
     * their last activity, each returned exactly once per quiet period — the caller is expected to
     * commit the real resize for every id returned.
     */
    fun pollReadyToCommit(nowMs: Long): List<Int> {
        if (pending.isEmpty()) return emptyList()
        val ready = pending.filter { id ->
            val last = lastActivityMs[id] ?: return@filter false
            nowMs - last >= quietPeriodMs
        }
        pending.removeAll(ready)
        return ready
    }

    fun cancel(paneId: Int) {
        pending.remove(paneId)
        lastActivityMs.remove(paneId)
    }

    fun cancelAll() {
        pending.clear()
        lastActivityMs.clear()
    }
}
