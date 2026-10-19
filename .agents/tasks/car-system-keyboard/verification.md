# Verification — real system keyboard (Gboard) over the Android Auto car browser

Iteration: SECOND (review.json present → fixed the two blocking integration findings + the one
non-blocking scroll finding; see "Review iteration" section at the bottom). First-iteration notes
retained below.

## Build / test gate

Command (run from the worktree root
`/Users/anuwat.t/Documents/ChatGPT/AutoBridge/.worktrees/car-system-keyboard`):

```
./gradlew :app:testSafeDebugUnitTest :app:testPersonalDebugUnitTest :app:testLabDebugUnitTest
```

Result: **BUILD SUCCESSFUL in 31s**, 147 actionable tasks. This compiled the Kotlin graphs for all
three flavors (`compileSafeDebugKotlin`, `compilePersonalDebugKotlin`, `compileLabDebugKotlin` — the
real compile proof, including the projection source set under personal/lab) and ran every JVM unit
test. The only warnings emitted are pre-existing `!!` lint notes in unrelated test files
(`HomeDashboardLayoutTest`, `CommandValidatorTest`, `WhisperModelStoreTest`); none originate from this
change.

`assembleSafeDebug` was NOT used as the gate per the task instruction (its only failure is the
unrelated `:whisper` CMake/NDK module, which is not a regression from this change).

## New tests (ran + passed, all three flavors — safe / personal / lab)

| Test class | tests | failures | errors |
| --- | --- | --- | --- |
| `CarImeCapabilityTest` | 5 | 0 | 0 |
| `CarImeTargetRoutingTest` | 10 | 0 | 0 |
| `CarImeBufferTest` | 6 | 0 | 0 |

- `CarImeCapabilityTest` — SDK gate (<15 blocked, 15+ boundary, 16 passes) AND hardware-mode
  requirement; legacy-canvas never passes. Inconclusive→canvas is modeled by `ImeProbeState`.
- `CarImeTargetRoutingTest` — the `routeImeEdit` result the renderer dispatches on (finding 3): FIELD
  edit → MirrorLive(trimmed), FIELD backspace-to-empty → MirrorLive(""), ADDRESS edit → Ignore,
  ADDRESS Go non-blank → CommitAddress, ADDRESS Go blank → Ignore, FIELD Go → CommitFieldSubmit; plus
  the `isKeyboardOpen` OR truth table and `shouldCloseImeOnInsets` (direct-Gboard-dismiss detection).
- `CarImeBufferTest` — `applyImeEdit`: ADDRESS first Commit replaces the select-all seed + collapses,
  second Commit appends; FIELD DeleteBefore to empty; Thai multi-char commits append verbatim; word
  replacement inserts at the collapsed cursor; DeleteBefore(100) never underflows past empty.

## Regression tests (canvas route untouched — still pass, personal flavor shown)

`BrowserKeyboardTest` (14), `CarKeyboardGesturesTest` (24), `CarKeyboardInputRoutingTest` (7),
`CarKeyboardLayoutTest` (6), `CarKeyboardMirrorTest` (6), `CarWebRendererJsEscapeTest` (8) — all 0
failures / 0 errors.

## Device-only (not JVM-testable, by design)

Whether `showSoftInput` actually raises Gboard over the projection and `WindowInsets.Type.ime()`
turns visible on an Android 15 head unit. The lazy probe + inconclusive-is-canvas design makes every
unverifiable path fall back safely to the canvas keyboard, so no JVM test is possible or required.

## What is build/test-verified vs. what needs a device

**Build/test-verified (JVM, all three flavors — safe / personal / lab):**

- The capability gate: `<15` blocked, `15+` boundary passes, hardware-mode required, legacy-canvas
  never passes (`CarImeCapabilityTest`).
- The pure routing the renderer dispatches on — FIELD edit → mirror-live, FIELD backspace-to-empty →
  mirror `""`, ADDRESS edit → ignore, ADDRESS Go → resolve+load, ADDRESS blank Go → ignore, FIELD Go
  → auto-submit; the `isKeyboardOpen` OR truth table; the `shouldCloseImeOnInsets` dismiss rule
  (`CarImeTargetRoutingTest`).
- The IME↔buffer reconciliation: EN + ไทย commits append verbatim, ADDRESS select-all seed replaced
  on first commit, delete-before never underflows past empty (`CarImeBufferTest`).
