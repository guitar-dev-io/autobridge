# Single-keyboard invariant and occludedTop reparameterization for the Gboard-over-projection route

This commit (`69c30fb`, "fix: enforce single-keyboard invariant on IME re-entry") is the second
integration-review iteration of the system-keyboard feature. It closes the two blocking findings and
one non-blocking finding the prior integration pass raised, touching only `CarWebRenderer.kt`
(13 insertions, 1 deletion). It adds an `anyKeyboardOpen` early-return at the single open branch point
(`openKeyboardFor`), a defense-in-depth session guard in `openImeKeyboard`, and extends the `scrollBy`
swallow to the IME-only state. I reviewed the full feature's integration surface — the single-keyboard
invariant across every `keyboard`-field gate, the `occludedTop` reparameterization of
`keepFocusedFieldAboveKeyboard`, the add-a-view-inside-the-existing-Presentation approach, reuse of the
existing routing plumbing, direct-Gboard-dismiss cleanup, and regression risk to the canvas route — by
reading the live code, not just the diff.

Watch for: nothing blocking. The single-keyboard invariant is now closed at the one funnel every
open passes through (`openKeyboardFor`), the orphan-editor re-entry is blocked (confirmed), and the
IME-only scroll leak is closed (confirmed). One pre-existing non-blocking nuance carried over from the
logic review (synchronous insets read on an `AVAILABLE` open) remains out of scope for this commit and
self-corrects on the next insets callback.

**Verdict**: APPROVED

## High-level view

The single-keyboard invariant is now enforced at the structural choke point rather than scattered
across callers. Every keyboard open — canvas or IME, address or field, chrome tap or page-field focus —
funnels through `openKeyboardFor`, and the new `if (anyKeyboardOpen) return` at its top means no second
keyboard can open while either a canvas tray or a live IME session is up. This is the correct fix for
the prior blocking finding, where a chrome ADDRESS tap in the IME-only state fell through
`onSurfaceClick`'s canvas-only `keyboard?.let` guard (which by design stays on `keyboard`, not
`keyboardOpen`) into `openAddressKeyboard → openKeyboardFor`. `openImeKeyboard`'s own
`if (imeSession != null) return true` is a second, independent latch so a caller that ever bypasses the
funnel still cannot overwrite a live `imeSession`/`imeInputView` pair and orphan the attached editor.

The fling, zoom, and FAB gates all read the same `anyKeyboardOpen` predicate, so the "no page motion
under a keyboard" rule holds identically for the canvas tray and for Gboard. The one hole the prior
pass found was `scrollBy`, which gated only on `keyboardTray()` — null in the IME-only state — and is
now closed with an `anyKeyboardOpen ||` prefix on the swallow guard. Scroll, fling, zoom, and FAB are
now symmetric across both routes.

`keepFocusedFieldAboveKeyboard` takes a nullable `occludedTop: Float?` in surface-pixel-Y space; the
canvas route passes `canvasOccludedTop()` (tray top) and the IME route passes `imeOccludedTop()`
(`surfaceHeight - ime().bottom`). Both helpers return null when there is nothing to scroll above (no
tray, or null/zero IME insets), and the function early-returns on null, so a missing inset is a
non-fatal skipped scroll rather than a bad scroll.

The add-a-view approach is honored: this commit adds no presentation route, and the editor remains a
1×1 child of the existing `CarHardwareWebWindow.contentLayer` inside the single `Presentation`. The
text transport is the existing plumbing unchanged — `routeImeEdit` → `dispatchImeRoute` →
`submitText`/`load`/`CarKeyboardMirror`. Direct Gboard dismissal is handled by the session-lifetime
insets listener routing to `closeImeKeyboard`, and `resetImeState` covers the LEGACY_CANVAS fallback
and window recreate/dismiss paths, so no half-open state survives a mode drop or surface churn.

<details>
<summary>Issues (1)</summary>

