# Duo Screen launch-fix + on-device touch verification — Implementation Plan

Working directory for every path and command below: `/Users/anuwat.t/Documents/ChatGPT/AutoBridge` (MAIN-TREE mode per the setup step; the `app/src/duoscreen/` tree is uncommitted — **do not commit anything**).
Device serial for every `adb`: `YXEMRCGYAI49S4SS`. Build flavor: `personal` (debug).

## Confirmed root cause (from live logcat; do not re-derive)

The pane launch fails at **intent resolution**, before any touch is exercised:
`startActivityAsUser(dev.autobridge -> display 2910) returned -91`, `ActivityStarterImpl: aInfo is null for resolve intent: Intent { act=MAIN cat=[LAUNCHER] pkg=dev.autobridge }`, result code `-91` = `START_CANCELED`. Yet `adb shell cmd package resolve-activity -c android.intent.category.LAUNCHER dev.autobridge` resolves `dev.autobridge.MainActivity`. So the implicit MAIN/LAUNCHER intent fails to resolve only inside the Shizuku shell-UID `startActivityAsUser` path with `setLaunchDisplayId` (MIUI XSpace/ActivityStarter involved).

Two distinct problems are visible in that evidence and both must be addressed:
1. **Wrong launch target.** The package launched was `dev.autobridge` (the app itself). The code defaults (`DuoScreenScreen.DEFAULT_PANES`, `DuoScreenSpikeActivity.DEFAULT_PACKAGES`) are `com.miui.calculator`/`com.android.settings`, never `dev.autobridge`. So `dev.autobridge` must come from the persisted layout (`DuoScreenStore`) — a pane whose stored package is the app's own package, selectable through `DuoScreenSettingsActivity.pickAppFor` which lists every launchable app including AutoBridge. This must be confirmed on-device and the self-launch guarded against.
2. **Implicit-intent resolution failure.** `DuoScreenShizukuOps.launchOnDisplay` builds an implicit `ACTION_MAIN`+`CATEGORY_LAUNCHER`+`setPackage` intent and relies on shell to resolve the component; MIUI returns `aInfo is null`.

## Design decisions (made here, grounded in the code read)

- **Decision — resolve the launcher `ComponentName` in-app and launch the explicit component (least-invasive fix "a").** `app/src/projection/AndroidManifest.xml` already declares `QUERY_ALL_PACKAGES`, and that manifest is the one merged into the `personal`/`lab` flavors that build `src/duoscreen` (per `app/build.gradle.kts` `android.sourceSets`). `DuoScreenSettingsActivity.pickAppFor` already resolves launcher activities in-process under that permission. So we can resolve the explicit `ComponentName` in `DuoScreenController` (it holds a `Context`) and pass it down — **no new `<queries>` entry and no blanket permission is needed; it is already held.** The in-app resolver is a pure function so it is JVM-unit-testable. The shell-UID launch itself is unchanged; only the intent now carries a pre-resolved component. The implicit intent is kept as a fallback for packages the in-app resolver cannot match, so already-working apps cannot regress. Chosen over resolving via a reflective `IPackageManager`-as-shell path, which is more code for a visibility property we no longer need.
- **Decision — guard against launching the app's own package into a pane.** Confirm on-device that the `dev.autobridge` target originates from stored pane config, then prevent self-launch: filter `dev.autobridge` (the app's own `packageName`) out of `DuoScreenSettingsActivity.pickAppFor`'s candidate list, and skip/clear it in `DuoScreenController.openPane` with a clear log. Launching the host app into its own car-hosted pane is never a valid Duo Screen target and is the concrete cause of the `-91` seen in the capture.
- **Decision — verify every launch change on-device via logcat, least-invasive first**, matching the task's required escalation: (a) explicit-component resolution; (b) if MIUI still cancels the cross-display launch, capture the exact rejection line and classify whether it is the trusted-display/display-owner boundary rather than resolution.
- **Decision — touch is only tested after a real app is live in a pane.** Prior investigation (`.agents/tasks/duoscreen-touch-display-investigation.md`) predicts cross-display injection into an own-content untrusted VirtualDisplay is likely PLATFORM-BLOCKED, but the launch never succeeded so touch was never actually exercised. The verdict must be recorded from fresh on-device logs, not assumed.

## Verification commands (project-real)

- Build gate: `./gradlew :app:assemblePersonalDebug`
- Unit regression: `./gradlew :app:testPersonalDebugUnitTest`
- Install for device test: `./gradlew :app:installPersonalDebug`
- On-device log capture pattern: `adb -s YXEMRCGYAI49S4SS logcat -c` → reproduce → `adb -s YXEMRCGYAI49S4SS logcat -d -s AutoBridgeDuoOps:* ActivityTaskManager:* ActivityStarterImpl:* XSpaceManagerServiceImpl:*` (touch phase adds `InputDispatcher:* InputManager:*`).

