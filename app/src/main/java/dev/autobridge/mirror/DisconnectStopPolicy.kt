package dev.autobridge.mirror

/**
 * Whether "Stop on disconnect" should end the mirror session now.
 *
 * The setting used to read a missing car surface as a disconnected car. But the surface also goes
 * away whenever the driver switches the head unit to another app (Maps, the phone screen, media),
 * so two seconds on Maps ended mirroring and the next return asked for screen-capture consent
 * again. The Android Auto host's own connection signal
 * ([dev.autobridge.car.CarConnectionMonitor]) says whether the car is really gone, so it decides
 * whenever it is known. Only a phone where that signal never arrived falls back to the surface.
 *
 * Pure, so every combination is a JVM test.
 */
object DisconnectStopPolicy {
    enum class Reason(val label: String) {
        HOST_DISCONNECTED("host_disconnect"),
        SURFACE_LOST("surface_disconnect")
    }

    /**
     * @param hostSignalKnown whether the host connection signal has reported at least once.
     * @return why the session should stop, or null to keep it.
     */
    fun decide(
        stopOnDisconnect: Boolean,
        sessionIdle: Boolean,
        hostSignalKnown: Boolean,
        hostConnected: Boolean,
        surfaceReady: Boolean
    ): Reason? = when {
        !stopOnDisconnect || sessionIdle -> null
        hostSignalKnown -> if (hostConnected) null else Reason.HOST_DISCONNECTED
        surfaceReady -> null
        else -> Reason.SURFACE_LOST
    }
}
