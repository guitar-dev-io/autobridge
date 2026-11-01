package dev.autobridge.obd

/**
 * The last things said to and heard from the adapter, kept so a reading that never arrives can be
 * explained instead of guessed at. Requests for Ford's own PIDs (service 22) get a small list of
 * their own: the standard ones answer many times a second and would push them out of a shared one.
 */
object ObdLog {
    private const val GENERAL = 120
    private const val ENHANCED = 40

    private val general = ArrayDeque<String>()
    private val enhanced = ArrayDeque<String>()

    @Synchronized
    fun add(command: String, reply: String) {
        val line = "$command  ->  ${reply.replace('\r', ' ').replace('\n', ' ').trim().ifEmpty { "(nothing)" }}"
        push(general, line, GENERAL)
        if (command.startsWith("22")) push(enhanced, line, ENHANCED)
    }

    @Synchronized
    fun clear() {
        general.clear()
        enhanced.clear()
    }

    /** Both lists as plain text, newest last. */
    @Synchronized
    fun snapshot(enhancedTitle: String, recentTitle: String): String = buildString {
        appendLine(enhancedTitle)
        if (enhanced.isEmpty()) appendLine("-") else enhanced.forEach { appendLine(it) }
        appendLine()
        appendLine(recentTitle)
        general.takeLast(60).forEach { appendLine(it) }
    }

    private fun push(list: ArrayDeque<String>, line: String, max: Int) {
        list.addLast(line)
        while (list.size > max) list.removeFirst()
    }
}
