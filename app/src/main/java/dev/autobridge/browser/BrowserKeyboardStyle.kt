package dev.autobridge.browser

import dev.autobridge.design.DesignTokens

/**
 * How the browser keyboard looks, shared by the car surface ([CarSurfaceKeyboard]) and the Duo
 * pane ([BrowserKeyboardView]) so the two cannot drift.
 *
 * iOS-style, after the design picked on the canvas: a dark navy tray, raised keys with large
 * rounded corners and a soft shadow under each, function keys a shade darker than letters, and
 * a blue Go with a chevron. Fixed colours rather than the browser theme: the keyboard is the one
 * piece of chrome that should look the same in every theme.
 */
object BrowserKeyboardStyle {
    const val TRAY = DesignTokens.Keyboard.TRAY
    const val TRAY_EDGE = DesignTokens.Keyboard.TRAY_EDGE
    const val LETTER_CAP = DesignTokens.Keyboard.LETTER_CAP
    const val FUNCTION_CAP = DesignTokens.Keyboard.FUNCTION_CAP
    const val CAP_SHADOW = DesignTokens.Keyboard.CAP_SHADOW
    const val SHIFT_ON_CAP = DesignTokens.Keyboard.SHIFT_ON_CAP
    const val SHIFT_ON_INK = DesignTokens.Keyboard.TRAY
    const val GO_CAP = DesignTokens.Keyboard.GO_CAP
    const val INK = DesignTokens.Keyboard.INK
    const val INK_SECONDARY = DesignTokens.Keyboard.INK_SECONDARY
    const val FIELD = DesignTokens.Keyboard.FUNCTION_CAP
    const val FIELD_EDGE = DesignTokens.Keyboard.FIELD_EDGE
    const val CURSOR = DesignTokens.Keyboard.CURSOR
    const val SELECTION = DesignTokens.Keyboard.SELECTION

    /** Corner radius of a key, in dp: rounder than Material, as on iOS. */
    const val KEY_RADIUS_DP = 10f

    /** How far the shadow sits below a key, in dp. */
    const val SHADOW_DP = 1.5f

    /** Cap and label colours for [key] with [shift] as it stands. */
    fun colours(key: BrowserKey, shift: ShiftState): Pair<Int, Int> = when {
        key is BrowserKey.Go -> GO_CAP to INK
        key is BrowserKey.Shift && shift.active -> SHIFT_ON_CAP to SHIFT_ON_INK
        key is BrowserKey.Text && key.lower.length <= 1 -> LETTER_CAP to INK
        key is BrowserKey.Space -> LETTER_CAP to INK_SECONDARY
        else -> FUNCTION_CAP to INK
    }

    /** A key cap while it is drawn pressed: a step lighter than any resting cap. */
    const val PRESSED_CAP = DesignTokens.Keyboard.PRESSED_CAP
    const val GO_PRESSED_CAP = DesignTokens.Keyboard.GO_PRESSED_CAP

    /** The cap for [key] on the frame drawn for the tap that hit it (the car host reports no press/release). */
    fun pressedCap(key: BrowserKey): Int = if (key is BrowserKey.Go) GO_PRESSED_CAP else PRESSED_CAP

    /** Go carries a chevron, like the iOS keyboard's action key. */
    fun goLabel(word: String): String = "$word ›"
}
