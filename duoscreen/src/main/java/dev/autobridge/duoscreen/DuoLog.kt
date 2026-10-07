package dev.autobridge.duoscreen

import android.util.Log
import dev.autobridge.logging.StructuredLog

/**
 * Logcat plus the in-app log that Send log reads. The display and Shizuku code logged to Logcat
 * alone, so a report from a real car could not say whether a pane got a trusted display or why
 * it did not. A failure's exception is written as its class and message.
 */
internal object DuoLog {
    fun i(tag: String, message: String) {
        Log.i(tag, message)
        StructuredLog.i(tag, message)
    }

    fun w(tag: String, message: String, error: Throwable? = null) {
        Log.w(tag, message, error)
        StructuredLog.w(tag, if (error == null) message else "$message: ${describe(error)}")
    }

    private fun describe(error: Throwable): String {
        val cause = error.cause ?: error
        return "${cause.javaClass.simpleName}: ${cause.message}"
    }
}
