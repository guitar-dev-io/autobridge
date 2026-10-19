# Implementation Plan — real system keyboard (Gboard) over the Android Auto car browser

Source of truth: `/Users/anuwat.t/Documents/ChatGPT/AutoBridge/.agents/tasks/car-system-keyboard/design.md`
(read it, including the final-review Addendum, before implementing). All paths below are inside the
worktree `/Users/anuwat.t/Documents/ChatGPT/AutoBridge/.worktrees/car-system-keyboard`. Use
`git -C /Users/anuwat.t/Documents/ChatGPT/AutoBridge/.worktrees/car-system-keyboard` for all git.

Build/test gate (the authoritative verification command for every code step):

```
./gradlew :app:testSafeDebugUnitTest :app:testPersonalDebugUnitTest :app:testLabDebugUnitTest
```

Run it from the worktree root. The projection source set (`DesktopBrowserActivity`, `CarPageWebView`)
compiles only under the `personal`/`lab` flavors (see `app/build.gradle.kts:303`), so all three test
tasks must pass. Do **not** rely on `assembleSafeDebug` — it fails only in the unrelated `:whisper`
CMake/NDK module, which is not a regression from this change. The three `test*DebugUnitTest` tasks
compile `src/main` + `src/test` (+ projection under personal/lab) and run every JVM test.

Design-review findings fold-in (all six are incorporated below):
- Findings 4/5/6 are documentation changes, already applied to `design.md` (final-review Addendum).
- Finding 1 (HIGH, single-keyboard invariant) is implemented in **step 5**.
- Finding 2 (MEDIUM, `keepFocusedFieldAboveKeyboard`) is implemented in **step 7**.
- Finding 3 (MEDIUM, tested mapper == executed path) is implemented in **step 3** (the `CarImeRoute`
  type + `routeImeEdit` entry point) and wired in **step 6**.

Ordering rationale: steps 1–4 add new, self-contained files (pure logic + its tests + the View +
the window hook) that compile and test independently without touching the renderer, so the tree stays
green. Step 5 fixes the invariant leaks (finding 1) as an isolated, separately verifiable change.
Steps 6–7 wire the renderer to the new pieces (the one large integration edit, split by concern).
Step 8 is the full gate.

---

- [ ] 1. Add the pure capability gate and probe-state enum: `CarImeCapability`.
      Create `internal object CarImeCapability` with `fun gate(sdkInt: Int, hardwareMode: Boolean): Boolean = sdkInt >= 35 && hardwareMode`
      (literal `35` with a `// Build.VERSION_CODES.VANILLA_ICE_CREAM` comment) and
      `enum class ImeProbeState { UNKNOWN, AVAILABLE, UNAVAILABLE }`. No Android types in the gate so it
      unit-tests on the JVM (the enum is a plain enum, also JVM-safe). This is the pure half of
      "Capability detection" in the design.
      Files: `app/src/main/java/dev/autobridge/browser/CarImeCapability.kt`
      Verify: `./gradlew :app:testSafeDebugUnitTest :app:testPersonalDebugUnitTest :app:testLabDebugUnitTest` compiles; the gate is exercised by step 2's test.

- [ ] 2. Add `CarImeCapabilityTest` (JVM), in the style of `app/src/test/java/dev/autobridge/browser/CarKeyboardMirrorTest.kt`.
      Cover the <15 / 15+ split and the hardware-mode requirement: `gate(34, true) == false`,
      `gate(35, true) == true` (boundary), `gate(36, true) == true`, `gate(35, false) == false`,
      `gate(34, false) == false`. JUnit4, `org.junit.Assert.*`, package `dev.autobridge.browser`.
      Files: `app/src/test/java/dev/autobridge/browser/CarImeCapabilityTest.kt`
      Verify: `./gradlew :app:testSafeDebugUnitTest :app:testPersonalDebugUnitTest :app:testLabDebugUnitTest` — all `CarImeCapabilityTest` cases pass.

