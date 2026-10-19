# Report — real system keyboard (Gboard) over the Android Auto car browser

Branch: `feat/car-system-keyboard`. Request (AABrowser parity): raise the real system keyboard — like
[AABrowser](https://github.com/kododake/AABrowser) does — over the projected car browser, using the
Google/system form keyboard where the host supports it, and falling back to the app's own canvas
keyboard where it does not (Android 15+ gets the real IME; pre-15 gets the canvas keyboard). Content
from the AABrowser repository was not copied; only its approach of surfacing a focused editor to the
projection informed the design.

## What changed

New files (all under `app/src/main/java/dev/autobridge/browser/`):

- **`CarImeCapability.kt`** — the pure capability gate (`CarImeCapability.gate(sdkInt, hardwareMode)`)
  and the `ImeProbeState { UNKNOWN, AVAILABLE, UNAVAILABLE }` tri-state. No Android window types, so
  it unit-tests on the JVM.
- **`CarImeInput.kt`** — the pure routing + reconciliation core: `ImeEdit` + `applyImeEdit` (fold an
  IME edit into the authoritative `BrowserTextBuffer`), `routeImeEdit` returning the single
  `CarImeRoute` result type the renderer dispatches on, `isKeyboardOpen` (the OR invariant), and
  `shouldCloseImeOnInsets` (direct-dismiss rule). All JVM-testable.
- **`CarImeInputView.kt`** — the invisible, focused editable `View` that owns a real, non-backed
  `BaseInputConnection` inside the existing hardware window. Modeled on
  `DesktopBrowserActivity.KeyInputView`: `onCheckIsTextEditor() = true`, commit-not-compose (so Thai
  lands in the field), `deleteSurroundingText(before)` only, `IME_ACTION_GO`, and a window-decor
  `OnApplyWindowInsetsListener` that reports `WindowInsets.Type.ime()` visibility (drives the probe
  and dismiss detection).

Changed files:

- **`CarHardwareWebWindow.kt`** (+16) — `attachImeInput` / `detachImeInput` to parent/detach the
  focused editor on the existing presentation's `contentLayer`; exposes `windowView` for the insets
  listener.
- **`CarWebRenderer.kt`** (+365/−26) — the wiring: the `ImeSession` holder, the lazy probe, the
  `openKeyboardFor` branch point (IME-or-canvas), the IME edit/action callbacks, `dispatchImeRoute`,
  `closeImeKeyboard`/`tearDownImeEditor`/`resetImeState`, the `anyKeyboardOpen`/`keyboardOpen` OR
  predicate used at every keyboard-state gate, and the reparameterized
  `keepFocusedFieldAboveKeyboard(occludedTop: Float?)`.

New tests (`app/src/test/java/dev/autobridge/browser/`): `CarImeCapabilityTest`,
`CarImeTargetRoutingTest`, `CarImeBufferTest`.

## Detection mechanism

Two stages, so nothing unverifiable ever decides "the user has no keyboard":

1. **Static gate (pure, JVM-tested).** `CarImeCapability.gate(sdkInt, hardwareMode)` requires
   `sdkInt >= 35` (Android 15, `VANILLA_ICE_CREAM`) AND the live render mode to be `HARDWARE`. Below
   15 the platform does not surface an IME over a virtual display the app does not own; the
   `LEGACY_CANVAS` path has no window to host an editor at all. The renderer also requires a live
   `hardwareWindow`. This covers the user's "15+ and <15" split with a single build (no flavor or
   setting gate; `compileSdk 36 / minSdk 29`).

2. **Runtime probe (stateful, device-only).** The first time the gate passes on a keyboard open, the
   renderer constructs the editor, attaches it, `requestFocus()` + `showSoftInput`, and starts a
   ≤`IME_PROBE_TIMEOUT_MS` (700 ms) timer. If the decor-view insets report `ime()` visible before the
   timer fires, the probe resolves **AVAILABLE** and the already-raised Gboard is kept for the
   session (no detach/re-raise). Any inconclusive outcome — timeout, null insets, missing IMM,
   exception — resolves **UNAVAILABLE**, tears the editor down, and opens the canvas keyboard seeded
   with the same text. The result is cached per renderer session, so only the first open pays the
   probe cost.

Top invariant: **the user always has a working keyboard.** Every path that cannot confirm a real IME
falls back to the canvas keyboard.

## Presentation route chosen and why

**Reuse the existing `CarHardwareWebWindow`** (a `Presentation` on a `VirtualDisplay` sinked to the
car `Surface`) and parent an invisible, focused 1-view editor into its public `contentLayer`, giving
it a real `InputConnection`. This is the lowest-risk option: the window, its surface, and its
lifecycle already exist and are proven; the IME just needs a focused editable `View` inside a window
the platform recognizes.

Rejected alternatives: (1) a separate dedicated window/dialog for the editor — more lifecycle and
surface plumbing for no gain; (2) making the `WebView` itself the text editor — `CarPageWebView`
deliberately forces `onCheckIsTextEditor() = false` / `onCreateInputConnection() = null`, and the car
`WebView` is non-interactive by design, so routing through it would fight the existing architecture.

The editor's connection is **non-backed** (`BaseInputConnection(this, false)`): the app's
`BrowserTextBuffer` stays the single source of truth. The connection reports the buffer to Gboard
(`initialSel*`, `getTextBefore/AfterCursor`) and forwards each raw edit to the renderer, which folds
it into the buffer with `applyImeEdit` and then routes it exactly like the canvas keyboard. Text is
committed, never composed, so multi-stage scripts (Thai) resolve into the field.

## How each of the 6 design-review findings was resolved

