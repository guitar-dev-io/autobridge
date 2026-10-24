package dev.autobridge.projection.desktop

/**
 * The "AutoBridge Chrome" Android Auto entry: an activity-alias of [DesktopBrowserActivity] that
 * Android Auto runs as a window on the car display. Pure logic, no Android types.
 */
object DesktopCarEntry {
    /** The alias's name in the projection manifest; the activity sees it as its intent component. */
    const val NAME = "dev.autobridge.projection.desktop.DesktopBrowserCarEntry"

    fun isCarEntry(className: String?): Boolean = className == NAME

    /** Parked-only, like DesktopBrowserCarScreen.allowed(): gate and feature policy both pass. */
    fun allowed(gateParked: Boolean, browserAvailable: Boolean): Boolean = gateParked && browserAvailable
}
