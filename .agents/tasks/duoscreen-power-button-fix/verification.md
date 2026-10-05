# Duo Screen power-button fix — verification

## (a) Root cause

The car pane runs on an **untrusted, default-display-group** `VirtualDisplay`, created through the
ordinary app `DisplayManager` with only `FLAG_OWN_CONTENT_ONLY | FLAG_SUPPORTS_TOUCH` (no
`FLAG_TRUSTED`, no `FLAG_OWN_DISPLAY_GROUP`). A display in the default display group follows the
power state of display 0 (the phone panel). When the hardware **POWER button** drives a real,
user-initiated `PowerManager.goToSleep(...)`, the whole default display group goes non-interactive
and the system stops composing/resuming that pane — so the car/DHU surface goes black and the
session effectively dies.

The app's `SCREEN_BRIGHT_WAKE_LOCK` (`ScreenPowerController`) only defeats the *automatic idle
timeout*; it does not block a user-initiated `goToSleep`. The existing panel-off path
(`ShizukuDisplayPowerController.setDisplayPower` on the internal display) deliberately uses panel
power mode rather than `goToSleep`, which is why the "hold the phone dark" feature from `acb4474`
keeps the car alive — but the hardware button bypasses all of that. So the fix must change the
*display*, not add a guard.

## (b) What changed and why it fixes it

The pane display is now created, **when Shizuku is granted**, as a **trusted display in its own
display group** over the already-proven Shizuku shell-UID binder seam. A trusted
(`FLAG_TRUSTED`) display placed in its own group (`FLAG_OWN_DISPLAY_GROUP`) does not follow
display 0's power state, so it keeps composing to the car/DHU surface after the phone panel is
blanked by the power button. The shell UID (uid 2000) that `ShizukuBinderWrapper` borrows holds
`ADD_TRUSTED_DISPLAY`, which an app process is refused — this is the privilege that was previously
unreachable and is what makes the fix possible.

Files changed (personal/lab only, all in `:duoscreen`; nothing in `app/src/safe`,
`ScreenPowerController`, or the safe build):

- `system/DuoScreenPrivilegedOps.kt` — interface gains `createTrustedVirtualDisplay`,
  `resizeTrustedVirtualDisplay`, `setTrustedVirtualDisplaySurface`, `releaseTrustedVirtualDisplay`,
  all with safe defaults (`-1`/`false`/no-op) so any non-Shizuku implementation degrades cleanly.
- `system/DuoScreenShizukuOps.kt` — implements the four via a reflectively-resolved shell-wrapped
  `IDisplayManager`
  (`createVirtualDisplay(VirtualDisplayConfig, IVirtualDisplayCallback, IMediaProjection, String)`
  et al.). The `VirtualDisplayConfig` is built through its Builder carrying `TRUSTED_FLAGS`; the
  `IVirtualDisplayCallback` token is a `Proxy` returning a real local `Binder` from `asBinder()`
  (the system matches resize/release on that identity). Args are filled positionally by type like
  the existing `startActivityAsUser` path, so a signature change across releases falls back rather
  than crashing. The display-API handle is cached in a `@Volatile` and dropped by `reset()`
  alongside `activityApi`/`inputApi`; the per-display callback tokens are tracked and cleared with
  it. Any failure logs and returns `-1`/`false` (never throws).
- `render/DuoScreenDisplays.kt` — defines `TRUSTED_FLAGS` (= own-content | supports-touch |
  trusted | own-display-group) and tries the privileged trusted path **first** when
  `ops.isAvailable`; on `-1` (or ops unavailable) it **falls back unchanged** to the existing
  untrusted `DisplayManager.createVirtualDisplay`. Each pane records whether it is trusted so
  `resize`/`setSurface`/`release` route back through the matching seam (shell for trusted, the
  `VirtualDisplay` handle for untrusted).
- `DuoScreenController.kt` — passes the controller's existing `ops` into `DuoScreenDisplays.create`
  at the one call site; `reopenPane`/`applyStoredSettings` resize paths route through the recorded
  per-pane handle inside `DuoScreenDisplays`, so no other controller change was needed.