---

# Implementation Plan

- [ ] 1. Add the pure launcher-resolution helper.
      Create `object DuoScreenLauncherResolver` with `data class LauncherActivity(val packageName: String, val activityName: String)` and `fun explicitComponentOrNull(packageName: String, candidates: List<LauncherActivity>): ComponentName?` — pick the first candidate whose `packageName` matches (preserving `queryIntentActivities` best-match order) and return its `ComponentName`; null when none match or the list is empty. Keep it free of framework calls (plain data in) so it is JVM-testable, mirroring `DuoScreenLayoutCodec`.
      Files: `app/src/duoscreen/java/dev/autobridge/duoscreen/DuoScreenLauncherResolver.kt`
      Verify: `./gradlew :app:assemblePersonalDebug` compiles.

- [ ] 2. Extend `launchOnDisplay` to accept an optional explicit `ComponentName`.
      Add `component: ComponentName? = null` to `DuoScreenPrivilegedOps.launchOnDisplay`. In `DuoScreenShizukuOps.launchOnDisplay`, when `component != null` set `.setComponent(component)` (still `ACTION_MAIN`+`CATEGORY_LAUNCHER`+`FLAG_ACTIVITY_NEW_TASK`); when null keep the existing implicit `setPackage` fallback. Downstream (`buildStartActivityArgs`, shell-UID `startActivityAsUser`, result handling) unchanged. Update the stale "deliberately unresolved to avoid QUERY_ALL_PACKAGES" comment to say resolution is now done in-app under the already-held permission, with the implicit intent kept only as fallback.
      Files: `app/src/duoscreen/java/dev/autobridge/duoscreen/DuoScreenPrivilegedOps.kt`, `app/src/duoscreen/java/dev/autobridge/duoscreen/DuoScreenShizukuOps.kt`
      Verify: `./gradlew :app:assemblePersonalDebug` compiles with the new signature.

