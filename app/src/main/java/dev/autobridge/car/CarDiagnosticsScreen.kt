package dev.autobridge.car

import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.Header
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import dev.autobridge.display.MirrorDiagnostics
import dev.autobridge.mirror.MirrorCoordinator
import dev.autobridge.mirror.ReconnectTracker

/**
 * Car-native live diagnostics for the mirror pipeline. All values are read from the same
 * [MirrorDiagnostics]/[MirrorCoordinator]/[ReconnectTracker] state used by the phone developer
 * view, so the two surfaces never disagree. Frame counters are meaningful only for the SELF_DRAWN
 * renderer; AUTO_MIRROR is a zero-copy OS path with no app-observable frames.
 */
class CarDiagnosticsScreen(carContext: CarContext) : Screen(carContext) {
    override fun onGetTemplate(): Template {
        val stats = MirrorDiagnostics.frameStats()
        val selfDrawn = MirrorCoordinator.activePipelineMode ==
            dev.autobridge.display.ScreenOffController.PipelineMode.SELF_DRAWN

        val list = ItemList.Builder()
            .addItem(statusRow("Pipeline", MirrorCoordinator.activePipelineMode.name))
            .addItem(statusRow("Mirror", if (MirrorCoordinator.isMirroring) "Active" else "Inactive"))
            .addItem(statusRow("Uptime", uptimeLabel()))
            .addItem(statusRow("Source size", sourceSizeLabel()))
            .addItem(statusRow("Reconnects", ReconnectTracker.reconnectCount.toString()))
            .addItem(
                statusRow(
                    "Frame rate",
                    if (selfDrawn) fpsLabel(stats) else "n/a • AUTO_MIRROR is OS-owned"
                )
            )
            .addItem(
                statusRow(
                    "Frames (r/d/c)",
                    if (selfDrawn) "${stats.rendered} / ${stats.dropped} / ${stats.captured}" else "n/a"
                )
            )
            .addItem(
                statusRow(
                    "Last latency",
                    if (selfDrawn) stats.lastLatencyMs?.let { "${it} ms" } ?: "—" else "n/a"
                )
            )
            .addItem(
                Row.Builder()
                    .setTitle("Refresh")
                    .addText("Re-read live diagnostics")
                    .setOnClickListener { invalidate() }
                    .build()
            )
            .addItem(
                Row.Builder()
                    .setTitle("Clear events")
                    .addText("Reset the diagnostics event ring")
                    .setOnClickListener {
                        MirrorDiagnostics.clearEvents()
                        CarToast.makeText(carContext, "Diagnostics events cleared", CarToast.LENGTH_SHORT).show()
                        invalidate()
                    }
                    .build()
            )
            .build()

        return ListTemplate.Builder()
            .setHeader(
                Header.Builder()
                    .setTitle("Diagnostics")
                    .setStartHeaderAction(Action.BACK)
                    .build()
            )
            .setSingleList(list)
            .build()
    }

    private fun statusRow(title: String, value: String): Row =
        Row.Builder()
            .setTitle(title)
            .addText(value)
            .setEnabled(false)
            .build()

    private fun fpsLabel(stats: MirrorDiagnostics.FrameStats): String =
        if (stats.rendered > 0) "%.1f FPS".format(stats.measuredFps) else "measuring…"

    private fun uptimeLabel(): String {
        val ms = MirrorDiagnostics.currentMirroringUptimeMs() ?: return "—"
        val totalSeconds = ms / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return if (minutes > 0) "${minutes}m ${seconds}s" else "${seconds}s"
    }

    private fun sourceSizeLabel(): String =
        MirrorCoordinator.activeSourceSize?.let { "${it.width} x ${it.height}" } ?: "—"
}
