package dev.autobridge.ui

import dev.autobridge.core.model.RuntimeContext
import dev.autobridge.core.model.VehicleState

/**
 * The user-facing wording for the Android Auto connection, derived from the single source of
 * truth ([dev.autobridge.core.state.RuntimeContextStore]). Home, Control, Car & Connection and
 * Duo Screen all render this, so none of them can ever disagree — and none of them can be
 * mistaken for the separate Shizuku connection, which has its own wording entirely.
 *
 * Internal values (environment such as REAL_CAR/DHU, build mode) are deliberately absent; they are
 * only shown on Settings > Advanced > Debug.
 *
 * [Labels] carries the localized wording so this object stays plain Kotlin (testable without a
 * Context); callers in Compose resolve it once via `stringResource` and pass it in. [DEFAULT_LABELS]
 * is English only, used when a caller does not need localization (e.g. unit tests).
 */
object ConnectionStatusText {

    /** [pill] is the short standalone word ("Parked", "Not connected"…) for a status pill/badge. */
    data class Status(val connected: Boolean, val summary: String, val pill: String)

    /** Localized wording for every piece [of] can show. */
    data class Labels(
        val notConnected: String,
        val parked: String,
        val driving: String,
        val checking: String,
        val connectedFormat: (String) -> String
    )

    val DEFAULT_LABELS = Labels(
        notConnected = "Not connected",
        parked = "Parked",
        driving = "Driving",
        checking = "Checking vehicle state",
        connectedFormat = { "Connected · $it" }
    )

    fun of(runtime: RuntimeContext, labels: Labels = DEFAULT_LABELS): Status =
        of(runtime.connected, runtime.vehicleState, labels)

    fun of(connected: Boolean, vehicleState: VehicleState, labels: Labels = DEFAULT_LABELS): Status {
        if (!connected) return Status(false, labels.notConnected, labels.notConnected)
        val drive = when (vehicleState) {
            VehicleState.PARKED -> labels.parked
            VehicleState.MOVING -> labels.driving
            VehicleState.UNKNOWN -> labels.checking
        }
        return Status(true, labels.connectedFormat(drive), drive)
    }
}
