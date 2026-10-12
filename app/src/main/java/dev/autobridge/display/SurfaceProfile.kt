package dev.autobridge.display

import dev.autobridge.core.model.Insets

/**
 * The one per-head-unit profile: everything AutoBridge adjusts for a particular car screen.
 *
 * There used to be a second set, `VehicleProfiles`, carrying insets, touch offsets and a frame rate
 * under the same Ford name. Nothing ever selected one of those, so the mirror read null and used
 * zero insets, zero offsets and the renderer's default rate on every car. Those fields now live
 * here, on the profile that is actually chosen and persisted, with exactly the values that were in
 * effect, so folding the two together changed nothing a driver could see.
 *
 * There is no API for a projected Android Auto app to detect which vehicle/head unit it's connected
 * to — [active] is therefore a manual choice (the phone Mirror settings and the car settings row),
 * persisted via `SettingsStore` by enum name, never auto-detected. What the host did report is in
 * [dev.autobridge.car.CarDisplayInfo.fingerprint]; that is what a profile's numbers are checked
 * against.
 *
 * - [fallbackDpi] is used by `MirrorCoordinator` only when a head unit reports a zero/invalid DPI.
 * - [safeInsets] and [touchOffsetX]/[touchOffsetY] feed `DisplayTransform` for the mirror surface.
 * - [preferredFps] is the self-drawn renderer's rate when no per-app profile sets one; null keeps
 *   the renderer default.
 *
 * [hardwareValidated] states plainly whether a profile's numbers were confirmed against real
 * hardware. [FORD_NEXT_GEN] is `false`: its values are identical to [DEFAULT] because no real Ford
 * session has recorded actual values yet. The UI reads this flag to say "unverified" rather than
 * pretending the profile is tuned — inventing numbers here would just be guessing. When a real
 * Ford session records values, update [FORD_NEXT_GEN]'s fields from that log and flip this to `true`.
 */
enum class SurfaceProfile(
    val displayLabel: String,
    val fallbackDpi: Int,
    val hardwareValidated: Boolean,
    val safeInsets: Insets = Insets.ZERO,
    val touchOffsetX: Float = 0f,
    val touchOffsetY: Float = 0f,
    val preferredFps: Int? = null
) {
    /** Adds nothing on top of what the host reports, so there is nothing in it to validate. */
    DEFAULT(displayLabel = "Default", fallbackDpi = 160, hardwareValidated = true),
    FORD_NEXT_GEN(
        displayLabel = "Ford Next Gen (unverified)",
        fallbackDpi = 160, // Replace from a real Ford CAR_DISPLAY log line, not by estimate.
        hardwareValidated = false
    );

    companion object {
        @Volatile
        var active: SurfaceProfile = DEFAULT
    }
}
