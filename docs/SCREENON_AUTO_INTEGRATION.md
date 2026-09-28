# ScreenOnAuto integration boundary

AutoBridge uses [ScreenOnAuto-releases](https://github.com/slzn/ScreenOnAuto-releases) as a public behavior and release-notes reference. That repository publishes APK downloads and documentation, not an application source tree with a reusable open-source license. AutoBridge therefore implements compatible behavior independently and does not decompile, copy, or redistribute ScreenOnAuto code or assets.

The public release notes describe the relevant feature direction across the ScreenOnAuto 1.7.x line, including privileged screen-off/real-touch work and later display/helper fixes. Those notes are attribution/reference material, not proof that AutoBridge has ScreenOnAuto's internal architecture.

## Integrated independently

| ScreenOnAuto idea | AutoBridge implementation | Notes |
|---|---|---|
| Prevent Sleep | `ScreenPowerController` + `MirrorSettings.preventScreenSleep` | Uses the public `WAKE_LOCK` permission and a screen WakeLock only for the active projection session. It is released on every projection stop/error path. |
| Auto Dim | `ScreenPowerController` + `AutoDimDelay` | Supports Off/15/30/60/120 seconds. Car touch/scroll/fling/scale callbacks restart the timer. This is best effort because the Android screen WakeLock API is deprecated on newer releases. |
| Optional panel-only screen-off | `ShizukuDisplayPowerController` + `ScreenPowerController` + `MirrorSettings.screenOffOnAutoDim` | Explicit opt-in. The Shizuku user service probes hidden `SurfaceControl`/`DisplayControl` APIs, attempts panel-only power mode, falls back to public dimming on failure, and restores panel power on teardown. Android/OEM support must be verified; this is not `OWN_CONTENT`. |
| Stop on Disconnect | `ProjectionService.onCarSurfaceDisconnected()` | Optional setting. A 2-second grace period allows a transient Android Auto surface replacement before stopping projection. |
| Auto Launch App | `QuickAppLauncher.autoLaunchLastSession()` | Optional setting. It reopens the last explicitly launched Quick App after both projection and the car surface are ready. It never replays MediaProjection consent. |
| Auto Start | `CarDashboardScreen.scheduleAutoStartIfReady()` | Optional setting. It opens the car mirror screen only when a user-consented projection already exists. Android's MediaProjection consent dialog is never bypassed. |
| Privileged input onboarding | Mirror Settings + `ShizukuInputBackend.requestPermission()` | Shizuku remains optional; Accessibility is still the no-root fallback. |
| Real touch injection | `ShizukuRealTouchController` + `TouchRouter.rawTouch*` + `MirrorSettings.realTouchEnabled` | Hidden `InputManager.injectInputEvent(MotionEvent)` sink with bounded pointer IDs/count/coordinates. It is exposed only after capability probing and does not convert Android Auto `onScale` into fake raw input; a host raw pointer stream is still required. |
| Back/Home/Recents | Existing car action strip and `InputBackend` | Uses the existing Accessibility/Shizuku backend and parked-only policy. |

All new settings are persisted by `SettingsStore` and default to disabled, so existing sessions retain their previous behavior after upgrade.

## Deliberately bounded

These boundaries are explicit rather than silently represented as complete:

- **Dedicated own-content screen-off display:** `OWN_CONTENT` remains an unavailable capability marker. Settings and runtime reject it or fall back to `AUTO_MIRROR`; AutoBridge does not claim a dedicated app-owned `VirtualDisplay`.
- **Raw host multi-touch:** the current Android Auto `SurfaceCallback` exposes click/scroll/fling/scale callbacks, not independent pointer IDs/actions. The real-touch sink is implemented as a privileged destination for a future/supported raw transport, while the current pinch path remains synthetic Accessibility input.
- **General screen-off survival:** `AUTO_MIRROR` and `SELF_DRAWN` still capture the default display. The panel-only backend may work on specific Android/OEM combinations, but hidden APIs, display tokens, OEM power behavior, and capture continuity require device validation.
- **Exact upstream architecture:** public ScreenOnAuto releases do not expose reusable source for us to verify or copy. AutoBridge's Shizuku/AIDL/reflection implementation is an independent design with its own failure and safety boundaries.
- **Self-drawn output / FILL-STRETCH / FPS:** `SelfDrawnMirrorEngine` independently implements `ImageReader -> bounded frame handoff -> RenderPlan/Canvas -> Android Auto Surface`. `SELF_DRAWN` is opt-in and supports actual FIT/FILL/STRETCH/ONE_TO_ONE output plus captured/dropped/rendered/latency diagnostics. It still captures the default display and requires DHU/device validation.

The upstream release notes are the source for the feature descriptions and version behavior: [ScreenOnAuto releases](https://github.com/slzn/ScreenOnAuto-releases/releases). Platform behavior and device compatibility must still be validated on DHU and a real Android Auto phone/head unit.

## 2026-09-10 — Phone display controls and panel lifecycle

Mirror Settings on the phone and the car's mirror control panel now expose
**Dim phone now** and **Restore phone screen**, with the current requested display
state. Dim uses the existing opt-in panel-off setting and falls back to dimming.
It requires active mirroring and the existing SCREEN_OFF policy. Restore remains
available after a state change or failed teardown and restarts the idle timer.

Panel-off now retains a dim display wake lock rather than releasing the last
screen wake lock. Bright/dim transitions acquire the replacement lock before
releasing the old one. Panel restoration failures retain ownership for retry and
are visible in the UI/diagnostics; they are not reported as successful restoration.
Automatic dimming waits for an active permitted mirror. Manual dim/restore does
not change persisted preferences or leave an extra lock when both prevent-sleep
and automatic dimming are disabled.

Unit tests cover panel command ordering, repeated calls, unavailable panel-off,
and restoration failure/retry. Actual display power, picture continuity, and
Shizuku reconnect behavior still require device validation. Multi-touch transport,
per-app mirroring, and a cross-app media proxy remain outside this change.