- Compilation of the whole IME + fallback wiring for 15+ and <15 in a single build (no flavor/setting
  gate; `compileSdk 36 / minSdk 29`).
- Every pre-existing canvas-keyboard JVM suite still passes (`BrowserKeyboardTest`,
  `CarKeyboardGesturesTest`, `CarKeyboardInputRoutingTest`, `CarKeyboardLayoutTest`,
  `CarKeyboardMirrorTest`, `CarWebRendererJsEscapeTest`).

**NOT verifiable on the JVM — requires a real head unit / DHU (see checklist below):** whether
`showSoftInput` raises the real IME over the projected virtual display, whether `WindowInsets.Type.
ime()` turns visible and reports a usable height on the target host, and the actual on-screen behavior
of Gboard, scroll-into-view, Back, and direct-dismiss. By design every path that cannot confirm the
IME falls back to the canvas keyboard, so the failure mode of an unverified host is "canvas keyboard,"
never "no keyboard."

## MANUAL DHU / device checklist

Run these on hardware. For each item, the pass criterion is stated; a fail means the IME route is
behaving incorrectly (not merely "fell back to canvas" — a safe fallback is still a pass for the
fallback cases b/c/d).

### (a) Android 15+ real head unit — the Gboard route

Preconditions: a physical Android Auto head unit (or a phone/DHU combo running the projection) whose
host is Android 15 (SDK 35) or newer, HARDWARE render mode active, a Gboard (or other system IME)
installed and enabled.

1. Open the car browser, tap the **address bar**. Expect: the real system keyboard (Gboard) rises
   over the projection within ~1 beat (first open may take up to 700 ms for the one-time probe); the
   canvas tray does **not** appear.
2. Type a URL in **English**, press **Go**. Expect: the typed text resolves/loads as a URL or search
   (same as the canvas address commit); the IME closes.
3. Open the address bar again, switch Gboard to **Thai (ไทย)**, type Thai characters. Expect: each
   character lands in the address field verbatim (nothing stranded in a candidate strip); Go resolves
   it.
4. Navigate to a page with a **text input field**, tap the field. Expect: Gboard rises; typing
   mirrors live into the page field as you type (EN and ไทย both); the focused field scrolls to sit
   above Gboard.
5. In a page field, type then **backspace to empty**. Expect: the field clears completely (the mirror
   reaches `""`); no leftover characters.
6. Press **Go / the IME action** in a page field. Expect: the field submits (auto-submit), the IME
   closes.
7. Confirm **exactly one keyboard** is ever on screen: at no point do both Gboard and the canvas tray
   appear together. Re-tap the address bar while Gboard is already up — nothing double-opens.

### (b) Pre-15 host or DHU without a usable IME — automatic canvas fallback

Preconditions: a head unit/DHU whose host is Android 14 or earlier, OR a 15+ host where the IME never
becomes visible over the virtual display.

1. Open the browser, tap the address bar. Expect: the **canvas keyboard** appears automatically (no
   Gboard). On a pre-15 host the SDK gate blocks the IME route outright; on a 15+ host that can't show
   the IME, the ≤700 ms probe times out and the canvas tray opens seeded with the same text.
2. Exercise the full prior canvas-keyboard behavior: EN + ไทย typing, address commit on Go, field
   live-mirror, backspace-to-empty, double-tap-a-key replay, scroll-into-view, car-Back to close.
   Expect: all behave exactly as before this feature.

### (c) Inconclusive / denied IME probe — still a working canvas keyboard

Preconditions: a 15+ hardware host, but force an inconclusive probe (no IMM available, IME disabled,
`showSoftInput` refused, or insets never report visible).

1. Tap the address bar. Expect: after at most 700 ms the app falls back to the canvas keyboard seeded
   with the current text; the user is never left without a keyboard. Repeat opens go straight to the
   canvas keyboard (the probe result is cached UNAVAILABLE for the session).

### (d) Direct Gboard dismissal — clean IME teardown + car-Back state

Preconditions: the Gboard route is live (case a).

1. With Gboard up, **swipe/dismiss the keyboard directly** (the system keyboard's own dismiss, not
   car-Back). Expect: the IME session closes cleanly — the editor is detached, insets listener
   removed, `onKeyboardChanged(false)` fires so car-Back returns to its normal (non-keyboard) state;
   no orphaned editor, no stuck "keyboard open" state.
