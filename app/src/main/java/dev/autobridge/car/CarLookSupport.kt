package dev.autobridge.car

import android.graphics.Typeface
import androidx.car.app.model.CarColor
import dev.autobridge.design.AppearanceSpec
import dev.autobridge.design.CarLook
import dev.autobridge.design.FontChoice
import dev.autobridge.design.ThaiStyle
import dev.autobridge.settings.AppearanceStore
import dev.autobridge.ui.AppFonts
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

/**
 * The car look's typefaces. [AppFonts.regular] and friends follow the phone's choice; a car
 * surface follows the car's ([CarLook.font], which is the default when "Use this look on Android
 * Auto" is off), so it builds its own and swaps them when the look's family changes.
 */
internal class CarTypefaces {
    private var key: Pair<FontChoice, ThaiStyle>? = null
    var regular: Typeface? = null
        private set
    var medium: Typeface? = null
        private set
    var bold: Typeface? = null
        private set

    /** Rebuilds the three weights when [look] asks for another family; otherwise nothing. */
    fun update(look: CarLook) {
        val next = look.font to look.thai
        if (next == key) return
        key = next
        val base = AppFonts.forChoice(look.font, look.thai)
        regular = Typeface.create(base, 400, false)
        medium = Typeface.create(base, 500, false)
        bold = Typeface.create(base, 700, false)
    }
}

/**
 * Calls [onChange] on the main thread whenever Settings > Appearance changes, so a car screen that
 * only repaints on its own events (the radio grid, the browser between page frames) picks up the
 * new look while it is on screen. The screen compares [CarLook.current] with the look it drew with
 * and lays out again when they differ, the way the car home does.
 */
internal class CarLookWatch(private val onChange: () -> Unit) {
    private var scope: CoroutineScope? = null

    fun start() {
        if (scope != null) return
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).also { s ->
            // The flow replays the current value on collect; only later changes repaint.
            s.launch { AppearanceStore.spec.drop(1).collect { onChange() } }
        }
    }

    fun stop() {
        scope?.let { (it.coroutineContext[Job])?.cancel() }
        scope = null
    }
}

/**
 * The accent for host-drawn (template) car screens. Android Auto draws a template's background
 * and text itself, so the accent is the only part of the look such a screen can carry: primary
 * actions and accent icon tints. The host picks the dark or the light shade by its own day/night
 * mode, so both shades of the chosen accent go in.
 */
internal object CarAccent {
    /** (dark-mode shade, light-mode shade) of the car's accent for [spec]. */
    fun shades(spec: AppearanceSpec): Pair<Int, Int> {
        val car = if (spec.carSameLook) spec else AppearanceSpec.DEFAULT
        return car.accent.onDark to car.accent.onLight
    }

    /**
     * `createCustom(color, colorDark)`: the first is drawn when the host is in day (light) mode,
     * the second at night, so the light-mode shade goes first.
     */
    fun color(spec: AppearanceSpec = AppearanceStore.current()): CarColor {
        val (dark, light) = shades(spec)
        return CarColor.createCustom(light, dark)
    }
}