- [ ] 3. Add the pure routing + reconciliation helpers: `CarImeInput.kt` (FINDING 3 implemented here).
      Create, all pure (no Android types, so JVM-testable), in package `dev.autobridge.browser`:
      - `sealed interface ImeEdit { data class Commit(val text: String); data class DeleteBefore(val count: Int) }`.
      - `fun applyImeEdit(buffer: BrowserTextBuffer, edit: ImeEdit)`: `Commit -> buffer.insert(text)`
        (selection-replace-then-collapse, per `BrowserTextBuffer.insert` at `BrowserKeyboard.kt:112`);
        `DeleteBefore -> repeat(count) { buffer.backspace() }` (uses `backspace()` at
        `BrowserKeyboard.kt:120`, surrogate/selection-safe, never underflows past empty).
      - `enum class ImeKeyKind { EDIT, GO }`.
      - `sealed interface CarImeRoute { data class MirrorLive(val text: String); data class CommitAddress(val text: String); data class CommitFieldSubmit(val text: String); data object Ignore }`.
      - `fun routeImeEdit(target: CarSurfaceKeyboard.Target, bufferText: String, kind: ImeKeyKind): CarImeRoute`:
        `EDIT` → delegate to the EXISTING `CarKeyboardMirror.liveWrite(pageMutated = true, target, bufferText)`,
        mapping a non-null result to `MirrorLive(it)` and null to `Ignore` (so FIELD mirrors the trimmed
        buffer incl. `""`, ADDRESS yields `Ignore` — parity with the canvas route, pinned by
        `CarKeyboardMirrorTest`); `GO` → `target == ADDRESS && bufferText.isNotBlank()` →
        `CommitAddress(bufferText.trim())`; `target == ADDRESS` (blank) → `Ignore`; else
        `CommitFieldSubmit(bufferText.trim())`. This is the ONE mapper the renderer will dispatch on in
        step 6 (no inline reimplementation), so the test guards the executed path.
      - Two lifecycle predicates: `fun isKeyboardOpen(hasCanvas: Boolean, hasImeSession: Boolean) = hasCanvas || hasImeSession`
        and `fun shouldCloseImeOnInsets(imeSessionActive: Boolean, imeVisible: Boolean) = imeSessionActive && !imeVisible`.
      Files: `app/src/main/java/dev/autobridge/browser/CarImeInput.kt`
      Verify: `./gradlew :app:testSafeDebugUnitTest :app:testPersonalDebugUnitTest :app:testLabDebugUnitTest` compiles; exercised by step 4's tests.

- [ ] 4. Add `CarImeTargetRoutingTest` and `CarImeBufferTest` (JVM), styled on `CarKeyboardMirrorTest`/`CarKeyboardLayoutTest`.
      `CarImeTargetRoutingTest` over `routeImeEdit` + the two predicates:
      FIELD EDIT text → `MirrorLive(trimmed)`; FIELD EDIT backspace-to-empty (`""`) → `MirrorLive("")`;
      ADDRESS EDIT → `Ignore`; ADDRESS GO non-blank → `CommitAddress(trimmed)`; ADDRESS GO blank →
      `Ignore`; FIELD GO → `CommitFieldSubmit(trimmed)`. Plus `isKeyboardOpen` truth table
      (canvas-only/IME-only/both → true, neither → false) and `shouldCloseImeOnInsets(true,false)==true`,
      `(true,true)==false`, `(false,false)==false`.
      `CarImeBufferTest` over `applyImeEdit(buffer, edit)` against `BrowserTextBuffer`:
      ADDRESS seeded URL + `selectAll=true`, first `Commit("x")` → `"x"` (selection replaced), cursor
      collapsed; second `Commit("y")` → `"xy"`; FIELD seeded `"ab"`, `DeleteBefore(1)` → `"a"`,
      `DeleteBefore(1)` → `""`; Thai `Commit("ส")` then `Commit("วัสดี")` appends verbatim;
      `Commit("worlds")` after `"world"` with collapsed end-cursor inserts at cursor;
      `DeleteBefore(100)` on a 3-char buffer → `""` (never underflows). Seed via
      `BrowserTextBuffer(seed, selectAll = ...)` matching `CarSurfaceKeyboard.kt:48`.
      Files: `app/src/test/java/dev/autobridge/browser/CarImeTargetRoutingTest.kt`, `app/src/test/java/dev/autobridge/browser/CarImeBufferTest.kt`
      Verify: `./gradlew :app:testSafeDebugUnitTest :app:testPersonalDebugUnitTest :app:testLabDebugUnitTest` — all new cases pass. (This is the `shouldCloseImeOnInsets` / `CarImeBufferTest` / `CarImeTargetRoutingTest` unit coverage the task requires.)

