# Design — real system keyboard (Gboard) over the Android Auto car browser

## Overview

The car browser (`CarBrowserScreen` → `CarWebRenderer`) today types with `CarSurfaceKeyboard`, a
keyboard the app draws straight onto the Android Auto surface with a `Canvas`. It exists because the
Android Auto projected surface could never show the system IME: historically the platform will not
raise an IME on a virtual display it does not own
(https://source.android.com/docs/core/display/multi_display/ime-support), and the car browser's
input arrives as positionless `SurfaceCallback` taps injected as synthetic events, never through a
focused editable `View`.

This design makes the car browser behave like AABrowser: when the user taps the address bar or a
page field, it **prefers the real system keyboard (Gboard, with its emoji/GIF/voice rows)** floating
over the projection, and **falls back to `CarSurfaceKeyboard`** when the host or OS cannot surface an
IME. One build supports both; the choice is made at runtime, per keyboard-open, with no build flavor
and no user setting.

The enabling fact, verified against the code below, is that the car browser's default render path
(`CarBrowserRenderMode.HARDWARE`) **already presents the page through a real, hardware-accelerated
window with a real attached `View` hierarchy** — `CarHardwareWebWindow`'s `Presentation` on a
`VirtualDisplay` whose sink is the car `Surface`. That is exactly the precondition the brief states
Android 15+ needs for a system IME over the projection (a real window + a real focused editable View
+ `onCreateInputConnection` + `showSoftInput`). The work is therefore not to build a new presentation
route, but to put a focused editable `View` with a real `InputConnection` into the window we already
have, probe whether the IME actually appears, and route committed text through the existing
mirror/submit plumbing — otherwise keep drawing `CarSurfaceKeyboard`.

The technology stack is locked to the existing one: Kotlin, `androidx.car.app` (SurfaceCallback
template route), the Android WebView, `android.app.Presentation` + `DisplayManager.createVirtualDisplay`
(already used by `CarHardwareWebWindow`), `android.view.inputmethod` (`InputMethodManager`,
`BaseInputConnection`, `EditorInfo`), and JVM JUnit4 for the pure-logic tests. No new libraries.

## Current-state analysis (verified against the code)

### Route (a) — SurfaceCallback / template route (the car browser; the one to change)

`CarBrowserScreen` (`app/src/main/java/dev/autobridge/car/CarBrowserScreen.kt`) is a
`Screen` + `SurfaceCallback`. It owns the Android Auto surface via `MirrorSurfaceOwnership`, receives
`onSurfaceAvailable`/`onClick`/`onScroll`/`onFling`/`onScale`, and forwards them to the
session-scoped `CarWebRenderer` (`CarBrowserRuntime.renderer`). Its `onGetTemplate` returns a
`NavigationTemplate`; the map action strip carries a keyboard icon whose click (line 239) calls
`if (renderer.keyboardOpen) renderer.closeKeyboard() else renderer.openKeyboard()`.

`CarWebRenderer` (`app/src/main/java/dev/autobridge/browser/CarWebRenderer.kt`, ~4200 lines) has two
sub-paths selected by `CarBrowserRenderMode`:

- **HARDWARE (default)** — `attachHardwareWindow()` (line 3165) builds a `CarHardwareWebWindow`
  (`CarHardwareWebWindow.kt`). That class calls `DisplayManager.createVirtualDisplay(..., surface,
  OWN_CONTENT_ONLY | PRESENTATION)` with the car surface as sink (line 249), then shows an
  `android.app.Presentation` whose root `FrameLayout` holds a public `contentLayer: FrameLayout`
  (line 58, the WebView is attached here via `placePage`) and a private `chromeLayer`. It exposes
  `windowView: View?` = `presentation.window?.decorView` (line 78). **This is a real window with a
  real, attached, hardware-accelerated `View` tree on the car display.** The WebView it hosts is a
  plain `CarWebRenderer.ScrollableWebView` (private inner class, line 246) — it does **not** override
  `onCreateInputConnection`/`onCheckIsTextEditor`, so it inherits WebView defaults.
- **LEGACY_CANVAS (rollback)** — `Surface.lockCanvas()` + `WebView.draw(canvas)`; no window at all.
  `activeMode` drops here if `attachHardwareWindow` catches a `RuntimeException` (line 3205), which
  logs `AutoBridgeVideoLog.w(...)` with `sdkInt`.

Input in **both** sub-paths comes from `SurfaceCallback`, not from the window: taps are injected as
synthetic `MotionEvent`s (`dispatchPageTap`), and `CarHardwareWebWindow`'s own doc (line 51) says
"Input does not come through this window." So even in HARDWARE mode the window never receives a real
focus-driven touch, and nothing in it is a focused editor — which is why no IME appears today.

The render-mode fields are: `requestedMode = CarBrowserRenderMode.current(appContext)` (line 286,
defaults to HARDWARE), `activeMode` (line 287, the live value, drops to LEGACY_CANVAS on failure),
and `hardwareMode get() = activeMode == HARDWARE` (line 292). `hardwareWindow` (line 290) is the live
`CarHardwareWebWindow?`.

The canvas keyboard is `CarSurfaceKeyboard` (`CarSurfaceKeyboard.kt`). `CarWebRenderer` opens it via
`openAddressKeyboard()` (Target.ADDRESS, line 1864, seed = `livePageUrl`, `mode = URL`) or
`openFieldKeyboard(info)` (Target.FIELD, line 1876, seeded from the focused page field read by
`FOCUSED_FIELD_INFO_SCRIPT`), stores it in `keyboard` (line 401), and routes surface taps to
`handleKeyboardTap()` (line 1914). On a `Result.Changed` it calls
`CarKeyboardMirror.liveWrite(result.pageMutated, board.target, board.buffer.text)?.let {
submitText(it, autoSubmit = false) }`; on `Result.Commit` it calls `commitKeyboard(board, text)`
(line 2010). `onKeyboardChanged?.invoke(true/false)` (lines 1900/1907) drives the car Back button
(`CarBrowserScreen.keyboardBack`). `CarKeyboardGestures` swallows page input while the tray is open.

Verified helper semantics that the IME route must match exactly:
- `CarKeyboardMirror.liveWrite(pageMutated, target, bufferText)` returns `bufferText.trim()` **only**
  when `pageMutated == true && target == FIELD`; null otherwise (ADDRESS never mirrors). The empty
  string is a valid return (backspace-to-empty clears the field).
- `submitText(text, autoSubmit)` (line 1408): trims; with no WebView, a Go (`autoSubmit=true`)
  `load`s, a live mirror does nothing; empty + `autoSubmit` returns (does not navigate); empty live
  write falls through so the page field clears; writes `document.activeElement` via JS escaped with
  `jsEscape` = `CarJsEscape.singleQuoted`; on `NO_FOCUS` a Go falls back to `load(clean)`, a live
  mirror is ignored.
- `commitKeyboard(board, text)` (line 2010): `closeKeyboard()`, then ADDRESS → if non-empty,
  `load(if isSentinel(text) text else BrowserInputResolver.resolveTyped(text, SearchEngineStore.engine(...)))`;
  FIELD → `submitText(text, autoSubmit = true)`.
- `BrowserTextBuffer(initial, selectAll)` (BrowserKeyboard.kt): `selectionStart = if (selectAll) 0
  else initial.length`, `selectionEnd = initial.length`. `insert(value)` replaces the selection (or
  inserts at cursor) and collapses the cursor after it. `backspace()` deletes the selection, else one
  code point before the cursor (surrogate-pair safe; Thai marks deleted one at a time). `cursor =
  selectionEnd`. There is also `withSelection(text, start, end)`, `setCursor`, `moveCursor`,
  `selectAll`, `clear`. **This is the authoritative editing model the IME route reuses** (see
  "Target-routing wiring").

`CarPageWebView` (`app/src/projection/.../CarPageWebView.kt`) **does** force
`onCheckIsTextEditor()=false` / `onCreateInputConnection()=null`, but it is used **only** by
`ProjectionBrowserActivity`, not by `CarWebRenderer`. Not in this route's path; not changed here.

### Route (b) — projection ACTIVITY route (reference only, not the target)

`ProjectionBrowserActivity` and `DesktopBrowserActivity` (`app/src/projection/...`) are real
`Activity` windows. `DesktopBrowserActivity.KeyInputView` (lines ~292-360) is the AABrowser-style
reference that works today: `onCheckIsTextEditor()=true`; a real `onCreateInputConnection` returning
`BaseInputConnection(this, false)`; `inputType = TYPE_CLASS_TEXT | TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
| TYPE_TEXT_FLAG_NO_SUGGESTIONS`; `imeOptions = IME_FLAG_NO_EXTRACT_UI | IME_FLAG_NO_FULLSCREEN |
IME_ACTION_GO`; `commitText`/`setComposingText→commitText` (Thai committed, not composed);
`deleteSurroundingText` uses `coerceAtMost(64)`; `performEditorAction → RETURN`; and critically
`sendKeyEvent` **does not blanket-consume** — it maps `KEYCODE_DEL`/unicode/Enter and otherwise
`return super.sendKeyEvent(event)` (verified lines ~365-380). `imeVisible()` reads
`rootWindowInsets?.isVisible(WindowInsets.Type.ime())`. These activities drive the desktop (VNC-style)
engine, not the car browser WebView, so they are the **pattern** we copy, not code we reuse directly.
They confirm the mechanism: a real focused editable View in a real window makes the IME commit normally.

### What this means

HARDWARE mode already satisfies the "real window + real View hierarchy" requirement. The two missing
pieces are: (1) a focused editable View with a real `InputConnection` inside that window, brought up
with `showSoftInput`; (2) a way for committed text to reach the page field / address bar. Both are
additive; neither requires a new presentation route. LEGACY_CANVAS has no window, so it can never host
an IME and always uses the canvas keyboard.

## Capability detection (SDK gate + runtime probe + inconclusive = safe)

A new pure object `CarImeCapability` (`app/src/main/java/dev/autobridge/browser/CarImeCapability.kt`)
owns the decision. It is split into a pure gate (unit-tested on the JVM) and a stateful probe
(exercised on a head unit).

### The SDK + mode gate (pure, tested)

```kotlin
internal object CarImeCapability {
    /**
     * The necessary preconditions, evaluated without touching a live window so they unit-test on
     * the JVM. All must hold before the probe is even attempted; any false forces the canvas
     * keyboard.
     *
     *  - sdkInt >= 35 (Build.VERSION_CODES.VANILLA_ICE_CREAM, Android 15): below this the platform
     *    does not surface an IME over a virtual display it does not own, regardless of host.
     *    AABrowser hard-requires this.
     *  - hardwareMode: only the HARDWARE render path has a real window to host a focused editor.
     *    LEGACY_CANVAS has no window and can never show an IME.
     */
    fun gate(sdkInt: Int, hardwareMode: Boolean): Boolean =
        sdkInt >= 35 && hardwareMode   // Build.VERSION_CODES.VANILLA_ICE_CREAM
}
```

The caller supplies `Build.VERSION.SDK_INT` and `activeMode == CarBrowserRenderMode.HARDWARE`. Note
`activeMode` (not `requestedMode`): a session that fell back to LEGACY_CANVAS because the platform
refused the hardware window must also fall back for the keyboard. The gate uses the literal `35`
(with a `// Build.VERSION_CODES.VANILLA_ICE_CREAM` comment) so the pure test can assert the boundary
without a device constant; `compileSdk = 36` / `minSdk = 29` confirm the single build genuinely spans
<15 and 15+ at runtime, as required. (**[review finding 7]** — one answer, literal 35, no "if
preferred" alternative.)

### The runtime probe (stateful, on the window)

The gate is necessary but not sufficient: a host (DHU, or an older-host Android 15 head unit) may
still refuse to surface the IME. The probe answers "did an IME actually appear?" by observation.

A new class `CarImeProbe` lives with the focused-editor view (below). The result is tri-state:

```kotlin
enum class ImeProbeState { UNKNOWN, AVAILABLE, UNAVAILABLE }
```

Flow, driven by `CarWebRenderer`:

1. Gate fails → state `UNAVAILABLE` immediately; never probe.
2. Gate passes and state `UNKNOWN` → attach the hidden focused editor to the hardware window's
   content layer, `requestFocus()`, `InputMethodManager.showSoftInput(view, 0)`. Register an
   `OnApplyWindowInsetsListener` on `windowView` and read
   `rootWindowInsets?.isVisible(WindowInsets.Type.ime())` (the same signal
   `DesktopBrowserActivity.imeVisible()` uses). Start a timeout (`PROBE_TIMEOUT_MS = 700`).
   - IME insets become visible within the timeout → `AVAILABLE`, cache for the session.
   - Timeout fires with no IME insets → `UNAVAILABLE`, cache, tear the editor down.
3. State `AVAILABLE` → go straight to the IME route.
4. State `UNAVAILABLE` → go straight to the canvas keyboard.

**Probe-editor lifecycle — one explicit rule, because the two probe entry points handle the editor
differently.** (**[review finding 2]**.)

- **Eager probe (first hardware-window attach, no session yet).** There is no `ImeSession` to serve, so
  the eager probe uses a **disposable** `CarImeInputView` seeded empty: attach → `requestFocus()` →
  `showSoftInput` → wait for the insets listener → on resolve (`AVAILABLE` *or* `UNAVAILABLE`), hide +
  **detach and discard** the editor. Only the cached `ImeProbeState` survives. This prewarms the answer
  without leaving a stray editor in the window.
- **Lazy probe (first keyboard open before the eager probe has resolved).** This runs at the exact
  moment the user wants to type, so it must **not** throw away the editor on success. The lazy probe
  constructs the real `CarImeInputView` **seeded from the `ImeSession` buffer** (the same editor that
  will serve the session, with `initialSel*`/`getTextBefore/AfterCursor` already wired to that buffer),
  attaches it, `requestFocus()`, `showSoftInput`, and waits. On `AVAILABLE` the editor is **kept and
  reused** for the session — no detach/re-attach, so the just-raised Gboard is never dropped and the
  seed/`initialSel*` alignment is never lost. On `UNAVAILABLE` the editor is detached and discarded and
  the canvas keyboard opens with the same seed.

So: eager probe discards its editor; lazy probe reuses its editor on success. In both cases the insets
listener that detected IME visibility stays registered for the life of any resulting session (finding 1).

**The probe is always asynchronous.** It never reads insets synchronously after `showSoftInput`
because IME insets animate in on a later frame; a synchronous read would observe not-visible and
wrongly cache `UNAVAILABLE` on exactly the capable Android 15 head units this feature targets. Both
the lazy (first-open) probe and the eager probe (below) resolve only when the insets listener fires
`ime()==visible` **or** `PROBE_TIMEOUT_MS` elapses. (**[review finding 4]**.)

**Inconclusive = safe.** Any ambiguous outcome resolves to `UNAVAILABLE` for this session: the
timeout elapsing, a null `rootWindowInsets`, an exception from `showSoftInput`/`createVirtualDisplay`,
a missing `InputMethodManager`, or the hardware window being absent/recreated mid-probe. The user is
never left without a keyboard — the canvas keyboard opens on every one of these paths. The result is
cached per renderer session (not persisted), because host+OS capability does not change within a
session, and re-probing per keystroke would add latency and flicker.

Caching lives in `CarWebRenderer` as `private var imeProbe: ImeProbeState = ImeProbeState.UNKNOWN`,
reset to `UNKNOWN` when the render mode drops to LEGACY_CANVAS or the hardware window is
released/recreated.

## Presentation-route decision

**Decision: reuse the existing HARDWARE-mode `CarHardwareWebWindow` and add a focused editable View
inside it. Do not build a new Activity route, and do not stack a second `Presentation` over the car
surface.**

Three options were considered:

1. **Add a focused editor View to the existing `CarHardwareWebWindow` (chosen).** The window is
   already a real, hardware-accelerated `Presentation` on a `VirtualDisplay` sinked to the car
   surface, with an attached `View` tree (`contentLayer`) and an exposed `windowView` decor view.
   Adding one more child View — an invisible 1×1 editor that owns the `InputConnection` — is the
   smallest change that meets every requirement. It inherits the window, display, focus owner, and
   insets plumbing that already exist. Risk is lowest: nothing about the presentation lifecycle,
   surface churn (`setSurface`), or chrome drawing changes.

2. **Route the car browser through a real `Activity` (`ProjectionBrowserActivity`-style).** Rejected:
   the car browser is deliberately a `SurfaceCallback` template screen so chrome, tabs, and the
   drawer draw on the same surface without detaching it or re-measuring the WebView. Moving it to an
   Activity would rebuild the entire browser shell and its session/tab model, a large and risky
   rewrite for input alone, and would fight `MirrorSurfaceOwnership`.

3. **Attach a second `Presentation`/overlay window over the SurfaceCallback surface just for input.**
   Rejected: a second window on the same display competes for focus with the content window and
   complicates insets; and in LEGACY_CANVAS there is no display to attach to. Option 1 already has a
   suitable window in exactly the mode where the IME can work, so a second window adds risk with no
   benefit.

The focused editor is a new View class `CarImeInputView`
(`app/src/main/java/dev/autobridge/browser/CarImeInputView.kt`), modeled on
`DesktopBrowserActivity.KeyInputView` but committing into the car browser's text plumbing. It is
**non-backed** (`BaseInputConnection(this, false)`), and it keeps the IME's view of the field aligned
with the app buffer by implementing `getTextBeforeCursor`/`getTextAfterCursor` from the authoritative
buffer (see "Target-routing wiring" for why non-backed is chosen over a backing `Editable`). The
callbacks it exposes carry the IME's raw edit operations; the renderer applies them to the buffer:

```kotlin
internal class CarImeInputView(
    context: Context,
    // Reads of the current field state the IME needs to align its own counts to the app buffer.
    private val textBeforeCursor: () -> CharSequence,   // buffer.text up to buffer.cursor
    private val textAfterCursor: () -> CharSequence,    // buffer.text from buffer.cursor
    private val selectionStart: () -> Int,
    private val selectionEnd: () -> Int,
    // Edit operations, applied by the renderer to the authoritative BrowserTextBuffer.
    private val onCommit: (String) -> Unit,             // commitText: replace selection with text
    private val onDeleteBefore: (Int) -> Unit,          // deleteSurroundingText(before): delete N cp before cursor
    private val onAction: () -> Unit,                    // IME_ACTION_GO / Enter
) : View(context) {
    init { isFocusable = true; isFocusableInTouchMode = true }
    override fun onCheckIsTextEditor() = true
    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or
            EditorInfo.IME_FLAG_NO_FULLSCREEN or EditorInfo.IME_ACTION_GO
        // Seed the IME's model so its deleteSurroundingText counts line up with the app buffer.
        outAttrs.initialSelStart = selectionStart()
        outAttrs.initialSelEnd = selectionEnd()
        return object : BaseInputConnection(this, false) {
            override fun getTextBeforeCursor(n: Int, flags: Int): CharSequence =
                textBeforeCursor().let { it.subSequence(maxOf(0, it.length - n), it.length) }
            override fun getTextAfterCursor(n: Int, flags: Int): CharSequence =
                textAfterCursor().let { it.subSequence(0, minOf(n, it.length)) }
            override fun commitText(t: CharSequence, p: Int): Boolean { onCommit(t.toString()); return true }
            override fun setComposingText(t: CharSequence, p: Int) = commitText(t, p) // Thai: commit, don't compose
            override fun deleteSurroundingText(before: Int, after: Int): Boolean {
                onDeleteBefore(before.coerceAtMost(64)); return true   // see finding 2 for 'after'
            }
            override fun performEditorAction(a: Int): Boolean { onAction(); return true }
            override fun sendKeyEvent(e: KeyEvent): Boolean {
                if (e.action != KeyEvent.ACTION_DOWN) return true
                return when (e.keyCode) {
                    KeyEvent.KEYCODE_DEL -> { onDeleteBefore(1); true }
                    KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> { onAction(); true }
                    else -> {
                        val ch = e.unicodeChar
                        if (ch != 0) { onCommit(String(Character.toChars(ch))); true }
                        else super.sendKeyEvent(e)   // do not blanket-consume unhandled keys
                    }
                }
            }
        }
    }
}
```

`TYPE_TEXT_VARIATION_VISIBLE_PASSWORD | NO_SUGGESTIONS` + commit-not-compose is the same recipe the
working `KeyInputView` uses so multi-stage scripts (Thai) resolve into the field instead of stranding
in the IME candidate strip. `sendKeyEvent` mirrors `KeyInputView`'s routing instead of eating every
key (**[review finding 6]**). The view is added to `CarHardwareWebWindow.contentLayer` at 1×1,
invisible — Gboard floats over the projection on its own.

`CarHardwareWebWindow` gains three small members: `attachImeInput(view: View)` (adds the editor to the
content layer), `detachImeInput(view: View)`, and it already exposes `windowView` for insets
observation. These keep all window/`Presentation` access inside the class that owns it.

## Target-routing wiring (reuse existing plumbing)

The IME route reuses the exact decision and transport the canvas keyboard uses, so address-vs-field
behavior, Thai, backspace-to-empty, and Go stay identical. The one genuinely new thing is how a real
IME's edit operations mutate the app buffer — specified below and made pure/testable.

### IME ↔ `BrowserTextBuffer` reconciliation (the authoritative rule) — [review findings 1, 2, 5]

The decision is **non-backed `BaseInputConnection`, with the app's `BrowserTextBuffer` as the single
source of truth**, not a backing `Editable`. Reasons:

- The existing live-mirror and commit plumbing (`CarKeyboardMirror.liveWrite`, `submitText`,
  `commitKeyboard`) all read `buffer.text`. Keeping `BrowserTextBuffer` authoritative means that
  plumbing is reused byte-for-byte with zero new text model.
- `BrowserTextBuffer` already implements exactly the selection semantics ADDRESS needs
  (`selectAll = true`, `insert` replaces the selection), surrogate-pair-safe backspace, and the
  trimmed read `liveWrite` expects. A separate `Editable` would duplicate and risk drifting from it.

The risk the review flagged is that a real IME (Gboard) keeps its *own* model and emits counts
relative to it. We neutralize that by **seeding and continuously feeding the IME from the buffer**:
`onCreateInputConnection` sets `initialSelStart/initialSelEnd` from the buffer, and the connection
implements `getTextBeforeCursor`/`getTextAfterCursor` from the buffer, so Gboard's
`deleteSurroundingText` counts and cursor arithmetic are computed against the real field text, not an
assumed-empty one. Every IME edit is then applied to the buffer by one **pure** function, which is the
executable form of this rule:

```kotlin
// CarImeInput.kt — pure, JVM-tested.
sealed interface ImeEdit {
    data class Commit(val text: String) : ImeEdit      // commitText / setComposingText / printable key
    data class DeleteBefore(val count: Int) : ImeEdit  // deleteSurroundingText(before,*) / KEYCODE_DEL
}

/** Applies one IME edit to the authoritative buffer, in place, matching BrowserTextBuffer semantics. */
fun applyImeEdit(buffer: BrowserTextBuffer, edit: ImeEdit) {
    when (edit) {
        is ImeEdit.Commit -> buffer.insert(edit.text)        // replaces selection (ADDRESS select-all), else inserts at cursor
        is ImeEdit.DeleteBefore -> repeat(edit.count) { buffer.backspace() }
    }
}
```

Explicit contract:
- **Commit REPLACES the current selection, then the cursor collapses after the inserted text.** This
  is `BrowserTextBuffer.insert`'s existing behavior. For ADDRESS the buffer is seeded `selectAll =
  true`, so the **first** commit replaces the whole URL (AABrowser's "type to replace the address")
  and collapses; subsequent commits append at the cursor, exactly like the canvas path. Because the
  connection reports `getTextBeforeCursor`/`getTextAfterCursor` after each edit, Gboard never believes
  the field is empty, so a word-commit or autocorrect replacement lands as a normal
  selection-replace-then-insert rather than corrupting the buffer. **Commit is not a wholesale
  buffer-replace** — it is a selection-replace, which is the same operation the canvas keyboard
  performs per key, so ADDRESS select-all-then-type and FIELD append both match today's behavior.
- **`deleteSurroundingText(before, after)` maps to `DeleteBefore(before.coerceAtMost(64))` and `after`
  is dropped, which is correct for this `EditorInfo`.** Because `imeOptions` sets no extracted/fullscreen
  UI and the connection reports the cursor at the end of the field after each commit (collapsed
  selection, nothing after the cursor), a well-behaved IME only ever requests deletion *before* the
  cursor; `after` is 0 in practice. The `coerceAtMost(64)` bound (same as `KeyInputView`) caps a
  pathological delete. `backspace()` deletes the selection if one exists, else one code point, so
  backspace-to-empty yields `buffer.text == ""`. (**[review finding 2]**.)
- Gboard composition/candidate selection resolves to `commitText` because we commit-not-compose, so
  there is no dangling composing region to reconcile.
- **`InputMethodManager.updateSelection` is intentionally not called after each edit.** The connection
  is non-backed and commit-only, with no composing region to track, so there is no selection state the
  IME must be pushed; Gboard re-reads surrounding context on demand via the `getTextBeforeCursor`/
  `getTextAfterCursor` the connection already implements from the buffer, and the collapsed-cursor-at-end
  invariant means those reads are always correct. This omission is deliberate, not an oversight. If a
  specific head unit shows cursor drift (the whole path is device-only), the remedy is a single
  `updateSelection(view, buffer.cursor, buffer.cursor, -1, -1)` call after each `applyImeEdit`; it is
  noted here so that fix is pre-scoped. (**[review finding 4 of this round]**.)

### Session holder and open

```kotlin
private class ImeSession(val target: CarSurfaceKeyboard.Target, val mode: BrowserKeyboardMode, seed: String) {
    val buffer = BrowserTextBuffer(seed, selectAll = target == CarSurfaceKeyboard.Target.ADDRESS && seed.isNotEmpty())
}
```

Wiring:

- **Open.** `CarWebRenderer.openKeyboard()` (line 1810) already resolves the target: no focused page
  field → `openAddressKeyboard()` (ADDRESS, seed = `livePageUrl`); a focused field →
  `openFieldKeyboard(info)` (FIELD, seeded with the field's value/label). These two methods become the
  single branch point. Each builds the `ImeSession` seed first, then asks the capability state: if
  `AVAILABLE`, open the IME session (construct `CarImeInputView` seeded from the `ImeSession` buffer,
  attach it, `showSoftInput`); if `UNAVAILABLE`, `showKeyboard(CarSurfaceKeyboard(...))` exactly as
  today; if `UNKNOWN` (gate passes but the eager probe has not resolved), run the **lazy probe** — which
  constructs the real session editor seeded from the `ImeSession` buffer up front and, per the
  probe-editor-lifecycle rule above, **reuses** that editor on `AVAILABLE` (no detach/re-attach) or
  detaches it and opens the canvas keyboard on `UNAVAILABLE`.
- **Per keystroke (`onCommit`/`onDeleteBefore`).** Call `applyImeEdit(session.buffer, edit)`, then run
  the **identical** mirror path the canvas uses:
  `CarKeyboardMirror.liveWrite(pageMutated = true, session.target, session.buffer.text)?.let {
  submitText(it, autoSubmit = false) }`. For FIELD this mirrors `buffer.text.trim()` (including `""`
  for backspace-to-empty); ADDRESS returns null and does not mirror — identical to the canvas rule and
  pinned by `CarKeyboardMirrorTest`. `pageMutated` is always `true` for a commit/delete because both
  change the buffer text; a key that does not change text (none, given the routing above) would not
  reach this path. `submitText` escapes via `CarJsEscape.singleQuoted` and writes
  `document.activeElement` through the existing JS — unchanged.
- **Go (`onAction`).** Mirror `commitKeyboard`: ADDRESS → if `session.buffer.text` non-empty,
  `load(if isSentinel(t) t else BrowserInputResolver.resolveTyped(t, SearchEngineStore.engine(...)))`;
  FIELD → `submitText(session.buffer.text, autoSubmit = true)`. Then `closeImeKeyboard()`.
- **Close.** Three triggers all route to a single `closeImeKeyboard()`:
  `InputMethodManager.hideSoftInputFromWindow(view.windowToken, 0)`, detach `CarImeInputView`, clear
  `imeSession`, and `onKeyboardChanged?.invoke(false)` — identical contract to `closeKeyboard()`. The
  first two have explicit call paths: **car Back** (`CarBrowserScreen.kt:85` → `renderer.closeKeyboard()`)
  and the **keyboard-icon toggle** (`:239` → `closeKeyboard()`), both of which route to `closeImeKeyboard()`
  while `imeSession != null`. The third — **the user dismissing Gboard via its own down-chevron / IME
  gesture** — has no call into the app, so it needs an observed trigger: **while an IME session is open,
  `CarImeInputView` keeps an `OnApplyWindowInsetsListener` registered on `windowView` (the same listener
  used by the probe, kept alive for the life of the session rather than torn down when the probe
  resolves). When `rootWindowInsets.isVisible(WindowInsets.Type.ime())` transitions to `false` while
  `imeSession != null`, it calls `closeImeKeyboard()`.** This is the live-session analogue of the probe's
  insets read (the `DesktopBrowserActivity` pattern at `:321`), and it is what keeps `imeSession`,
  `keyboardOpen`, and the car-Back enabled-state from going stale when the user dismisses the IME
  directly. Without it, `imeSession` would stay non-null, `keyboardOpen` would stay true, car-Back would
  stay enabled, and `onSurfaceClick`'s guard would stay off — a half-open state that violates the
  "exactly one keyboard" invariant. (**[review finding 1]**.)

EN/ไทย: with the real IME, language switching is Gboard's own (the user's system keyboard already has
Thai), so the app's EN/ไทย toggle is not drawn on the IME route — the expected AABrowser behavior (the
real keyboard replaces the hand-drawn one entirely). On the canvas fallback the app's EN/ไทย toggle,
Go/submit, and NO_FOCUS navigation all remain exactly as today.

### Field focus and scroll-into-view

`openFieldKeyboard` currently calls `keepFocusedFieldAboveKeyboard()` so the page field is not hidden
behind the canvas tray; it computes the occluded height from the tray geometry. With the real IME the
occluding surface is Gboard, whose height we learn from `WindowInsets.Type.ime()`. The IME route
parameterizes `keepFocusedFieldAboveKeyboard` by an occluded-bottom height: pass the IME inset height
instead of the tray top. If insets are unavailable, skip the scroll (non-fatal).

## Fallback switching

The canvas keyboard is unchanged and remains the default whenever the IME is not confirmed available.
Switching is seamless because both routes share the same open/close contract and the same text
transport:

- The branch happens once per open, in `openAddressKeyboard`/`openFieldKeyboard`, before anything is
  shown. The user sees either Gboard or the canvas tray, never both. To avoid a visible delay on the
  first open, the probe runs **eagerly** the first time the hardware window is attached and the gate
  passes — using the **same asynchronous insets wait** as the lazy probe (attach editor,
  `showSoftInput`, resolve `AVAILABLE`/`UNAVAILABLE` only when `ime()` insets turn visible OR
  `PROBE_TIMEOUT_MS` elapses, then hide + detach). It never reads-and-tears-down synchronously. If the
  eager probe has not resolved by the first open, that open uses the lazy timeout path. (**[review
  finding 4]**.)

- **All `keyboardOpen` readers audited.** `keyboardOpen` changes from `keyboard != null` (line 405) to
  `keyboard != null || imeSession != null`. There are **four** actual `keyboardOpen` readers (the toggle
  plus three debug strings), plus `onSurfaceClick`'s separate `keyboard?.let` guard which is
  intentionally left reading `keyboard`, not switched to the OR. Each reader and its intended IME-only
  behavior (state: `keyboard == null, imeSession != null`): (**[review finding 3 of this round]**.)
  1. `CarBrowserScreen.kt:239` keyboard-icon toggle `if (renderer.keyboardOpen) closeKeyboard() else
     openKeyboard()`. **Must close the IME in the IME-only state.** `closeKeyboard()` is updated to
     route to `closeImeKeyboard()` when `imeSession != null` (and keep its canvas behavior otherwise),
     so the toggle closes whichever keyboard is up. No change at the call site.
  2. `CarBrowserScreen.kt:511`, `:523`, `:535` — three debug/trace strings `keyboardOpen=...`.
     Read-only; correct once `keyboardOpen` is the OR. No behavior change.
  3. `onSurfaceClick` (line 1456) guards on `keyboard?.let { ... }`, **not** `keyboardOpen`. This is
     intentionally left unchanged: in the IME-only state `keyboard == null`, so the guard does not
     fire and surface taps fall through to normal page handling — which is correct, because Gboard owns
     its own touches on the projection and the app must not try to route taps into a canvas tray that
     isn't there. `CarKeyboardGestures`' "swallow page input while the tray is open" likewise keys off
     the canvas tray and correctly does nothing on the IME route.
  No other site gates on `keyboardOpen` (verified by workspace-wide grep: the four `keyboardOpen`
  readers above — toggle + three debug strings — are all of them; `onSurfaceClick` reads `keyboard`, not
  `keyboardOpen`, and is deliberately left that way).

- Everything downstream of "which keyboard" is identical: `CarKeyboardMirror`, `CarJsEscape`,
  `submitText`, `BrowserInputResolver`, `commitKeyboard` semantics, and `onKeyboardChanged`.

- Mode rollback: if `activeMode` is or becomes LEGACY_CANVAS, the gate fails, `imeProbe` resets to
  `UNKNOWN`, and the canvas keyboard is always used — no IME code runs.

No preserved behavior from the prior keyboard reports is lost: the canvas path's double-tap filtering,
pressed-key latency work (`car-keyboard-latency2`), per-keystroke live mirror (`car-keyboard-focus`),
Target.ADDRESS vs FIELD, Go/submit, and NO_FOCUS navigation all remain on the canvas route and are
re-implemented identically (same helpers) on the IME route.

## Files to add / change

Add:

- `app/src/main/java/dev/autobridge/browser/CarImeCapability.kt` — the pure gate (`gate(sdkInt,
  hardwareMode)`) and the `ImeProbeState` enum. JVM-testable.
- `app/src/main/java/dev/autobridge/browser/CarImeInput.kt` — the pure routing + reconciliation
  helpers: `ImeEdit`, `applyImeEdit(buffer, edit)`, a `CarImeTarget` mapper
  `(target, bufferText, keyKind) -> {MirrorLive | CommitAddress | CommitFieldSubmit | Ignore}`, and two
  tiny pure predicates that pin the lifecycle rules so they are JVM-testable without a window:
  `isKeyboardOpen(hasCanvas: Boolean, hasImeSession: Boolean) = hasCanvas || hasImeSession` (the OR
  definition of `keyboardOpen`) and `shouldCloseImeOnInsets(imeSessionActive: Boolean, imeVisible:
  Boolean) = imeSessionActive && !imeVisible` (the dismiss-detection rule from finding 1). JVM-testable.
  (Kept separate from `CarImeInputView` so no Android types leak into the tested logic.)
- `app/src/main/java/dev/autobridge/browser/CarImeInputView.kt` — the focused editable `View` +
  non-backed `BaseInputConnection` (seeded from the buffer via `getTextBeforeCursor`/
  `getTextAfterCursor`, commit-not-compose, `IME_ACTION_GO`, routed `sendKeyEvent`). Also hosts the
  session-lifetime `OnApplyWindowInsetsListener` that reports `ime()` visibility back to the renderer so
  a direct Gboard dismissal closes the session (finding 1).
- `app/src/test/java/dev/autobridge/browser/CarImeCapabilityTest.kt`
- `app/src/test/java/dev/autobridge/browser/CarImeTargetRoutingTest.kt`
- `app/src/test/java/dev/autobridge/browser/CarImeBufferTest.kt`

Change:

- `app/src/main/java/dev/autobridge/browser/CarWebRenderer.kt` — add `imeProbe` state, `imeSession`,
  and `imeInputView`; branch in `openAddressKeyboard`/`openFieldKeyboard`; add `openImeKeyboard`,
  `closeImeKeyboard`, `onImeCommit`/`onImeDeleteBefore`/`onImeAction`; run the async eager probe in
  `attachHardwareWindow`; change `keyboardOpen` to the OR and route `closeKeyboard()` to
  `closeImeKeyboard()` when `imeSession != null`; parameterize `keepFocusedFieldAboveKeyboard` by an
  occluded-bottom height; keep a session-lifetime `ime()` insets listener that calls
  `closeImeKeyboard()` when the user dismisses Gboard (finding 1). Reset `imeProbe`/clear editor on
  LEGACY_CANVAS fallback and on window release/recreate.
- `app/src/main/java/dev/autobridge/browser/CarHardwareWebWindow.kt` — add `attachImeInput(view)` /
  `detachImeInput(view)` (the `windowView` accessor for insets already exists).

Not changed: `CarSurfaceKeyboard`, `CarKeyboardPanel`, `CarKeyboardLayout`, `CarKeyboardMirror`,
`CarJsEscape`, `BrowserInputResolver`, `CarKeyboardGestures`, `CarPageWebView`,
`ProjectionBrowserActivity`, `DesktopBrowserActivity`, and the `CarBrowserScreen` call sites (its
toggle and debug strings keep their source unchanged; only `keyboardOpen`'s definition and
`closeKeyboard`'s internals change, in `CarWebRenderer`).

## Error handling (per failing operation)

- `DisplayManager.createVirtualDisplay` / `Presentation.show()` — already handled by
  `CarHardwareWebWindow`/`attachHardwareWindow` (line 3165): on `RuntimeException` the session drops to
  LEGACY_CANVAS and logs `AutoBridgeVideoLog.w(...)` with `sdkInt`. Consequence: the gate then sees
  `hardwareMode == false` and the canvas keyboard is used. Recoverable (feature-wise); fatal to the
  IME route for the session. Logged at warn by existing code.
- `InputMethodManager` missing (`getSystemService` returns null) — probe `UNAVAILABLE`; canvas
  keyboard. Recoverable. Logged once at info.
- `showSoftInput` throws or returns without the IME appearing — caught; probe `UNAVAILABLE`; canvas
  keyboard. Recoverable. Logged once at info with `sdkInt`.
- `rootWindowInsets == null` when reading IME visibility — treated as not-visible; if it stays null
  until `PROBE_TIMEOUT_MS`, probe `UNAVAILABLE`. Recoverable. Not logged per-read; logged once on
  timeout.
- Probe timeout (`PROBE_TIMEOUT_MS = 700`) with no IME insets — probe `UNAVAILABLE`; detach editor;
  open canvas keyboard. Recoverable. Logged once at info.
- Hardware window released/recreated mid-probe (surface churn, size change) — the in-flight probe is
  abandoned, `imeProbe` reset to `UNKNOWN`, editor reference dropped; next open re-probes. No user
  error. Logged at info.
- User dismisses Gboard via its own UI while an IME session is open (`ime()` insets transition to
  not-visible with `imeSession != null`) — not an error, but a state-cleanup event: the session's
  insets listener calls `closeImeKeyboard()`, which clears `imeSession`, detaches `CarImeInputView`, and
  fires `onKeyboardChanged(false)` so car-Back disables and `keyboardOpen` returns to `false`.
  Recoverable; the next open re-opens the IME (capability is already cached `AVAILABLE`). Logged once at
  info. (**[review finding 1]**.)
- `evaluateJavascript` in `submitText` returning `NO_FOCUS` on a live mirror (`autoSubmit=false`) —
  already handled: ignored. On Go (`autoSubmit=true`) it falls back to `load()`. Unchanged.
- `BaseInputConnection.commitText` with a value that would break the JS string — impossible to inject
  because `CarJsEscape.singleQuoted` escapes it; pinned by `CarWebRendererJsEscapeTest`, reused
  unchanged.

Every failure path has the same terminal behavior: the canvas keyboard opens. There is no path where
a failure leaves the user with no keyboard.

## Input validation (external inputs)

- **IME-committed text** (`commitText`/`setComposingText`/printable key): type `CharSequence`,
  arbitrary length and content (Thai, emoji, newlines, quotes). Not rejected; `insert`ed into the
  buffer verbatim and escaped by `CarJsEscape.singleQuoted` before it reaches the page. No buffer
  length cap (the address bar / search field impose their own).
- **`deleteSurroundingText(before, after)`**: integers; `before` is clamped `coerceAtMost(64)` and
  applied as N `backspace()`s; `after` expected 0 for this `EditorInfo` and dropped (justified above).
- **`Build.VERSION.SDK_INT`**: integer, read-only; compared `>= 35`. No failure mode.
- **`activeMode`**: enum; only `HARDWARE` passes the gate. No failure mode.
- **`WindowInsets.Type.ime()` visibility**: boolean (or null insets); null → false. Required for
  `AVAILABLE`; absence within the timeout → `UNAVAILABLE`.
- **Focused-field info JSON** (`FOCUSED_FIELD_INFO_SCRIPT`): already parsed defensively in
  `readFocusedField` (null → address target). Unchanged; the IME route consumes the same parsed result.

## Invariants and ownership

- **"The user always has a keyboard."** Owned by `CarWebRenderer.openAddressKeyboard`/
  `openFieldKeyboard`: every branch ends in either an IME session or a `CarSurfaceKeyboard`, and every
  IME failure path falls through to the latter. Top invariant and the reason inconclusive = canvas.
- **"Exactly one keyboard is shown at a time, and `keyboardOpen` reflects reality."** Owned by
  `CarWebRenderer`: opening either keyboard asserts the other is closed; `keyboardOpen` is the OR of
  both; the branch is taken before anything is shown. The one way the IME can close without an app call
  is the user dismissing Gboard directly — so the session's `ime()` insets listener (finding 1) is the
  co-owner of this invariant: when `ime()` visibility drops to `false` while `imeSession != null` it
  calls `closeImeKeyboard()`, keeping `imeSession`/`keyboardOpen`/car-Back from going stale.
- **"The app `BrowserTextBuffer` is the single source of truth for the field text."** Owned by
  `CarWebRenderer`/`applyImeEdit`: the IME connection is non-backed and merely reports the buffer to
  Gboard (`getTextBefore/AfterCursor`, `initialSel*`) and forwards edits; the buffer, not the IME,
  holds the text. This is what keeps the two editors from desyncing.
- **"FIELD mirrors live; ADDRESS commits only on Go."** Owned by `CarKeyboardMirror.liveWrite`, reused
  verbatim by both routes, so the rule cannot drift between them.
- **"JS injection is always escaped."** Owned by `CarJsEscape.singleQuoted` in `submitText`, the one
  place raw text becomes a JS literal. Reused unchanged.
- **Window/Presentation access is encapsulated.** Owned by `CarHardwareWebWindow`: editor
  attach/detach and the `windowView` insets reads go through it; no other class touches the
  `Presentation`.

## Testability

The architecture is split so the decision and reconciliation logic is pure and JVM-testable, while the
parts that genuinely need a device are isolated and small.

Unit-testable on the JVM (no Android framework), in the style of `CarKeyboardMirrorTest` /
`CarWebRendererJsEscapeTest` / `CarKeyboardLayoutTest`:

- **`CarImeCapabilityTest`** — the gate: `gate(34, true) == false` (Android 14 blocked),
  `gate(35, true) == true` (Android 15 + hardware), `gate(35, false) == false` (legacy canvas, no
  window), `gate(36, true) == true`, boundary at exactly 35. Pins the "SDK gate AND hardware mode" rule
  and the <15 / 15+ split the user asked for.

- **`CarImeTargetRoutingTest`** — the pure `CarImeTarget` mapper `(target, bufferText, keyKind) ->
  {MirrorLive(text) | CommitAddress(text) | CommitFieldSubmit(text) | Ignore}`, tested without a WebView
  or IME. Cases: FIELD text mutation → `MirrorLive(trimmed)`; FIELD backspace-to-empty → `MirrorLive("")`;
  ADDRESS text mutation → `Ignore` (no live mirror); ADDRESS Go non-empty → `CommitAddress`; ADDRESS Go
  empty → `Ignore`; FIELD Go → `CommitFieldSubmit`. Mirrors `CarKeyboardMirrorTest` and guarantees
  IME-route parity with the canvas route.

- **`CarImeBufferTest`** — the new reconciliation rule `applyImeEdit(buffer, edit)` against
  `BrowserTextBuffer`, covering the IME↔buffer desync risk directly (**[review finding 5]**): (**[review
  findings 1, 2]**)
  - ADDRESS seeded with a URL + `selectAll=true`, first `Commit("x")` → buffer text `"x"` (selection
    replaced), cursor collapsed.
  - Append after replace: second `Commit("y")` → `"xy"`.
  - FIELD seeded `"ab"`, `DeleteBefore(1)` → `"a"`, `DeleteBefore(1)` → `""` (backspace-to-empty yields
    empty string).
  - Thai multi-char commit `Commit("ส" ) ` then `Commit("วัสดี")` appends verbatim (no composing loss).
  - Word/autocorrect replacement: with a collapsed cursor at end, `Commit("worlds")` after `"world"`
    verifies commit inserts at the cursor (the IME-supplied replacement text is taken as-is; the buffer
    does not second-guess it).
  - `DeleteBefore(100)` on a 3-char buffer deletes to empty (clamp behavior; `coerceAtMost(64)` is in
    the view, so the helper test passes the already-clamped count and asserts it never underflows past
    empty).

- **`CarImeTargetRoutingTest` also pins the two lifecycle predicates** (finding 1): `isKeyboardOpen`
  is true for canvas-only, IME-only, and both, and false only when neither is present — proving
  `keyboardOpen` is the OR; and `shouldCloseImeOnInsets(true, false) == true` (session open, IME insets
  gone → close), `shouldCloseImeOnInsets(true, true) == false` (still visible → stay),
  `shouldCloseImeOnInsets(false, false) == false` (no session → nothing to close). This is the pure form
  of "a direct Gboard dismissal clears the session," the test-plan growth item the review named.

- Existing `CarKeyboardMirrorTest`, `CarWebRendererJsEscapeTest`, `CarKeyboardLayoutTest`,
  `CarKeyboardInputRoutingTest`, `CarKeyboardGesturesTest`, `BrowserKeyboardTest` continue to pass
  unchanged (the canvas route is untouched) and serve as the regression guard for the fallback.

Integration / device-only (documented, not automated here): whether `showSoftInput` actually raises
Gboard over the projection and `WindowInsets.Type.ime()` turns visible — the whole point of the runtime
probe; exercisable only on an Android 15 head unit (DHU will generally probe `UNAVAILABLE`, correctly
falling back to canvas). Per-keystroke Thai commit, backspace-to-empty clearing, and `IME_ACTION_GO`
navigation likewise need a head unit; they reuse the same `submitText`/`BaseInputConnection` recipe
already proven in `DesktopBrowserActivity`.

Compile/verify command (the new files are in `src/main`/`src/test`; projection code compiles under
personal/lab): `./gradlew :app:testPersonalDebugUnitTest` runs the new and existing JVM tests.
`assembleSafeDebug`'s only failure is the unrelated `:whisper` NDK module, so rely on
`test*DebugUnitTest` for compile verification.

## Summary of required-decision answers

1. **Host-capability detection** — `CarImeCapability.gate(SDK_INT >= 35 AND activeMode == HARDWARE)`
   plus a stateful `CarImeProbe` that attaches a focused editor, calls `showSoftInput`, and confirms
   `WindowInsets.Type.ime()` becomes visible within `PROBE_TIMEOUT_MS` via an **asynchronous** insets
   listener (never a synchronous read). Tri-state, cached per session. Inconclusive (timeout, null
   insets, any exception, missing IMM, no window) → `UNAVAILABLE` → canvas keyboard. Never leaves the
   user without a keyboard.
2. **Presentation route** — reuse the existing HARDWARE-mode `CarHardwareWebWindow` (already a real
   `Presentation` with a real attached `View` tree on the car `VirtualDisplay`) and add an invisible
   focused `CarImeInputView` with a real non-backed `InputConnection` seeded from the app buffer.
   Lowest risk: no new window, no Activity rewrite, no second overlay; it is the only mode where the
   IME can work anyway.
3. **Target routing** — reuse `CarKeyboardMirror.liveWrite` (FIELD live mirror, ADDRESS never),
   `CarJsEscape.singleQuoted`, `submitText(autoSubmit)`, `BrowserInputResolver.resolveTyped`, and the
   `commitKeyboard` ADDRESS/FIELD semantics. The `BaseInputConnection` is non-backed with the app's
   `BrowserTextBuffer` authoritative; a pure `applyImeEdit` applies each IME edit (`Commit` =
   selection-replace-then-insert, `DeleteBefore` = N backspaces), and the connection reports the buffer
   to the IME via `initialSel*` + `getTextBefore/AfterCursor` so counts never desync. EN/ไทย handled by
   Gboard on the IME route; preserved on the canvas route. Backspace-to-empty and `IME_ACTION_GO` wired
   through the same helpers.
4. **Fallback switching** — one branch per open, before anything is shown; an **async** eager probe on
   first hardware-window attach so the first real open already knows the answer; the canvas keyboard is
   unchanged and automatic whenever the IME is not confirmed available. `keyboardOpen` becomes the OR of
   both and its four readers are audited (toggle + three debug strings); `closeKeyboard()` routes to
   `closeImeKeyboard()` in the IME-only state; `onSurfaceClick`'s separate `keyboard?.let` guard is
   deliberately left on `keyboard` so taps fall through to the page while Gboard owns its own touches. A
   direct Gboard dismissal is detected by the session's `ime()` insets listener and routed to
   `closeImeKeyboard()` so no state goes stale.

## Responses to design-review findings

### Round 2 (current `design-review.json`, verdict CHANGES_REQUESTED: 0 HIGH, 2 MEDIUM, 2 NIT)

- **Finding 1 (MEDIUM) — no mechanism to detect the user dismissing Gboard; `imeSession`/car-Back go
  stale.** ADDRESSED. Specified a session-lifetime `OnApplyWindowInsetsListener` (on `windowView`, via
  `CarImeInputView`) that calls `closeImeKeyboard()` when `ime()` visibility transitions to `false`
  while `imeSession != null` — the `DesktopBrowserActivity:321` insets pattern applied to a live
  session rather than only the probe. Documented in "Target-routing wiring → Close", the error-handling
  section (as a non-error cleanup event), the "exactly one keyboard / `keyboardOpen` reflects reality"
  invariant (listener named co-owner), the files-to-change entries for `CarWebRenderer` and
  `CarImeInputView`, and a new pure predicate `shouldCloseImeOnInsets` with tests in
  `CarImeTargetRoutingTest`.
- **Finding 2 (MEDIUM) — probe→session editor handoff unspecified.** ADDRESSED. Added an explicit
  "probe-editor lifecycle" rule: the **eager** probe uses a disposable empty-seeded editor and
  hide+detaches it on resolve; the **lazy** probe constructs the real session editor seeded from the
  `ImeSession` buffer up front and **reuses** it on `AVAILABLE` (no detach/re-attach, so the just-raised
  Gboard and the `initialSel*` alignment are never dropped), detaching only on `UNAVAILABLE`. The "Open"
  wiring bullet was updated to match.
- **Finding 3 (NIT) — "five `keyboardOpen` readers" conflates a `keyboard` reader.** ADDRESSED. Reworded
  to "four `keyboardOpen` readers (toggle + three debug strings), plus `onSurfaceClick`'s separate
  `keyboard?.let` guard intentionally left on `keyboard`." Fixed in the fallback-switching audit, the
  grep-summary sentence, and Decision 4 of the summary. No code change.
- **Finding 4 (NIT) — `updateSelection` omitted silently.** ADDRESSED. Added a bullet to the
  reconciliation contract stating `updateSelection` is intentionally not called (non-backed,
  commit-only, no composing region; Gboard re-reads via `getTextBefore/AfterCursor`), with the
  pre-scoped remedy — `updateSelection(view, cursor, cursor, -1, -1)` after each `applyImeEdit` — if a
  device shows cursor drift.

### Round 1 (prior review, all findings carried forward and still addressed)

- **Finding 1 (HIGH) — IME ↔ buffer reconciliation unspecified.** ADDRESSED. Chose the non-backed
  connection with `BrowserTextBuffer` authoritative (review option b), made concrete: `initialSel*` +
  `getTextBefore/AfterCursor` seed and feed the IME from the buffer so its counts align; a pure
  `applyImeEdit` defines `Commit` as selection-replace-then-insert (not wholesale replace, not blind
  append) and `DeleteBefore` as N `backspace()`s. ADDRESS select-all-then-type is preserved because the
  seed uses `selectAll=true` and the first commit replaces the selection. See "IME ↔ BrowserTextBuffer
  reconciliation".
- **Finding 2 (MEDIUM) — `deleteSurroundingText` semantics.** ADDRESSED. Non-backed path specified:
  `onDeleteBefore(before.coerceAtMost(64))` deletes `before` code points before the buffer cursor;
  `after` is 0 for this `EditorInfo` (no extracted/fullscreen UI, cursor reported at field end) and is
  documented as dropped with that justification. Backspace-to-empty yields `""`.
- **Finding 3 (MEDIUM) — `keyboardOpen` readers.** ADDRESSED. The four `keyboardOpen` readers (toggle
  at `CarBrowserScreen:239` + three debug strings) are enumerated, plus `onSurfaceClick`'s separate
  `keyboard?.let` guard, with the IME-only behavior at each: the toggle closes via
  `closeKeyboard()`→`closeImeKeyboard()`; debug strings are read-only; `onSurfaceClick`'s guard is
  intentionally left on `keyboard` so taps reach the page while Gboard owns its touches. (The exact
  "four readers + one `keyboard` guard" wording was tightened in Round 2 finding 3.)
- **Finding 4 (MEDIUM) — eager probe timing.** ADDRESSED. The eager probe now uses the same async
  insets-listener wait as the lazy probe (resolve on `ime()` visible OR `PROBE_TIMEOUT_MS`), never a
  synchronous read-and-teardown. Stated in both "runtime probe" and "fallback switching".
- **Finding 5 (MEDIUM) — reconciliation untested.** ADDRESSED. Added `CarImeBufferTest` over the pure
  `applyImeEdit`, with the exact cases the review named (ADDRESS seeded first-commit replace, append,
  delete-to-empty, Thai multi-char, word-commit replacement) plus a clamp case.
- **Finding 6 (NIT) — `sendKeyEvent` blanket-consume.** ADDRESSED. `sendKeyEvent` now routes
  `KEYCODE_DEL`→delete, `ENTER`/`NUMPAD_ENTER`→action, printable `unicodeChar`→commit, else
  `super.sendKeyEvent(e)`; only `ACTION_DOWN` is handled, matching `KeyInputView`.
- **Finding 7 (NIT) — SDK gate ambiguity.** ADDRESSED. Gate is `sdkInt >= 35` with a
  `// Build.VERSION_CODES.VANILLA_ICE_CREAM` comment and one answer; the "if preferred" alternative is
  removed.

## Addendum — final design review (`design-review.json`, CHANGES_REQUESTED: 1 HIGH, 3 MEDIUM, 2 NIT)

This addendum is authoritative and supersedes any earlier text it contradicts (notably the eager-probe
description). Findings 1/2/3 are implementation concerns resolved in `plan.md`; findings 4/5/6 are the
design-document changes recorded here.

### Finding 4 (MEDIUM) — DROP the eager probe (no Gboard flash at surface-attach). DESIGN CHANGE.

`showSoftInput` raises the real IME window (Gboard), not just the invisible 1×1 editor, so an eager
probe at hardware-window attach would flash Gboard over the projection for up to `PROBE_TIMEOUT_MS`
(700 ms) before the user has asked to type. **Decision: there is no eager probe.** Capability is
resolved only by the **lazy probe**, which runs the first time the user opens the keyboard with the
gate passing and `imeProbe == UNKNOWN`. The cost is a one-time ≤`PROBE_TIMEOUT_MS` latency on the very
first keyboard open of a session; the benefit is no unprompted keyboard flash at browser open.

This supersedes, throughout this document, every statement that an eager probe runs at
`attachHardwareWindow` / first hardware-window attach, and every description of a probe as "invisible"
(the 1×1 *editor* is invisible; the IME it summons is not). Concretely:

- The "runtime probe" flow keeps steps 1, 3, 4 and the lazy branch of step 2; the eager-probe entry
  point is removed. The "probe-editor lifecycle" rule reduces to the **lazy** case only: construct the
  real `CarImeInputView` seeded from the `ImeSession` buffer, attach, `requestFocus()`, `showSoftInput`,
  wait on the insets listener; on `AVAILABLE` keep and reuse the editor for the session, on
  `UNAVAILABLE` detach/discard and open the canvas keyboard with the same seed. No disposable
  empty-seeded editor is ever created.
- "Fallback switching": delete the eager-probe paragraph. The first open uses the lazy timeout path;
  there is no prewarm.
- "Files to change → `CarWebRenderer.kt`": drop "run the async eager probe in `attachHardwareWindow`".
  `attachHardwareWindow` is unchanged by this feature except that `imeProbe` resets to `UNKNOWN` on
  LEGACY_CANVAS fallback / window release/recreate (unchanged from before).
- "Summary of required-decision answers" decision 4: strike "an **async** eager probe on first
  hardware-window attach so the first real open already knows the answer"; the canvas keyboard remains
  the default whenever the IME is not confirmed available, and the lazy probe decides on first open.

### Finding 5 (NIT) — probe's null-insets handling vs. `KeyInputView`'s `?: shown` fallback. DOC NOTE.

`DesktopBrowserActivity.imeVisible()` is `rootWindowInsets?.isVisible(ime()) ?: shown` — it falls back
to a remembered `shown` flag when insets are null. The probe deliberately does **not** adopt that
fallback: its entire purpose is to observe whether the host actually raised a real IME, so it maps
null `rootWindowInsets` → not-visible, and a probe that never sees visible insets within
`PROBE_TIMEOUT_MS` resolves `UNAVAILABLE`. A remembered-flag fallback would defeat the probe by
reporting "visible" without an observation. Inconclusive = safe canvas is the intended behavior, not a
divergence bug.

### Finding 6 (NIT) — `deleteSurroundingText(after)` dropped in the selected (pre-first-commit) ADDRESS state. DOC NOTE.

The justification for dropping `after` must also cover the ADDRESS seed state, where the buffer is
`selectAll = true` (selectionStart=0, selectionEnd=len) — a non-collapsed selection with text after the
selection start. In that state the connection reports the selection to the IME via
`outAttrs.initialSelStart`/`initialSelEnd` (and `getTextBefore/AfterCursor`), so a well-behaved IME
that wants to replace/delete the selection does so via `commitText("")` or a delete-before, both of
which `applyImeEdit` handles (`insert("")` collapses the selection to empty; `backspace()` deletes the
selection when one exists). An IME does not issue `deleteSurroundingText(after>0)` against a live
selection it has been told about. So dropping `after` remains correct in both the append-at-end state
and the pre-first-commit selected state.

### Findings 1/2/3 — implemented per `plan.md` (pointers)

- **Finding 1 (HIGH)** — single-keyboard invariant across all `keyboard`-field gates: a new
  `anyKeyboardOpen` predicate used in `offerFieldInput` and re-audited at the fling/zoom/FAB sites.
  Implemented in `CarWebRenderer.kt` per plan.md step 5.
- **Finding 2 (MEDIUM)** — `keepFocusedFieldAboveKeyboard(occludedTop: Float)` reparameterization with
  the IME inset→surface-Y conversion at the call site. Implemented in `CarWebRenderer.kt` per plan.md
  step 7.
- **Finding 3 (MEDIUM)** — single `CarImeRoute` result type + `routeImeEdit(target, bufferText, kind)`
  entry point that the renderer dispatches on, so `CarImeTargetRoutingTest` guards the executed path.
  Implemented in `CarImeInput.kt` (step 3) and wired in `CarWebRenderer.kt` (step 6).
