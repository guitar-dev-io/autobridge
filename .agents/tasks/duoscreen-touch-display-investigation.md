# Duo Screen — Touch Routing & Display Selection Investigation

Read-only investigation. No code was modified. All findings are grounded in the
`app/src/duoscreen/.../*.kt` sources and `ROADMAP.md`.

Context update incorporated from the orchestrator: the screenshots were taken on the
**DHU (Desktop Head Unit)**, not a real head unit. The split layout **renders correctly**
(left pane = calculator, right pane = Google Maps), so the compositor and display
composition work. The remaining symptom is touch only: a red touch indicator appears on the
left pane where the user taps, but the hosted app's buttons do not react.

---

## Summary answer (read this first)

**Problem 1 — Touch does not go through: PLATFORM-BLOCKED (with one in-code risk worth ruling out first).**
The in-process chain that turns a host gesture into an injected `MotionEvent` is wired
correctly end to end. The break is at the last hop: injecting a pointer event into our
**own-content, untrusted VirtualDisplay** via the shell UID. On a standard device/DHU the
system silently drops (or refuses) input injected at a virtual display that the injector is
not trusted/privileged for. We deliberately create the pane displays **untrusted**
(`VIRTUAL_DISPLAY_FLAG_TRUSTED` needs `ADD_TRUSTED_DISPLAY`, which an app cannot hold — see
`DuoScreenDisplays`), and Shizuku's shell UID is not the display owner. The red dot the user
sees is the **DHU's own tap overlay on the projected surface** — our code draws no such dot —
so it only proves the tap reached our Screen surface, not that it was delivered to the pane's
display. Net: this is not fixable with app code on a standard head unit without a
privileged/trusted-display path.

**Problem 2 — Cannot select a display: FIXABLE in the narrow sense, but partly a feature gap.**
There is no "pick a physical display" feature in the code at all. "Selection" in Duo Screen
means two different things, neither of which is physical-display selection:
- **Phone settings** (`DuoScreenSettingsActivity`) lets you pick the **app per pane** and the
  **pane count (2–3)** — this works and is ordinary app code.
- **Edit mode on the car surface** (`DuoScreenInputRouter` EDIT) lets you **select a pane** by
  tapping it. This depends on the host delivering `onClick`, and on the mode toggle in the
  action strip. If "select" fails here it is the *same touch-delivery question* as Problem 1
  for the gesture reaching our router (the router/pane hit-test logic itself is correct and
  unit-testable). Physical display enumeration (`DisplayManager.getDisplays`) is **not used**;
  displays are auto-created VirtualDisplays, one per pane.

**Overall recommendation: feature-flag Duo Screen OFF for real head units / standard DHU until a
supported privileged (trusted-display) input path exists.** The rendering half is sound and
worth keeping behind the flag, but the core value proposition — interacting with apps running
in the panes — is blocked by the platform's cross-display input-injection restriction, which
`ROADMAP.md` already records as PARTIAL/BLOCKED.

---

## Evidence

### 1. Touch routing — the full chain, hop by hop

The intended path for a tap:

1. **Host → our Screen.** `DuoScreenScreen` implements `androidx.car.app.SurfaceCallback` and
   forwards the four gestures the host provides:
   `onClick`, `onScroll`, `onFling`, `onScale`
   (`DuoScreenScreen.onClick/onScroll/onFling/onScale` → `controller.*`). There is **no raw
   pointer / multi-touch stream** — the car app library only exposes these synthesized
   gestures.
2. **Controller → router.** `DuoScreenController.onClick(x,y)` → `router?.onClick(x,y)`.
3. **Router hit-test.** `DuoScreenInputRouter.onClick` finds the topmost pane under (x,y) via
   `panes.paneAt(x,y)`. In NORMAL mode it records `lastTouchedPaneId` and calls
   `port.forwardTap(paneId, x - pane.rect.left, y - pane.rect.top)` — i.e. it converts the
   surface-local coordinate into a **pane-local** coordinate. This math is correct and lives in
   pure, JVM-testable code (`DuoScreenPaneSet.paneAt`, `DuoScreenLayout.Rect.contains`).
4. **Controller → touch controller.** `DuoScreenController.forwardTap` maps `paneId` to the
   pane's VirtualDisplay id via `DuoScreenDisplays.displayId(paneId)` and calls
   `touch.tap(displayId, localX, localY)`.
5. **Touch controller builds the event.** `DuoScreenTouchController` keeps per-display pointer
   bookkeeping and builds a proper `DOWN`/`MOVE`/`UP` `MotionEvent` stream
   (`createEvent` sets `SOURCE_TOUCHSCREEN`, pointer coords, downTime, etc.), then calls
   `ops.injectMotion(event, displayId)`.