- [ ] 5. Fix the single-keyboard invariant leaks in `CarWebRenderer` (FINDING 1 — HIGH).
      Add `private val anyKeyboardOpen: Boolean get() = keyboard != null || imeSession != null` (place the
      `imeSession` field here or in step 6; this step may declare it as `private var imeSession: ImeSession? = null`
      with a minimal placeholder `private class ImeSession` so the file compiles — step 6 fleshes it out).
      Re-audit and update every direct `keyboard`-field gate so a live IME session counts as "a keyboard
      is up", enumerated:
      - `offerFieldInput` (~line 1824–1827): early-return guard `if (overlay != Overlay.NONE || anyKeyboardOpen) return`,
        and the inner re-check `if (info != null && overlay == Overlay.NONE && !anyKeyboardOpen) openFieldKeyboard(info)`.
        This is the HIGH leak: without it, a page field gaining focus posts `offerFieldInput`, which in the
        IME-only state (`keyboard == null`) would call `openFieldKeyboard` → a canvas tray over a live Gboard.
      - `stepFling` (~line 2140, `if (keyboard != null) { stopFling(); return false }`): change to
        `anyKeyboardOpen` — the page must not glide under either keyboard. (The design review cited this as
        "fling ~2142"; it is the `stepFling` guard.)
      - `scaleBy` (~line 2168, `val board = keyboard`): the canvas double-tap/pinch handling stays keyed on
        the canvas `board` (there is no canvas tray to replay taps on in the IME state), but the page-zoom
        fall-through must be blocked under the IME too — gate the zoom with `anyKeyboardOpen` (no page zoom
        while Gboard is up), matching the canvas behavior. Decision: IME-only state behaves like canvas-open
        (block zoom).
      - `fabVisible` (~line 2259, `keyboard == null`): change to `!anyKeyboardOpen` so the FAB is hidden
        while the IME is up, same as under the canvas tray. Decision: IME-only behaves like canvas-open
        (hide FAB).
      Leave `onSurfaceClick`'s `keyboard?.let { ... }` guard (~line 1456) reading `keyboard` (NOT
      `anyKeyboardOpen`): in the IME-only state taps must fall through to the page because Gboard owns its
      own touches — this is the design's explicit decision.
      Files: `app/src/main/java/dev/autobridge/browser/CarWebRenderer.kt`
      Verify: `./gradlew :app:testSafeDebugUnitTest :app:testPersonalDebugUnitTest :app:testLabDebugUnitTest` compiles and all existing JVM tests (`CarKeyboardGesturesTest`, `CarKeyboardInputRoutingTest`, etc.) still pass.

