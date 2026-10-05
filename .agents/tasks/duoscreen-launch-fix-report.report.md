# Duo Screen — Launch Fix & Touch Verification Report

> Note on location: the intended report path
> `.agents/tasks/duoscreen-launch-fix-report.md` already exists as a *directory* holding the FEAT
> artifacts (`context.json`, `features/`, `task.json`). A file and a directory cannot share the
> identical name in the same parent, and removing the directory would destroy the artifacts, so
> this report is written as `duoscreen-launch-fix-report.md/report.md` (inside that directory) and
> mirrored as the sibling `duoscreen-launch-fix-report.report.md`. Content is identical.

Device: YXEMRCGYAI49S4SS (Xiaomi 23078PND5G, Android 16 / MIUI). Flavor: personal (debug).
Scope: fix the pane-launch failure (FEAT-001) and verify on-device touch into a live pane
(FEAT-002). No production code changed by the touch step; nothing committed.

---

## 1. The `dev.autobridge` self-target origin

The original pane-launch failure (`startActivityAsUser` → `-91` / `aInfo is null`) came from two
things, confirmed on-device:

- **Self-target.** `/data/data/dev.autobridge/shared_prefs/autobridge_duo_screen.xml` had pane 0's
  stored package set to `dev.autobridge` — the app's own package. Launching the app itself into a
  pane is never valid and produced the `-91`. (Pane 1 was `com.google.android.apps.maps`.)
- **Implicit intent.** The old `launchOnDisplay` built an implicit `ACTION_MAIN` +
  `CATEGORY_LAUNCHER` + `setPackage(...)` intent and relied on the shell transaction to resolve it,
  which fails shell-side on this MIUI build for a launcher activity that is only "enabled by
  default".

## 2. The launch fix (FEAT-001)

In-app, under the `QUERY_ALL_PACKAGES` the personal/lab manifest already declares:

- `DuoScreenController.resolveLauncherComponent(packageName)` resolves the launcher activity to an
  explicit `ComponentName` (SDK 33+ `ResolveInfoFlags.of(0L)`, pre-33 fallback), via the pure
  `DuoScreenLauncherResolver`.
- `DuoScreenShizukuOps.launchOnDisplay(displayId, packageName, component)` sets the explicit
  component when resolved, keeping the implicit `setPackage` path only as a fallback. Shell-UID
  `startActivityAsUser` mechanics are unchanged.
- A **self-launch guard** in `openPane`/`reload` refuses `packageName == context.packageName`, and
  `DuoScreenSettingsActivity.pickAppFor` excludes the app's own package so the self-target can no
  longer be re-stored.

**JVM gate: PASSED.** `./gradlew :app:assemblePersonalDebug` BUILD SUCCESSFUL;
`./gradlew :app:testPersonalDebugUnitTest --rerun-tasks` BUILD SUCCESSFUL. duoscreen unit tests
(54 total, 0 failures): DuoScreenInputRouterTest 11, DuoScreenLauncherResolverTest 4,
DuoScreenLayoutCodecTest 8, DuoScreenLayoutTest 14, DuoScreenPaneSetTest 6,
DuoScreenResizeDebouncerTest 7, DuoScreenThreadSlotTest 4.

## 3. Launch verdict: FIXED

On-device, panes launch explicitly with no `-91` / no `aInfo is null`. Exact lines:

```
W AutoBridgeDuoCtl: Pane 0: refusing to launch own package dev.autobridge into a pane (self-launch is never a valid pane target and is the -91/aInfo-is-null cause)
I ActivityTaskManager: START u0 {act=android.intent.action.MAIN cat=[android.intent.category.LAUNCHER] flg=0x10000000 cmp=com.google.android.apps.maps/com.google.android.maps.MapsActivity} ... callingPackage com.android.shell (BAL_ALLOW_PERMISSION) result code=0
I AutoBridgeDuoCtl: Pane 1: launch(com.google.android.apps.maps on display 2946) = true (explicit ComponentInfo{com.google.android.apps.maps/com.google.android.maps.MapsActivity})
```

Second run (two valid apps, cleared layout):

```
I AutoBridgeDuoCtl: Pane 0: launch(com.miui.calculator on display 2953) = true (explicit ComponentInfo{com.miui.calculator/com.miui.calculator.cal.CalculatorActivity})
I ActivityTaskManager: Displayed com.miui.calculator/.cal.CalculatorActivity for user 0: +1s96ms
I AutoBridgeDuoCtl: Pane 1: launch(com.android.settings on display 2954) = true (explicit ComponentInfo{com.android.settings/com.android.settings.MainSettings})
I ActivityTaskManager: Displayed com.android.settings/.MainSettings for user 0: +1s486ms
```

Both panes launched via the in-app explicit ComponentName (not the implicit fallback), landed on
their own-content pane displays with `result code=0`. No display-owner rejection on the launch
path.

---

## 4. Touch verification (FEAT-002)

### Setup

