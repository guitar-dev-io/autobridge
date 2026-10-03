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
import dev.autobridge.R
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

        val carSurface = CarDisplayInfo.last
        val carDisplayLabel = CarDisplayInfo.label(carContext, carSurface)
        val carDisplayText =
            if (carSurface != null && CarDisplayInfo.isLowestStream(carSurface)) {
                // The head unit stretches this stream to its panel, so everything looks enlarged.
                carContext.getString(R.string.car_diag_lowest_resolution, carDisplayLabel)
            } else {
                carDisplayLabel
            }

        val list = ItemList.Builder()
            .addItem(statusRow(carContext.getString(R.string.car_diag_car_display), carDisplayText))
            .addItem(statusRow(carContext.getString(R.string.car_diag_pipeline), MirrorCoordinator.activePipelineMode.name))
            .addItem(
                statusRow(
                    carContext.getString(R.string.car_diag_mirror),
                    carContext.getString(
                        if (MirrorCoordinator.isMirroring) R.string.car_diag_active
                        else R.string.car_diag_inactive
                    )
                )
            )
            .addItem(statusRow(carContext.getString(R.string.car_diag_uptime), uptimeLabel()))
            .addItem(statusRow(carContext.getString(R.string.car_diag_source_size), sourceSizeLabel()))
            .addItem(
                statusRow(
                    carContext.getString(R.string.car_diag_reconnects),
                    ReconnectTracker.reconnectCount.toString()
                )
            )
            .addItem(
                statusRow(
                    carContext.getString(R.string.car_diag_frame_rate),
                    if (selfDrawn) fpsLabel(stats)
                    else carContext.getString(R.string.car_diag_os_owned)
                )
            )
            .addItem(
                statusRow(
                    carContext.getString(R.string.car_diag_frames),
                    if (selfDrawn) "${stats.rendered} / ${stats.dropped} / ${stats.captured}"
                    else carContext.getString(R.string.car_diag_not_applicable)
                )
            )
            .addItem(
                statusRow(
                    carContext.getString(R.string.car_diag_last_latency),
                    if (selfDrawn) stats.lastLatencyMs?.let { "$it ms" } ?: "—"
                    else carContext.getString(R.string.car_diag_not_applicable)
                )
            )
            .addItem(
                Row.Builder()
                    .setTitle(carContext.getString(R.string.car_diag_audio))
                    .addText(carContext.getString(R.string.car_diag_audio_caption))
                    .setBrowsable(true)
                    .setOnClickListener {
                        CarNavigation.open(screenManager, "CarAudioDiagnosticsScreen") {
                            CarAudioDiagnosticsScreen(carContext)
                        }
                    }
                    .build()
            )
            .addItem(
                Row.Builder()
                    .setTitle(carContext.getString(R.string.car_diag_refresh))
                    .addText(carContext.getString(R.string.car_diag_refresh_caption))
                    .setOnClickListener { invalidate() }
                    .build()
            )
            .addItem(
                Row.Builder()
                    .setTitle(carContext.getString(R.string.car_diag_clear))
                    .addText(carContext.getString(R.string.car_diag_clear_caption))
                    .setOnClickListener {
                        MirrorDiagnostics.clearEvents()
                        CarToast.makeText(
                            carContext,
                            carContext.getString(R.string.car_diag_cleared),
                            CarToast.LENGTH_SHORT
                        ).show()
                        invalidate()
                    }
                    .build()
            )
            .build()

        return ListTemplate.Builder()
            .setHeader(
                Header.Builder()
                    .setTitle(carContext.getString(R.string.car_diag_title))
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
        if (stats.rendered > 0) "%.1f FPS".format(stats.measuredFps)
        else carContext.getString(R.string.car_diag_measuring)

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
