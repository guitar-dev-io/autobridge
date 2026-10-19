# Single-keyboard invariant on IME re-entry — correctness & edge-case review

The commit under review (`69c30fb`) closes the two blocking integration findings and the one
non-blocking scroll finding from the prior pass on the Gboard-over-projection feature: a chrome
ADDRESS tap could re-enter `openKeyboardFor` over a live IME session, `openImeKeyboard` could orphan
the prior editor on re-entry, and `scrollBy` did not swallow the gesture on the IME-only route. The
fix adds an `anyKeyboardOpen` early-return at the single open branch point, a defense-in-depth
`imeSession != null` guard inside `openImeKeyboard`, and an `anyKeyboardOpen ||` clause on the scroll
swallow gate. I re-read the executed paths plus the pure helpers and buffer model that findings
3/4/5/6 depend on, and read back the recorded test evidence rather than re-running the suites.

Watch for: nothing blocking. The three fixes are correct and land exactly on the re-entrancy and
gesture gaps the prior pass flagged (confirmed). The earlier logic-review non-blocking notes
(synchronous insets read on AVAILABLE open, ≤700ms probe-in-flight blank window, whitespace-only
ADDRESS Go parity) remain accurate and remain non-blocking; none were in scope for this commit and
none regressed (confirmed).

**Verdict**: APPROVED

## High-level view

The single-keyboard invariant now has one real chokepoint and one backstop. `openKeyboardFor` is the
sole branch point every canvas/IME open funnels through, and its new `if (anyKeyboardOpen) return`
closes the one path the prior pass found open: a chrome ADDRESS tap in the IME-only state that
slipped past `onSurfaceClick`'s canvas-only guard. The `if (imeSession != null) return true` inside
`openImeKeyboard` is a true backstop — unreachable through the guarded funnel but correct if a future
caller bypasses it, and it returns `true` so a bypassing caller treats the open as handled rather
than falling through to a second canvas keyboard.

The scroll fix is sound for the asymmetry it targets: `CarKeyboardGestures.pageReceives` keys purely
on the canvas tray (`tray == null` → page receives), so in the IME-only state (no tray) the page
would have scrolled under Gboard. Prepending `anyKeyboardOpen ||` swallows the scroll under either
keyboard, matching the fling/zoom/FAB gates that already switched to `anyKeyboardOpen`.

The reconciliation and routing surface findings 3/5/6 cover was not touched by this commit and
remains as the prior logic pass verified: `applyImeEdit` folds Commit into `buffer.insert`
(selection-replace, so ADDRESS select-all is replaced then appends) and DeleteBefore into N
surrogate-safe `backspace()`s that never underflow; `routeImeEdit` returns the single
`MirrorLive/CommitAddress/CommitFieldSubmit/Ignore` result the renderer dispatches on directly. The
SDK gate (finding 4/5) stays `sdkInt >= 35 && hardwareMode` with inconclusive-is-canvas on every
probe branch. The three JVM suites guard these executed helpers, not parallel copies.

<details>
<summary>Issues (0 blocking)</summary>

No blocking issues. The three non-blocking logic notes from the prior pass are unchanged and were
not in scope for this commit:

1. **Synchronous insets read on AVAILABLE open** — `openImeKeyboard(probing=false)` reads
   `imeOccludedTop()` right after `showSoftInput`, before `ime()` insets apply, so the first
   scroll-into-view is skipped. Self-corrects via the session-lifetime insets listener. No action.
   (confirmed)
2. **Probe-in-flight blank window ≤700ms** — during the lazy probe `imeSession` is non-null and
   `onKeyboardChanged(true)` has fired while nothing is drawn; Back/toggle route to
   `closeImeKeyboard` and a resolved-UNAVAILABLE rebuilds the canvas keyboard. Acceptable. No action.
   (confirmed)