1. **Synchronous insets read on AVAILABLE open (carried over, non-blocking, out of scope)** — on a
   cached-`AVAILABLE` open the field-scroll reads `ime().bottom` before Gboard has animated in, so the
   first `keepFocusedFieldAboveKeyboard` may see zero insets and skip; it self-corrects on the next
   insets callback via `onImeVisibilityChanged`. Already dispositioned "no action required" by the
   logic review; not introduced or altered by this commit. No action.

</details>

<details>
<summary>Details</summary>

### Single-keyboard invariant: the funnel guard and the re-entry it closes

`anyKeyboardOpen` is `keyboard != null || imeSession != null` (line 451), the exact same expression as
the public `keyboardOpen` (line 453). Every open path converges on `openKeyboardFor`:
`openKeyboard()` → `openAddressKeyboard()`/`openFieldKeyboard()` → `openKeyboardFor`, and
`offerFieldInput` → `openFieldKeyboard` → `openKeyboardFor`. The new `if (anyKeyboardOpen) return`
(line 1955) sits above the gate evaluation, so no open — canvas or IME — proceeds while either keyboard
is live.

The concrete re-entry this closes: in the IME-only state (`keyboard == null, imeSession != null`),
`onSurfaceClick`'s top guard is `keyboard?.let { ... }` (line 1506 region), which does not fire because
`keyboard` is null. The tap falls through to chrome hit-testing, and `ChromeZone.ADDRESS -> openAddressKeyboard()`
(line 1605) runs with a live IME session up. Before this commit that reached `openKeyboardFor` and
re-opened over Gboard; now the funnel guard returns early. I traced this path end to end in the live
code — the guard is correctly positioned to catch it. Leaving `onSurfaceClick`'s guard on `keyboard`
(not `keyboardOpen`) is intentional and correct: in the IME-only state Gboard owns its own touches on
the projection, so surface taps must fall through to the page, not into a canvas tray that isn't there.

`offerFieldInput` independently guards the reverse direction — a page field gaining focus while an IME
session is live — with `if (overlay != Overlay.NONE || anyKeyboardOpen) return` plus an inner
`!anyKeyboardOpen` re-check after the async `readFocusedField` callback (lines 1875, 1877). The inner
re-check matters because focus can resolve a frame later; both are present. So neither direction can
double-open: no canvas keyboard over a live IME, and no IME over a live canvas keyboard.

### openImeKeyboard's orphan-editor latch

`openImeKeyboard` gets `if (imeSession != null) return true` (line 1994) right after it resolves the
window. Returning `true` (not `false`) is correct: `true` means "an IME is already handling this," so
the caller does not fall through to `showKeyboard(CarSurfaceKeyboard(...))` and stack a canvas tray on
top. Had it returned `false`, a bypassing caller would drop to the canvas path and reproduce the
double-open this is meant to prevent. The comment is honest that this is redundant with the
`openKeyboardFor` funnel guard — it is defense-in-depth against a future caller that reaches
`openImeKeyboard` by another route — and the cost it prevents is real: overwriting `imeSession`/
`imeInputView` would leave the prior `CarImeInputView` attached to `contentLayer` with its decor insets
listener silently replaced, leaking a view and a focus owner. Both latches are cheap and independent;
keeping both is the right call.

### Fling / zoom / FAB / scroll gate symmetry

The four page-motion gates now read `anyKeyboardOpen` uniformly:

- `stepFling()` (line 2445): `if (anyKeyboardOpen) { stopFling(); return false }` — no glide under
  either keyboard.
- `scaleBy()` (line 2506): after the canvas double-tap-replay branch (which is keyed on the canvas
  `board`/`tray` and is inherently inert in the IME-only state), `if (anyKeyboardOpen) return@runOnMain`
  blocks page zoom under Gboard.
- `fabVisible()` (line 2566): `overlay == Overlay.NONE && !anyKeyboardOpen && ...` — the FAB is not
  drawn or hit-tested while either keyboard is up.
