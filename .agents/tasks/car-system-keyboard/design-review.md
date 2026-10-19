# Design review — car system-keyboard (Gboard over the Android Auto car browser)

Reviewed: `/Users/anuwat.t/Documents/ChatGPT/AutoBridge/.agents/tasks/car-system-keyboard/design.md`
Code verified against: `/Users/anuwat.t/Documents/ChatGPT/AutoBridge/.worktrees/car-system-keyboard`
Reviewed fresh, without the context that produced the design. Builds/tests were not run (per instructions).

## Summary

The design is strong on three of the four required decisions. Capability detection (SDK gate +
async probe + inconclusive=canvas), the presentation-route choice (reuse `CarHardwareWebWindow`,
add an invisible focused editor), and the IME↔buffer reconciliation (non-backed `BaseInputConnection`
with `BrowserTextBuffer` authoritative, pure `applyImeEdit`) are specified concretely and match the
actual code. The `KeyInputView` pattern it copies, the `CarKeyboardMirror`/`BrowserTextBuffer`/
`submitText`/`commitKeyboard` plumbing it reuses, the window shape, and the `compileSdk 36 / minSdk 29`
single-build span were all checked against source and are accurately described.

The blocking problems are in the fallback/invariant wiring, not the core mechanism:

- The "I audited every keyboard-state gate" claim is **wrong**. The audit only covered the four
  `keyboardOpen` *string* readers and `onSurfaceClick`. Several other sites read the `keyboard` field
  directly to mean "is a keyboard up," and at least one — `offerFieldInput` (lines 1825/1827) — will
  **open a canvas field keyboard on top of a live IME session**, directly breaking the design's own
  top invariant ("exactly one keyboard is shown at a time"). (Finding 1, HIGH.)
- `keepFocusedFieldAboveKeyboard` is described as "parameterize by an occluded-bottom height; pass
  the IME inset height instead of the tray top," but tray-top is a Y coordinate in surface space and
  the IME inset is a height; the mapping to the `visible` fraction is left ambiguous, and the method
  currently hard-returns when `keyboard == null`. (Finding 2, MEDIUM.)
- The tested pure abstraction `CarImeTarget` is never shown to be the code path the renderer actually
  executes; the wiring section instead reimplements `liveWrite`/`commitKeyboard` inline, so the test
  can pass while production drifts. (Finding 3, MEDIUM.)
- The eager probe calls `showSoftInput` at surface-attach time, which raises Gboard on screen for up
  to `PROBE_TIMEOUT_MS` (700 ms) before hiding it — an unprompted keyboard flash at startup that the
  design does not address. (Finding 4, MEDIUM.)

Verdict: **CHANGES_REQUESTED** (1 HIGH, 3 MEDIUM, 2 NIT).

---

## Findings

### 1. HIGH — `offerFieldInput` can open a canvas keyboard on top of a live IME session; the "audited every gate" claim is incomplete

Where: design "Fallback switching → All `keyboardOpen` readers audited" and the "Invariants" section;
code `CarWebRenderer.kt:1824-1827`.

The design asserts: *"No other site gates on `keyboardOpen` (verified by workspace-wide grep: the four
`keyboardOpen` readers above … are all of them; `onSurfaceClick` reads `keyboard`, not `keyboardOpen`)."*
That audit scoped itself to the string `keyboardOpen`. But the single-keyboard invariant is actually
enforced throughout the renderer by reading the **`keyboard` field** directly. Grep for `keyboard`
(not `keyboardOpen`) shows sites the audit missed:

```
1825:  if (overlay != Overlay.NONE || keyboard != null) return          // offerFieldInput guard
1827:  if (info != null && overlay == Overlay.NONE && keyboard == null) openFieldKeyboard(info)
2142:  if (keyboard != null) { stopFling(); return false }              // no fling under keyboard
2169:  val board = keyboard ; ... (scaleBy: no page zoom under keyboard)
2259:  overlay == Overlay.NONE && keyboard == null && fabAlpha(...)      // FAB visibility
```

