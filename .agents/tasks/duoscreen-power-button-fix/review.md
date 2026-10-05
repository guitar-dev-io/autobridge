# Trusted own-group VirtualDisplay to keep the car pane alive across a phone power-off

The Duo Screen car pane was created as an ordinary app `VirtualDisplay` with only `FLAG_OWN_CONTENT_ONLY | FLAG_SUPPORTS_TOUCH`, which lives in the default display group and follows display 0's power state — so a hardware POWER-button `goToSleep` blanks the car/DHU surface along with the phone panel. This change adds a privileged creation path that mints the pane display as `FLAG_TRUSTED | FLAG_OWN_DISPLAY_GROUP` over the already-proven Shizuku shell-UID binder seam (uid 2000 holds `ADD_TRUSTED_DISPLAY`, which the app process is refused), so an own-group display no longer tracks display 0 and keeps composing after the phone panel goes dark. When Shizuku is absent or the trusted creation fails on a given ROM, it falls back unchanged to the untrusted `DisplayManager.createVirtualDisplay` path, so the session is never worse than before. The `acb4474` "hold the phone dark" behavior is untouched — it lives entirely in `:app`/`ScreenPowerController`, which this diff does not modify.

Watch for: (1) **possible** — the whole fix's device payoff cannot be proven from JVM; survival of a true hardware power-off on the Xiaomi/MediaTek Android-16 target is still unverified and explicitly deferred to on-device testing. (2) **confirmed** — an unrelated change commenting out the Android Auto force-stop block in `scripts/dhu-run.sh` is bundled in. (3) **likely** — the `IVirtualDisplayCallback` is a `Proxy` used only as a binder identity; if a ROM calls back into it during creation it returns null, a theoretical risk on heavily-modified frameworks.

**Verdict**: APPROVED

## High-level view

The root-cause diagnosis is sound and the fix targets it directly. The failure was never the app's wakelock or its panel-off path — those correctly handle auto-dim — but the display group the pane lived in. Moving the pane to a trusted, own-display-group display is the mechanism projection tools use to keep composing while the main panel sleeps, and the shell UID is what makes the trusted flag reachable. The design is the minimal correct change.

The privileged path is implemented over the same `ShizukuBinderWrapper` + reflective `asShellInterface` seam the module already uses for `startActivityAsUser` and `injectInputEvent`, with the same probe-and-fall-back discipline: every reflective lookup and invocation is guarded, failures log and return `-1`/`false`/no-op, and nothing throws into the caller. Args are matched positionally by type exactly like the existing `buildStartActivityArgs`, so a signature drift across releases degrades to the untrusted path rather than crashing.

Flavor isolation holds. Every production change is inside `:duoscreen`, which only `personal`/`lab` pull in; `src/safe`, `ScreenPowerController`, and the safe build are untouched, and the verification matrix confirms safe still compiles without referencing the trusted-display code. The degradation contract is real: the interface defaults (`-1`/`false`/no-op) force the untrusted fallback for any non-Shizuku implementation, and this is the one behavior the new JVM tests actually pin down.

The honest limitation, stated clearly in both plan and verification, is that JVM tests prove only the flag composition and the fallback selection — not that an own-group trusted display survives a real power HAL power-off on the target OEM. That is the single unproven link and it is correctly flagged for on-device verification, with `FLAG_ALWAYS_UNLOCKED`/`FLAG_OWN_FOCUS` named as the tuning surface if bit 11 alone is insufficient.

<details>
<summary>Issues (4)</summary>

1. **On-device survival unproven (possible)** — the power-off survival depends on the target OEM power HAL and cannot be shown from JVM; must be confirmed on the DHU/head unit per the verification checklist before the bug is considered fixed. Non-blocking: the fallback guarantees no regression if it fails.
2. **Unrelated dhu-run.sh change bundled (confirmed)** — the Android Auto force-stop/restart block is commented out in `scripts/dhu-run.sh`, unrelated to the display fix. Split it out or justify it in the commit; harmless to the fix itself.
3. **Proxy callback re-entrancy (likely)** — `newVirtualDisplayCallback` returns null for any method other than `asBinder`/`toString`/`hashCode`/`equals`; a ROM that synchronously invokes a real callback method during creation would get null. Not observed; capture logs on device if creation misbehaves.
4. **resize/setSurface over shell unverified on device (possible)** — `resizeVirtualDisplay`/`setVirtualDisplaySurface` matched by name may be absent or differ on the target ROM; the code logs and returns false (pane keeps old geometry) rather than failing, so worst case is a stale size, not a crash. Confirm during on-device drag/move testing.