1. **(HIGH) `offerFieldInput` could open a canvas keyboard over a live IME session; "audited every
   gate" was incomplete.** Added a single `anyKeyboardOpen` predicate
   (`keyboard != null || imeSession != null`) and used it at *every* site that read the raw
   `keyboard` field to mean "a keyboard is up": `offerFieldInput`'s early-return and its inner
   re-check, the fling (`stepFling`), the zoom fall-through (`scaleBy`), and FAB visibility. The
   public `keyboardOpen` is now the same OR. `onSurfaceClick`'s canvas tray guard stays on
   `keyboard` by design (it only hit-tests the canvas tray), so the re-entrancy is closed at the
   funnel instead — see finding resolution in the integration pass below.

2. **(MEDIUM) `keepFocusedFieldAboveKeyboard` reparameterization was ambiguous and null-guarded out
   the IME path.** Reparameterized to `keepFocusedFieldAboveKeyboard(occludedTop: Float?)` — a
   surface-pixel Y of the top edge of whatever covers the page bottom. Canvas passes
   `canvasOccludedTop()` (the tray top); the IME passes `imeOccludedTop()` =
   `surfaceHeight - ime().bottom`. `null` (no tray / no IME insets yet) skips the scroll as a non-
   fatal no-op. The old `keyboard ?: return` is gone.

3. **(MEDIUM) The tested mapper was not shown to be the executed path (test/production drift).** One
   result type `CarImeRoute` and one pure entry point `routeImeEdit` live in `CarImeInput.kt`; the
   renderer's `dispatchImeRoute` executes that exact result (`MirrorLive → submitText(false)`,
   `CommitAddress → load(resolve/sentinel)`, `CommitFieldSubmit → submitText(true)`, `Ignore → {}`),
   with no inline reimplementation. `CarImeTargetRoutingTest` therefore guards the live path, and the
   EDIT case reuses `CarKeyboardMirror.liveWrite` so FIELD-mirror/ADDRESS-never parity with the
   canvas route is literally the same decision.

4. **(MEDIUM) The eager probe flashed Gboard (~700 ms) at surface-attach.** The eager probe was
   dropped. Only the lazy probe remains: it runs once, on the first keyboard open with the gate
   passing, so there is no unprompted keyboard flash at browser open. The cost is a one-time ≤700 ms
   latency on that first open, not a startup flash.

5. **(NIT) Probe divergence from the `KeyInputView` `?: shown` fallback was unstated.** The probe
   intentionally maps null `rootWindowInsets` → not-visible → (on timeout) `UNAVAILABLE`; it does not
   adopt `KeyInputView`'s remembered-flag fallback, because the probe's whole purpose is to observe
   real insets and a remembered flag would defeat it. Inconclusive = safe canvas.

6. **(NIT) `deleteSurroundingText(after)` dropped without defending the selection-replace case.** The
   `after` argument is dropped; the pre-first-commit ADDRESS state (`selectAll = true`) is reported
   to the IME via `initialSelStart/End`, and a well-behaved IME replaces a selection with
   `commitText`/`setComposingText` (→ `applyImeEdit` `insert`) or deletes-before — both handled by
   the buffer's own semantics, so dropping `after` stays correct.

### Integration-review pass (2nd iteration) — two blocking findings fixed

- **Chrome address-tap re-entrancy.** A tap on `ChromeZone.ADDRESS` in the IME-only state fell
  through `onSurfaceClick`'s canvas-only guard into `openAddressKeyboard → openKeyboardFor`. Fix:
  `if (anyKeyboardOpen) return` at the top of `openKeyboardFor` — the single branch point every
  open funnels through — so no re-entry can double-open.
- **`openImeKeyboard` orphaning the previous editor.** Added `if (imeSession != null) return true`
  as defense-in-depth at the top of `openImeKeyboard`, so a bypassing caller can never overwrite a
  live editor reference without teardown.
- **(non-blocking) scroll under a live Gboard.** Added `anyKeyboardOpen ||` to the `scrollBy` swallow
  guard, matching the fling/zoom gates.

The final review verdict is **APPROVED** (`review.json`); its three remaining findings are all
non-blocking / no-action (synchronous insets read self-corrects on the next callback; the ≤700 ms
probe-in-flight blank window is acceptable; the whitespace-only ADDRESS-Go parity nuance is
unreachable because text is trimmed upstream).

## Build / test result

Gate (from the worktree root), single-run:

```
./gradlew :app:testSafeDebugUnitTest :app:testPersonalDebugUnitTest :app:testLabDebugUnitTest
```

**BUILD SUCCESSFUL** — all three flavor Kotlin graphs compile
(`compileSafeDebugKotlin`/`compilePersonalDebugKotlin`/`compileLabDebugKotlin`, including the
projection source set) and every JVM unit suite passes. New suites: `CarImeCapabilityTest` (5),
`CarImeTargetRoutingTest` (10), `CarImeBufferTest` (6); canvas regression suites all green. See
`verification.md` for the per-suite breakdown and the manual DHU/device checklist.

## Manual test checklist

The real-IME behaviors (Gboard actually raising over the projection, insets reporting visible, on-
screen scroll/Back/dismiss) cannot be verified on the JVM and require a head unit or DHU. The full
checklist is in `verification.md` under "MANUAL DHU / device checklist" and covers: (a) Android 15+
host — address bar + page field raise Gboard, EN + ไทย land in the correct target, Go works,
backspace-to-empty clears the field, exactly one keyboard on screen; (b) pre-15 host / DHU —
automatic canvas fallback with all prior canvas behavior intact; (c) inconclusive/denied probe — the
canvas keyboard still works and the user is never left without one; (d) direct Gboard dismissal —
clean IME teardown and restored car-Back state.