3. **Whitespace-only ADDRESS Go parity nuance** — `routeImeEdit` ADDRESS Go uses `isNotBlank()`
   while canvas `commitKeyboard` guards on `isEmpty()`; both receive trimmed text upstream, so the
   divergence is unreachable. No action. (possible)

</details>

<details>
<summary>Details</summary>

### Re-entrancy guard at the single open branch point

`openKeyboardFor` is the one method every open funnels through (`openAddressKeyboard` and
`openFieldKeyboard` both call it), and the new `if (anyKeyboardOpen) return` sits at its top before
the gate is even consulted. `anyKeyboardOpen` is `keyboard != null || imeSession != null`, so a
second open is refused whether the live keyboard is canvas or IME. The flagged path was a chrome
ADDRESS tap in the IME-only state: `onSurfaceClick`'s guard is `keyboard?.let`, which is null on the
IME route, so the tap falls through to chrome hit-testing and reaches `openAddressKeyboard`. That
now terminates at the funnel guard instead of double-opening. Leaving `onSurfaceClick` keyed on the
canvas `keyboard` is still correct — on the IME route Gboard owns its own touches and surface taps
should fall through to page handling; the funnel guard is what prevents the re-open.

The `if (imeSession != null) return true` inside `openImeKeyboard` is defense-in-depth. Through the
guarded funnel it is unreachable (the outer `anyKeyboardOpen` return fires first), but if a caller
ever reaches `openImeKeyboard` with a live session it prevents overwriting `imeSession`/`imeInputView`
— which would strand the prior `CarImeInputView` on `contentLayer` and drop its decor insets
listener (the orphan the prior pass flagged). Returning `true` rather than `false` is the right
choice: `openKeyboardFor` treats a `true` return as "IME handled it" and does not fall through to
`showKeyboard(CarSurfaceKeyboard(...))`, so the backstop cannot itself produce a second (canvas)
keyboard on top of the live IME. The error path later in the same method does the inverse — on a
`RuntimeException` from `attachImeInput`/`showSoftInput` it tears the editor down, nulls the session,
caches `UNAVAILABLE`, and returns `false` so the canvas keyboard opens.

### Scroll swallow on the IME-only route

`CarKeyboardGestures.pageReceives(gesture, x, y, tray)` is `tray == null` — it only knows about the
canvas tray geometry. On the IME route `keyboardTray()` returns null (it reads `keyboard?.geometry`,
and `keyboard` is null), so `pageReceives` returns `true` and the old guard `!pageReceives(...)`
evaluated to `false` — the scroll was applied and the page moved under Gboard. Prepending
`anyKeyboardOpen ||` makes the guard swallow the scroll whenever any keyboard is up, independent of
tray geometry. This matches the sibling gates: `stepFling`/`scaleBy`/`fabVisible` all switched to
`anyKeyboardOpen` in the feature, so scroll was the one remaining gesture still keyed only on the
tray. The canvas route is unaffected — when a canvas tray exists, `anyKeyboardOpen` is already true,
so the OR short-circuits to the same swallow the tray check produced before.

### Reconciliation and routing (findings 3, 5, 6) — unchanged, re-verified

Not touched by this commit; re-read to confirm the fixes did not disturb them. `applyImeEdit` is the
whole reconciliation surface: `Commit` → `buffer.insert`, which replaces `[min(selStart,selEnd),
max(selStart,selEnd))` and collapses the cursor after the inserted text; `DeleteBefore(n)` →
`repeat(n){ buffer.backspace() }`. I read `BrowserTextBuffer` to confirm: `insert` substrings around
the selection and `collapseTo(start + value.length)`; `backspace` calls `insert("")` when
`hasSelection`, else deletes one code point via `offsetByCodePoints(cursor, -1)` and guards
`cursor == 0`, so it never underflows. For the pre-first-commit ADDRESS select-all state (finding 6),
`selectionStart = 0, selectionEnd = len`, so a first `Commit` replaces the whole URL and a
`DeleteBefore` hits `hasSelection` → `insert("")` → clears the field; both are the authoritative
behavior, and `CarImeBufferTest` pins the first-commit-replace, second-commit-append,
backspace-to-empty, Thai-append, and DeleteBefore(100)-no-underflow cases.