- `scrollBy()` (line 2396): the swallow guard is now `anyKeyboardOpen || !CarKeyboardGestures.pageReceives(...)`.
  This is the prior pass's non-blocking finding. `CarKeyboardGestures.pageReceives(..., keyboardTray())`
  returns `keyboardTray()`-based occlusion, and `keyboardTray()` is null in the IME-only state, so
  without the `anyKeyboardOpen ||` prefix a scroll would move the page under a live Gboard. The prefix
  closes it and matches the fling/zoom treatment exactly. The canvas path is unchanged — when a tray
  exists, `pageReceives` still decides, and `anyKeyboardOpen` is also true, so the guard is a strict
  superset of the old behavior and cannot regress the canvas route.

### keepFocusedFieldAboveKeyboard occludedTop reparameterization

The signature is `keepFocusedFieldAboveKeyboard(occludedTop: Float?)` (line 2326), documented as the
surface-pixel-Y of the top edge of whatever covers the bottom of the page. Both call sites and both
producers are in surface-pixel-Y space:

- Canvas: `canvasOccludedTop() = keyboard?.geometry(keyboardArea(), sizes.dp(1f))?.tray?.top` — the tray
  top in surface pixels, same geometry `drawChrome`/`keyboardTray()` use. Null when no tray.
- IME: `imeOccludedTop()` reads `ime().bottom` from `windowView.rootWindowInsets`, returns null on null
  insets, returns null when `imeBottom <= 0`, else `surfaceHeight - imeBottom`. Converting the IME
  bottom inset into a surface-Y top edge is the correct transform, and the `<= 0` guard is what makes
  "zero insets skip the scroll" hold as the task requires.

The function early-returns on `occludedTop == null` (line 2329 region), so both the no-tray and
null/zero-insets cases skip the scroll non-fatally rather than computing against a bogus value. The
prior `keyboard ?: return` no-op that the design called out for removal is gone — the function no
longer references `keyboard` at all, so it works on the IME route where `keyboard` is null. Call sites:
`openKeyboardFor` passes `canvasOccludedTop()` on the canvas branch (line 1969); `openImeKeyboard`
passes `imeOccludedTop()` (line 2024); `resolveImeProbe` passes `imeOccludedTop()` on probe-success and
`canvasOccludedTop()` on the canvas fallback; `onImeVisibilityChanged` passes `imeOccludedTop()` when
insets turn visible. Every call is in the same coordinate space the parameter documents.

### Add-a-view-inside-the-existing-Presentation is honored

This commit adds no presentation route — the diff is entirely within `CarWebRenderer.kt` and touches
no window/display/Presentation construction. `CarHardwareWebWindow` still constructs exactly one
`Presentation` (line 271), and the IME editor is attached as a 1×1 child of the existing `contentLayer`
via `attachImeInput`/`detachImeInput` (lines 152/159), which live inside the class that owns the
window. `windowView` is the existing decor-view accessor. No second overlay window, no Activity route,
no `createVirtualDisplay` beyond the one already there. The approach the design committed to is intact.

### Reuse of the existing routing plumbing

The IME edit path dispatches through one mapper result: `onImeCommit`/`onImeDeleteBefore` fold the edit
into the buffer via `applyImeEdit` then call `dispatchImeRoute(routeImeEdit(target, buffer.text, kind))`;
`onImeAction` routes with `ImeKeyKind.GO` then `closeImeKeyboard()`. `dispatchImeRoute` (line 2127)
maps `MirrorLive → submitText(text, autoSubmit=false)`, `CommitAddress → load(sentinel-or-resolveTyped)`,
`CommitFieldSubmit → submitText(text, autoSubmit=true)`, `Ignore → {}`. These are the same
`submitText`, `load`, `BrowserInputResolver.resolveTyped`, `SearchEngineStore.engine`, and
`WebVideoDiagnostics.isSentinel` helpers the canvas `commitKeyboard`/live-mirror path uses. There is no
parallel inline reimplementation — the renderer dispatches on the single `routeImeEdit` result that
`CarImeTargetRoutingTest` pins, so the live path and the tested path are the same path. `keyboardOpen`
remaining the OR expression means the car-Back toggle (`CarBrowserScreen` → `closeKeyboard()`) and the
debug strings read the combined state, and `closeKeyboard()` routes to `closeImeKeyboard()` when
`imeSession != null` (line 2199), so the toggle and car-Back close whichever keyboard is up through one
contract.

