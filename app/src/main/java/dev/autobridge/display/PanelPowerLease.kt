package dev.autobridge.display

/** Tracks panel ownership independently of the Android wake-lock and Binder implementations. */
internal class PanelPowerLease(
    private val keepAwake: () -> Unit,
    private val turnOff: () -> Boolean,
    private val turnOn: () -> Boolean
) {
    var isOff: Boolean = false
        private set
    var restoreFailed: Boolean = false
        private set

    fun hide(): Boolean {
        if (isOff) return true
        // Hold a display wake lock BEFORE changing panel power; keep it until restoration.
        keepAwake()
        isOff = turnOff()
        return isOff
    }

    fun restore(): Boolean {
        if (!isOff) return true
        if (!turnOn()) {
            restoreFailed = true
            return false
        }
        isOff = false
        restoreFailed = false
        return true
    }
}