6. **Privileged injection.** `DuoScreenShizukuOps.injectMotion`:
   - resolves `IInputManager` reflectively through a `ShizukuBinderWrapper` so the transaction
     runs as the **shell UID** (`asShellInterface("input", ...)`),
   - calls `MotionEvent.setDisplayId(displayId)` reflectively (`setDisplayId`),
   - invokes hidden `injectInputEvent` / `injectInputEventToTarget` with mode
     `INJECT_INPUT_EVENT_MODE_WAIT_FOR_FINISH`.

**Where it breaks (steps 5–6 land, but the event does not take effect in the pane):**

- The pane display is created in `DuoScreenDisplays.create` with
  `FLAGS = FLAG_OWN_CONTENT_ONLY (1<<3) | FLAG_SUPPORTS_TOUCH (1<<6)` and **no TRUSTED flag**.
  The file's own KDoc states this plainly: `VIRTUAL_DISPLAY_FLAG_TRUSTED` requires
  `ADD_TRUSTED_DISPLAY`, which "is refused to an app process … confirmed on-device", so the
  display is "deliberately private + own-content and **not trusted**".
- Input injection targeted at a specific `displayId` through `IInputManager.injectInputEvent`
  is governed by the system's input/display security policy. Injecting into an **untrusted
  virtual display** that the injector does not own is restricted: the event is dropped or the
  window is treated as not-touchable from that source. The shell UID (via Shizuku) is not the
  display owner (our app process created it), and shell is not automatically trusted for
  arbitrary virtual displays.
- Consequently the `injectInputEvent` call can return without the pane's app ever receiving a
  dispatched event. `DuoScreenShizukuOps` already logs failures
  (`"injectInputEvent failed on display …"`), and `setDisplayId` logs
  `"MotionEvent.setDisplayId is unavailable; cannot target a display"` if that hidden method is
  missing — these logs are the place to confirm *which* sub-failure is occurring on the DHU.

**On the red dot / DHU specifics (from the screenshot context):**

- Our code draws **no** touch indicator anywhere. A grep across `app/src/duoscreen` for
  `drawCircle` / red-dot / pointer-icon logic finds nothing; the only red constant is the EGL
  config's `EGL_RED_SIZE` in `DuoScreenCompositor`. The red dot is therefore the **DHU's own
  input visualization** painted on the projected Android Auto surface.
- That tells us the chain is intact through **step 1–2** (the host delivered the tap to our
  Screen surface and our process received it). It says nothing about steps 5–6 succeeding on
  the target display. So the symptom "indicator shows, app does not react" matches exactly the
  cross-display injection block, not a missing/incorrect gesture callback.

**DHU vs. real head unit — how far to trust the DHU result:**

- `ROADMAP.md` lists host/vehicle validation as **BLOCKED/TODO** and explicitly says raw-touch
  landing and Shizuku binding "need device runs". It also notes a DHU/host-version
  incompatibility caveat (DHU 2.0 vs AA 17.7.x).
- The DHU proxies touches to the projected surface the same way a car host does, so the
  **upstream** half (host → our Screen → router) is representative: if gestures arrive on the
  DHU, they will arrive on a real unit. The **downstream** half (shell-UID injection into an
  untrusted virtual display) is a function of the *device's* framework policy where the app
  runs (the phone/emulator hosting AutoBridge), **not** of the DHU. So the injection failure
  reproduces off the device's own input policy and is a reliable negative signal: it is very
  unlikely to behave *better* on a production head unit, and `ROADMAP.md` already predicts this.
- Bottom line: a DHU **success** for rendering is trustworthy and matches the screenshot. A DHU
  **failure** for cross-display injection is also trustworthy as a lower bound — it will not be
  more permissive on a locked-down OEM unit.

### 2. Touch — one thing to rule out before declaring pure platform-block

The chain is correct, but two in-code preconditions must hold for injection to even be
attempted; if either is false the symptom is identical (dot shows, nothing happens) yet the
cause is partly our side:

- **Shizuku must be granted.** `DuoScreenShizukuOps.isAvailable` delegates to
  `ShizukuInputBackend.isPermissionGranted`. If not granted, `injectMotion` returns `false`
  immediately and `openPane` also skips launching the app
  (`"Shizuku is not granted, … not launched"`). The screenshot shows apps *did* launch into the
  panes, which implies Shizuku launch worked — but launch and injection resolve **two different
  hidden services** (`IActivityTaskManager` vs `IInputManager`), so injection can still fail
  while launch succeeded.
- **`injectInputEvent`/`setDisplayId` must resolve.** Both are hidden APIs reached reflectively
  with `HiddenApiBypass`. If the ROM moved the signature, `resolveInputApi` returns null and
  `injectMotion` returns false. This is the one branch that *might* be fixable in code (adjust
  the reflective lookup), and the logs will show it.

Direction if (and only if) logs show injection is being *attempted* and returning false/no-op
on a trusted-display check: that is the platform block, not fixable. If logs show the method
never resolved, that is a fixable reflection/compat bug.