- [ ] 6. Add the focused editor View + window hook, and wire the IME route into `CarWebRenderer` (FINDING 3 dispatch).
      This is the core integration; implement as one coherent change so the file stays buildable.
      a) `CarImeInputView.kt`: an `internal class CarImeInputView(context, textBeforeCursor, textAfterCursor,
         selectionStart, selectionEnd, onCommit, onDeleteBefore, onAction)` extending `View`, modeled on
         `DesktopBrowserActivity.KeyInputView` (projection source). `isFocusable/isFocusableInTouchMode = true`;
         `onCheckIsTextEditor() = true`; `onCreateInputConnection` sets `inputType = TYPE_CLASS_TEXT |
         TYPE_TEXT_VARIATION_VISIBLE_PASSWORD | TYPE_TEXT_FLAG_NO_SUGGESTIONS`, `imeOptions = IME_FLAG_NO_EXTRACT_UI |
         IME_FLAG_NO_FULLSCREEN | IME_ACTION_GO`, `initialSelStart/End` from the buffer callbacks, and returns a
         non-backed `BaseInputConnection(this, false)` implementing `getTextBeforeCursor`/`getTextAfterCursor`
         from the buffer, `commitText`→`onCommit`, `setComposingText`→commit (Thai: commit not compose),
         `deleteSurroundingText(before, _)`→`onDeleteBefore(before.coerceAtMost(64))` (drop `after`),
         `performEditorAction`→`onAction`, and a routed `sendKeyEvent` (only `ACTION_DOWN`: `KEYCODE_DEL`→
         `onDeleteBefore(1)`, `ENTER`/`NUMPAD_ENTER`→`onAction`, printable `unicodeChar`→`onCommit`, else
         `super.sendKeyEvent`). The class also owns the session-lifetime `OnApplyWindowInsetsListener` wiring
         used for probe + dismiss detection (expose a hook the renderer registers on `windowView`).
      b) `CarHardwareWebWindow.kt`: add `fun attachImeInput(view: View)` (add to `contentLayer` at 1×1,
         invisible) and `fun detachImeInput(view: View)` (`contentLayer.removeView`). `windowView` already exists
         for insets observation. Keep all `Presentation`/window access inside this class.
      c) `CarWebRenderer.kt` IME session + routing:
         - Fields: `private var imeProbe: ImeProbeState = ImeProbeState.UNKNOWN`, `private var imeSession: ImeSession?`,
           `private var imeInputView: CarImeInputView?`; `private class ImeSession(target, mode, seed)` holding
           `val buffer = BrowserTextBuffer(seed, selectAll = target == ADDRESS && seed.isNotEmpty())`.
         - `keyboardOpen`: change its definition (line 405) to `keyboard != null || imeSession != null`.
         - `openAddressKeyboard`/`openFieldKeyboard`: build the `ImeSession` seed first, then branch on
           `CarImeCapability.gate(Build.VERSION.SDK_INT, activeMode == HARDWARE)` + `imeProbe`:
           `UNAVAILABLE` or gate false → `showKeyboard(CarSurfaceKeyboard(...))` exactly as today;
           `AVAILABLE` → `openImeKeyboard(session)`; `UNKNOWN` (gate passes) → run the **lazy probe** (no eager
           probe — see design Addendum finding 4): construct the real `CarImeInputView` seeded from the session
           buffer, `attachImeInput`, `requestFocus`, `InputMethodManager.showSoftInput(view, 0)`, register the
           insets listener, start `PROBE_TIMEOUT_MS = 700`; on `ime()` visible → cache `AVAILABLE`, keep the
           editor (reuse for the session); on timeout/null-insets/exception/missing-IMM/no-window → cache
           `UNAVAILABLE`, detach/discard the editor, `showKeyboard(canvas)` with the same seed.
         - `openImeKeyboard(session)`: attach+show the editor (reusing the lazy-probe editor on `AVAILABLE`),
           set `imeSession`, `onKeyboardChanged?.invoke(true)`.
         - `onImeCommit(text)` / `onImeDeleteBefore(n)`: `applyImeEdit(session.buffer, ...)`, then dispatch
           `routeImeEdit(session.target, session.buffer.text, ImeKeyKind.EDIT)` — `MirrorLive -> submitText(it.text, autoSubmit = false)`,
           `Ignore -> {}` (ADDRESS does not mirror). `onImeAction()`: dispatch
           `routeImeEdit(session.target, session.buffer.text, ImeKeyKind.GO)` — `CommitAddress ->
           load(if WebVideoDiagnostics.isSentinel(it.text) it.text else BrowserInputResolver.resolveTyped(it.text, SearchEngineStore.engine(appContext)))`,
           `CommitFieldSubmit -> submitText(it.text, autoSubmit = true)`, `Ignore -> {}`; then `closeImeKeyboard()`.
           The renderer MUST dispatch on `routeImeEdit`'s result (no parallel inline copy) so
           `CarImeTargetRoutingTest` guards the live path (finding 3).
         - `closeImeKeyboard()`: `InputMethodManager.hideSoftInputFromWindow(view.windowToken, 0)`, `detachImeInput`,
           clear `imeSession`/`imeInputView`, `onKeyboardChanged?.invoke(false)`. Route `closeKeyboard()` to
           `closeImeKeyboard()` when `imeSession != null` (keep canvas behavior otherwise) so the car-Back and the
           keyboard-icon toggle (`CarBrowserScreen.kt:85`, `:239`, both call `closeKeyboard()`) close whichever
           keyboard is up — no `CarBrowserScreen` change.
         - Session-lifetime insets listener: while `imeSession != null`, when `rootWindowInsets.isVisible(ime())`
           transitions to `false` (use `shouldCloseImeOnInsets(imeSession != null, imeVisible)`), call
           `closeImeKeyboard()` so a direct Gboard dismissal doesn't leave stale state.
         - Reset `imeProbe` to `UNKNOWN` and drop `imeInputView` on LEGACY_CANVAS fallback / hardware-window
           release/recreate.
      Files: `app/src/main/java/dev/autobridge/browser/CarImeInputView.kt`, `app/src/main/java/dev/autobridge/browser/CarHardwareWebWindow.kt`, `app/src/main/java/dev/autobridge/browser/CarWebRenderer.kt`
      Verify: `./gradlew :app:testSafeDebugUnitTest :app:testPersonalDebugUnitTest :app:testLabDebugUnitTest` compiles and all existing + new JVM tests pass.

