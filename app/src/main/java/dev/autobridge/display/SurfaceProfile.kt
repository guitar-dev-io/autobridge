package dev.autobridge.display

/**
 * Per-head-unit rendering knobs. There is no API for a projected Android Auto app to detect
 * which vehicle/head unit it's connected to — [active] is therefore a manual choice (see the
 * toggle in `MainActivity`), persisted via `SettingsStore`, never auto-detected.
 *
 * [fallbackDpi] is used by `MirrorCoordinator.reconcileLocked()` only when a head unit reports a
 * zero/invalid DPI for its surface.
 *
 * [hardwareValidated] states plainly whether a profile's numbers were confirmed against real
 * hardware or are still placeholders. [FORD_NEXT_GEN] is `false`: its values are currently
 * identical to [DEFAULT] because no real Ford/DHU session has recorded actual values yet (see
 * `docs/FORD_TEST.md`). This flag is the single source of truth the UI reads to say "unverified"
 * rather than pretending the profile is tuned — inventing DPI numbers here would just be guessing.
 * When a real Ford session records values (per FORD_TEST.md), update [FORD_NEXT_GEN]'s fields and
 * flip this to `true`.
 */
enum class SurfaceProfile(
    val displayLabel: String,
    val fallbackDpi: Int,
    val hardwareValidated: Boolean
) {
    DEFAULT(displayLabel = "Default", fallbackDpi = 160, hardwareValidated = true),
    FORD_NEXT_GEN(
        displayLabel = "Ford Next Gen (unverified)",
        fallbackDpi = 160, // TODO(docs/FORD_TEST.md): replace once measured on real hardware.
        hardwareValidated = false
    );

    companion object {
        @Volatile
        var active: SurfaceProfile = DEFAULT
    }
}
