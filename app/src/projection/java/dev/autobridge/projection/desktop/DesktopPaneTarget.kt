package dev.autobridge.projection.desktop

import android.content.Context
import dev.autobridge.desktop.DesktopEngine
import dev.autobridge.desktop.browser.DesktopBrowsers
import dev.autobridge.duoscreen.system.DuoScreenSelfPane

/**
 * Tells Multi-app (:duoscreen, which cannot see the desktop engine) whether AutoBridge's desktop
 * browser can be offered as a pane: an arm64 phone, PRoot in this build, and Chrome or Brave
 * installed. Installed from the phone's Multi-app settings and from the car session, the two
 * places a pane's app is picked.
 */
object DesktopPaneTarget {
    fun install(context: Context) {
        val app = context.applicationContext
        DuoScreenSelfPane.desktopAvailable = {
            runCatching {
                DesktopEngine.init(app)
                DesktopEngine.supported && DesktopBrowsers.all.any { DesktopEngine.state(it.id).state.installed }
            }.getOrDefault(false)
        }
    }
}
