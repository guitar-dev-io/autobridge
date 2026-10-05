# Explicit launcher-activity resolution for Duo Screen panes

Duo Screen launched a pane's app with an implicit `MAIN`/`LAUNCHER` + `setPackage` intent over the Shizuku shell-UID binder, which on this MIUI build fails to resolve for a package whose launcher activity is "enabled by default but not yet explicitly enabled" (repro `dev.autobridge`, `am start` result `-91`), leaving the pane blank. The fix resolves the launcher activity to an explicit `ComponentName` in-app — using the `QUERY_ALL_PACKAGES` visibility the personal/lab manifest already holds — and launches that explicit component, keeping the implicit intent only as a fallback when nothing resolves. The shell-UID launch mechanics are untouched; only the `ComponentName` on the intent is now pre-resolved. A pure resolver helper carries the match logic and is covered by a JVM unit test.

Watch for: nothing blocking. The one behavioral asymmetry worth noting is that `reload()` does not log the resolve-failure reason the way `openPane()` does (likely), a minor diagnosability gap rather than a correctness bug.

**Verdict**: APPROVED

## High-level view

The design follows the plan's option (b): resolution happens in `DuoScreenController` (which holds a `Context`), not in the stateless `DuoScreenShizukuOps` object, so the privileged ops stay Context-free and the shell-UID launch path is unchanged. `launchOnDisplay` gained an optional `component: ComponentName?`; non-null sets the explicit component, null preserves the old implicit `setPackage` intent as a fallback, so any app that already launched cannot regress.

The package-visibility posture is correct and the most security-relevant point here. `QUERY_ALL_PACKAGES` lives only in `src/projection/AndroidManifest.xml`, which `app/build.gradle.kts` wires into the `personal` and `lab` flavors — the only flavors that compile the duoscreen source set. The safe/Play flavor and the always-on main manifest never see the permission. The fix adds no new visibility surface: resolution reuses the exact query the pane picker already runs under that same permission.

The match logic is isolated in a pure `DuoScreenLauncherResolver.explicitActivityOrNull` that takes plain `(packageName, activityName)` pairs and returns the first candidate matching the package, preserving `queryIntentActivities` best-match ordering. It returns `LauncherActivity` rather than `ComponentName` so it stays JVM-testable, and the controller builds the `ComponentName`. Four unit tests cover single match, first-of-many ordering, wrong-package, and empty.

Failure diagnosability improved on the `openPane` path: a null resolution is logged with the package name and candidate count, and the success line records whether the explicit component or the implicit fallback was used. The `reload` path shares the resolver helper but not that logging.

<details>
<summary>Issues (2)</summary>

1. **reload() resolve-failure logging** — `reload(paneId)` calls `resolveLauncherComponent` (which warns on null) but, unlike `openPane`, logs neither the launch result nor whether an explicit/implicit path was taken; a pane that reloads blank is less diagnosable than one that opens blank. Non-blocking; consider matching `openPane`'s success line. (likely)
2. **Verification evidence is self-reported in the plan** — build/test results (`BUILD SUCCESSFUL`, 574 tests, 0 failed) come from the plan doc, not re-run here per instruction. Accepted as evidence; no action unless the loop requires independent confirmation. (confirmed)

</details>

<details>
<summary>Details</summary>

### Resolution lives in the controller, not the privileged object

`DuoScreenShizukuOps` is a stateless `object` with no `Context`, so it cannot query `PackageManager` itself. The fix puts resolution in `DuoScreenController.resolveLauncherComponent(packageName)`, which holds a `Context`, and passes the resulting `ComponentName?` down into `launchOnDisplay`. This keeps the privileged object Context-free and leaves the shell-UID transaction exactly as it was.

```
DuoScreenController.openPane / reload
  -> resolveLauncherComponent(pkg)                         [app visibility, QUERY_ALL_PACKAGES]
       queryIntentActivities(MAIN/LAUNCHER + setPackage)
       -> DuoScreenLauncherResolver.explicitActivityOrNull  [pure, unit-tested]
       -> ComponentName(pkg, activity)  or  null
  -> ops.launchOnDisplay(displayId, pkg, component)
       component != null -> intent.component = component     [explicit]
       component == null -> intent.setPackage(pkg)           [implicit fallback]
       -> startActivityAsUser as com.android.shell on displayId   [unchanged]
```

