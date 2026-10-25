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
    /** Nothing to set up: this build has no desktop browsers. */
    @Suppress("UNUSED_PARAMETER")
    fun install(context: android.content.Context) = Unit

    @Suppress("UNUSED_PARAMETER")
    fun moreEntries(carContext: CarContext, screenManager: ScreenManager): List<CarExtensionEntry> =
        emptyList()

    /** False: the Web card opens [CarBrowserScreen]. */
    @Suppress("UNUSED_PARAMETER")
    fun openWeb(carContext: CarContext, screenManager: ScreenManager): Boolean = false

    /** False: no Chrome for Linux in this build. */
    @Suppress("UNUSED_PARAMETER")
    fun canOpenChrome(carContext: CarContext): Boolean = false

    /** False: no desktop browser here, so there is nothing to ask the user to install. */
    @Suppress("UNUSED_PARAMETER")
    fun canInstallChrome(carContext: CarContext): Boolean = false

    /** False: no desktop browser, so the site opens in the built-in browser. */
    @Suppress("UNUSED_PARAMETER")
    fun openUrl(carContext: CarContext, screenManager: ScreenManager, url: String): Boolean = false

    /** False: no desktop browser to offer on the browser's start page. */
    @Suppress("UNUSED_PARAMETER")
    fun canOpenWeb(carContext: CarContext): Boolean = false
}