- [ ] 3. Resolve the component in the controller, guard self-launch, improve logging.
      In `DuoScreenController`, add a private helper that queries `context.packageManager.queryIntentActivities(Intent(ACTION_MAIN).addCategory(CATEGORY_LAUNCHER).setPackage(packageName), 0)` (use the SDK 33+ `ResolveInfoFlags.of(0L)` form with a `@Suppress("DEPRECATION")` pre-33 fallback), maps results to `DuoScreenLauncherResolver.LauncherActivity`, and returns `explicitComponentOrNull(...)`. Call it in both `openPane` and `reload`, passing the resolved component to `ops.launchOnDisplay(displayId, packageName, component)`. Guard self-launch: if `packageName == context.packageName` (the app's own package, `dev.autobridge`), skip the launch and log a clear warning (this is the concrete `-91` cause in the capture). When resolution yields null for any other package, log a clear warning naming the package, then still call with `component = null` so the implicit fallback runs. Keep the `= $launched` success log and note whether an explicit component was used.
      Files: `app/src/duoscreen/java/dev/autobridge/duoscreen/DuoScreenController.kt`
      Verify: `./gradlew :app:assemblePersonalDebug` compiles; both call sites pass a component.

- [ ] 4. Exclude the app's own package from the pane picker.
      In `DuoScreenSettingsActivity.pickAppFor`, filter `packageName == this.packageName` out of the launchable list so AutoBridge itself can no longer be chosen as a pane app (prevents re-storing the `dev.autobridge` self-target that caused `-91`).
      Files: `app/src/duoscreen/java/dev/autobridge/duoscreen/DuoScreenSettingsActivity.kt`
      Verify: `./gradlew :app:assemblePersonalDebug` compiles.

- [ ] 5. Unit-test the pure resolver.
      Create `DuoScreenLauncherResolverTest` in `src/duoscreen/test` (wired into `testPersonal`/`testLab` by `app/build.gradle.kts`), plain JUnit like `DuoScreenPaneSetTest`. Cover: single match → explicit component with that activity; multiple candidates → first; different-package candidates → null; empty list → null. If `ComponentName` throws "not mocked" under JVM tests despite `isReturnDefaultValues`, switch the helper to return the matched `LauncherActivity` and build the `ComponentName` at the call site, asserting on `packageName`/`activityName`.
      Files: `app/src/duoscreen/test/dev/autobridge/duoscreen/DuoScreenLauncherResolverTest.kt`
      Verify: `./gradlew :app:testPersonalDebugUnitTest` — new tests pass, no existing duoscreen test regresses.

- [ ] 6. Build + unit-test gate for the whole code fix.
      No code change. Final JVM gate before going on-device.
      Files: none.
      Verify: `./gradlew :app:assemblePersonalDebug` builds, then `./gradlew :app:testPersonalDebugUnitTest` passes all unit tests.

- [ ] 7. Install and confirm the stored self-target on-device, then reproduce the launch.
      `./gradlew :app:installPersonalDebug`. First confirm the `dev.autobridge` origin: inspect the stored layout — `adb -s YXEMRCGYAI49S4SS shell run-as dev.autobridge cat /data/data/dev.autobridge/shared_prefs/autobridge_duo_screen.xml` (or via the Duo Screen settings screen) — and record whether a pane's stored package is `dev.autobridge`. If so, re-pick valid pane apps (e.g. `com.miui.calculator`, `com.android.settings`) through the Duo Screen settings screen now that step 4 excludes self. Then `adb -s YXEMRCGYAI49S4SS logcat -c`, launch the Duo Screen session (DHU/car surface, or `adb -s YXEMRCGYAI49S4SS shell am start -n dev.autobridge/.duoscreen.DuoScreenSpikeActivity --es packages com.miui.calculator,com.android.settings` as the on-phone harness), then `adb -s YXEMRCGYAI49S4SS logcat -d -s AutoBridgeDuoOps:* ActivityTaskManager:* ActivityStarterImpl:* XSpaceManagerServiceImpl:*`.
      Files: none (on-device).
      Verify: the launch no longer returns `-91` with `aInfo is null`; log shows `startActivityAsUser(<real pkg> -> display N)` with result `>= 0` and the pane app visibly runs. Record the exact log lines in the report.

- [ ] 8. If MIUI still cancels the cross-display launch, capture and classify the rejection.
      Only if step 7 still fails after explicit-component resolution: re-run the capture and record the exact rejection line (XSpace/ActivityStarter/trusted-display). Determine whether it is now a trusted-display/display-owner restriction (the platform boundary `ROADMAP.md` flags under "Own-content and privileged display — PARTIAL/BLOCKED") rather than intent resolution, and document precisely which. If resolution now succeeds and the launch lands, mark launch FIXED and skip to step 9.
      Files: none (on-device); findings recorded in the report.
      Verify: a precise verdict for the launch — FIXED, or PLATFORM-BLOCKED with the exact failing log line and the boundary it hits.

- [ ] 9. Touch test — only once an app is actually live in a pane.
      With a real app running in a pane (from step 7/8), tap a button in that pane on the surface. `adb -s YXEMRCGYAI49S4SS logcat -c` before the tap, then `adb -s YXEMRCGYAI49S4SS logcat -d -s AutoBridgeDuoOps:* InputDispatcher:* InputManager:*`. Determine (a) whether `IInputManager.injectInputEvent` and `MotionEvent.setDisplayId` resolve (no "unavailable"/"Could not reach the input manager" warnings) and the injection is attempted, and (b) whether the event reaches and actuates the pane app (button reacts), vs. being dropped at the untrusted-display boundary.
      Files: none (on-device).
      Verify: a recorded touch verdict — touch WORKS/FIXABLE, or PLATFORM-BLOCKED — with the exact failing or succeeding log line.

- [ ] 10. Write the report and the loop stop verdict.
      Record both verdicts (launch, touch) with the exact log lines in `/Users/anuwat.t/Documents/ChatGPT/AutoBridge/.agents/tasks/duoscreen-launch-fix-report.md`. Write `/Users/anuwat.t/Documents/ChatGPT/AutoBridge/.agents/tasks/duoscreen-launch-review.json` with top-level `"verdict": "APPROVED"` only once the code fix builds, unit tests pass, and the on-device launch+touch outcomes are recorded (a PLATFORM-BLOCKED touch verdict with evidence is a complete, approvable outcome — the task is to fix-or-prove-unfixable, not to force touch to work).
      Files: `/Users/anuwat.t/Documents/ChatGPT/AutoBridge/.agents/tasks/duoscreen-launch-fix-report.md`, `/Users/anuwat.t/Documents/ChatGPT/AutoBridge/.agents/tasks/duoscreen-launch-review.json`
      Verify: both files exist; the review JSON's top-level `verdict` is `APPROVED`.

## Notes / assumptions

- Nothing here touches the safe/Play flavor, the main manifest, or the shell-UID launch mechanics; only the intent's component and the pane picker/launch guards change.
- The implicit-intent fallback is retained so any package the in-app resolver cannot match keeps its pre-fix behavior.
- If the device is disconnected at verification time, record that the JVM gate (steps 1–6) passed and that the on-device steps are blocked on hardware, rather than fabricating a result.
