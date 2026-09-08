# Feature policy

Updated: 2026-09-08

`dev.autobridge.core.policy.FeaturePolicy` is the single decision boundary for product mode, environment, and vehicle state. UI code may explain a decision, but it must not replace the policy with scattered `BuildConfig`, speed, or mode checks.

## Decision model

```kotlin
val decision = FeaturePolicy.app.decide(Feature.MIRROR)
if (decision.allowed) {
    // enter the feature
} else {
    // show decision.reason
}
```

A `FeatureDecision` contains:

- `feature`: the capability being evaluated;
- `allowed`: the final boolean;
- `reason`: a user/developer-readable explanation;
- `requiresParked`: whether the feature is in the parked-only set.

`FeaturePolicy.app` reads `RuntimeContextStore.context`. Tests and isolated code can construct `FeaturePolicy` with an explicit context provider and call `decide(feature, context)` without Android services.

## Feature inventory

The policy knows these capabilities:

`MIRROR`, `TOUCH`, `QUICK_APPS`, `APP_LAUNCHER`, `MEDIA`, `BROWSER`, `VIDEO`, `AUDIO_CAPTURE`, `SCREEN_OFF`, `SHIZUKU`, `DEVELOPER`, and `DEBUG_OVERLAY`.

The parked-only set is currently:

```text
MIRROR, TOUCH, QUICK_APPS, APP_LAUNCHER, BROWSER,
VIDEO, AUDIO_CAPTURE, SCREEN_OFF
```

`MEDIA` is intentionally not in that set. Native audio may continue through the MediaSession when the vehicle moves or speed becomes unknown; video tracks are separately disabled by the policy.

## Mode rules

| Context | Mode decision before vehicle state |
|---|---|
| `SAFE` | Only `MEDIA` and `QUICK_APPS` are mode-enabled. |
| `PERSONAL` | All listed features are mode-enabled. |
| `LAB` | All listed features are mode-enabled. |

After the mode decision, every parked-only feature must have `VehicleState.PARKED`. A `MOVING` or `UNKNOWN` context returns a denial such as `MIRROR requires PARKED; vehicle is moving`.

For `LAB + REAL_CAR`, an allowed decision explains `LAB on real car; real vehicle state enforced`; this is not a simulator permission. A real-car `MOVING` or `UNKNOWN` context is still denied for parked-only features.

## Representative matrix

| Feature | SAFE parked | PERSONAL parked | PERSONAL moving/unknown | LAB bench parked | LAB real-car moving/unknown |
|---|---:|---:|---:|---:|---:|
| Media/audio (`MEDIA`) | yes | yes | yes | yes | yes |
| Mirror (`MIRROR`) | no | yes | no | yes | no |
| Touch (`TOUCH`) | no | yes | no | yes | no |
| Browser/video (`BROWSER`, `VIDEO`) | no | yes | no | yes | no |
| Quick Apps (`QUICK_APPS`) | yes | yes | no | yes | no |
| Developer/diagnostics | no | yes | yes | yes | yes |
| Shizuku capability | no | yes | yes | yes | yes |

The table describes policy availability, not external Android permissions. Accessibility, Shizuku, MediaProjection consent, `CAR_SPEED`, `WRITE_SETTINGS`, and MediaSession controller authorization remain separate runtime prerequisites.

## Enforcement locations

The same policy is checked at both entry points and low-level boundaries:

- `MirrorCoordinator` refuses to render when `MIRROR` is unavailable;
- `MirrorCarScreen` and `TouchRouter` gate car callbacks and system actions with `TOUCH`;
- Accessibility and Shizuku operations check `TOUCH` again before injection;
- `QuickAppLauncher` checks `QUICK_APPS` and the resolved Smart Mode feature;
- `EntertainmentActivity` maps WEB/AUDIO/VIDEO to `BROWSER`/`MEDIA`/`VIDEO` and re-enforces the decision on state changes;
- `MediaPlaybackService` disables video tracks when `VIDEO` is unavailable while leaving audio policy independent;
- developer screens and LAB controls require `DEVELOPER`/LAB conditions;
- the production `SpeedGate` independently updates `ParkingStateStore` and stops the projection on movement.

Defensive checks at Android boundaries are expected. What is prohibited is using a local mode or speed check as a replacement for `FeaturePolicy`.

## State transition behavior

When a valid zero-speed sample expires, `SpeedGate` moves the state to `UNKNOWN`; it does not keep the last `PARKED` decision indefinitely. When a non-zero sample arrives, the movement callback stops projection and the car session. When a LAB mock state changes, it travels through the same `ParkingStateStore` and `RuntimeContextStore` flows, so tests exercise real denial paths.

## Tests

Pure regression coverage lives in `app/src/test/java/dev/autobridge/`:

- `AppProfileRegressionTest` checks SAFE/PERSONAL/LAB decisions and reason strings;
- `CoreRegressionTest` checks strict speed classification, transform geometry, and fail-closed gates;
- `RuntimeBoundaryTest` checks emulator-only DEV_MODE, content routing, controller authorization, bounded Shizuku command vectors, reconnect outcomes, and diagnostics.