2. After the direct dismiss, press **car-Back**. Expect: it acts on the page/chrome as normal (it is
   no longer consumed as "close the keyboard").
3. With Gboard up, press **car-Back** instead. Expect: the IME closes (routed through
   `closeImeKeyboard`), same end state as the direct dismiss.

## Design-review findings — all six implemented

1. (HIGH) `anyKeyboardOpen` added and used in `offerFieldInput`'s early-return + inner re-check, and
   at the `stepFling` (fling), `scaleBy` (zoom fall-through), and `fabVisible` gates.
   `onSurfaceClick`'s `keyboard?.let` guard left on `keyboard` by design.
2. (MEDIUM) `keepFocusedFieldAboveKeyboard(occludedTop: Float?)` reparameterized; canvas passes
   `tray.top`, IME passes `surfaceHeight - ime().bottom`, null/zero insets skip the scroll; the
   `keyboard ?: return` no-op removed.
3. (MEDIUM) Single `CarImeRoute` type + pure `routeImeEdit`; renderer `dispatchImeRoute` executes the
   mapper result (MirrorLive→submitText(false), CommitAddress→load(resolve/sentinel),
   CommitFieldSubmit→submitText(true), Ignore→{}); no inline reimplementation.
4. (MEDIUM) Eager probe dropped; only the lazy probe runs (one-time ≤`IME_PROBE_TIMEOUT_MS`=700ms on
   first keyboard open). No Gboard raise at surface-attach.
5. (NIT) Probe maps null rootWindowInsets → not-visible → UNAVAILABLE (inconclusive = safe canvas);
   no `?: shown` remembered-flag fallback.
6. (NIT) `deleteSurroundingText(after)` dropped; the pre-first-commit selected ADDRESS state
   (selectAll=true) is handled via `applyImeEdit` (Commit(empty) / delete-before).

## Review iteration (2nd) — blocking findings fixed

Gate re-run from the worktree root:

```
./gradlew :app:testSafeDebugUnitTest :app:testPersonalDebugUnitTest :app:testLabDebugUnitTest
```

Result: **BUILD SUCCESSFUL in 9s**, 147 actionable tasks. All three Kotlin graphs recompiled
(`compileSafeDebugKotlin`/`compilePersonalDebugKotlin`/`compileLabDebugKotlin`) and all JVM unit
suites (`testSafeDebugUnitTest`/`testPersonalDebugUnitTest`/`testLabDebugUnitTest`) passed.

Findings from `review.json` (reviewDoc: `review-integration.md`, `review-logic.md`):

1. (BLOCKING, integration) **Chrome address-tap re-entrancy breaks the single-keyboard invariant** —
   in the IME-only state a tap on `ChromeZone.ADDRESS` fell through `onSurfaceClick`'s canvas-only
   guard into `openAddressKeyboard() → openKeyboardFor → openImeKeyboard` with a live session up.
   FIX: added `if (anyKeyboardOpen) return` at the top of `openKeyboardFor` (CarWebRenderer.kt), the
   single branch point every canvas/IME open funnels through — so no re-entry can double-open.

2. (BLOCKING, integration) **`openImeKeyboard` orphans the previous editor on re-entry** —
   overwriting `imeSession`/`imeInputView` left the prior `CarImeInputView` attached to
   `contentLayer` with its decor insets listener dropped. FIX: added `if (imeSession != null) return
   true` as defense-in-depth at the top of `openImeKeyboard`, so a bypassing caller can never
   overwrite a live editor reference without teardown.

3. (NON-BLOCKING, integration, "possible") **Scroll not swallowed on the IME route** — `scrollBy`
   gated only on `keyboardTray()` (null in the IME-only state), letting a scroll move the page under
   a live Gboard. FIX (behavior-aligning, matches the canvas route + the fling/zoom `anyKeyboardOpen`
   gates): added `anyKeyboardOpen ||` to the `scrollBy` swallow guard.

Logic-review findings (all non-blocking, "no action required"): synchronous insets read on AVAILABLE
open (self-corrects on next insets callback), probe-in-flight blank window ≤700ms (acceptable),
whitespace-only ADDRESS Go parity nuance (unreachable — text is trimmed upstream). No code changes.
