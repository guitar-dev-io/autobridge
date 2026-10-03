package dev.autobridge.bridge

import dev.autobridge.display.StructuredLog

/**
 * The bridge's one logging entry point, so every line about a connect, a send, a routing decision
 * or a decoder fault is greppable under a single tag.
 *
 * ```
 * adb logcat -s AutoBridge
 * ```
 *
 * Lines are `event key=value key=value`, in that order, because the interesting question when
 * reading a session back is almost always "what happened, then what happened" followed by "with
 * what". It mirrors into [StructuredLog] so the in-app log screen shows the same entries the
 * host's logcat does — a head unit is frequently the only place a fault reproduces, and it is
 * rarely attached to a laptop at the time.
 *
 * Values are rendered with [redact] applied to anything URL-shaped: a shared link can carry a
 * session token in its query, and the log is readable from the car's own diagnostics screen.
 */
object BridgeLog {
    const val TAG = "AutoBridge"

    fun i(event: String, vararg fields: Pair<String, Any?>) = write(Level.I, event, fields)
    fun w(event: String, vararg fields: Pair<String, Any?>) = write(Level.W, event, fields)
    fun e(event: String, vararg fields: Pair<String, Any?>) = write(Level.E, event, fields)

    private enum class Level { I, W, E }

    private fun write(level: Level, event: String, fields: Array<out Pair<String, Any?>>) {
        val line = buildString {
            append(event)
            fields.forEach { (key, value) ->
                if (value == null) return@forEach
                append(' ').append(key).append('=').append(format(key, value))
            }
        }
        when (level) {
            Level.I -> StructuredLog.i(TAG, line)
            Level.W -> StructuredLog.w(TAG, line)
            Level.E -> StructuredLog.e(TAG, line)
        }
    }

    private fun format(key: String, value: Any): String {
        val text = value.toString()
        return if (key == "url" || key == "source" || text.startsWith("http")) redact(text) else text
    }

    /**
     * A URL with its query reduced to the names of its parameters.
     *
     * Which parameters a link carries is what makes a routing decision readable; their values are
     * what leaks an account. A YouTube `v=` is the exception worth keeping, because without it two
     * consecutive sends look identical in the log.
     */
    fun redact(url: String): String {
        val queryAt = url.indexOf('?')
        if (queryAt < 0) return url.substringBefore('#')
        val base = url.substring(0, queryAt)
        val params = url.substring(queryAt + 1).substringBefore('#')
            .split('&')
            .filter { it.isNotEmpty() }
            .joinToString("&") { param ->
                val name = param.substringBefore('=')
                if (name == "v" || name == "t") param else "$name=…"
            }
        return if (params.isEmpty()) base else "$base?$params"
    }
}
