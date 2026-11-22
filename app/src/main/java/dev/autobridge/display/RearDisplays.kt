package dev.autobridge.display

import android.content.Context
import android.hardware.display.DisplayManager
import android.view.Display

/**
 * Finds a screen the passengers can watch: HDMI or USB-C video out from the phone, a Miracast
 * receiver, or the second screen of an Android head unit. See `docs/REAR_SCREEN.md`.
 *
 * The rule is kept apart from Android ([pick] over plain [Facts]) so it can be tested without a
 * device, since the displays that must NOT be chosen are the easy mistake: the phone's own screen,
 * Android Auto's projection display (private), and AutoBridge's own virtual displays for the car
 * browser, Duo Screen and mirroring, which are named `AutoBridge…`.
 */
object RearDisplays {

    /** What [pick] needs to know about one display. */
    data class Facts(
        val id: Int,
        val name: String,
        val isPresentation: Boolean,
        val isPrivate: Boolean,
        val isValid: Boolean,
        val isOn: Boolean,
    )

    private const val OWN_PREFIX = "AutoBridge"

    /** The display to use as the rear screen, or null. A screen that is on wins over one that is off. */
    fun pick(displays: List<Facts>): Facts? = displays
        .filter { it.id != Display.DEFAULT_DISPLAY }
        .filter { it.isPresentation && !it.isPrivate && it.isValid }
        .filterNot { it.name.startsWith(OWN_PREFIX) }
        .sortedWith(compareByDescending<Facts> { it.isOn }.thenBy { it.id })
        .firstOrNull()

    /** The rear screen connected right now, or null. */
    fun find(context: Context): Display? {
        val manager = context.getSystemService(DisplayManager::class.java) ?: return null
        val displays = runCatching {
            manager.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION).toList()
        }.getOrDefault(emptyList())
        val chosen = pick(displays.map { facts(it) }) ?: return null
        return displays.firstOrNull { it.displayId == chosen.id }
    }

    fun facts(display: Display) = Facts(
        id = display.displayId,
        name = display.name.orEmpty(),
        isPresentation = display.flags and Display.FLAG_PRESENTATION != 0,
        isPrivate = display.flags and Display.FLAG_PRIVATE != 0,
        isValid = display.isValid,
        isOn = display.state == Display.STATE_ON,
    )

    /**
     * Calls [onChange] on the main thread whenever a display comes, goes or changes, so a screen
     * can show or hide its rear-screen button. Returns the call that stops listening.
     */
    fun watch(context: Context, onChange: () -> Unit): () -> Unit {
        val manager = context.getSystemService(DisplayManager::class.java) ?: return {}
        val listener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) = onChange()
            override fun onDisplayRemoved(displayId: Int) = onChange()
            override fun onDisplayChanged(displayId: Int) = Unit
        }
        manager.registerDisplayListener(listener, android.os.Handler(android.os.Looper.getMainLooper()))
        return { manager.unregisterDisplayListener(listener) }
    }
}