### Direct-Gboard-dismiss cleanup and car-Back restoration

`CarImeInputView` registers an `OnApplyWindowInsetsListener` on the window decor view for the life of
the attached view (`observeImeInsets`/`stopObservingImeInsets`), reporting `ime()` visibility to
`onImeVisibilityChanged`. When the user dismisses Gboard with its own chevron, insets drop to
not-visible; with `imeSession != null && !imeProbing`, `shouldCloseImeOnInsets(true, false)` is true and
`closeImeKeyboard()` runs. `closeImeKeyboard` tears down the editor (stops insets observation, hides the
IME, detaches the view), clears `imeSession`/`imeInputView`, and — because `hadSession` was true —
fires `onKeyboardChanged(false)`, which disables car-Back and flips `keyboardOpen` back to false. So a
direct dismissal cannot leave `imeSession` non-null, car-Back stuck enabled, or `keyboardOpen` stuck
true. The same listener resolves the lazy probe (`imeProbing && visible → resolveImeProbe(available=true)`),
and the probe timeout (`imeProbeTimeout`, 700ms) resolves `UNAVAILABLE` and falls back to the canvas
keyboard, so no probe path strands the session either.

### No regression to the prior canvas-keyboard behavior

The canvas route is reached by the same `openKeyboardFor` fall-through (`showKeyboard(CarSurfaceKeyboard(...))`)
and its tap/mirror/commit handling (`handleKeyboardTap`, `CarKeyboardMirror.liveWrite`, `commitKeyboard`)
is untouched by this commit. The three changed guards are all strict supersets on the canvas side:
`openKeyboardFor`'s new guard only fires when a keyboard is already open (the canvas route never opened a
second tray over an existing one anyway); `scrollBy`'s guard adds `anyKeyboardOpen ||` in front of the
existing canvas-tray check, which is already true whenever a canvas tray is up; the fling/zoom/FAB gates
already used `anyKeyboardOpen` in the prior iteration. `resetImeState` is invoked only on LEGACY_CANVAS
fallback and window recreate/dismiss — paths where the IME route is void — and resets to `UNKNOWN`
without touching canvas state. I found no path where this commit changes what the canvas keyboard does.

### Test evidence (read, not re-run, per instruction)

`verification.md` records the gate `./gradlew :app:testSafeDebugUnitTest :app:testPersonalDebugUnitTest
:app:testLabDebugUnitTest` → BUILD SUCCESSFUL, all three Kotlin graphs compiled, all JVM suites passed,
on the second iteration (9s). The invariant predicates this commit relies on are pinned by pure tests:
`isKeyboardOpen` OR truth table and `shouldCloseImeOnInsets` dismiss detection in
`CarImeTargetRoutingTest`; the routing parity in the same suite; `applyImeEdit` in `CarImeBufferTest`;
the gate in `CarImeCapabilityTest`. Canvas-route regression suites (`BrowserKeyboardTest`,
`CarKeyboardGesturesTest`, `CarKeyboardInputRoutingTest`, `CarKeyboardLayoutTest`,
`CarKeyboardMirrorTest`, `CarWebRendererJsEscapeTest`) are recorded passing. Not tested on the JVM, by
design and documented: whether `showSoftInput` actually raises Gboard and `ime()` turns visible on a
real Android 15 head unit — the whole reason for the runtime probe and the inconclusive-is-canvas
fallback. The `anyKeyboardOpen` scroll/fling/zoom gate behavior and the `occludedTop` wiring are
device-observable but logically covered by the pure `isKeyboardOpen` predicate test; the evidence is
present and adequate for the integration concerns in scope.

</details>

<details>
<summary>File map</summary>

- `app/src/main/java/dev/autobridge/browser/CarWebRenderer.kt` — the only file in this commit:
  `anyKeyboardOpen` early-return added to `openKeyboardFor`; `imeSession != null` early-return added to
  `openImeKeyboard`; `scrollBy` swallow guard extended with `anyKeyboardOpen ||`.

Full diff: `git show 69c30fb` in the `car-system-keyboard` worktree.

</details>
