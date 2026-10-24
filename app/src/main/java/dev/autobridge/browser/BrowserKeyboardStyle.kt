package dev.autobridge.browser

import dev.autobridge.design.DesignTokens

/**
 * How the browser keyboard looks, shared by the car surface ([CarSurfaceKeyboard]) and the Duo
 * pane ([BrowserKeyboardView]) so the two cannot drift.
 *
 * Material 3 dark, Gboard-like: a tonal surface tray, flat rounded keys (letters on a neutral
 * surface, function keys on the secondary container), a primary-coloured Go, and a 12% state
 * layer for the pressed key. Fixed colours rather than the browser theme: the keyboard is the one
 * piece of chrome that should look the same in every theme.
 */
object BrowserKeyboardStyle {
    const val TRAY = DesignTokens.Keyboard.TRAY
    const val TRAY_EDGE = DesignTokens.Keyboard.TRAY_EDGE
    const val LETTER_CAP = DesignTokens.Keyboard.LETTER_CAP
    const val FUNCTION_CAP = DesignTokens.Keyboard.FUNCTION_CAP
    const val CAP_SHADOW = DesignTokens.Keyboard.CAP_SHADOW
    const val SHIFT_ON_CAP = DesignTokens.Keyboard.SHIFT_ON_CAP
    const val SHIFT_ON_INK = DesignTokens.Keyboard.SHIFT_ON_INK
    const val ON_FUNCTION_CAP = DesignTokens.Keyboard.ON_FUNCTION_CAP
    const val ON_GO_CAP = DesignTokens.Keyboard.ON_GO_CAP
    const val GO_CAP = DesignTokens.Keyboard.GO_CAP
    const val INK = DesignTokens.Keyboard.INK
    const val INK_SECONDARY = DesignTokens.Keyboard.INK_SECONDARY
    const val FIELD = DesignTokens.Keyboard.FIELD_FILL
    const val FIELD_EDGE = DesignTokens.Keyboard.FIELD_EDGE
    const val CURSOR = DesignTokens.Keyboard.CURSOR
    const val SELECTION = DesignTokens.Keyboard.SELECTION

    /** Corner radius of a key, in dp: Material 3 "medium" (a quarter of the key height, as on the rail). */
    const val KEY_RADIUS_DP = 12f

    /** How far a shadow sits below a key, in dp: none, Material keys are flat. */
    const val SHADOW_DP = 0f

    /** Cap and label colours for [key] with [shift] as it stands. */
    fun colours(key: BrowserKey, shift: ShiftState): Pair<Int, Int> = when {
        key is BrowserKey.Go -> GO_CAP to ON_GO_CAP
        key is BrowserKey.Shift && shift.active -> SHIFT_ON_CAP to SHIFT_ON_INK
        key is BrowserKey.Text && key.lower.length <= 1 -> LETTER_CAP to INK
        key is BrowserKey.Space -> LETTER_CAP to INK_SECONDARY
        else -> FUNCTION_CAP to ON_FUNCTION_CAP
    }

    const val LETTER_PRESSED = DesignTokens.Keyboard.LETTER_PRESSED
    const val FUNCTION_PRESSED = DesignTokens.Keyboard.FUNCTION_PRESSED
    const val GO_PRESSED = DesignTokens.Keyboard.GO_PRESSED
    const val SHIFT_ON_PRESSED = DesignTokens.Keyboard.SHIFT_ON_PRESSED

    /** The cap for [key] on the frame drawn for the tap that hit it (the car host reports no press/release). */
    fun pressedCap(key: BrowserKey, shift: ShiftState = ShiftState.OFF): Int = when {
        key is BrowserKey.Go -> GO_PRESSED
        key is BrowserKey.Shift && shift.active -> SHIFT_ON_PRESSED
        key is BrowserKey.Text && key.lower.length <= 1 -> LETTER_PRESSED
        key is BrowserKey.Space -> LETTER_PRESSED
        else -> FUNCTION_PRESSED
    }

    /** Go carries a chevron. */
    fun goLabel(word: String): String = "$word ›"
}