`routeImeEdit` returns one of `MirrorLive/CommitAddress/CommitFieldSubmit/Ignore`, and the renderer
executes it via `dispatchImeRoute(routeImeEdit(...))` in all of `onImeCommit`, `onImeDeleteBefore`,
and `onImeAction` with no inline re-derivation — so `CarImeTargetRoutingTest`'s six routing cases
guard the live dispatch. The EDIT case delegates to `CarKeyboardMirror.liveWrite(pageMutated = true,
target, bufferText)`, the exact canvas decision, so FIELD mirrors the trimmed buffer (including `""`
for backspace-to-empty) and ADDRESS never mirrors. `dispatchImeRoute`'s `CommitAddress` resolves via
`BrowserInputResolver.resolveTyped` + the sentinel check then `load`, matching canvas
`commitKeyboard`'s ADDRESS arm; `CommitFieldSubmit` calls `submitText(text, autoSubmit = true)`.

### Capability gate + probe (findings 4, 5) — unchanged, re-verified

`CarImeCapability.gate` is `sdkInt >= 35 && hardwareMode` (literal 35 with a `VANILLA_ICE_CREAM`
comment), and `openKeyboardFor` additionally requires `hardwareWindow != null` before consulting the
probe. `CarImeCapabilityTest` pins 34→false, 35→true, 36→true, and `hardwareMode=false`→false on
both 34 and 35 — the <15 / 15+ split the user asked for. The probe is the device-only half; every
inconclusive outcome (null insets, timeout, missing IMM, no window, exception) resolves to
`UNAVAILABLE` and opens the canvas keyboard. `resolveImeProbe` is single-resolve (guards
`if (!imeProbing) return`), and on UNAVAILABLE it tears the editor down, fires
`onKeyboardChanged(false)`, and reopens the canvas keyboard seeded with `session.buffer.text`, so the
user always has a keyboard.

### Test evidence

verification.md records `./gradlew :app:testSafeDebugUnitTest :app:testPersonalDebugUnitTest
:app:testLabDebugUnitTest` → BUILD SUCCESSFUL (9s on the re-run after this commit's fixes, 147
actionable tasks), with all three Kotlin graphs recompiled and all JVM suites green, and the commit
message repeats the same gate result. The three IME suites (`CarImeCapabilityTest` 5,
`CarImeTargetRoutingTest` 10, `CarImeBufferTest` 6) plus the canvas-route regression suites
(`CarKeyboardGesturesTest`, `CarKeyboardMirrorTest`, `BrowserKeyboardTest`, etc.) are recorded
passing. The suites guard the executed pure helpers (`gate`, `applyImeEdit`, `routeImeEdit`,
`isKeyboardOpen`, `shouldCloseImeOnInsets`) that this commit's fixes feed into, so the recorded green
is meaningful for the invariant the fix enforces. The evidence left no articulable doubt requiring a
spot-check; I did not re-run the suites per the task instruction.

Not tested (device-only, by design): whether the chrome-tap re-entry, Gboard raise, and `ime()`
insets behave on a real Android 15 head unit. The guards added here are pure state checks on
`anyKeyboardOpen`/`imeSession`, exercised by `isKeyboardOpen`, so the JVM coverage is as complete as
it can be for this change; the window-driven parts stay behind the inconclusive-is-canvas fallback.

</details>

<details>
<summary>Files changed</summary>

- `CarWebRenderer.kt` — `openKeyboardFor` gains `if (anyKeyboardOpen) return`; `openImeKeyboard`
  gains `if (imeSession != null) return true`; `scrollBy` gains `anyKeyboardOpen ||` on the swallow
  guard.

Full diff: `git show 69c30fb` in the `car-system-keyboard` worktree.

</details>
