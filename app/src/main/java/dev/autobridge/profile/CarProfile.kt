package dev.autobridge.profile

import dev.autobridge.car.CarDisplayInfo
import dev.autobridge.core.model.Insets

/**
 * A car profile someone saved or shared: the head unit's screen as it reported itself, plus the
 * values AutoBridge applies for it.
 *
 * The [screen] is the measurement — what Android Auto handed the app on that car — and is what
 * lets a profile say "this is the car you are connected to". The other fields are the same knobs
 * as [dev.autobridge.display.SurfaceProfile]. A profile captured in a car copies today's values
 * (zero insets and offsets), so sharing one never invents numbers; it only records the screen.
 *
 * [verified] is never true for a saved or imported profile: a file from someone else is a claim,
 * not a measurement made here. Nothing in a profile touches the parked gate, the bypass or any
 * other safety setting — there is no field for it.
 */
data class CarProfile(
    val id: String,
    val name: String,
    val vehicle: String = "",
    val headUnit: String = "",
    val fallbackDpi: Int = 160,
    val safeInsets: Insets = Insets.ZERO,
    val touchOffsetX: Float = 0f,
    val touchOffsetY: Float = 0f,
    val preferredFps: Int? = null,
    val screen: Screen? = null,
    /** ISO-8601 date the screen was recorded, as written by the app that recorded it. */
    val measuredAt: String? = null,
    val appVersion: String? = null,
    val verified: Boolean = false
) {
    /** The car screen as Android Auto reported it, in surface pixels. */
    data class Screen(
        val width: Int,
        val height: Int,
        val dpi: Int,
        val stable: CarDisplayInfo.Area? = null,
        val visible: CarDisplayInfo.Area? = null
    ) {
        val label: String get() = "${width}x$height @ $dpi dpi"
    }

    /** Same screen as the car connected now; dpi included, since the same panel can stream at two. */
    fun matches(surface: CarDisplayInfo.Surface?): Boolean =
        surface != null && screen != null &&
            screen.width == surface.width && screen.height == surface.height && screen.dpi == surface.dpi

    companion object {
        /**
         * A profile for the car connected now, from what it reported ([fingerprint]). Values are
         * left at their defaults on purpose; see the class note.
         */
        fun capture(
            id: String,
            name: String,
            vehicle: String,
            fingerprint: CarDisplayInfo.Fingerprint,
            measuredAt: String,
            appVersion: String
        ): CarProfile? {
            val surface = fingerprint.surface ?: return null
            return CarProfile(
                id = id,
                name = name,
                vehicle = vehicle,
                fallbackDpi = surface.dpi.takeIf { it in CarProfileCodec.DPI_RANGE } ?: 160,
                screen = Screen(surface.width, surface.height, surface.dpi, fingerprint.stable, fingerprint.visible),
                measuredAt = measuredAt,
                appVersion = appVersion
            )
        }
    }
}
