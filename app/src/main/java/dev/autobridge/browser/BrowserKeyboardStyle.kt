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

    /**
     * The car keyboard's colours. [DARK] is the fixed Material 3 set above, which the Duo pane's
     * keyboard keeps; the car's own keyboard takes [fromChrome] of the car browser's scheme
     * ([BrowserTheme.car]), so it follows Settings > Appearance with the rest of the car browser:
     * the tray on the card colour, letters on the raised step, Go on the accent with its onAccent
     * label, and the cursor and selection in the accent.
     */
    data class Colors(
        val tray: Int,
        val trayEdge: Int,
        val letterCap: Int,
        val functionCap: Int,
        val onFunctionCap: Int,
        val shiftOnCap: Int,
        val shiftOnInk: Int,
        val goCap: Int,
        val onGoCap: Int,
        val ink: Int,
        val inkSecondary: Int,
        val field: Int,
        val cursor: Int,
        val selection: Int,
        val letterPressed: Int,
        val functionPressed: Int,
        val goPressed: Int,
        val shiftOnPressed: Int,
    ) {
        /** Cap and label colours for [key] with [shift] as it stands. */
        fun colours(key: BrowserKey, shift: ShiftState): Pair<Int, Int> = when {
            key is BrowserKey.Go -> goCap to onGoCap
            key is BrowserKey.Shift && shift.active -> shiftOnCap to shiftOnInk
            key is BrowserKey.Text && key.lower.length <= 1 -> letterCap to ink
            key is BrowserKey.Space -> letterCap to inkSecondary
            else -> functionCap to onFunctionCap
        }

        /** The cap for [key] on the frame drawn for the tap that hit it. */
        fun pressedCap(key: BrowserKey, shift: ShiftState = ShiftState.OFF): Int = when {
            key is BrowserKey.Go -> goPressed
            key is BrowserKey.Shift && shift.active -> shiftOnPressed
            key is BrowserKey.Text && key.lower.length <= 1 -> letterPressed
            key is BrowserKey.Space -> letterPressed
            else -> functionPressed
        }

        companion object {
            val DARK = Colors(
                tray = TRAY, trayEdge = TRAY_EDGE, letterCap = LETTER_CAP, functionCap = FUNCTION_CAP,
                onFunctionCap = ON_FUNCTION_CAP, shiftOnCap = SHIFT_ON_CAP, shiftOnInk = SHIFT_ON_INK,
                goCap = GO_CAP, onGoCap = ON_GO_CAP, ink = INK, inkSecondary = INK_SECONDARY, field = FIELD,
                cursor = CURSOR, selection = SELECTION, letterPressed = LETTER_PRESSED,
                functionPressed = FUNCTION_PRESSED, goPressed = GO_PRESSED, shiftOnPressed = SHIFT_ON_PRESSED,
            )

            /**
             * The keyboard on [c]'s roles, the same structure as [DARK]: the tray one step off the
             * page, letters a step above the tray, function keys on the tile colour, and a pressed
             * key 12% of the way to its label colour (the M3 state layer).
             */
            fun fromChrome(c: BrowserColors): Colors {
                fun pressed(cap: Int, ink: Int) = BrowserTheme.blend(cap, ink, 0.12f)
                val letter = c.surfaceContainerHighest
                val function = c.secondaryContainer
                return Colors(
                    tray = c.surfaceContainer, trayEdge = c.outlineVariant,
                    letterCap = letter, functionCap = function, onFunctionCap = c.onSecondaryContainer,
                    shiftOnCap = c.primaryContainer, shiftOnInk = c.onPrimaryContainer,
                    goCap = c.primary, onGoCap = c.onPrimary,
                    ink = c.onSurface, inkSecondary = c.onSurfaceVariant,
                    field = c.surfaceContainerLowest,
                    cursor = c.primary, selection = (0x66 shl 24) or (c.primary and 0x00FFFFFF),
                    letterPressed = pressed(letter, c.onSurface),
                    functionPressed = pressed(function, c.onSecondaryContainer),
                    goPressed = pressed(c.primary, c.onPrimary),
                    shiftOnPressed = pressed(c.primaryContainer, c.onPrimaryContainer),
                )
            }
        }
    }

    /** Go carries a chevron. */
    fun goLabel(word: String): String = "$word ›"
}
