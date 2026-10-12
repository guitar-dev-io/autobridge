package dev.autobridge.mirror

import dev.autobridge.safety.ParkingStateStore

/**
 * Whether a live mirror session is torn down for the current vehicle reading.
 *
 * Off PARKED — moving, or UNKNOWN because no speed arrived in time or the car session ended — the
 * MediaProjection is stopped and mirroring needs fresh consent. That stays the default.
 *
 * The one exception is the driver's own runtime bypass ([dev.autobridge.safety.BypassPolicyStore],
 * off by default, announced by a persistent notification while on). It already lets every other
 * parked-only feature open in any vehicle state; before this, mirroring was the only one it did
 * not reach, so with the bypass on the browser kept playing while the mirror kept dying. With the
 * bypass on, the session is kept whether the car is moving or stopped. Turning the bypass off
 * while not parked tears the session down at that moment, as if the reading had just arrived.
 *
 * Pure so the whole matrix is a JVM test.
 */
object MirrorParkedTeardown {
    fun shouldTearDown(state: ParkingStateStore.State, bypassOverridesParked: Boolean): Boolean =
        state != ParkingStateStore.State.PARKED && !bypassOverridesParked
}
