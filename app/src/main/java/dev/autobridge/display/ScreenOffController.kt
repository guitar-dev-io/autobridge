package dev.autobridge.display

import dev.autobridge.input.ShizukuInputBackend

/**
 * Pipeline and screen-off capability model.
 *
 * `OWN_CONTENT` remains a dedicated app-display marker and is intentionally unavailable. The
 * privileged panel-off capability is separate: it can turn only the physical panel off through a
 * Shizuku/root-style backend while AUTO_MIRROR or SELF_DRAWN continues using the existing source.
 */
object ScreenOffController {
    enum class PipelineMode {
        /** Current default: OS zero-copy mirror of the physical display. */
        AUTO_MIRROR,

        /** ImageReader + Canvas output; transform/FPS are app-controlled. */
        SELF_DRAWN,

        /** Dedicated own-content virtual display; not implemented in this build. */
        OWN_CONTENT
    }

    @Volatile
    var pipelineMode: PipelineMode = PipelineMode.AUTO_MIRROR

    fun isAvailable(mode: PipelineMode = pipelineMode): Boolean =
        mode != PipelineMode.OWN_CONTENT

    /** Only a dedicated own-content display can make the source independent of panel power. */
    fun survivesScreenOff(mode: PipelineMode = pipelineMode): Boolean =
        mode == PipelineMode.OWN_CONTENT

    /** True only after the connected Shizuku user service passes its reflection capability probe. */
    fun panelOffAvailable(): Boolean = ShizukuInputBackend.isPanelPowerAvailable

    fun panelOffStatusLabel(): String = if (panelOffAvailable()) {
        "privileged panel-off ready (Shizuku/root; opt-in)"
    } else {
        "privileged panel-off unavailable; public dim fallback"
    }

    /** Human-readable explanation for the selected mirror pipeline. */
    fun statusLabel(mode: PipelineMode = pipelineMode): String = when (mode) {
        PipelineMode.AUTO_MIRROR ->
            if (panelOffAvailable()) {
                "screen-off: optional privileged panel-off; source is still AUTO_MIRROR"
            } else {
                "screen-off: mirror pauses (AUTO_MIRROR follows phone display power)"
            }
        PipelineMode.SELF_DRAWN ->
            if (panelOffAvailable()) {
                "screen-off: optional privileged panel-off; capture source remains default display"
            } else {
                "screen-off: not guaranteed (self-drawn capture follows source display power)"
            }
        PipelineMode.OWN_CONTENT ->
            "screen-off: continues with dedicated own-content display (not available in this build)"
    }
}
