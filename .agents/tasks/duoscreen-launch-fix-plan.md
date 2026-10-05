# Duo Screen launch-fix implementation plan

## Problem

`DuoScreenShizukuOps.launchOnDisplay(displayId, packageName)` builds an *implicit*
`ACTION_MAIN` + `CATEGORY_LAUNCHER` + `setPackage(packageName)` intent and hands it to
`startActivityAsUser` over the Shizuku shell-UID binder. On this device's MIUI build, a package
whose launcher activity is in MIUI's "enabled by default but not yet explicitly enabled" state
fails to resolve from that implicit intent (`am start ... -a MAIN -c LAUNCHER <pkg>` returns
result `-91`, "unable to resolve Intent"), even though `cmd package resolve-activity` and an
explicit `-n <pkg>/.MainActivity` launch both succeed. The pane stays blank. Apps whose launcher
activity is in a normal state (chrome, youtube, calculator, settings, maps) launch fine, which is
why the bug looks intermittent.

Confirmed in code (read 2026 state of the repo):
- `app/src/duoscreen/java/dev/autobridge/duoscreen/DuoScreenShizukuOps.kt` `launchOnDisplay`
  builds the implicit intent and never resolves a `ComponentName`. Its comment says resolution is
  *deliberately* left to the shell transaction to avoid needing `QUERY_ALL_PACKAGES`.
- `app/src/duoscreen/java/dev/autobridge/duoscreen/DuoScreenController.kt` `openPane` calls
  `ops.launchOnDisplay(displayId, packageName)` and logs `= $launched` only — a failed launch
  leaves no diagnosable reason, just a blank pane.

## Decision: option (b) — resolve the launcher ComponentName in-app, launch the explicit component

Reasoning, grounded in the code read:

1. **The permission worry in the comment is already moot for the flavors Duo Screen ships in.**
   `app/src/projection/AndroidManifest.xml` already declares
   `<uses-permission android:name="android.permission.QUERY_ALL_PACKAGES" />`, and that manifest is
   the one merged into the `personal` and `lab` flavors (per `app/build.gradle.kts`
   `android.sourceSets`), which are the only flavors that build the `src/duoscreen` source set.
   The safe/Play flavor never sees this manifest, source set, or permission.
2. **In-app resolution is already the established pattern in the exact same flavor.**
   `DuoScreenSettingsActivity.pickAppFor` already calls
   `packageManager.queryIntentActivities(MAIN/LAUNCHER)` under that permission to build the pane
   picker. Resolving the same launcher activity for launch is consistent with code that already
   runs here; it introduces no new visibility surface.
3. **Option (a) (resolve via `IPackageManager` over the Shizuku binder) is more code for no gain.**
   It would mean standing up a new reflective `IPackageManager`/`resolveActivity`-as-shell path
   next to the activity/input ones. The only thing it buys is keeping resolution under shell
   visibility — a property we no longer need, because the app already legitimately holds
   `QUERY_ALL_PACKAGES` here. Option (b) is the minimal, consistent fix.

**Visibility change justification:** the fix resolves the launcher activity with the *app's* package
visibility instead of shell's. That is acceptable and already true elsewhere in Duo Screen because
`QUERY_ALL_PACKAGES` is held in personal/lab. The shell-UID *launch* itself is unchanged:
`startActivityAsUser` is still invoked as `com.android.shell` on the target display id; only the
`ComponentName` on the intent is now pre-resolved. The now-outdated comment is updated to say so.

### Shape of the change

`DuoScreenShizukuOps` is a stateless `object` with no `Context`, so resolution happens in the
caller (`DuoScreenController`, which holds a `Context`) and the resolved `ComponentName` is passed
down. This keeps the `object` Context-free, keeps the shell-UID launch untouched, and lets the
pure resolution logic be unit-tested without Android.

- Pure helper `DuoScreenLauncherResolver.explicitComponentOrNull(packageName, candidates)` turns a
  package name + the `ResolveInfo`-derived candidate list into an explicit `ComponentName` (or
  null). No Android framework calls inside it beyond the data classes it is handed — it takes
  plain `(packageName, activityName)` pairs so it is JVM-testable, mirroring
  `InstalledAppRepository.prepare` / `DuoScreenLayoutCodec`.
- `DuoScreenController.openPane` resolves via `context.packageManager`, passes the resulting
  `ComponentName?` to `launchOnDisplay`, and logs a clear reason when resolution yields nothing.
- `launchOnDisplay` gains an optional `component: ComponentName?` parameter: when non-null it sets
  the explicit component on the intent; when null it falls back to the existing implicit
  MAIN/LAUNCHER intent (so the shell transaction still resolves it, preserving old behavior for any
  path that cannot resolve in-app, e.g. Shizuku-granted-but-package-hidden edge cases).

---

# Implementation Plan

