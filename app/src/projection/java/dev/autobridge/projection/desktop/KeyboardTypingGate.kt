package dev.autobridge.projection.desktop

import dev.autobridge.R
import dev.autobridge.core.model.Feature
import dev.autobridge.core.policy.FeaturePolicy
import dev.autobridge.safety.BypassPolicyStore
import dev.autobridge.safety.ParkingStateStore

/**
 * Typing on car-facing routes is parked-only even while the global gate only reports
 * (SafetyEnforcement). Checked at every keyboard entry point and per input event on the car
 * screen, the AutoBridge Chrome car entry, the phone keyboard companion and the debug harness.
 * UNKNOWN counts as not parked (fail closed); [BypassPolicyStore.overridesParked] is honoured.
 * The existing browsing gates (`allowed()`) stay in front of it, unchanged.
 */
object KeyboardTypingGate {
    private val policy = FeaturePolicy(enforceParkedOnly = true)

    fun allowed(
        parked: Boolean = ParkingStateStore.isParked,
        bypass: Boolean = BypassPolicyStore.overridesParked,
        browserAllowed: () -> Boolean = { policy.isAvailable(Feature.BROWSER) },
    ): Boolean = (parked || bypass) && browserAllowed()

    /**
     * Why typing is refused, as a string resource. [companionCarConnected] is for the phone
     * companion only: false means the car session ended (its vehicle-state lease closed), so
     * UNKNOWN is "no session" there. The car screen and the car entry pass null.
     */
    fun refusalMessage(
        state: ParkingStateStore.State = ParkingStateStore.state,
        companionCarConnected: Boolean? = null,
    ): Int = when {
        state == ParkingStateStore.State.UNKNOWN && companionCarConnected == false -> R.string.desktop_keyboard_no_session
        state == ParkingStateStore.State.UNKNOWN -> R.string.desktop_keyboard_no_vehicle_state
        else -> dev.autobridge.desktop.R.string.desktop_park_to_browse
    }
}