The `launchOnDisplay` body sets `FLAG_ACTIVITY_NEW_TASK` on both branches and only differs in `intent.component` vs `intent.setPackage`, which is the whole behavioral change at the launch site. Everything downstream — `resolveActivityApi`, `buildStartActivityArgs`, the `startActivityAsUser` invocation as `com.android.shell`, and the `result >= 0` handling — is unchanged, so the fix does not touch the shell-UID attribution or the display targeting.

### Package visibility stays scoped to sideload flavors

The resolution runs with the app's own package visibility rather than shell's. That is only sound because `QUERY_ALL_PACKAGES` is held, and it is held in exactly the right place: `src/projection/AndroidManifest.xml` declares it, `app/build.gradle.kts` merges that manifest into `personal`/`lab` only, and those are the only flavors that compile `src/duoscreen`. Confirmed by search: the permission appears in no other manifest — not `src/main`, not any safe/Play manifest. The query itself (`Intent(MAIN).addCategory(LAUNCHER).setPackage(packageName)`) is the same one `DuoScreenSettingsActivity`'s pane picker already issues under this permission, so no new visibility surface is introduced — the fix reuses an access the flavor already legitimately exercises.

The SDK-gated query form matches the established pattern: `ResolveInfoFlags.of(0L)` on API ≥ 33, the `@Suppress("DEPRECATION")` int-flags call below it, mirroring `InstalledAppRepository.queryLaunchableActivities`.

### The fallback preserves pre-fix behavior

When `explicitActivityOrNull` returns null, `resolveLauncherComponent` returns null and `launchOnDisplay` takes the implicit `setPackage` branch — the exact pre-fix intent, resolved shell-side. So a package the in-app resolver cannot match (e.g. Shizuku granted but the package hidden from the app) still launches the way it did before; the change can only add resolutions, never remove them. Apps that already worked (chrome, youtube, maps) resolve to their explicit component now, but that is the same activity the implicit intent would have found, launched the same way.

### Pure resolver and its test

`DuoScreenLauncherResolver.explicitActivityOrNull(packageName, candidates)` is a one-liner — `candidates.firstOrNull { it.packageName == packageName }` — with no framework calls, taking `LauncherActivity(packageName, activityName)` value objects. Returning `LauncherActivity` instead of `ComponentName` is a deliberate choice recorded in the plan: `ComponentName`'s accessors return unmocked defaults under the JVM test runtime, so the controller builds the `ComponentName` from the returned pair. The test file lives in `src/duoscreen/test`, wired into `testPersonal`/`testLab` by the build script, and covers the first-of-many ordering case that protects the best-match guarantee — the property that matters most for picking the right launcher activity when a package exposes several.

### openPane logs a diagnosable reason; reload does not

`resolveLauncherComponent` emits a `StructuredLog.w` naming the package and candidate count when nothing resolves, so a blank pane is no longer silent. `openPane`'s success line additionally records `explicit $component` vs `implicit fallback`. `reload(paneId)`, however, returns `ops.launchOnDisplay(...)` directly without logging the result or the path taken. The shared null-resolution warning still fires from the helper, so a total resolution failure is visible, but a pane that reloads blank for any other reason is less diagnosable than one that opens blank. Minor, non-blocking.

</details>

<details>
<summary>File map</summary>

The whole `app/src/duoscreen/` tree is untracked in git (`?? app/src/duoscreen/`), so `git diff` shows nothing for it; the change was reviewed by reading the working-tree files directly against the plan.

- `app/src/duoscreen/java/dev/autobridge/duoscreen/DuoScreenLauncherResolver.kt` — new pure helper: first candidate matching the package, else null.
- `app/src/duoscreen/java/dev/autobridge/duoscreen/DuoScreenPrivilegedOps.kt` — `launchOnDisplay` gains `component: ComponentName? = null`; interface doc updated.
- `app/src/duoscreen/java/dev/autobridge/duoscreen/DuoScreenShizukuOps.kt` — explicit-component branch + implicit fallback; stale "deliberately unresolved" comment replaced.
- `app/src/duoscreen/java/dev/autobridge/duoscreen/DuoScreenController.kt` — `resolveLauncherComponent` helper, wired into `openPane` and `reload`, with failure logging.
- `app/src/duoscreen/test/dev/autobridge/duoscreen/DuoScreenLauncherResolverTest.kt` — new, 4 cases.
- `app/src/projection/AndroidManifest.xml` — holds `QUERY_ALL_PACKAGES` (unchanged by this fix; verified scoped to personal/lab).

</details>
