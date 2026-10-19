package dev.autobridge.browser

/**
 * The pure routing + reconciliation logic for the real-system-keyboard (Gboard) route. Kept free of
 * Android types so it unit-tests on the JVM ([CarImeTargetRoutingTest], [CarImeBufferTest]) and — the
 * point of finding 3 — so the renderer dispatches on the SAME [routeImeEdit] result the test guards,
 * with no inline reimplementation of the routing body.
 *
 * The app's [BrowserTextBuffer] stays the single source of truth for the field text. The IME's
 * [android.view.inputmethod.BaseInputConnection] is non-backed: it merely reports the buffer to
 * Gboard (`initialSel*` + `getTextBefore/AfterCursor`) and forwards each raw edit here as an
 * [ImeEdit], which [applyImeEdit] folds into the buffer using the buffer's own semantics.
 */

/** One raw IME edit, applied to the authoritative [BrowserTextBuffer] by [applyImeEdit]. */
internal sealed interface ImeEdit {
    /** commitText / setComposingText / a printable key: replace the selection with [text]. */
    data class Commit(val text: String) : ImeEdit

    /** deleteSurroundingText(before,*) / KEYCODE_DEL: delete [count] code points before the cursor. */
    data class DeleteBefore(val count: Int) : ImeEdit
}

/**
 * Applies one IME edit to the authoritative [buffer], in place, matching [BrowserTextBuffer]
 * semantics exactly:
 *  - [ImeEdit.Commit] -> [BrowserTextBuffer.insert]: replaces the current selection (so the ADDRESS
 *    `selectAll = true` seed is replaced on the first commit, AABrowser's "type to replace the
 *    address"), then collapses the cursor after the inserted text. Subsequent commits append at the
 *    cursor. Commit is a selection-replace, never a wholesale buffer-replace.
 *  - [ImeEdit.DeleteBefore] -> N [BrowserTextBuffer.backspace]s, which delete the selection if one
 *    exists else one code point each (surrogate/Thai-mark safe) and never underflow past empty.
 */
internal fun applyImeEdit(buffer: BrowserTextBuffer, edit: ImeEdit) {
    when (edit) {
        is ImeEdit.Commit -> buffer.insert(edit.text)
        is ImeEdit.DeleteBefore -> repeat(edit.count) { buffer.backspace() }
    }
}

/** Whether an IME key is an ordinary edit or the Go / Enter action. */
internal enum class ImeKeyKind { EDIT, GO }

/**
 * Where a committed IME edit must go — the SINGLE result type the renderer dispatches on (finding
 * 3). Each case maps to the EXACT existing transport the canvas keyboard uses, so address-vs-field
 * behaviour cannot drift between the two routes.
 */
internal sealed interface CarImeRoute {
    /** FIELD edit: mirror the (trimmed) buffer live into the focused page field. `submitText(text, autoSubmit = false)`. */
    data class MirrorLive(val text: String) : CarImeRoute

    /** ADDRESS Go: resolve + load the typed address/search. */
    data class CommitAddress(val text: String) : CarImeRoute

    /** FIELD Go: submit the field (auto-submit). `submitText(text, autoSubmit = true)`. */
    data class CommitFieldSubmit(val text: String) : CarImeRoute

    /** Nothing to do (ADDRESS edits never mirror; a blank ADDRESS Go does not navigate). */
    data object Ignore : CarImeRoute
}

/**
 * The one mapper the renderer executes for every IME edit/action. Reuses [CarKeyboardMirror.liveWrite]
 * for the EDIT case so FIELD-mirror / ADDRESS-never parity with the canvas route is literally the
 * same decision, pinned by [CarKeyboardMirrorTest]. For GO it mirrors `commitKeyboard`'s semantics:
 * ADDRESS non-blank -> [CarImeRoute.CommitAddress] (trimmed); ADDRESS blank -> [CarImeRoute.Ignore];
 * FIELD -> [CarImeRoute.CommitFieldSubmit] (trimmed).
 */
internal fun routeImeEdit(
    target: CarSurfaceKeyboard.Target,
    bufferText: String,
    kind: ImeKeyKind,
): CarImeRoute = when (kind) {
    ImeKeyKind.EDIT ->
        CarKeyboardMirror.liveWrite(pageMutated = true, target = target, bufferText = bufferText)
            ?.let { CarImeRoute.MirrorLive(it) }
            ?: CarImeRoute.Ignore

    ImeKeyKind.GO -> when (target) {
        CarSurfaceKeyboard.Target.ADDRESS ->
            if (bufferText.isNotBlank()) CarImeRoute.CommitAddress(bufferText.trim())
            else CarImeRoute.Ignore

        CarSurfaceKeyboard.Target.FIELD -> CarImeRoute.CommitFieldSubmit(bufferText.trim())
    }
}

/**
 * `keyboardOpen` is true when either keyboard is up — the OR definition. Pure so the invariant is
 * JVM-testable without a window.
 */
internal fun isKeyboardOpen(hasCanvas: Boolean, hasImeSession: Boolean): Boolean =
    hasCanvas || hasImeSession

/**
 * The dismiss-detection rule (finding 1): while an IME session is open, a transition of
 * `WindowInsets.Type.ime()` visibility to false means the user dismissed Gboard directly, so the
 * session must be closed. Pure so the rule is JVM-testable without a window.
 */
internal fun shouldCloseImeOnInsets(imeSessionActive: Boolean, imeVisible: Boolean): Boolean =
    imeSessionActive && !imeVisible
