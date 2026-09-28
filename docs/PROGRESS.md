Updated: 2026-09-08

This file is the current verification record. Older entries that reported 43/46 tests or a single unflavored debug build describe earlier source states and should not be used as the current variant status.

## Baseline

The original single-module project was inspected and its unflavored debug tests/build passed before feature work. The project was then upgraded in place; it was not rebuilt as a new project or split into duplicated flavor source trees.

## Implemented milestones

- Added shared runtime models, `RuntimeContextStore`, vehicle profiles, structured logs, and centralized `FeaturePolicy`.
- Added `safe`, `personal`, and `lab` flavors with `BuildConfig.AUTOBRIDGE_MODE` and release/debug DEV_MODE boundaries.
- Added production `SpeedGate`, emulator/LAB mock provider, shared vehicle leases, stale-speed expiry, and first-callback ordering protection.
- Added `MirrorEngine`/`AutoMirrorEngine`, explicit surface state, resize/rebind behavior, safe/visible geometry, rotation-aware mapping, and reconnect tracking.
- Added Quick Apps as the phone/car source of truth, profile persistence/reset, Smart Mode, force-landscape restoration, and explicit session restore/forget.
- Added the bounded Compose phone control-center slice while retaining Activity and Android Auto template ownership.
- Added developer/LAB diagnostics, bounded Shizuku commands and dead-binder cleanup, MediaSession controller authorization, media connection state, WEB/AUDIO/VIDEO routing, and consent-preserving ScreenOnAuto-inspired sleep/dim/disconnect/auto-start settings.
- Added opt-in Shizuku panel-only power probing through hidden display-control APIs, public-dim fallback, teardown restoration, and explicit default-disabled settings.
- Added a bounded Shizuku hidden `InputManager` real-touch sink, AIDL pointer operations, raw router entry points, capability reporting, cancellation, and explicit host-source limitation; the existing Accessibility pinch remains the fallback for `onScale`.
- Added pure regression coverage for safety decisions, transforms, mapping, profiles, media resolution, command vectors, reconnect outcomes, diagnostic formatting/clearing, privileged defaults/failure boundaries, raw-pointer validation, and `OWN_CONTENT` availability.
- Added opt-in `SELF_DRAWN` ImageReader/Canvas rendering with shared RenderPlan geometry, FILL/STRETCH/crop/ONE_TO_ONE output, FPS throttling, frame counters, and surface-generation protection.

## Latest local validation

The current privileged-port source state passed compilation and unit tests with:

```text
./gradlew :app:compileSafeDebugKotlin :app:compilePersonalDebugKotlin :app:compileLabDebugKotlin testSafeDebugUnitTest testPersonalDebugUnitTest testLabDebugUnitTest --no-daemon --console=plain
```

The same shared tests ran successfully for `safe`, `personal`, and `lab`: **74 tests per flavor** (13 `AppProfileRegressionTest`, 49 `CoreRegressionTest`, 9 `RuntimeBoundaryTest`, and 3 `ContentAddressTest`), with zero failures, errors, or skips. The test reports were verified under `app/build/test-results/test{Safe,Personal,Lab}DebugUnitTest`.

The Compose dependency is pinned to BOM `2025.06.01`/Compose UI `1.8.3`, which is compatible with the current compileSdk 36 and AGP 8.13.2 toolchain. Gradle continues to emit the known warning that Gradle 8.13 will eventually need to move to 8.14.4 for future Kotlin 2.5 compatibility; it is not a build failure.

## Device/host evidence boundary

Earlier DHU/emulator work established useful template/surface and render wiring, but it did not establish all production behavior. The current claims remain bounded:

- a valid production `CAR_SPEED` zero sample from the target host is still required;
- Ford surface/DPI/visible-area values remain unmeasured;
- wireless Android Auto, controller identity, media buttons, Accessibility, Shizuku binding, touch landing, and reconnect need device runs;
- the optional panel-only backend uses hidden `SurfaceControl`/`DisplayControl` APIs and needs phone/OEM/Android capability and failure/restore validation;
- SELF_DRAWN output, ImageReader color/order, exact FPS, CPU/battery cost, surface reconnects, and protected content need device runs;
- dedicated OWN_CONTENT/per-app display remains unavailable;
- the real-touch sink is implemented, but current Android Auto callbacks do not provide the raw pointer IDs/actions needed for end-to-end multi-touch; no synthetic `onScale` conversion is claimed.

## Final local validation

The final all-flavor lint and assemble matrix passed as separate single-use Gradle invocations:

```text
./gradlew lintSafeDebug lintPersonalDebug lintLabDebug --no-daemon --max-workers=1 --console=plain
./gradlew assembleSafeDebug assemblePersonalDebug assembleLabDebug --no-daemon --console=plain
```

Both commands returned `BUILD SUCCESSFUL`. All three debug APK assemblies completed. All three flavor lint tasks completed without lint errors; Gradle emitted only the known toolchain/dependency freshness warning(s) and the existing project lint findings. The compile/test command above also returned `BUILD SUCCESSFUL` after the JVM-only boundary test was kept independent of Android `SystemClock` initialization.

`git diff --check` remains the final worktree formatting check. See [`DHU_SCENARIOS.md`](DHU_SCENARIOS.md), [`FORD_TEST.md`](FORD_TEST.md), [`FORD_NEXT_GEN.md`](FORD_NEXT_GEN.md), and [`LAB_MODE.md`](LAB_MODE.md).

## 2026-09-10 — Phone panel controls

Added manual Dim phone now / Restore phone screen in the phone Mirror Settings and
car mirror control panel, plus runtime display diagnostics. Panel-off retains a
display wake lock; brightness changes overlap old/new lock ownership. Failed
restoration remains visible and retryable. Automatic dimming requires an active
permitted mirror. Existing speed/feature gates are unchanged.

Validation: `testSafeDebugUnitTest`, `testPersonalDebugUnitTest`, and
`testLabDebugUnitTest` each passed 78 tests (zero failures/errors/skips).
`assemblePersonalDebug` and `lintPersonalDebug` passed; `git diff --check` passed.
Four new PanelPowerLease tests exercise ordering, idempotence, failure and retry.
No APK installation or physical panel/touch verification was performed this turn;
follow the new scenarios in DHU_SCENARIOS.md on the target phone/head unit.