### 3. Display selection — what exists and what does not

- **No physical-display picker exists.** Nothing in `app/src/duoscreen` calls
  `DisplayManager.getDisplays()` or enumerates external/physical displays. The only
  `DisplayManager` use is `createVirtualDisplay` in `DuoScreenDisplays`. "Display" in this
  feature always means a per-pane VirtualDisplay that the app creates itself.
- **App-per-pane selection works (phone side).** `DuoScreenSettingsActivity.pickAppFor`
  enumerates launchable apps (`queryIntentActivities`, needs `QUERY_ALL_PACKAGES`) and persists
  the choice via `DuoScreenStore.setPackage`. `cyclePaneCount` sets 2–3 panes. This is ordinary
  app code with no privileged dependency and should work regardless of the touch block.
- **Pane selection (car side, EDIT mode) depends on touch delivery.**
  `DuoScreenInputRouter.setMode(EDIT)` then `onClick` → `selectedPaneId = pane.id`,
  `bringToFront`, `port.onSelectionChanged`. The hit-test and z-order logic are correct and
  pure. This selection only reaches the router if the host delivers `onClick` (it does on the
  DHU — the dot proves it) and the EDIT toggle in `onGetTemplate`'s action strip fires. So "can't
  select" on the car surface is **not** an enumeration/permission problem in our code; it is the
  same upstream question, and the pane-selection path itself has no privileged dependency.

Classification:
- App-per-pane and pane-count selection: **FIXABLE / already works** (no privileged path).
- Physical/head-unit display selection: **not implemented** (feature gap, not a bug). If the
  user expected to choose *which car display* panes go to, that was never built.
- Edit-mode pane selection not responding: tied to the same injection/delivery context; the
  selection logic is correct, so if the toggle + onClick arrive it will select.

### 4. ROADMAP corroboration

`ROADMAP.md` → "Own-content and privileged display — PARTIAL/BLOCKED":
- `OWN_CONTENT` dedicated app-owned VirtualDisplay is "intentionally unavailable"; runtime mode
  changes are rejected rather than making a false promise. Content was rephrased for compliance
  with licensing restrictions.
- The real-touch injection sink "accepts bounded raw pointer IDs/actions, but Android Auto's
  current `SurfaceCallback` contract does not provide those raw events"; `onScale` is the
  synthetic Accessibility fallback and "no end-to-end raw multi-touch claim is made". Content
  was rephrased for compliance with licensing restrictions.
- Host/vehicle validation (incl. "raw touch landing", Shizuku binding) is BLOCKED/TODO and needs
  device runs.

This matches the code exactly: the limitation is the privileged cross-display input path, and
the host gesture contract — both outside what app code can grant itself.

---

## Verdicts

| Problem | Verdict | Why / fix direction |
|---|---|---|
| **1. Touch not delivered to pane app** | **PLATFORM-BLOCKED** (confirm one in-code precondition first) | Injecting into an own-content **untrusted** VirtualDisplay as shell is restricted by the framework; app cannot hold `ADD_TRUSTED_DISPLAY`. First confirm via logs that `IInputManager.injectInputEvent` + `MotionEvent.setDisplayId` resolve and are *attempted*; if they resolve and still no-op, it is the trusted-display block (not fixable in app code). If they fail to resolve, that reflective lookup is the only fixable part. |
| **2. Cannot select a display** | **Mixed: FIXABLE/works for app+pane selection; feature-gap for physical display; pane-selection gated by Problem 1 on the car surface** | No `DisplayManager.getDisplays` picker exists. App-per-pane and pane-count selection are plain app code and work. Edit-mode pane selection logic is correct but needs the host gesture to arrive — same context as Problem 1. |

---

## Recommendation

**Feature-flag Duo Screen off on real head units / standard DHU for now**, keeping it behind
the existing flavor isolation (`DuoScreenCarAppService` is already separate from the main car
service). Rationale, strictly from the code:

1. Rendering and composition are sound (confirmed by the screenshot and by `DuoScreenCompositor`
   / `DuoScreenDisplays`), so the work is not wasted — keep it buildable behind the flag.
2. The feature's actual purpose (touch-driving apps inside panes) depends on cross-display input
   injection into an untrusted virtual display, which `ROADMAP.md` already classifies as
   PARTIAL/BLOCKED and which no app-level permission unlocks on a standard unit.
3. Before flipping the flag, spend one short diagnostic pass reading on-device logs
   (`AutoBridgeDuoOps`) to confirm whether `injectInputEvent`/`setDisplayId` resolve. This
   cheaply separates "fixable reflection/compat bug" from "trusted-display platform block". If it
   is the latter, do not invest further until a specifically supported trusted-display/OEM
   contract exists (ROADMAP item: revisit OWN_CONTENT only for a supported device/OEM contract).
4. If physical-display selection was an expected feature, note it as **not implemented** rather
   than broken.

No code changes were made.
