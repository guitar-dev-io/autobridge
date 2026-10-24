package dev.autobridge.desktop

import android.util.Log
import dev.autobridge.logging.StructuredLog

/**
 * Logcat plus the in-app log that Send log reads, so a report from the car says which step of the
 * runtime, install or session failed. `adb logcat -s AutoBridgeDesktop` follows it live.
 */
object DesktopLog {
    const val TAG = "AutoBridgeDesktop"

    fun i(message: String) {
        Log.i(TAG, message)
        runCatching { StructuredLog.i(TAG, message) }
    }

    /** Logcat only (high-volume detail such as dropped keyboard input). */
    fun d(message: String) {
        Log.d(TAG, message)
    }

    fun w(message: String, error: Throwable? = null) {
        Log.w(TAG, message, error)
        runCatching { StructuredLog.w(TAG, if (error == null) message else "$message: ${describe(error)}") }
    }

    fun describe(error: Throwable): String {
        val cause = error.cause ?: error
        return "${cause.javaClass.simpleName}: ${cause.message}"
    }
}
