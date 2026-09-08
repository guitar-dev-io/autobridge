# ScreenOnAuto feature integration

AutoBridge uses [ScreenOnAuto-releases](https://github.com/slzn/ScreenOnAuto-releases) as a public behavior and release-notes reference. That repository publishes APK downloads and documentation, not an application source tree with a reusable open-source license. AutoBridge therefore implements compatible behavior independently and does not decompile, copy, or redistribute ScreenOnAuto code or assets.

## Integrated independently

| ScreenOnAuto idea | AutoBridge implementation | Notes |
|---|---|---|
| Prevent Sleep | `ScreenPowerController` + `MirrorSettings.preventScreenSleep` | Uses the public `WAKE_LOCK` permission and a screen WakeLock only for the active projection session. It is released on every projection stop/error path. |
| Auto Dim | `ScreenPowerController` + `AutoDimDelay` | Supports Off/15/30/60/120 seconds. Car touch/scroll/fling/scale callbacks restart the timer. This is best effort because the Android screen WakeLock API is deprecated on newer releases. |
| Stop on Disconnect | `ProjectionService.onCarSurfaceDisconnected()` | Optional setting. A 2-second grace period allows a transient Android Auto surface replacement before stopping projection. |
| Auto Launch App | `QuickAppLauncher.autoLaunchLastSession()` | Optional setting. It reopens the last explicitly launched Quick App after both projection and the car surface are ready. It never replays MediaProjection consent. |
| Auto Start | `CarDashboardScreen.scheduleAutoStartIfReady()` | Optional setting. It opens the car mirror screen only when a user-consented projection already exists. Android's MediaProjection consent dialog is never bypassed. |
| Privileged input onboarding | Mirror Settings + `ShizukuInputBackend.requestPermission()` | Shizuku remains optional; Accessibility is still the no-root fallback. |
| Back/Home/Recents | Existing car action strip and `InputBackend` | Uses the existing Accessibility/Shizuku backend and parked-only policy. |

All new settings are persisted by `SettingsStore` and default to disabled, so existing sessions retain their previous behavior after upgrade.

## Deliberately not copied

These features remain separate research work rather than pretending they are complete:

- **Turn the phone panel off while mirroring:** the current `AUTO_MIRROR` pipeline mirrors the default physical display and follows its power state. A real panel-off solution needs an own-content/self-drawn renderer plus a privileged display-control backend and device validation.
- **Real touch injection:** Android Auto exposes bounded gesture callbacks, not raw pointer streams. The current Shizuku path provides tap/swipe/keyevent primitives; independent multi-touch still needs a privileged input design and hardware testing.
- **Self-drawn mirror, FILL/STRETCH/crop, and per-frame FPS:** these require changing the renderer away from the OS-owned `AUTO_MIRROR` path. The shared transform is prepared, but output must not be reported as implemented until it is connected to a renderer.
- **Silent MediaProjection auto-start:** Android user consent remains mandatory, including after process death or a new projection session.

The upstream release notes are the source for the feature descriptions and version behavior: [ScreenOnAuto releases](https://github.com/slzn/ScreenOnAuto-releases/releases). Platform behavior and device compatibility must still be validated on DHU and a real Android Auto phone/head unit.