- [ ] 7. Reparameterize `keepFocusedFieldAboveKeyboard` for both routes (FINDING 2 — MEDIUM).
      Change the signature to `private fun keepFocusedFieldAboveKeyboard(occludedTop: Float)` (surface-pixel-Y
      of the top edge of whatever covers the bottom of the page); remove the `val board = keyboard ?: return`
      early-return; keep the `focusedView`/`pane` guards; compute
      `val visible = ((occludedTop - pane.top) / pane.height.toFloat()).coerceIn(0.2f, 1f)` and the existing
      scroll JS unchanged. Canvas call site passes `board.geometry(keyboardArea(), sizes.dp(1f)).tray.top`.
      IME call site converts the inset: `val imeBottom = windowView?.rootWindowInsets?.getInsets(WindowInsets.Type.ime())?.bottom`;
      if non-null and `> 0`, call with `occludedTop = surfaceHeight - imeBottom.toFloat()`, else skip the scroll
      (non-fatal). Wire the IME call into the field-focus path of the IME route (step 6).
      Files: `app/src/main/java/dev/autobridge/browser/CarWebRenderer.kt`
      Verify: `./gradlew :app:testSafeDebugUnitTest :app:testPersonalDebugUnitTest :app:testLabDebugUnitTest` compiles and all JVM tests pass.

- [ ] 8. Full build/test gate and regression check.
      Run the gate command from the worktree root; confirm every new test
      (`CarImeCapabilityTest`, `CarImeTargetRoutingTest`, `CarImeBufferTest`) passes and every existing
      keyboard test (`BrowserKeyboardTest`, `CarKeyboardMirrorTest`, `CarKeyboardLayoutTest`,
      `CarKeyboardGesturesTest`, `CarKeyboardInputRoutingTest`, `CarWebRendererJsEscapeTest`) still passes —
      the canvas route is untouched and must not regress.
      Files: none (verification only).
      Verify: `./gradlew :app:testSafeDebugUnitTest :app:testPersonalDebugUnitTest :app:testLabDebugUnitTest` — BUILD SUCCESSFUL, all unit tests green across safe/personal/lab.

---

## Notes / assumptions

- Device-only behavior (not JVM-testable, documented in the design): whether `showSoftInput` actually
  raises Gboard over the projection and `WindowInsets.Type.ime()` turns visible on an Android 15 head
  unit. The probe + inconclusive-is-canvas design makes every unverifiable path fall back safely to the
  canvas keyboard, so no JVM test is possible or required for it.
- Exact line numbers drift as the file is edited; they are given as anchors against the current worktree
  `CarWebRenderer.kt`. Locate the gate sites in step 5 by the surrounding code shown in the design review,
  not by absolute line number.
- No new libraries, no build flavor, no user setting — the route is decided at runtime per keyboard-open,
  as required.
