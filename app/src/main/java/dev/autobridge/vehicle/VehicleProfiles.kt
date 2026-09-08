package dev.autobridge.vehicle

import dev.autobridge.core.model.Insets
import dev.autobridge.core.model.ScaleMode
import dev.autobridge.core.model.Size
import dev.autobridge.core.model.VehicleProfile

/** Default profiles. Runtime surface dimensions always take precedence over hints here. */
object VehicleProfiles {
    val FORD_NEXT_GEN = VehicleProfile(
        id = "FORD_NEXT_GEN",
        name = "Ford Next Gen",
        carResolution = null,
        safeInsets = Insets.ZERO,
        touchOffsetX = 0f,
        touchOffsetY = 0f,
        preferredFps = 60,
        defaultScale = ScaleMode.FIT
    )

    val GENERIC_ANDROID_AUTO = VehicleProfile(
        id = "GENERIC_ANDROID_AUTO",
        name = "Generic Android Auto",
        carResolution = null,
        safeInsets = Insets.ZERO,
        touchOffsetX = 0f,
        touchOffsetY = 0f,
        preferredFps = null,
        defaultScale = ScaleMode.FIT
    )

    val DHU_TEST = VehicleProfile(
        id = "DHU_TEST",
        name = "Android Auto DHU",
        carResolution = Size(800, 480),
        safeInsets = Insets.ZERO,
        touchOffsetX = 0f,
        touchOffsetY = 0f,
        preferredFps = 60,
        defaultScale = ScaleMode.FIT
    )

    val all: List<VehicleProfile> = listOf(FORD_NEXT_GEN, GENERIC_ANDROID_AUTO, DHU_TEST)

    fun byId(id: String?): VehicleProfile =
        all.firstOrNull { it.id == id } ?: GENERIC_ANDROID_AUTO
}
