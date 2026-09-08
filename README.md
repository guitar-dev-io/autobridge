# AutoBridge

AutoBridge is a personal/development Android project for experimenting with a parked-only phone-to-Android-Auto surface bridge. It is an independent codebase, not a merge of the reference projects listed in [`docs/REFERENCE_PROJECTS.md`](docs/REFERENCE_PROJECTS.md).

**Safety boundary:** mirroring, video, browser, touch, and Quick App launching require an authoritative `PARKED` state. `MOVING` stops projection and leaves the car app. `UNKNOWN` is fail-closed. LAB controls never falsify a real vehicle's speed.

## Current implementation

- One shared `:app` module and one `src/main` implementation with `safe`, `personal`, and `lab` product flavors.
- Central `FeaturePolicy` decisions backed by `RuntimeContextStore` and `VehicleState` flows.
- Production `CAR_SPEED`/`SpeedGate` provider with strict finite-zero parking classification and a three-second stale-update timeout.
- Emulator-only LAB mock provider with `PARKED`, `MOVING`, and `UNKNOWN` transitions; a physical debug phone stays on the production provider.
- Android 14+ MediaProjection foreground service and direct `MediaProjection -> VirtualDisplay(AUTO_MIRROR) -> Android Auto Surface` output.
- Shared surface lifecycle, reconnect tracking, safe insets, visible-area bounds, rotation-aware coordinate mapping, and structured mirror diagnostics.
- Accessibility and bounded Shizuku input backends with capability reporting and parked-only enforcement.
- Quick Apps, installed-app filtering, legacy-favorite migration, per-app profiles, Smart Mode, force-landscape cleanup, and explicit session resume/forget actions.
- Media3 progressive/HLS/DASH/local playback through a MediaSession, with controller authorization and independent audio/video policy.
- Entertainment routing that distinguishes HTTPS web pages, audio, and video instead of treating all sources as browser content.
- Bounded Compose phone control-center content embedded in the existing Activity; Android Auto remains host-managed through Car App templates.
- ScreenOnAuto-inspired mirror automation: optional prevent-sleep, timed auto-dim, stop-on-disconnect, last-app auto-launch, auto-open with existing consent, and Shizuku permission onboarding.

The code is implemented and flavored builds/tests are the primary local validation. Ford hardware, production Android Auto host behavior, exact `CAR_SPEED` delivery, MediaProjection consent, MediaSession controller identities, Shizuku, and touch injection remain device-dependent checks.

## Modes

| Flavor | Use | Important boundary |
|---|---|---|
| `safe` | Conservative baseline | Policy enables media and parked Quick Apps; mirror/browser/video/touch are disabled. |
| `personal` | Personal parked use | Broad feature set, but parked-only features still require `PARKED`. |
| `lab` | Emulator/DHU or controlled bench | Broad feature set plus LAB controls; real-car state is never replaced. |

See [`docs/MODES.md`](docs/MODES.md), [`docs/FEATURE_POLICY.md`](docs/FEATURE_POLICY.md), and [`docs/LAB_MODE.md`](docs/LAB_MODE.md).

## Requirements

- Android Studio with Android SDK 36
- JDK 17
- Android 10+ phone (`minSdk 29`, `targetSdk 36`)
- Android Auto/DHU for host testing
- Android Gradle Plugin 8.13.2, Gradle 8.13, Kotlin 2.4.10
- AndroidX Car App 1.7.0 and Media3 1.11.0

