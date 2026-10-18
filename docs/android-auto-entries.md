# One Android Auto app: AutoBridge

AutoBridge is the only Android Auto entry this project lists. Everything else it builds for the
car opens from inside it.

## Audit (before this change, personal/lab flavors)

| Component | Kind | Listed before | Now |
|---|---|---|---|
| `car.AutoBridgeCarAppService` | CarAppService, NAVIGATION | yes | **yes, the only entry** |
| `duoscreen.car.DuoScreenCarAppService` ("Multi-app") | CarAppService, NAVIGATION | yes | disabled; opens from More › Multi-app |
| `projection.desktop.DesktopBrowserCarAppService` ("Desktop Browser") | CarAppService, NAVIGATION | yes | disabled; opens from the Web card |
| `projection.ProjectionCarService` ("Bridge Web") | CATEGORY_PROJECTION (unofficial SDK) | yes | disabled; can be listed again in Settings |
| `projection.ProjectionMirrorCarService` ("Bridge Mirror") | CATEGORY_PROJECTION | no (already off) | unchanged |

The safe (Play) flavor only ever had AutoBridge. Third-party apps (VelvetLauncher, AABrowser) are
not touched. `scripts/check-car-entries.py` checks this table on every CI run.

## How each feature is reached now

- **Browser** – the Web card. It opens the desktop engine (Linux Chrome or Brave) when one is
  chosen *and* installed; otherwise the built-in browser, exactly as before. Site tiles, Send to
  Car, Favorites and the Agent keep using the built-in browser, which is also the fallback whenever
  the engine is unavailable.
- **Browser engine** – More › "Browser engine: …", the same choice as Settings › Desktop Browser
  Engine on the phone. One browser entry, not one per engine.
- **Multi-app (Duo Screen)** – More › Multi-app. The same `DuoScreenScreen`, pushed with
  `embedded = true`: Exit ends the panes and returns to AutoBridge instead of closing the car app.
- **Bridge Web** – a projection activity, which a template app cannot open. Its only unique ability
  is the split screen beside Maps, so it is unlisted by default and Settings › Bridge Web on
  Android Auto › "List Bridge Web on Android Auto" brings it back. Reconnect after changing.
- **Entertainment / media / Send to Car** – unchanged.

The disabled services are kept, not deleted, so a standalone route can still be tested:
`adb shell pm enable dev.autobridge/<service class>`.

## Host restrictions behind the design

- Only NAVIGATION car apps get a drawing surface (`AppManager.setSurfaceCallback`), so Multi-app
  and the desktop engine must live in a NAVIGATION session. AutoBridge already is one, so they are
  screens on its stack instead of sessions of their own.
- The surface callback is one slot per session. Every surface screen claims it through
  `MirrorSurfaceOwnership` (moved to `:common` so `:duoscreen` shares it), so popping a screen
  never clears the callback of the screen underneath.
- A template app cannot launch a CATEGORY_PROJECTION activity, which is why Bridge Web stays a
  separate (opt-in) entry rather than a screen.
- Parked only: the desktop engine and the built-in browser both gate on
  `SafetyEnforcement.gateParked` + `FeaturePolicy` for `BROWSER`. While moving, no page or video is
  drawn and input is ignored.

## DHU / device checklist (to run; not run in the cloud session that made this change)

1. `scripts/dhu-run.sh personal` (or install the personal debug APK) and open the DHU launcher:
   only **AutoBridge** is listed for this project. `adb shell dumpsys package dev.autobridge`
   should show the four services above as disabled.
2. Home › More › Multi-app: panes start; Exit returns to the More list; Back returns too; the home
   dashboard repaints (surface callback handed back).
3. Web card with "Browser engine: AutoBridge Browser": the built-in browser opens as before.
4. Install Chrome or Brave on the phone, pick it under More › Browser engine, tap the Web card: the
   desktop page fills the screen; Home returns to AutoBridge; the dashboard repaints.
5. LAB mock MOVING: browser and desktop show the park notice, no video drawn.
6. Send to Car a link and a video: they open in the built-in browser / bridge player as before.
7. Settings › Bridge Web on Android Auto: switch on, reconnect, Bridge Web is listed; off, gone.
8. Settings › Send log: the "… listed" rows read "no (default)" for all four services.
