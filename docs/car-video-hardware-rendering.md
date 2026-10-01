# Car browser video: hardware-composited rendering on Android Auto

Status: in progress. Core path implemented and verified on the Desktop Head Unit (DHU);
some matrix tests still pending (see [Test results](#test-results)).

## 1. Architecture selected

The car browser renders through a **real, hardware-accelerated window whose pixels land
directly on the Android Auto `Surface`** — not through `Surface.lockCanvas()` + `WebView.draw(Canvas)`.

```
androidx.car.app SurfaceContainer.getSurface()   (owned by the Android Auto host)
      ▲   SurfaceFlinger / HWC composition — GPU, no copy, no Canvas
VirtualDisplay  (sink = that car surface; OWN_CONTENT_ONLY | PRESENTATION)
      ▲
Presentation window  (hardware accelerated)
   ├─ contentLayer (FrameLayout)
   │    ├─ WebView            ← Chromium GPU compositor, incl. inline <video> layer
   │    └─ fullscreen view    ← WebChromeClient.onShowCustomView (FullscreenVideoController)
   └─ chromeLayer (View)      ← renderer's toolbar / drawer / tab switcher / FAB, drawn on
                                the hardware canvas in the same coordinate space as before
```

Rendering modes live in `CarBrowserRenderMode`:

- `HARDWARE` (default) — the path above.
- `LEGACY_CANVAS` (rollback only) — the old `lockCanvas()` + `WebView.draw()` path, kept as a
  per-session fallback. The renderer drops to it automatically if the platform refuses to create
  the window, with the exact failure logged.

The mode is persisted (`CarBrowserRenderMode.current/select`) so a rollback is a preference flip,
not a code change.

## 2. Why it works, where the old path was black

`WebView.draw(Canvas)` only replays the WebView's **display list** into a bitmap-backed canvas.
Chromium does **not** put video frames in that display list — decoded video is a separate GPU
texture/surface the compositor stitches in later, outside `View.draw()`. So the software path got
HTML/CSS/UI and audio, but the `<video>` region came out black. That is a property of the
architecture, not a tunable; `setLayerType(SOFTWARE|HARDWARE)` cannot change it.

In the hardware path nothing is replayed. The WebView is attached to a real window on a real
display, renders through its own hardware draw functor, and SurfaceFlinger composites the finished
frame — **video layer included** — straight into the car surface. Hence video is visible.

Mechanism precedent: the Android Auto Car App Library hands an app only a `Surface`
(`SurfaceContainer` exposes `surface/width/height/dpi`, no `Display` and no display id), so an
Activity cannot be launched onto the projected screen (`ActivityOptions.setLaunchDisplayId` has no
id to target, and the display is owned by the Gearhead process). Fermata Auto's Car App route
(`MirrorDisplay.java`) sidesteps this the same way we now do: it builds a `VirtualDisplay` whose
sink is `SurfaceContainer.getSurface()` and swaps the surface with `VirtualDisplay.setSurface(...)`
on surface churn. We use an **own-content** display (not a mirror), so no `MediaProjection` consent
is required.

## 3. Files changed

New:
- `browser/CarBrowserRenderMode.kt` — `HARDWARE`/`LEGACY_CANVAS` enum + persistence, and the
  `AutoBridgeVideo` logger (`adb logcat -s AutoBridgeVideo`).
- `browser/CarHardwareWebWindow.kt` — the VirtualDisplay + Presentation window: create, `setSurface`
  for churn, size-change recreate, page placement, chrome layer, fullscreen host, teardown.

Modified:
- `browser/CarWebRenderer.kt` — HARDWARE path wiring: attach/reattach the window on surface
  available/resize, point the display at `null` on surface destroyed (keep the window + page +
  playback alive), recreate on unexpected `Presentation` dismiss, drop to `LEGACY_CANVAS` on
  failure. `drawFrame()` split so chrome draws on both the legacy canvas and the hardware chrome
  layer; `onShowCustomView/onHideCustomView` route HTML5 fullscreen into the car window; Back exits
  fullscreen first; touch dispatch targets the fullscreen view when present; `videodiag` sentinel
  loads the non-DRM MP4 test page; `setOffscreenPreRaster` is legacy-only.
- `browser/FullscreenVideoController.kt` — generalized from Activity-only to any
  `Context` + content `FrameLayout` + `Window?`, so it hosts fullscreen in the car Presentation as
  well as the phone activities.
- `car/CarBrowserScreen.kt` — removed the disabled `launchDisplayId` experiment; the renderer owns
  the surface directly.

Removed:
- `browser/HardwareCarBrowserExperiment.kt` — the disabled `setLaunchDisplayId` experiment, proven
  unreachable on real AA and superseded by the VirtualDisplay path.

## 4. Rendering flow (runtime)

1. `CarBrowserScreen.onSurfaceAvailable` → `CarWebRenderer.start(surface,…)`.
2. HARDWARE: `attachHardwareWindow` → `DisplayManager.createVirtualDisplay(sink = car surface,
   OWN_CONTENT_ONLY | PRESENTATION)` → `Presentation.show()` → WebView placed in `contentLayer`.
3. The frame pump no longer calls `lockCanvas`; it only invalidates the chrome layer so fades and
   overlays advance. The WebView and its video render themselves through the window.
4. Template pushed over the browser / DHU unplug → `onSurfaceDestroyed` → `setSurface(null)`
   (window + page + playback survive). Surface returns → `setSurface(newSurface)`, same display id.
5. Car surface size change → window recreated (a `Presentation` cancels itself on display metrics
   change, so an in-place resize cannot be used).

## 5. Logging

`adb logcat -s AutoBridgeVideo` prints state transitions only (never per frame): render mode,
target/window/WebView display ids, WebView attached + hwAccel, surface valid/size, virtual-display
setSurface, fullscreen enter/exit, page URL. Example from the DHU:

```
car-surface available valid=true size=800x400 dpi=160
hardware window created displayId=643 800x400 @480dpi
stage=page-finished mode=HARDWARE targetDisplayId=643 windowDisplayId=643 webViewDisplayId=643 webViewAttached=true hwAccel=true url=https://m.youtube.com/watch?v=…
virtual-display setSurface displayId=643 attached=true valid=true
stage=hardware-surface-reattached mode=HARDWARE targetDisplayId=643 …
fullscreen enter url=https://m.youtube.com/watch?v=…
```

## 6. Test results (DHU, 800×400 @160dpi, Android 16 / API 36, DHU 2.0 mac-arm64)

Verification method: DHU screenshots + a 2-sample pixel diff over the video region vs a static
region, cross-checked against the WebView's own `<video>`/`#movie_player` state over chrome://inspect
(DevTools), plus `dumpsys media.audio_flinger` and the `VDS-AutoBridgeCarBrowser SINK` frame counter.

| # | Case | Expected | Result |
|---|------|----------|--------|
| 1 | Plain HTML page | PASS | pending |
| 2 | Normal webpage | PASS | pending |
| 3 | Direct non-DRM MP4 (`videodiag`) | VIDEO VISIBLE | pending |
| 4 | YouTube home page | PASS | pending |
| 5 | YouTube inline video | VIDEO + AUDIO | **PASS** — moving frames (video region Δ≈25 vs header Δ=0); AudioFlinger active track from app uid; sink ~29–40 fps |
| 6 | YouTube fullscreen | VIDEO + AUDIO | in progress — `onShowCustomView` fires and hosts in the car window; verifying it stays |
| 7 | Exit fullscreen | restore page | pending (Back path implemented: exits fullscreen before history) |
| 8 | Next YouTube video | works | partial — playlist auto-advance keeps video; manual pick pending |
| 9 | Pause / resume | PASS | **PASS** — pause freezes pixels (Δ=0) + `paused:true`; resume moves again |
| 10 | Back button | correct history | pending |
| 11 | DHU disconnect/reconnect | recovers | pending (setSurface(null)→reattach verified via Settings template round-trip, same display id) |
| 12 | Phone screen off/on | no black screen | pending |

Confirmed so far: **actual YouTube video pixels are visible and moving on the Android Auto DHU,
with audio.** (TEST 5.)

## 7. Android / DHU limitations found

- Car App Library exposes no `Display`/display id for the projected screen — only a `Surface`.
  Launching an Activity onto the AA display is not possible; the VirtualDisplay-on-surface approach
  is the route that works (same as Fermata Auto's Car App path).
- `Presentation` cancels itself when its display's metrics change, so a car-surface **size** change
  must recreate the window rather than resize the display in place.
- Force-stopping the app while connected makes the host show "AutoBridge isn't responding" (host-side
  binder death, not an app crash) and can restart the session from its root screen. This is a
  testing artifact of killing the process mid-session, not a user-path bug.

## 8. Known issues / follow-ups

- AutoBridge's floating menu button overlaps YouTube's fullscreen button in the bottom-right. There
  is a user setting ("Floating button on left") that resolves it, but that settings row is currently
  dropped by the host's list-item limit on the car Settings screen — needs to be surfaced
  (reorder/condense the settings list, or move the FAB corner by default).
- Temporary diagnostics in `BrowserActivity` (`WebVideoDiagnostics`, console logging, `videodiag`
  sentinel) are still in place and should be removed once the matrix is complete.

## 9. Steps to reproduce

1. `./gradlew :app:assemblePersonalDebug && adb install -r app/build/outputs/apk/personal/debug/app-personal-debug.apk`
2. Start the DHU (AA developer settings → Start head unit server; `adb forward tcp:5277 tcp:5277`;
   run `desktop-head-unit`).
3. On the head unit: open **AutoBridge → Web browser**, navigate to a YouTube video.
4. `adb logcat -s AutoBridgeVideo` to watch the render mode and display ids.
5. For the non-DRM MP4 check, open the browser address input and type `videodiag`.
