package dev.autobridge.browser

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
    const val TRAY = 0xFF0D1626.toInt()
    const val TRAY_EDGE = 0xFF1E2A3E.toInt()
    const val LETTER_CAP = 0xFF2C374B.toInt()
    const val FUNCTION_CAP = 0xFF1C2638.toInt()
    const val CAP_SHADOW = 0xFF05090F.toInt()
    const val SHIFT_ON_CAP = 0xFFE9EEF7.toInt()
    const val SHIFT_ON_INK = 0xFF0D1626.toInt()
    const val GO_CAP = 0xFF1A73FF.toInt()
    const val INK = 0xFFFFFFFF.toInt()
    const val INK_SECONDARY = 0xFF9AA7BA.toInt()
    const val FIELD = 0xFF1C2638.toInt()
    const val FIELD_EDGE = 0xFF33415A.toInt()
    const val CURSOR = 0xFF4C9BFF.toInt()
    const val SELECTION = 0x664C9BFF

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
    const val PRESSED_CAP = 0xFF55657F.toInt()
    const val GO_PRESSED_CAP = 0xFF5C9DFF.toInt()

    /** The cap for [key] on the frame drawn for the tap that hit it (the car host reports no press/release). */
    fun pressedCap(key: BrowserKey): Int = if (key is BrowserKey.Go) GO_PRESSED_CAP else PRESSED_CAP

    /** Go carries a chevron, like the iOS keyboard's action key. */
    fun goLabel(word: String): String = "$word ›"
}