Degradation contract: without Shizuku, or if trusted creation fails on an OEM/ROM, the session
uses the untrusted display exactly as before — works while the phone is awake, dies on a real
power-off — so the change is never worse than today. The `acb4474` phone-panel-dark behavior is
untouched (it lives entirely in `:app`/`ScreenPowerController`).

## Commands run and results

All from `/Users/anuwat.t/Documents/ChatGPT/AutoBridge`.

| Command | Result |
| --- | --- |
| `./gradlew :app:assemblePersonalDebug` | **PASS** — BUILD SUCCESSFUL (clean compile) |
| `./gradlew :duoscreen:testDebugUnitTest` | **PASS** — BUILD SUCCESSFUL |
| `./gradlew :app:testPersonalDebugUnitTest` | **PASS** — BUILD SUCCESSFUL |
| `./gradlew :duoscreen:assembleDebug` | **PASS** (part of matrix below) |
| `./gradlew testSafeDebugUnitTest testPersonalDebugUnitTest testLabDebugUnitTest assembleSafeDebug assemblePersonalDebug assembleLabDebug` | **PASS** — BUILD SUCCESSFUL, 195 tasks; safe flavor builds without referencing the trusted-display code |

New JVM tests added (meaningful at the JVM level, no device required):

- `duoscreen/src/test/.../render/DuoScreenDisplaysFlagsTest.kt` — asserts `TRUSTED_FLAGS` is exactly
  bits 3, 6, 10, 11 (own-content, supports-touch, trusted, own-display-group) — the flag decision
  this fix turns on.
- `duoscreen/src/test/.../system/DuoScreenPrivilegedOpsDefaultsTest.kt` — asserts the interface
  defaults (`-1`/`false`/no-op) so a non-Shizuku implementation forces the untrusted fallback (the
  degradation contract).

The actual platform behavior (a trusted own-group display surviving a hardware power-off and still
accepting injected touch) **cannot be proven from JVM tests or compilation** — it depends on the
head unit / DHU and the OEM power HAL. That is the on-device test below.

## (c) On-device test the user must run

1. Install personal (or lab) debug, grant Shizuku.
2. Start a Duo Screen session via DHU (`scripts/dhu-run.sh`) or the real head unit.
3. In logcat (`AutoBridgeDuoDisplay`) confirm each pane logged
   `Pane N -> trusted display M` — NOT `untrusted display`. If it says untrusted, the trusted
   creation failed on this ROM and the result below will not hold (see risks).
4. Press the phone's hardware **POWER** button to blank the phone panel.
5. **Expect:** the car/DHU panes stay lit and keep rendering; the phone panel is dark. Interact on
   the car side and confirm injected touch still lands.
6. Regression check (`acb4474`): with the power button NOT pressed, confirm the auto-dim panel-off
   still blanks the phone while the car stays live, and session end restores the phone panel.
7. Fallback check: revoke Shizuku, restart the session — it must still start (untrusted path) and
   behave exactly as before (no crash; dies on power-button as the pre-fix behavior did).

## (d) OEM / Android-version risk (may still not survive a true hardware power-off)

- The whole trusted path depends on the shell-wrapped `IDisplayManager.createVirtualDisplay`
  signature and `VirtualDisplayConfig.Builder` being reachable as shell UID on the target ROM
  (Android 16 / Xiaomi-MediaTek). Args are matched by type and every step is guarded, so a mismatch
  falls back to untrusted rather than crashing — but then the bug is *not* fixed on that device.
- `FLAG_OWN_DISPLAY_GROUP` (bit 11) is the decisive flag; some OEM power HALs may still gate even
  own-group displays off at a deep hardware power-off (as opposed to screen-off/sleep). If step 5
  fails while step 3 shows the trusted path was taken, the tuning surface is the flag combination:
  try adding `FLAG_ALWAYS_UNLOCKED` (1 shl 12) and/or `FLAG_OWN_FOCUS` (1 shl 14) in
  `DuoScreenDisplays.TRUSTED_FLAGS`, and capture the exact `createVirtualDisplay` log/failure.
- The `IVirtualDisplayCallback` is a `Proxy` used only as a binder identity for resize/release; if a
  ROM ever calls back into it synchronously during creation, those calls return null — which has
  not been observed but is a theoretical risk on a heavily-modified framework.