The bounded Compose slice uses the Kotlin Compose compiler plugin and Compose BOM `2026.06.01`, selected because the current project targets compileSdk 36/AGP 8.13. See the [official Compose dependency setup guidance](https://developer.android.com/develop/ui/compose/setup-compose-dependencies-and-compiler).

## Build and test

Use a specific flavor; all modes share the same source code:

```bash
./gradlew testSafeDebugUnitTest assembleSafeDebug
./gradlew testPersonalDebugUnitTest assemblePersonalDebug
./gradlew testLabDebugUnitTest assembleLabDebug
```

Full local verification:

```bash
./gradlew \
  testSafeDebugUnitTest \
  testPersonalDebugUnitTest \
  testLabDebugUnitTest \
  assembleSafeDebug \
  assemblePersonalDebug \
  assembleLabDebug
```

Optional lint tasks, when available in the local Android toolchain:

```bash
./gradlew lintSafeDebug lintPersonalDebug lintLabDebug
```

## Development flow

### Emulator/DHU LAB flow

1. Install `labDebug` on an Android emulator.
2. Start the Desktop Head Unit and connect it to the emulator.
3. Open AutoBridge in the DHU launcher.
4. Select a non-`REAL_CAR` LAB environment and set the mock vehicle state to `PARKED`.
5. Start mirror on the phone and approve the system screen-capture consent.
6. Verify the `READY` projection state, surface/mirror diagnostics, and visible pixels.
7. Change the mock state to `MOVING`; projection must stop and the car app must exit.
8. Change it to `UNKNOWN`; parked-only actions must remain blocked.

### Production-style flow

1. Install `personalDebug` only for development or a controlled personal test.
2. Connect Android Auto/DHU and open AutoBridge.
3. Request/grant `CAR_SPEED`; wait for a valid zero-speed callback.
4. Start mirror from the phone and approve fresh MediaProjection consent.
5. Enable Accessibility or Shizuku only if touch testing is required.
6. Stop the session before changing vehicle state; a non-zero speed must stop it automatically.

The official Android Auto app model does not provide a general arbitrary-screen-mirroring category. AutoBridge uses a `NavigationTemplate` custom surface as a development/personal POC; do not present it as a compliant navigation app for store distribution.

## Architecture at a glance

```text
Phone MainActivity / bounded Compose controls
             |
             | explicit MediaProjection consent
             v
ProjectionService (foreground service)
             |
             v
MirrorCoordinator -- VirtualDisplay(AUTO_MIRROR) --> Android Auto Surface
       ^                         ^                         |
       |                         |                         v
FeaturePolicy             MirrorSurfaceController     DHU / vehicle host
       ^                         ^                         |
       |                         |                         v
RuntimeContextStore <--- VehicleStateSession <--- SpeedGate or LAB mock
             |
             +--> TouchRouter --> Shizuku / Accessibility --> phone input
             +--> Quick Apps / profiles / MediaSession / Entertainment
```

For ownership and lifecycle details see [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) and [`docs/MIRROR_ENGINE.md`](docs/MIRROR_ENGINE.md).

## Platform limitations

- Direct AUTO_MIRROR is OS-owned FIT output. FILL, STRETCH, crop, and ONE_TO_ONE geometry are implemented for shared mapping/future rendering, but not as actual output modes.
- Screen-off survival requires an own-content renderer or dedicated per-app virtual display; the current default-display mirror follows phone display power.
- The optional prevent-sleep/auto-dim controls use public, deprecated screen WakeLock APIs as a best-effort policy. They do not provide privileged panel-off behavior and must be validated on a real phone.
- Target-app-only capture/fullscreen cannot be forced reliably through public APIs.
- Protected video surfaces (for example Netflix/Widevine content) may be blank in MediaProjection/AUTO_MIRROR output. AutoBridge does not bypass DRM; use an officially supported car/app integration for protected playback.
- Android Auto exposes click/scroll/fling/scale callbacks, not raw pointer streams or independent multi-touch.
- Ford profile values are placeholders until measured on real hardware; see [`docs/FORD_NEXT_GEN.md`](docs/FORD_NEXT_GEN.md).
- A successful JVM test/build does not establish DHU, Ford, wireless Android Auto, touch, media-button, or Shizuku compatibility.

## Documentation

- [`ROADMAP.md`](ROADMAP.md) — implemented scope and remaining work
- [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) — ownership and runtime boundaries
- [`docs/MODES.md`](docs/MODES.md) — SAFE/PERSONAL/LAB variants
- [`docs/FEATURE_POLICY.md`](docs/FEATURE_POLICY.md) — centralized feature decisions
- [`docs/MIRROR_ENGINE.md`](docs/MIRROR_ENGINE.md) — projection/surface/reconnect lifecycle
- [`docs/INPUT_SYSTEM.md`](docs/INPUT_SYSTEM.md) — transforms and input backends
- [`docs/APP_PROFILES.md`](docs/APP_PROFILES.md) — Quick Apps, profiles, Smart Mode
- [`docs/LAB_MODE.md`](docs/LAB_MODE.md) — emulator/DHU development boundaries
- [`docs/FORD_NEXT_GEN.md`](docs/FORD_NEXT_GEN.md) — measured Ford profile procedure
- [`docs/REFERENCE_PROJECTS.md`](docs/REFERENCE_PROJECTS.md) — independent reference matrix
- [`docs/SCREENON_AUTO_INTEGRATION.md`](docs/SCREENON_AUTO_INTEGRATION.md) — independent ScreenOnAuto feature mapping and limits
- [`docs/DHU_SCENARIOS.md`](docs/DHU_SCENARIOS.md) — manual host/device scenarios
- [`docs/FORD_TEST.md`](docs/FORD_TEST.md) — DHU/Ford safety checklist
- [`docs/CARVIEW_PARITY.md`](docs/CARVIEW_PARITY.md) — product-reference comparison
- [`docs/SCREENON_AUTO_INTEGRATION.md`](docs/SCREENON_AUTO_INTEGRATION.md) — independently integrated sleep/dim/auto-start ideas
- [`docs/PROGRESS.md`](docs/PROGRESS.md) — current verification record
