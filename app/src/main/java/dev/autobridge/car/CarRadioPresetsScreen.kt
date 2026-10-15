package dev.autobridge.car

import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.GridItem
import androidx.car.app.model.GridTemplate
import androidx.car.app.model.Header
import androidx.car.app.model.ItemList
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Template
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import dev.autobridge.R
import dev.autobridge.iptv.RadioPresetsStore
import dev.autobridge.media.MediaPlaybackClient
import dev.autobridge.radio.RadioStation

/**
 * The six radio presets (slots 1–6) as a [GridTemplate], capped at [RadioPresetsStore.SLOT_COUNT]
 * cells so a host never has to page them. A filled cell recalls its station onto the shared
 * MediaSession; an empty one says how to fill it from the phone. The full station list stays the
 * paged [ListTemplate] on [CarIptvSourcesScreen] — this grid is only the quick six.
 *
 * Presets are re-read in [DefaultLifecycleObserver.onStart] and again in [onGetTemplate], each
 * guarded by [repaint] (`isAtLeast(STARTED)`), the same re-read pattern [CarIptvScreens]/
 * [CarFavoritesScreen] use so a preset saved on the phone appears here without any sync step.
 */
class CarRadioPresetsScreen(carContext: CarContext) : Screen(carContext) {
    private val mediaPlayback = MediaPlaybackClient(carContext)
    private var presets: List<RadioStation?> = List(RadioPresetsStore.SLOT_COUNT) { null }

    init {
        mediaPlayback.connect(onConnected = { repaint() }, onError = { repaint() })
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                presets = RadioPresetsStore.presets(carContext)
                repaint()
            }

            override fun onDestroy(owner: LifecycleOwner) = mediaPlayback.disconnect()
        })
    }

    private fun repaint() {
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) invalidate()
    }

    override fun onGetTemplate(): Template {
        // Re-read on every template request too, guarded like the other radio screens.
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            presets = RadioPresetsStore.presets(carContext)
        }
        val grid = ItemList.Builder()
        (1..RadioPresetsStore.SLOT_COUNT).forEach { slot ->
            val station = presets.getOrNull(slot - 1)
            grid.addItem(
                GridItem.Builder()
                    .setTitle(carContext.getString(R.string.radio_preset_slot, slot))
                    .setText(station?.title ?: carContext.getString(R.string.car_radio_preset_empty))
                    .setImage(DashboardArtwork.icon(DashboardArtwork.Kind.RADIO), GridItem.IMAGE_TYPE_LARGE)
                    .setOnClickListener { recall(slot, station) }
                    .build()
            )
        }
        return GridTemplate.Builder()
            .setHeader(
                Header.Builder()
                    .setTitle(carContext.getString(R.string.car_radio_presets))
                    .setStartHeaderAction(Action.BACK)
                    .build()
            )
            .setSingleList(grid.build())
            .build()
    }

    private fun recall(slot: Int, station: RadioStation?) {
        if (station == null || station.streamUrl.isBlank()) {
            CarToast.makeText(
                carContext,
                carContext.getString(R.string.car_radio_preset_empty_hint),
                CarToast.LENGTH_LONG
            ).show()
            return
        }
        if (!mediaPlayback.isConnected) {
            CarToast.makeText(
                carContext,
                carContext.getString(R.string.car_iptv_connecting),
                CarToast.LENGTH_SHORT
            ).show()
            return
        }
        mediaPlayback.play(station.streamUrl, station.title)
        screenManager.push(CarRadioNowPlayingScreen(carContext))
    }
}

/**
 * The honest FM state on the car: a [MessageTemplate] that says FM radio is not available on this
 * device and never offers a tuning control. There is no accessible hardware tuner on this target
 * (see [dev.autobridge.radio.HardwareFmProbe]); the phone application cannot control a head-unit
 * tuner through any supported API, so this screen only states the limitation. Internet Radio stays
 * fully functional on the list behind it.
 */
class CarRadioFmUnavailableScreen(carContext: CarContext) : Screen(carContext) {
    override fun onGetTemplate(): Template =
        MessageTemplate.Builder(carContext.getString(R.string.radio_fm_unavailable))
            .setHeader(
                Header.Builder()
                    .setTitle(carContext.getString(R.string.car_radio_fm_title))
                    .setStartHeaderAction(Action.BACK)
                    .build()
            )
            .build()
}
