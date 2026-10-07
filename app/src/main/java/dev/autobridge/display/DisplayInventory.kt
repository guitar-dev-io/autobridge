package dev.autobridge.display

import android.content.Context
import android.hardware.display.DisplayManager
import android.view.Display
import dev.autobridge.logging.StructuredLog

/**
 * What screens this device's Android can see, for the log.
 *
 * A head unit with a second (rear) screen may present it to Android as another display, or may
 * not (it can just as well be fed by the car's own hardware, which no app can address). Which one
 * a given unit does cannot be told from the model name, so the log records it: how many displays,
 * and for each its id, name, size, density and state. Only screen facts are logged, nothing about
 * the person or what is shown.
 */
object DisplayInventory {
    private const val TAG = "DISPLAYS"

    /** One line per display, e.g. `id=1 "HDMI Screen" 1920x720 dpi=240 state=ON valid=true`. */
    @Suppress("DEPRECATION") // getRealSize/getRealMetrics are the only way to read a display that is not this window's.
    fun describe(context: Context): List<String> {
        val manager = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager ?: return emptyList()
        return manager.displays.map { display ->
            val size = android.graphics.Point().also { runCatching { display.getRealSize(it) } }
            val metrics = android.util.DisplayMetrics().also { runCatching { display.getRealMetrics(it) } }
            "id=${display.displayId} \"${display.name}\" ${size.x}x${size.y} dpi=${metrics.densityDpi} " +
                "state=${stateName(display.state)} valid=${display.isValid}"
        }
    }

    /** Writes the inventory to the log; safe to call more than once. */
    fun log(context: Context) {
        val lines = runCatching { describe(context) }.getOrDefault(emptyList())
        StructuredLog.i(TAG, "${lines.size} display(s)")
        lines.forEach { StructuredLog.i(TAG, it) }
    }

    private fun stateName(state: Int): String = when (state) {
        Display.STATE_ON -> "ON"
        Display.STATE_OFF -> "OFF"
        Display.STATE_DOZE -> "DOZE"
        Display.STATE_DOZE_SUSPEND -> "DOZE_SUSPEND"
        Display.STATE_VR -> "VR"
        Display.STATE_ON_SUSPEND -> "ON_SUSPEND"
        else -> "UNKNOWN"
    }
}