- [ ] 1. Add the pure resolution helper `DuoScreenLauncherResolver`.
      Create an `object` with `data class LauncherActivity(val packageName: String, val activityName: String)`
      and `fun explicitComponentOrNull(packageName: String, candidates: List<LauncherActivity>): ComponentName?`.
      Logic: pick the first candidate whose `packageName == packageName` (preserve order, as
      `queryIntentActivities` returns best match first), and return
      `ComponentName(it.packageName, it.activityName)`; return null when none match or the list is
      empty. `ComponentName` is a plain parcelable value type usable under the unit-test
      `isReturnDefaultValues` option; if it proves awkward in tests, return the matched
      `LauncherActivity` instead and build the `ComponentName` at the call site — decide while
      writing the test in step 4, keep the public return type consistent with that choice.
      Files: `app/src/duoscreen/java/dev/autobridge/duoscreen/DuoScreenLauncherResolver.kt`
      Verify: `./gradlew :app:assemblePersonalDebug` compiles (helper is referenced in later
      steps; this step alone just needs to compile).

- [ ] 2. Extend `launchOnDisplay` to accept an optional explicit `ComponentName` and launch it when present.
      In `DuoScreenPrivilegedOps.launchOnDisplay`, add a parameter
      `component: ComponentName? = null`. In `DuoScreenShizukuOps.launchOnDisplay`, when
      `component != null` build the intent with `.setComponent(component)` (still
      `ACTION_MAIN` + `CATEGORY_LAUNCHER` + `FLAG_ACTIVITY_NEW_TASK`, no `setPackage` needed when a
      component is set); when null keep the existing implicit `setPackage(packageName)` intent as
      the fallback. Everything downstream (`resolveActivityApi`, `buildStartActivityArgs`,
      `startActivityAsUser` as `com.android.shell`, result handling) is unchanged. Update the
      method's comment: resolution is now done in-app under the `QUERY_ALL_PACKAGES` the
      personal/lab manifest already declares, so the old "deliberately unresolved to avoid
      QUERY_ALL_PACKAGES" rationale no longer holds; the implicit intent remains only as the
      fallback when the caller could not resolve a component.
      Files: `app/src/duoscreen/java/dev/autobridge/duoscreen/DuoScreenPrivilegedOps.kt`,
      `app/src/duoscreen/java/dev/autobridge/duoscreen/DuoScreenShizukuOps.kt`
      Verify: `./gradlew :app:assemblePersonalDebug` — compiles with the new signature.

