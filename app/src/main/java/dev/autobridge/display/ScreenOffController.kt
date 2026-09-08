package dev.autobridge.display

/**
 * V0.6 experiment: keeping the car mirror alive while the phone's own screen is off.
 *
 * The honest platform constraint: `MirrorCoordinator` uses `VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR`,
 * which mirrors the phone's *default* display. When the phone screen turns off, the default
 * display stops composing real content, so an AUTO_MIRROR virtual display goes black too — the
 * mirror does not survive screen-off on this pipeline. Making it survive requires either:
 *   1. a self-drawn capture pipeline (ImageReader + Canvas) that reads frames independent of the
 *      default display's power state — the same deferred pipeline FILL/STRETCH and per-frame FPS
 *      need (see [dev.autobridge.input.DisplayTransform] / [MirrorDiagnostics]); or
 *   2. launching the mirrored app onto a dedicated *own-content* VirtualDisplay instead of
 *      auto-mirroring the physical one (see [PerAppDisplayController]).
 *
 * Rather than pretend to force the screen off (which needs a DeviceAdmin `lockNow()` or root and
 * has real safety implications in a vehicle), this controller exposes the *policy decision* as
 * pure, testable logic: given the current [PipelineMode], can the mirror survive the phone screen
 * turning off? [MirrorCoordinator] and the UI use this to tell the user the truth instead of
 * silently showing a black head-unit screen.
 */
object ScreenOffController {

    /** How the mirror pixels are produced. Only [OWN_CONTENT] is independent of the phone's screen power. */
    enum class PipelineMode {
        /** Current default: OS zero-copy mirror of the physical display (dies when the phone screen sleeps). */
        AUTO_MIRROR,

        /** A dedicated own-content virtual display the app draws into (survives screen-off). */
        OWN_CONTENT
    }

    @Volatile
    var pipelineMode: PipelineMode = PipelineMode.AUTO_MIRROR

    /**
     * Whether the car mirror can keep displaying content after the phone screen turns off, for the
     * given [mode]. Pure so it's unit-testable and so callers can warn the user up front.
     */
    fun survivesScreenOff(mode: PipelineMode = pipelineMode): Boolean =
        mode == PipelineMode.OWN_CONTENT

    /** Human-readable explanation for the status/overlay UI. */
    fun statusLabel(mode: PipelineMode = pipelineMode): String = when (mode) {
        PipelineMode.AUTO_MIRROR ->
            "screen-off: mirror pauses (AUTO_MIRROR follows phone display power)"
        PipelineMode.OWN_CONTENT ->
            "screen-off: mirror continues (own-content display)"
    }
}
