package dev.autobridge.car

import androidx.car.app.CarContext
import androidx.car.app.ScreenManager

/**
 * The safe (Play) flavor's car extensions: none. Multi-app and the desktop browser engines are
 * sideload-only (see src/projection/java/dev/autobridge/car/CarExtensions.kt), so this build lists
 * no extra rows and the Web card always opens the built-in browser.
 *
 * Exactly one of the two CarExtensions objects is compiled into a flavor, the same way
 * src/translate and src/notranslate split the subtitle engines.
 */
internal object CarExtensions {
    @Suppress("UNUSED_PARAMETER")
    fun moreEntries(carContext: CarContext, screenManager: ScreenManager): List<CarExtensionEntry> =
        emptyList()

    /** False: the Web card opens [CarBrowserScreen]. */
    @Suppress("UNUSED_PARAMETER")
    fun openWeb(carContext: CarContext, screenManager: ScreenManager): Boolean = false
}