`offerFieldInput` (called on a short delay after a page field gains focus — see `openKeyboard`'s
sibling path at 1801-1803 posting `offerFieldInput`) guards on `keyboard != null`. In the IME-only
state `keyboard == null`, so this guard does **not** fire, and `offerFieldInput` will call
`openFieldKeyboard(info)` → `showKeyboard(...)` **while an IME session is already open**. Result: a
canvas tray is drawn over/under a live Gboard, both "keyboards" are active, `imeSession != null` and
`keyboard != null` simultaneously — the exact state the design says can never happen ("opening either
keyboard asserts the other is closed").

Concrete fix: the IME route must participate in every "is a keyboard up" gate, not just the four
`keyboardOpen` string readers. Introduce a single private predicate and use it everywhere the raw
`keyboard != null` currently means "a keyboard is up":

```kotlin
private val anyKeyboardOpen: Boolean get() = keyboard != null || imeSession != null
```

Then in `offerFieldInput`:

```kotlin
if (overlay != Overlay.NONE || anyKeyboardOpen) return
readFocusedField(page) { info ->
    if (info != null && overlay == Overlay.NONE && !anyKeyboardOpen) openFieldKeyboard(info)
}
```

and re-audit 2142 (fling), 2169 (scale), 2259 (FAB) explicitly: decide for each whether the IME-only
state should behave like the canvas-open state (block fling/zoom, hide FAB) or deliberately differ,
and state the decision. The design's audit section must enumerate these `keyboard`-field sites, not
only the `keyboardOpen` string readers, and justify each.

### 2. MEDIUM — `keepFocusedFieldAboveKeyboard` reparameterization is ambiguous and the null-guard blocks the IME route

Where: design "Field focus and scroll-into-view"; code `CarWebRenderer.kt:2028-2046`.

The current method is:

```kotlin
private fun keepFocusedFieldAboveKeyboard() {
    val board = keyboard ?: return                 // returns immediately when keyboard == null
    ...
    val trayTop = board.geometry(...).tray.top     // a Y coordinate in surface pixels
    val visible = ((trayTop - pane.top) / pane.height).coerceIn(0.2f, 1f)
    ... window.scrollBy ...
}
```

Two problems:

1. On the IME route `keyboard == null`, so `val board = keyboard ?: return` exits before doing
   anything. The design says to "parameterize … by an occluded-bottom height," but a bare
   reparameterization still has to remove this early-return for the IME path.
2. "Pass the IME inset height instead of the tray top" conflates two different quantities: `trayTop`
   is an absolute Y coordinate (surface pixels from the top), whereas an IME inset is a **height**
   measured from the bottom. Substituting one for the other without a conversion rule is a bug
   waiting to happen.

Concrete fix: give the method an explicit occluded-top parameter in the same coordinate space the
canvas path already uses, and convert the IME inset at the call site:

```kotlin
// occludedTop is a surface-pixel Y: the top edge of whatever covers the bottom of the page.
private fun keepFocusedFieldAboveKeyboard(occludedTop: Float) {
    val page = focusedView ?: return
    val pane = (if (sideActive) sideViewport else mainViewport) ?: return
    val visible = ((occludedTop - pane.top) / pane.height.toFloat()).coerceIn(0.2f, 1f)
    // ... unchanged JS ...
}

// canvas call site:
keepFocusedFieldAboveKeyboard(board.geometry(keyboardArea(), sizes.dp(1f)).tray.top)

// IME call site (inset height -> surface-space top), or skip when insets unavailable:
val imeH = windowView?.rootWindowInsets?.getInsets(WindowInsets.Type.ime())?.bottom
if (imeH != null && imeH > 0) keepFocusedFieldAboveKeyboard(surfaceHeight - imeH.toFloat())
```

State the "insets unavailable → skip scroll (non-fatal)" rule as the explicit `else` of that `if`.

### 3. MEDIUM — the tested `CarImeTarget` mapper is not shown to be the executed path; risk of test/production drift

Where: design "Files to add" (`CarImeInput.kt` includes a `CarImeTarget` mapper
`(target, bufferText, keyKind) -> {MirrorLive | CommitAddress | CommitFieldSubmit | Ignore}`) and
"Testability" (`CarImeTargetRoutingTest` tests that mapper) versus "Target-routing wiring" (per
keystroke / Go), which calls `CarKeyboardMirror.liveWrite(...)` and reimplements `commitKeyboard`'s
ADDRESS/FIELD body inline — it does not call the `CarImeTarget` mapper.

The whole point of the pure mapper is parity with the canvas route. But if the renderer routes via
inline `liveWrite` + inline `load/submitText` while the test exercises a *separate* `CarImeTarget`
function, the two can diverge and the test will still pass. The design even notes the IME "Go" path
cannot call `commitKeyboard(board, text)` because it has no `CarSurfaceKeyboard` board, so it
necessarily duplicates that logic — which is precisely the drift risk.

Concrete fix: make the renderer dispatch on the mapper, so the tested function is the executed
function. Define one result type and one entry point, and have both the keystroke and Go paths go
through it:

```kotlin
// CarImeInput.kt — pure, JVM-tested, and ALSO what the renderer calls.
sealed interface CarImeRoute {
    data class MirrorLive(val text: String) : CarImeRoute      // submitText(text, autoSubmit=false)
    data class CommitAddress(val text: String) : CarImeRoute   // load(resolve/sentinel)
    data class CommitFieldSubmit(val text: String) : CarImeRoute // submitText(text, autoSubmit=true)
    data object Ignore : CarImeRoute
}

fun routeImeEdit(target: Target, bufferText: String, kind: ImeKeyKind): CarImeRoute = when (kind) {
    ImeKeyKind.EDIT ->
        CarKeyboardMirror.liveWrite(pageMutated = true, target, bufferText)
            ?.let { CarImeRoute.MirrorLive(it) } ?: CarImeRoute.Ignore
    ImeKeyKind.GO -> when {
        target == Target.ADDRESS && bufferText.isNotBlank() -> CarImeRoute.CommitAddress(bufferText.trim())
        target == Target.ADDRESS -> CarImeRoute.Ignore
        else -> CarImeRoute.CommitFieldSubmit(bufferText.trim())
    }
}
```

Renderer then does `when (routeImeEdit(...)) { is MirrorLive -> submitText(it.text, false); is
CommitAddress -> load(if isSentinel …); is CommitFieldSubmit -> submitText(it.text, true); Ignore -> {} }`.
State in the design that the renderer executes the mapper's result (not a parallel inline copy), so
`CarImeTargetRoutingTest` actually guards the live path.

### 4. MEDIUM — the eager probe raises Gboard on screen (~700 ms flash) at surface-attach, unprompted

Where: design "The runtime probe" + "Fallback switching" (eager probe) + "Capability detection".

The eager probe runs "the first time the hardware window is attached and the gate passes" and
"attach the hidden focused editor … `requestFocus()`, `InputMethodManager.showSoftInput(view, 0)`."
`showSoftInput` raises the *actual IME window* (Gboard), not just the 1×1 editor. On a capable
Android 15 head unit the probe path that resolves `AVAILABLE` does so precisely because Gboard became
visible — i.e. Gboard visibly appears over the projection at browser open, for up to
`PROBE_TIMEOUT_MS = 700` ms, and is then hidden again, before the user has asked to type. The design
treats "invisible 1×1 editor" as if it made the probe invisible; it does not — the editor is
invisible, the IME it summons is not.

This is a real UX regression at startup and is unaddressed. Concrete options (pick one and specify):

- **Defer the probe to first open (drop the eager probe).** Accept a one-time ≤700 ms latency on the
  very first keyboard open instead of a startup flash. Simplest; the lazy probe already exists.
- **Keep the eager probe but suppress IME visibility during it**, e.g. probe via
  `InputMethodManager.showSoftInput(view, SHOW_IMPLICIT)` is still visible, so this needs a real
  suppression mechanism; if none is reliable on the target hosts, this option is not viable and the
  design should say so.
- **Keep the eager probe only when the surface is attached while the app is not foregrounded/visible**
  to the driver (if such a signal exists), else defer.

Whichever is chosen, the design must stop implying the eager probe is invisible and state the
user-visible behavior explicitly.

### 5. NIT — probe treats `null rootWindowInsets` as not-visible, diverging from the `KeyInputView` pattern it cites

Where: design "runtime probe" / "Input validation"; code
`DesktopBrowserActivity.kt:320-321` (`imeVisible()`).

The design cites `DesktopBrowserActivity.imeVisible()` as the signal it reuses, but that method is
`rootWindowInsets?.isVisible(ime()) ?: shown` — it falls back to the remembered `shown` flag when
insets are null. The design deliberately maps null insets → not-visible → (on timeout) `UNAVAILABLE`.
That divergence is defensible (inconclusive = safe), but the design presents it as "the same signal"
without noting the difference. Concrete fix: one sentence acknowledging the probe intentionally does
**not** adopt `KeyInputView`'s `?: shown` fallback because the probe's whole purpose is to observe
real insets, and a remembered flag would defeat it.

### 6. NIT — `deleteSurroundingText(after)` dropped: assumption is reasonable but not defended for selection replacement

Where: design "IME ↔ BrowserTextBuffer reconciliation", `deleteSurroundingText` bullet.

The design argues `after` is always 0 because the cursor is reported at field end with a collapsed
selection. That holds for append-at-end typing. It is weaker right after the ADDRESS seed, where the
buffer is `selectAll = true` (selectionStart=0, selectionEnd=len) — a non-collapsed selection with
text *after* the (start of the) selection. A well-behaved IME shouldn't issue `deleteSurroundingText`
against a selection (it issues `commitText`/`setComposingText` which replaces it), so the conclusion
is probably fine, but the justification as written ("cursor reported at the end of the field after
each commit") does not cover the pre-first-edit selected state. Concrete fix: add that in the
selected (pre-first-commit) state the connection reports the selection via `initialSelStart/End`, and
an IME that wants to delete the selection commits empty text or deletes-before, both of which
`applyImeEdit` handles via `insert("")`/`backspace()`; `after` remaining dropped is still correct.

---

## Verified assumptions (checked against source)

1. `CarHardwareWebWindow` is a real `Presentation` on a `VirtualDisplay` sinked to the car `Surface`
   with `OWN_CONTENT_ONLY | PRESENTATION`, a public `contentLayer: FrameLayout`, and
   `windowView: View? = presentation.window?.decorView`. — CONFIRMED (`CarHardwareWebWindow.kt`,
   `create(...)`, `val contentLayer`, `val windowView`). `attachImeInput/detachImeInput` do not yet
   exist (correct for a design).
2. `ScrollableWebView` (CarWebRenderer inner class, line 246) does **not** override
   `onCreateInputConnection`/`onCheckIsTextEditor`. — CONFIRMED (only the class declaration matched;
   no overrides present).
3. `CarPageWebView` forces `onCheckIsTextEditor()=false` / `onCreateInputConnection()=null` and is
   used only by the projection activity, not `CarWebRenderer`. — CONFIRMED
   (`CarPageWebView.kt:34,36`).
4. `DesktopBrowserActivity.KeyInputView` is the working reference: `onCheckIsTextEditor()=true`,
   `BaseInputConnection(this, false)`, `inputType = TYPE_CLASS_TEXT | TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
   | TYPE_TEXT_FLAG_NO_SUGGESTIONS`, `imeOptions = IME_FLAG_NO_EXTRACT_UI | IME_FLAG_NO_FULLSCREEN |
   IME_ACTION_GO`, `setComposingText → commitText`, `deleteSurroundingText` with `coerceAtMost(64)`,
   `performEditorAction → RETURN`, `sendKeyEvent` only handles `ACTION_DOWN` and `else →
   super.sendKeyEvent(event)` (no blanket consume), `imeVisible()` reads
   `rootWindowInsets?.isVisible(ime())`. — CONFIRMED (`DesktopBrowserActivity.kt:292-379`). The design's
   `CarImeInputView` recipe faithfully mirrors it.
5. `CarKeyboardMirror.liveWrite(pageMutated, target, bufferText)` returns `bufferText.trim()` only
   when `pageMutated && target == FIELD`, null otherwise; empty string is a valid return. — CONFIRMED
   (`CarKeyboardMirror.kt`).
6. `BrowserTextBuffer(initial, selectAll)`: `selectionStart = if (selectAll) 0 else initial.length`,
   `selectionEnd = initial.length`; `insert` replaces selection then collapses; `backspace` deletes
   selection else one code point (surrogate-safe). — CONFIRMED (`BrowserKeyboard.kt`).
7. `CarSurfaceKeyboard` seeds `BrowserTextBuffer(seed, selectAll = target == ADDRESS && seed.isNotEmpty())`.
   — CONFIRMED (`CarSurfaceKeyboard.kt:48`), and the design's `ImeSession` uses the identical rule.
8. `submitText(text, autoSubmit)` (line 1408): trims; no-WebView Go → `load`, live mirror → nothing;
   empty+autoSubmit returns; `NO_FOCUS` on Go → `load`, on live mirror → ignored; escapes via
   `CarJsEscape.singleQuoted`. — CONFIRMED (`CarWebRenderer.kt:1408-1446`).
9. `commitKeyboard` (line 2010): `closeKeyboard()`, ADDRESS non-empty →
   `load(if isSentinel text else BrowserInputResolver.resolveTyped(...))`, FIELD →
   `submitText(text, autoSubmit=true)`. — CONFIRMED (`CarWebRenderer.kt:2010-2026`).
10. `handleKeyboardTap` live-mirror path: `CarKeyboardMirror.liveWrite(result.pageMutated, board.target,
    board.buffer.text)?.let { submitText(it, autoSubmit=false) }`; `onKeyboardChanged?.invoke(true/false)`
    on open/close. — CONFIRMED (`CarWebRenderer.kt:1914-1955`, `showKeyboard`, `closeKeyboard`).
11. Render-mode fields: `requestedMode = CarBrowserRenderMode.current(appContext)` (286, HARDWARE
    default), `activeMode` (287), `hardwareMode get() = activeMode == HARDWARE` (292),
    `hardwareWindow` (290); `attachHardwareWindow` (3165) drops to `LEGACY_CANVAS` on `RuntimeException`
    and logs at warn with `SDK_INT`. — CONFIRMED.
12. `openKeyboard` (1810) resolves target via `focusedView`/`readFocusedField` → `openAddressKeyboard`
    (ADDRESS, seed=`livePageUrl`) or `openFieldKeyboard(info)` (FIELD). `readFocusedField` parses the
    JSON defensively, null → address. — CONFIRMED (`CarWebRenderer.kt:1810-1838`).
13. Exactly four `keyboardOpen` *readers*: toggle `CarBrowserScreen.kt:239` + three debug strings
    `:511,:523,:535`; definition at `CarWebRenderer.kt:405`. Car Back → `renderer.closeKeyboard()`
    (`CarBrowserScreen.kt:84-86`). — CONFIRMED. (But see Finding 1: `keyboard`-field gates beyond
    these were not audited.)
14. Single build spans <15 and 15+: `compileSdk = 36`, `minSdk = 29`, `targetSdk = 36`; no
    flavor/user-setting gate for the keyboard feature (flavors `safe/personal/lab` exist but are
    unrelated; the feature lives in `src/main`). — CONFIRMED (`app/build.gradle.kts:103,118,119`).
15. Existing JVM tests named as regression guards exist: `BrowserKeyboardTest`,
    `CarKeyboardGesturesTest`, `CarKeyboardInputRoutingTest`, `CarKeyboardLayoutTest`,
    `CarKeyboardMirrorTest`, `CarWebRendererJsEscapeTest`. — CONFIRMED.
16. `CarSurfaceKeyboard.Target { ADDRESS, FIELD }`. — CONFIRMED (`CarSurfaceKeyboard.kt:28`).

## Unverified / wrong assumptions

1. **WRONG (Finding 1):** "the four `keyboardOpen` readers … are all of them" and "opening either
   keyboard asserts the other is closed." The single-keyboard invariant is also enforced by direct
   `keyboard`-field reads at `CarWebRenderer.kt:1825, 1827, 2142, 2169, 2259`. `offerFieldInput`
   (1825/1827) will open a canvas field keyboard while an IME session is active.
2. **AMBIGUOUS (Finding 2):** "pass the IME inset height instead of the tray top." Tray-top is a
   surface-space Y coordinate; the IME inset is a bottom-anchored height. Not a drop-in substitution,
   and the current method early-returns on `keyboard == null`.
3. **UNSUPPORTED BY WIRING (Finding 3):** the `CarImeTarget` mapper is tested but the renderer wiring
   describes inline `liveWrite`/`commitKeyboard` reimplementation; not shown to be the executed path.
4. **OVERSTATED (Finding 4):** the eager probe is described as if invisible ("invisible 1×1 editor");
   `showSoftInput` makes the real IME (Gboard) visible for up to 700 ms at surface-attach.
5. **DEVICE-ONLY (not a defect, acknowledged by the design):** whether `showSoftInput` actually
   raises Gboard over the projection and `WindowInsets.Type.ime()` turns visible on an Android 15 head
   unit cannot be verified from source and was not tested here (builds/tests not run per instructions).
   The design correctly scopes this to on-device validation.

## Required-decision scorecard

1. Host-capability detection (SDK≥35 AND probe, inconclusive→canvas) — **MET** (core), with Finding 4
   (eager-probe UX flash) to resolve.
2. Real focused editable View on the CAR SurfaceCallback route — **MET**; lowest-risk reuse of
   `CarHardwareWebWindow` is justified against two rejected alternatives and matches the code.
3. Target routing reusing existing plumbing (ADDRESS commit-on-Go, FIELD live mirror, EN/ไทย,
   backspace-to-empty, IME_ACTION_GO) — **MOSTLY MET**; blocked by Finding 3 (tested mapper vs.
   executed path) for parity assurance.
4. Seamless fallback to `CarSurfaceKeyboard` preserving prior behavior — **NOT FULLY MET**; blocked by
   Finding 1 (second keyboard can open over the IME) and Finding 2 (scroll-into-view).

Single build for 15+ and <15 at runtime, no flavor/setting gate — **MET** (Verified #14).
Test plan covers the pure logic — **MET in intent** (gate, routing, buffer, lifecycle predicates),
contingent on Finding 3 so the routing test guards the real path.