</details>

<details>
<summary>Details</summary>

### Root cause and whether the fix addresses it

The plan's diagnosis is grounded in the actual flag constants in `DuoScreenDisplays.kt` (`FLAG_OWN_CONTENT_ONLY | FLAG_SUPPORTS_TOUCH`, no trusted/own-group bit) and correctly separates two different "screen off" events: the app's panel-off path (`ShizukuDisplayPowerController.setDisplayPower` via `setDisplayPowerMode`, which blanks only the phone's internal panel and deliberately avoids `goToSleep`) versus the hardware button's real `goToSleep` of the whole default display group. A `SCREEN_BRIGHT_WAKE_LOCK` defeats only the idle timeout, not a user-initiated sleep — so the wakelock reasoning in the root cause is correct, and the conclusion that the fix must change the *display*, not add a screen-off guard, follows. Creating the pane as `FLAG_TRUSTED | FLAG_OWN_DISPLAY_GROUP` removes it from the default group so it no longer tracks display 0. This is the correct mechanism, and the diff implements exactly it: `TRUSTED_FLAGS = FLAGS or FLAG_TRUSTED or FLAG_OWN_DISPLAY_GROUP` (bits 3, 6, 10, 11), tried first when `ops.isAvailable`.

### Shell-UID creation path and reflection safety

`createTrustedVirtualDisplay` resolves `IDisplayManager` through the same `asShellInterface("display", ...)` helper that backs the activity and input seams, wrapping the service binder in `ShizukuBinderWrapper` so the transaction runs as uid 2000. The AIDL target is `createVirtualDisplay(VirtualDisplayConfig, IVirtualDisplayCallback, IMediaProjection, String)`; `buildCreateDisplayArgs` fills it positionally by type — config, token, null for `IMediaProjection` (no projection token is needed because the trusted flag, not a projection, grants the display), and `SHELL_PACKAGE` ("com.android.shell") for the `packageName` String. The package choice is consistent with the launch path's reasoning that the system rejects a calling package not owned by the calling uid. `VirtualDisplayConfig` is built reflectively through its hidden `Builder` with `setFlags`/`setSurface`, and `displayApi` is cached in a `@Volatile` and nulled on any create failure so a half-broken handle is not reused. This all matches the module's established probe-and-report discipline.

One concrete point worth on-device attention: `buildCreateDisplayArgs` assigns the first type-assignable slot and relies on `VirtualDisplayConfig` and the callback `Proxy` not colliding on type (they don't — distinct classes), and on `IMediaProjection` being the only remaining reference slot that should be null. If a future release reorders or adds a second String parameter, the by-type fill would still place `SHELL_PACKAGE` in the first String slot and null elsewhere, degrading to a likely `-1` return and fallback rather than a wrong-but-silent call. Acceptable given the fallback.

### The Proxy callback as a binder identity

`newVirtualDisplayCallback` sidesteps the hidden abstract `IVirtualDisplayCallback.Stub` by returning a `Proxy` that answers `asBinder()` with a real local `Binder` and stubs `toString`/`hashCode`/`equals`; everything else returns null. The system matches resize/release on `asBinder()` identity, and the token is retained in `displayTokens` keyed by display id until release, which is what makes later resize/release addressable. The documented risk — a ROM synchronously invoking a real callback method during creation would receive null — is real but has not been observed and would at worst fail creation into the fallback. Flagged, not blocking.

### Lifecycle, resize/setSurface, and release routing

`PaneDisplay` now records `trustedDisplayId` and the `ops` that minted it, and `isTrusted` routes `resize`/`setSurface`/`release` through the shell seam for trusted panes and the ordinary `VirtualDisplay` handle for untrusted ones. `release` removes the pane from the map first then releases via the matching path, so a session end or `reopenPane` tears the shell-registered display down through `releaseVirtualDisplay` rather than leaking it. `reset()` now also nulls `displayApi` and clears `displayTokens` — correct, because the tokens are only valid against the server that minted them and a reconnected Shizuku server would not recognize them. `resize`/`setSurface` return false and keep the prior geometry if the shell method is missing, which is a stale-size degradation rather than a crash. The `release`-before-create at the top of `create` prevents a double-registration across `reopenPane`.

### Flavor isolation and the degradation contract

Every production file touched is under `duoscreen/`, pulled in only by `personalImplementation`/`labImplementation`. Nothing in `app/src/safe`, `ScreenPowerController`, or `PanelPowerLease` is modified, so the `acb4474` phone-dark feature is structurally untouched and cannot regress from this diff. The interface defaults (`createTrustedVirtualDisplay` → `-1`, resize/setSurface → `false`, release → no-op) mean any implementation other than `DuoScreenShizukuOps`, including the safe-flavor seam, forces the untrusted path. `DuoScreenPrivilegedOpsDefaultsTest` pins these defaults, and `DuoScreenDisplaysFlagsTest` pins `TRUSTED_FLAGS` to exactly bits 3/6/10/11. These are the correct JVM-level guards for a change whose payoff is otherwise device-only.

### Test coverage

Covered at the JVM level: the trusted flag set is exactly the four intended bits, and the interface defaults force the fallback. Not tested (and not testable off-device, correctly deferred): that an own-group trusted display survives a real hardware power-off; that `createVirtualDisplay`/`resizeVirtualDisplay`/`setVirtualDisplaySurface` resolve and accept these args as shell UID on the Android-16 Xiaomi/MediaTek ROM; that injected touch still lands on the trusted display; and the `acb4474` regression check (auto-dim panel-off still blanks the phone while the car stays live, session end restores the panel). The verification notes enumerate all of these as the on-device checklist, which is the right disposition — not something to run the JVM suites against.

### Verification evidence

`verification.md` records a full flavor matrix (`testSafeDebugUnitTest testPersonalDebugUnitTest testLabDebugUnitTest assembleSafeDebug assemblePersonalDebug assembleLabDebug`, 195 tasks, BUILD SUCCESSFUL) plus the targeted `:app` and `:duoscreen` builds, and names the two new JVM tests. The evidence is specific, matches the files in the diff, and leaves no articulable doubt that would justify re-running a suite. The device-behavior claim is honestly scoped as unprovable from JVM. No re-run performed.

### Unrelated change

`scripts/dhu-run.sh` comments out the entire `RESTART`-guarded Android Auto force-stop block. This is unrelated to the display fix and should be split into its own commit or justified in the message. It does not affect the fix's correctness.

</details>

<details>
<summary>File map</summary>

- `duoscreen/.../DuoScreenController.kt` — passes existing `ops` into `DuoScreenDisplays.create` at the one call site.
- `duoscreen/.../render/DuoScreenDisplays.kt` — `TRUSTED_FLAGS`, privileged-first create with untrusted fallback, path-aware resize/setSurface/release, `PaneDisplay` tracks trusted id + ops.
- `duoscreen/.../system/DuoScreenPrivilegedOps.kt` — interface gains create/resize/setSurface/release trusted-display ops with safe defaults.
- `duoscreen/.../system/DuoScreenShizukuOps.kt` — shell-UID `IDisplayManager` reflection, reflective `VirtualDisplayConfig.Builder`, `Proxy` callback token, token map, `reset()` extended.
- `duoscreen/src/test/.../render/DuoScreenDisplaysFlagsTest.kt` — asserts `TRUSTED_FLAGS` bits.
- `duoscreen/src/test/.../system/DuoScreenPrivilegedOpsDefaultsTest.kt` — asserts fallback defaults.
- `scripts/dhu-run.sh` — unrelated: comments out the Android Auto force-stop/restart block.

Full diff: `git -C /Users/anuwat.t/Documents/ChatGPT/AutoBridge diff HEAD`

</details>