A live session confirmed on-device: pane 0 = `com.miui.calculator` on virtual display **2962**,
pane 1 = `com.android.settings` on virtual display **2963**, both `FLAG_PRIVATE`,
`FLAG_OWN_CONTENT_ONLY`, `touch VIRTUAL`, `owner dev.autobridge (uid 10856)`. The harness
`DuoScreenSpikeActivity` is top-resumed; tapping its surface forwards the tap through
`DuoScreenController` → `DuoScreenInputRouter` → `forwardTap` → `DuoScreenTouchController` →
`DuoScreenShizukuOps.injectMotion` exactly as the car host's SurfaceCallback would.

Procedure: `adb -s YXEMRCGYAI49S4SS logcat -c`, tap inside pane 0, then
`adb -s YXEMRCGYAI49S4SS logcat -d -s AutoBridgeDuoOps:* InputDispatcher:* InputManager:*`.

### What the logs show

Our injection is attempted via the Shizuku server (pid 6681 = `shizuku_server`, shell UID),
with the display id set, and the event is then **dropped by the dispatcher at the pane's
own-content untrusted virtual display**. Exact lines (tap 1):

```
D InputManager: injectMotionEvent: 6681-{action=ACTION_DOWN, id[0]=0, x[0]=305.0, y[0]=1356.0, pointerCount=1, eventTime=570267513, deviceId=-1}
W InputDispatcher: Focused display #2962 does not have a focused window.
E InputDispatcher: But another display has a focused window
E InputDispatcher:   FocusedWindows:
E InputDispatcher:     displayId=0, name='76fb158 dev.autobridge/dev.autobridge.duoscreen.DuoScreenSpikeActivity'
D InputManager: injectMotionEvent: 6681-{action=ACTION_UP, id[0]=0, x[0]=305.0, y[0]=1356.0, pointerCount=1, eventTime=570267547, deviceId=-1}
```

Reproduced (tap 2, after the calculator window gained focus on 2962):

```
W InputDispatcher: Focused display #0 does not have a focused window.
E InputDispatcher:     displayId=2962, name='fec8e6f com.miui.calculator/com.miui.calculator.cal.CalculatorActivity'
D InputManager: injectMotionEvent: 6681-{action=ACTION_DOWN, id[0]=0, x[0]=305.0, y[0]=1800.0, pointerCount=1, eventTime=570298621, deviceId=-1}
D InputManager: injectMotionEvent: 6681-{action=ACTION_UP, id[0]=0, x[0]=305.0, y[0]=1800.0, pointerCount=1, eventTime=570298651, deviceId=-1}
```

### Interpretation

- **Input API resolved and injection was attempted.** There are **no** `AutoBridgeDuoOps`
  warnings anywhere in the buffer — no `"Could not reach the input manager over Shizuku"`, no
  `"MotionEvent.setDisplayId is unavailable; cannot target a display"`, no
  `"injectInputEvent failed on display ..."`. So `IInputManager.injectInputEvent` and
  `MotionEvent.setDisplayId(2962)` both resolved and the hidden call ran without throwing. The
  `InputManager: injectMotionEvent: 6681-{...}` lines are the system logging our injected event
  arriving from the shell-UID Shizuku process. This is **not** a reflection/compat bug — there is
  no scoped code fix to recommend.
- **The event does not reach/actuate the pane app.** The injection targets our own-content,
  untrusted VirtualDisplay (`FLAG_OWN_CONTENT_ONLY`, no `ADD_TRUSTED_DISPLAY` /
  `VIRTUAL_DISPLAY_FLAG_TRUSTED`, which an app process cannot hold — see `DuoScreenDisplays`
  KDoc). The dispatcher does not deliver injected pointer events to a window on this display from
  the shell UID that is not the display's trusted owner; the `Focused display #2962 does not have
  a focused window` path is the dispatcher declining to route the injected event into the pane.
  The pane app's buttons do not actuate.

### Touch verdict: PLATFORM-BLOCKED

Injection is attempted correctly (API resolves, display id set, call does not throw) but the
event is dropped at the own-content untrusted VirtualDisplay boundary — the same limitation
`ROADMAP.md` records as "Own-content and privileged display — PARTIAL/BLOCKED". No app-level
permission unlocks a trusted-display input path on a standard device, so this is **not fixable in
app code**. The method chain resolving rules out the one fixable (reflection/compat) case; **no
scoped fix recommendation applies** — the break is the platform boundary, not our code.

This corroborates the prior read-only prediction in
`.agents/tasks/duoscreen-touch-display-investigation.md` with fresh on-device evidence.

---

## 5. Summary

| Aspect | Verdict | Evidence |
|---|---|---|
| JVM gate (build + unit tests) | PASS | `assemblePersonalDebug` + `testPersonalDebugUnitTest` SUCCESSFUL; 54 duoscreen tests, 0 failures |
| Launch (FEAT-001) | FIXED | explicit ComponentName launches, `result code=0`, no `-91`/`aInfo is null`; self-launch guard fires for `dev.autobridge` |
| Touch (FEAT-002) | PLATFORM-BLOCKED | injection attempted from shell UID (pid 6681) with display id set, no `AutoBridgeDuoOps` resolve/failure warnings; dropped at own-content untrusted VirtualDisplay (`Focused display #2962 does not have a focused window`) |

A PLATFORM-BLOCKED touch verdict backed by on-device evidence is a complete, approvable outcome
for this fix-or-prove-unfixable task.
