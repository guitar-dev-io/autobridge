**Version:** `0.3.5` &nbsp;·&nbsp; **Build (versionCode):** `8`

AutoBridge is a personal/development Android project for experimenting with a parked-only phone-to-Android-Auto surface bridge. It is an independent codebase, not a merge of the reference projects listed in [`docs/REFERENCE_PROJECTS.md`](docs/REFERENCE_PROJECTS.md).

**Safety boundary:** mirroring, video, browser, touch, and Quick App launching require an authoritative `PARKED` state. `MOVING` stops projection and leaves the car app. `UNKNOWN` is fail-closed. LAB controls never falsify a real vehicle's speed.

## Current implementation

- One shared `:app` module and one `src/main` implementation with `safe`, `personal`, and `lab` product flavors.
- Central `FeaturePolicy` decisions backed by `RuntimeContextStore` and `VehicleState` flows.
- Production `CAR_SPEED`/`SpeedGate` provider with strict finite-zero parking classification and a three-second stale-update timeout.
- Emulator-only LAB mock provider with `PARKED`, `MOVING`, and `UNKNOWN` transitions; a physical debug phone stays on the production provider.
- Android 14+ MediaProjection foreground service with two explicit renderers: default direct `AUTO_MIRROR` and opt-in `SELF_DRAWN` (`ImageReader -> Canvas -> Android Auto Surface`) for app-controlled FIT/FILL/STRETCH/ONE_TO_ONE output and measured frame diagnostics.
- Shared surface lifecycle, reconnect tracking, safe insets, visible-area bounds, rotation-aware coordinate mapping, and structured mirror diagnostics.
- Accessibility and bounded Shizuku input backends with capability reporting and parked-only enforcement.
- Optional Shizuku user-service capability probes for panel-only power-off and a real pointer-id/action `MotionEvent` sink; both are disabled by default and remain device/host dependent.
- Quick Apps, installed-app filtering, legacy-favorite migration, per-app profiles, Smart Mode, force-landscape cleanup, and explicit session resume/forget actions.
- Media3 progressive/HLS/DASH/local playback through a MediaSession, with controller authorization and independent audio/video policy.
- Entertainment routing that distinguishes HTTPS web pages, audio, and video instead of treating all sources as browser content.
- A Fermata-Xtream-style home on both surfaces: one shared `HomeSection` list renders as the phone launcher grid and as the Android Auto dashboard (TV, Radio, Web browser, Youtube, YouTube Music, YouTube Kids, Folders, Favorites, Playlists, Gallery, then Mirror/Apps/Remote/Settings).
- Xtream Codes and M3U IPTV sources for the TV and Radio sections, with unit-tested credential/playlist parsing, a shared catalog cache, host-aware list paging, favourites and a recently-played list. See [`docs/IPTV_SECTIONS.md`](docs/IPTV_SECTIONS.md).
- Built-in free public playlists ([Free-TV](https://github.com/Free-TV/IPTV), iptv-org, radio-browser): TV and Radio start with default lists so there are channels to browse out of the box, further lists are one tap away in the picker, and any of them can be removed for good. AutoBridge stores addresses only and fetches each list live from the project that publishes it; nothing is hosted, bundled or redistributed here. Community-playlist conventions are read rather than ignored: a channel whose entry is a YouTube/Twitch watch page opens in the browser instead of failing inside the player, and Free-TV's `Ⓢ`/`Ⓖ`/`Ⓨ` name markers become subtitle hints.
- MediaStore-backed Folders, Playlists and Gallery sections on the phone and in the car, reusing the existing MediaSession and car video surface.
- An AutoBridge phone design language (`AutoBridgeDesign`): ink surfaces, hairline borders, a per-section accent that carries from the home card into that section's screens and player, a dependency-free cached image loader for channel logos, a shared now-playing bar, and a designed player with a scrubber and a LIVE state. See [`docs/PHONE_UI.md`](docs/PHONE_UI.md).
- A step-by-step car setup screen: notifications, an input backend (Shizuku or accessibility) and screen capture in the order they happen, each with its live state and one action, plus an optional Bluetooth media-session start. See [`docs/MIRROR_ENGINE.md`](docs/MIRROR_ENGINE.md).
- Optional YouTube add-ons for the in-app browser, off by default: SponsorBlock segment skipping with per-category switches and a privacy-preserving hash-prefix lookup, and an auto-highest-quality setting. Both run in the phone and car browsers from one implementation. See [`docs/YOUTUBE_ADDONS.md`](docs/YOUTUBE_ADDONS.md).
- Bounded Compose phone control-center content embedded in the existing Activity; Android Auto remains host-managed through Car App templates.
- ScreenOnAuto-inspired mirror automation: optional prevent-sleep, timed auto-dim, opt-in panel-only screen-off, stop-on-disconnect, last-app auto-launch, consented auto-open, Shizuku onboarding, and explicit self-drawn renderer selection.

The panel-only and real-touch work is implemented independently from the public [ScreenOnAuto releases](https://github.com/slzn/ScreenOnAuto-releases), which are credited as the behavioral/reference source. AutoBridge does not copy or redistribute an upstream source tree or assets.

The code is implemented and flavored builds/tests are the primary local validation. Ford hardware, production Android Auto host behavior, exact `CAR_SPEED` delivery, MediaProjection consent, MediaSession controller identities, Shizuku, hidden display-power APIs, and raw touch injection remain device-dependent checks.

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

The bounded Compose slice uses the Kotlin Compose compiler plugin and Compose BOM `2025.06.01`, selected because the current project targets compileSdk 36/AGP 8.13. See the [official Compose dependency setup guidance](https://developer.android.com/develop/ui/compose/setup-compose-dependencies-and-compiler).

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

### Installing on a physical phone

The debug variants are signed with the local Android debug key and can be installed directly for development:

```bash
./gradlew assemblePersonalDebug
adb install -r app/build/outputs/apk/personal/debug/app-personal-debug.apk
```

If more than one device is connected, add `-s <device-serial>` to the `adb install` command. The `personal`, `safe`, and `lab` variants use the same `applicationId` (`dev.autobridge`), so install only one at a time unless you intentionally want to replace the existing variant.

`app-personal-release-unsigned.apk` is not installable because it has no signing certificate. The local release signing setup is now available:

```bash
./gradlew assemblePersonalRelease
adb install -r app/build/outputs/apk/personal/release/app-personal-release.apk
```

The keystore is `keystore/autobridge-release.jks` and its local signing properties are in `keystore/release.properties`. Both are ignored by Git; back them up securely because losing this keystore prevents future updates to the same app identity. The generated release certificate SHA-256 fingerprint is:

```text
96:a2:78:fa:a1:39:a0:07:34:a0:fc:42:76:39:fb:aa:d3:fb:2e:a7:0d:86:37:2b:6c:1a:f9:2d:fa:50:4e:7f
```

Do not rename or sideload the `-unsigned.apk` file. A release APK distributed through Google Play/Internal testing must continue using this same keystore for all future updates.

### Google Play upload

Build the signed Android App Bundle for Google Play Internal testing or App Sharing:

```bash
./gradlew :app:bundlePersonalRelease
```

Upload this file in Play Console:

```text
app/build/outputs/bundle/personalRelease/app-personal-release.aab
```

The bundle is signed with the AutoBridge release keystore and contains `dev.autobridge`, version `0.3.5`, and `versionCode 8`. Increment `versionCode` for every later upload; keep the same keystore for updates. Start with Internal testing/App Sharing before attempting production release. The current Android Auto surface is a development/personal-use POC using a `NavigationTemplate` for mirroring, so Play/Android Auto policy approval is not guaranteed.

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

1. Install `personalDebug` only for development or a controlled personal test. For a real vehicle, Android Auto may require a trusted distribution source for apps built with the Android for Cars App Library; use the Desktop Head Unit for sideloaded APK validation, or distribute a signed build through Google Play Internal testing/App Sharing for a vehicle test.
2. Connect Android Auto/DHU and open AutoBridge.
3. Request/grant `CAR_SPEED`; wait for a valid zero-speed callback.
4. Start mirror from the phone and approve fresh MediaProjection consent.
5. Enable Accessibility or Shizuku only if touch testing is required.
6. If testing panel-only screen-off, connect Shizuku, confirm the capability status, and leave the opt-in setting disabled until the device behavior is understood.
7. Stop the session before changing vehicle state; a non-zero speed must stop it automatically.

Android Auto's official testing guidance states that the unknown-sources exception does not apply to apps built with the Android for Cars App Library. This limitation is summarized from the [official Android testing guidance](https://developer.android.com/training/cars/testing/); content was rephrased for compliance with licensing restrictions. Therefore, an ADB-installed `personalDebug` APK can be valid and discoverable by Android's service resolver while still being absent from a real vehicle's launcher.

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
MirrorCoordinator -- AUTO_MIRROR --> Android Auto Surface
       |
       +-------------- SELF_DRAWN --> ImageReader -> RenderPlan/Canvas -> Surface
                                      |
                                      v
                               DHU / vehicle host
       ^
       |
FeaturePolicy <--- RuntimeContextStore <--- VehicleStateSession <--- SpeedGate/LAB mock
       |
       +--> TouchRouter --> Shizuku / Accessibility --> phone input
       |                     |
       |                     +--> optional panel power / raw-touch privileged sink
       +--> Quick Apps / profiles / MediaSession / Entertainment
```

For ownership and lifecycle details see [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) and [`docs/MIRROR_ENGINE.md`](docs/MIRROR_ENGINE.md).

## Platform limitations

- Direct `AUTO_MIRROR` is OS-owned FIT output. The opt-in `SELF_DRAWN` pipeline applies FILL, STRETCH, crop, and ONE_TO_ONE through `RenderPlan`/Canvas; it adds copy/draw latency and must be measured on the target device.
- `SELF_DRAWN` still captures the default display. A dedicated `OWN_CONTENT`/app-owned display remains unavailable and is rejected or falls back safely; panel-only power control is a separate capability and does not claim that own-content boundary.
- The optional prevent-sleep/auto-dim controls use public, deprecated screen WakeLock APIs as a best-effort policy. They do not lock or sleep the device intentionally and must be validated on a real phone.
- When explicitly enabled, the Shizuku user service can attempt panel-only power-off through hidden `SurfaceControl`/`DisplayControl` APIs after capability probing. It defaults to off, falls back to public dimming on failure, and restores panel power during teardown; Android/OEM support is not guaranteed.
- When explicitly enabled, the real-touch sink uses hidden `InputManager.injectInputEvent(MotionEvent)` with bounded pointer IDs, pointer count, and coordinates. The current Android Auto host still exposes click/scroll/fling/scale callbacks rather than a raw pointer stream, so this sink is not fed by `onScale`; the existing pinch path remains synthetic Accessibility input.
- Target-app-only capture/fullscreen cannot be forced reliably through public APIs.
- Protected video surfaces (for example Netflix/Widevine content) may be blank in MediaProjection/AUTO_MIRROR output. AutoBridge does not bypass DRM; use an officially supported car/app integration for protected playback.
- Ford profile values are placeholders until measured on real hardware; see [`docs/FORD_NEXT_GEN.md`](docs/FORD_NEXT_GEN.md).
- A successful JVM test/build does not establish DHU, Ford, wireless Android Auto, panel-off behavior, raw touch landing, media-button, or Shizuku compatibility.

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
- [`docs/YOUTUBE_ADDONS.md`](docs/YOUTUBE_ADDONS.md) — optional SponsorBlock and quality behaviour
- [`docs/DHU_SCENARIOS.md`](docs/DHU_SCENARIOS.md) — manual host/device scenarios
- [`docs/FORD_TEST.md`](docs/FORD_TEST.md) — DHU/Ford safety checklist
- [`docs/CARVIEW_PARITY.md`](docs/CARVIEW_PARITY.md) — product-reference comparison
- [`docs/PROGRESS.md`](docs/PROGRESS.md) — current verification record
