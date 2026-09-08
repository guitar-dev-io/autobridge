# Progress and verification

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
- Added pure regression coverage for safety decisions, transforms, mapping, profiles, media resolution, command vectors, reconnect outcomes, and diagnostic formatting/clearing.

## Latest local validation

The following latest batches passed after the task #8 and task #9 changes:

```text
./gradlew compileSafeDebugKotlin compilePersonalDebugKotlin compileLabDebugKotlin
./gradlew testSafeDebugUnitTest testPersonalDebugUnitTest testLabDebugUnitTest
```

The test suites execute the same shared tests against each flavor. Gradle continues to emit the known warning that Gradle 8.13 will eventually need to move to 8.14.4 for future Kotlin 2.5 compatibility; it is not a build failure.

## Device/host evidence boundary

Earlier DHU/emulator work established useful template/surface and render wiring, but it did not establish all production behavior. The current claims remain bounded:

- a valid production `CAR_SPEED` zero sample from the target host is still required;
- Ford surface/DPI/visible-area values remain unmeasured;
- wireless Android Auto, controller identity, media buttons, Accessibility, Shizuku, touch landing, and reconnect need device runs;
- direct AUTO_MIRROR screen-off survival, FILL/STRETCH output, app-only rendering, and raw multi-touch are platform/architecture partials.

## Final local validation

The required final matrix passed with a single-use Gradle daemon:

```text
./gradlew --no-daemon testSafeDebugUnitTest testPersonalDebugUnitTest testLabDebugUnitTest assembleSafeDebug assemblePersonalDebug assembleLabDebug
```

Result: `BUILD SUCCESSFUL`; all three flavors executed 69 JVM tests and all three debug APK assemblies completed. A representative lint run also passed:

```text
./gradlew --no-daemon --max-workers=1 lintSafeDebug
```

Lint reports warnings only: target/Gradle/dependency freshness, debug exported-service configuration, WakeLock lifetime analysis, existing overdraw/icon/unused-resource findings, and existing localization/KTX suggestions. No lint errors were reported. An all-flavor lint invocation also completed earlier; a later combined lint graph hit a Gradle/lint merged-manifest race, so the representative standalone lint result is the reliable final lint evidence.

See [`DHU_SCENARIOS.md`](DHU_SCENARIOS.md), [`FORD_TEST.md`](FORD_TEST.md), [`FORD_NEXT_GEN.md`](FORD_NEXT_GEN.md), and [`LAB_MODE.md`](LAB_MODE.md).
