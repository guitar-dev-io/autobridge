package dev.autobridge.browser

/**
 * The single source of truth for "should this keystroke mirror live into the focused page field,
 * and with what text". Pure, so the decision that drives the [CarWebRenderer] live-mirror route is
 * unit-tested on the JVM rather than discovered on a head unit.
 *
 * The rule matches the projection route's `mutated` set exactly (see
 * `ProjectionBrowserActivity.onKey`): only a keystroke that changed the page-relevant text, and
 * only when the keyboard targets a page field, writes to the page. Shift, language, cursor moves
 * and a cursor-only field tap leave [CarSurfaceKeyboard.Result.Changed.pageMutated] false and never
 * mirror; the address bar ([CarSurfaceKeyboard.Target.ADDRESS]) is never mirrored (it commits only
 * on Go). The returned text is the trimmed authoritative buffer, which includes the empty string so
 * backspace-to-empty clears the page field.
 */
internal object CarKeyboardMirror {
    /**
     * The text to mirror into the focused page field, or null when this keystroke must not mirror.
     *
     * Returns `bufferText.trim()` (possibly empty) only when [pageMutated] is true AND [target] is
     * [CarSurfaceKeyboard.Target.FIELD]; null otherwise.
     */
    fun liveWrite(
        pageMutated: Boolean,
        target: CarSurfaceKeyboard.Target,
        bufferText: String,
    ): String? {
        if (!pageMutated) return null
        if (target != CarSurfaceKeyboard.Target.FIELD) return null
        return bufferText.trim()
    }
}
