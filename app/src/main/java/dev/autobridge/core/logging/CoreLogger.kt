package dev.autobridge.core.logging

import dev.autobridge.logging.StructuredLog

/** Shared structured categories used by policy and lifecycle code. */
enum class LogCategory {
    CAR,
    PROJECTION,
    SURFACE,
    MIRROR,
    INPUT,
    MEDIA,
    VEHICLE,
    POLICY,
    APP,
    SESSION
}

object CoreLogger {
    fun d(category: LogCategory, message: String) =
        StructuredLog.d(category.name, message)

    fun i(category: LogCategory, message: String) =
        StructuredLog.i(category.name, message)

    fun w(category: LogCategory, message: String) =
        StructuredLog.w(category.name, message)

    fun e(category: LogCategory, message: String) =
        StructuredLog.e(category.name, message)
}