- [ ] 3. Resolve the component in `openPane` and improve the failure logging.
      In `DuoScreenController.openPane`, before launching, query
      `context.packageManager.queryIntentActivities(Intent(ACTION_MAIN).addCategory(CATEGORY_LAUNCHER).setPackage(packageName), 0)`
      (use the `Build.VERSION.SDK_INT >= 33` `ResolveInfoFlags.of(0L)` form with the pre-33
      `@Suppress("DEPRECATION")` fallback, matching `InstalledAppRepository.queryLaunchableActivities`),
      map the results to `DuoScreenLauncherResolver.LauncherActivity`, call
      `DuoScreenLauncherResolver.explicitComponentOrNull(packageName, candidates)`, and pass the
      result to `ops.launchOnDisplay(displayId, packageName, component)`. When the component is
      null, log a clear warning naming the package and that no launcher activity resolved (so a
      blank pane is diagnosable), then still call `launchOnDisplay` with `component = null` so the
      shell-side implicit fallback runs. Keep the existing success/`= $launched` log line, and
      include whether an explicit component was used in that line.
      Also update `reload(paneId)` to resolve and pass the component the same way (so the "pane
      went black" reload path gets the same fix); extract the resolve-to-ComponentName step into a
      small private helper in the controller to avoid duplicating it between `openPane` and
      `reload`.
      Files: `app/src/duoscreen/java/dev/autobridge/duoscreen/DuoScreenController.kt`
      Verify: `./gradlew :app:assemblePersonalDebug` — compiles; both call sites pass a component.

- [ ] 4. Add the unit test for the pure resolver.
      Create `DuoScreenLauncherResolverTest` in the flavor-specific test source set
      (`src/duoscreen/test`, which `app/build.gradle.kts` wires into `testPersonal`/`testLab`).
      Cover: (a) a single matching candidate yields the explicit component with that activity name;
      (b) multiple candidates for the same package yield the FIRST (best-match ordering preserved);
      (c) candidates for a different package only yields null; (d) an empty candidate list yields
      null. Follow the plain-JUnit pattern of `DuoScreenLayoutCodecTest` /
      `DuoScreenPaneSetTest` (`org.junit.Test`, `org.junit.Assert.*`). If step 1's `ComponentName`
      return type throws "not mocked" under JVM tests despite `isReturnDefaultValues = true`,
      switch the helper to return the matched `LauncherActivity` (per step 1's note) and assert on
      its `packageName`/`activityName`.
      Files: `app/src/duoscreen/test/dev/autobridge/duoscreen/DuoScreenLauncherResolverTest.kt`
      Verify: `./gradlew :app:testPersonalDebugUnitTest` — the new tests pass and no existing
      duoscreen test regresses.

- [ ] 5. Full build + test gate.
      No code change; final verification of the whole fix.
      Files: none.
      Verify: `./gradlew :app:assemblePersonalDebug` builds, then
      `./gradlew :app:testPersonalDebugUnitTest` passes all unit tests (new resolver test plus the
      existing `DuoScreenLayoutCodecTest`, `DuoScreenPaneSetTest`, `DuoScreenInputRouterTest`,
      `DuoScreenResizeDebouncerTest`, `DuoScreenThreadSlotTest`, `DuoScreenLayoutTest`).

## Notes / assumptions

- The on-device confirmation of the actual launch (serial `YXEMRCGYAI49S4SS`) is out of scope for
  this plan's automated verification, which is JVM build + unit tests only, matching the task's
  stated verification commands. The runtime behavior is exercised by the pure resolver test plus
  the compile-checked call-site wiring.
- The implicit-intent fallback is retained deliberately: it preserves the exact pre-fix behavior
  for any package the in-app resolver cannot match, so apps that already worked cannot regress.
- No change touches the safe/Play flavor, the main manifest, or the shell-UID launch mechanics.

## Verification

Implemented first iteration (no review json present). Changes:
- `DuoScreenLauncherResolver.kt` (new): pure `explicitActivityOrNull(packageName, candidates)` →
  first `LauncherActivity` matching the package, else null. Returns `LauncherActivity` (not
  `ComponentName`) because `ComponentName`'s accessors return unmocked defaults under JVM unit
  tests (`isReturnDefaultValues = true`); the caller builds the `ComponentName`.
- `DuoScreenPrivilegedOps.launchOnDisplay` / `DuoScreenShizukuOps.launchOnDisplay`: added
  `component: ComponentName? = null`; when non-null the intent uses `.component`, else the
  existing implicit `setPackage(packageName)` fallback. Shell-UID `startActivityAsUser` launch
  unchanged. Stale "deliberately unresolved to avoid QUERY_ALL_PACKAGES" comment replaced to
  describe the in-app resolution + fallback.
- `DuoScreenController`: `resolveLauncherComponent(packageName)` queries
  `queryIntentActivities(MAIN/LAUNCHER+setPackage)` under the personal/lab QUERY_ALL_PACKAGES
  (SDK≥33 `ResolveInfoFlags` form + pre-33 fallback, matching `InstalledAppRepository`), maps to
  resolver candidates, builds the `ComponentName`, and `StructuredLog.w`'s a clear reason naming
  the package when nothing resolves. Both `openPane` and `reload` call it; the `openPane` success
  line now says whether an explicit component or the implicit fallback was used.
- `DuoScreenLauncherResolverTest.kt` (new): 4 cases — single match, first-of-many (order
  preserved), wrong-package → null, empty → null.

Constraints held: QUERY_ALL_PACKAGES unchanged (still only in `src/projection/AndroidManifest.xml`,
personal/lab); shell-UID launch and injection paths untouched; fallback preserves pre-fix behavior
for already-working apps.

Commands run (from repo root), all on 2026-10-04:

1. `./gradlew :app:assemblePersonalDebug` → **BUILD SUCCESSFUL** (6s).
2. `./gradlew :app:testPersonalDebugUnitTest` → first run **FAILED**: the 2 resolver cases
   asserting on `ComponentName` accessors hit unmocked defaults, as the plan's step-1/step-4 note
   anticipated. Switched the helper to return `LauncherActivity`; re-ran
   `./gradlew :app:assemblePersonalDebug :app:testPersonalDebugUnitTest` → **BUILD SUCCESSFUL**,
   574 tests completed, 0 failed (the 4 new resolver tests plus every existing duoscreen and core
   test).

Optional on-device (serial `YXEMRCGYAI49S4SS`):

3. `./gradlew :app:installPersonalDebug` → **Installed on 1 device.**
4. The in-process pane selection (`DuoScreenController.reload`/`openPane`) cannot be triggered from
   adb, so it was not exercised directly. Instead the fix's premise was confirmed on-device:
   - `cmd package resolve-activity -c android.intent.category.LAUNCHER dev.autobridge` →
     `name=dev.autobridge.MainActivity packageName=dev.autobridge` (so the in-app query yields the
     explicit component the fix launches).
   - `am start -a android.intent.action.MAIN -c android.intent.category.LAUNCHER dev.autobridge`
     (the implicit buggy path) → `Error: Activity not started, unable to resolve Intent` — the
     blank-pane repro.
   - `am start -n dev.autobridge/.MainActivity` (the explicit path the fix now takes) →
     `Starting: Intent { cmp=dev.autobridge/.MainActivity }`, succeeds.
