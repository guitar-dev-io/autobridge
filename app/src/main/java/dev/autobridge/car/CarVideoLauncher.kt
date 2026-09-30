package dev.autobridge.car

import androidx.car.app.CarContext
import androidx.car.app.ScreenManager
import dev.autobridge.settings.VideoDisclaimerStore

/**
 * Single entry point for opening [CarVideoScreen]. Every place that plays video (Media Center's
 * Video/Streaming tabs, the media/gallery library, IPTV) routes through here so the one-time safety
 * warning ([CarVideoDisclaimerScreen]) is never accidentally skipped by a call site that pushes
 * CarVideoScreen directly.
 */
object CarVideoLauncher {
    fun open(screenManager: ScreenManager, carContext: CarContext, uri: String, title: String) {
        if (VideoDisclaimerStore.isAccepted(carContext)) {
            screenManager.push(CarVideoScreen(carContext, uri, title))
        } else {
            screenManager.push(CarVideoDisclaimerScreen(carContext, uri, title))
        }
    }
}
