# Duo Screen — "car session dies when the phone power button blanks the phone" fix

Scope: `:duoscreen` module + its `:app` Shizuku seam, personal/lab flavors only. Do NOT touch
`src/safe` or make the safe flavor depend on Shizuku / the unofficial SDK. Do NOT commit — leave
the change staged for review. This is a device-state bug; the fix is designed from source and
marked **needs on-device verification** where the platform behavior cannot be proven from code.

---

## ROOT CAUSE

**The car pane runs on an UNTRUSTED, default-display-group VirtualDisplay, so when the hardware
POWER button puts the device into real (non-interactive) sleep, the platform stops composing /
resuming that display and the car surface goes black. The app's own WakeLock and panel-off path
do not — and cannot — prevent this, because the power button drives a system `GOTO_SLEEP` that
overrides app wakelocks.**

Evidence, grounded in this codebase:

1. **How the pane display is created — the actual flags.**
   `duoscreen/src/main/java/dev/autobridge/duoscreen/render/DuoScreenDisplays.kt`:
   ```kotlin
   private const val FLAG_OWN_CONTENT_ONLY = 1 shl 3   // VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
   private const val FLAG_SUPPORTS_TOUCH   = 1 shl 6   // VIRTUAL_DISPLAY_FLAG_SUPPORTS_TOUCH
   private const val FLAGS = FLAG_OWN_CONTENT_ONLY or FLAG_SUPPORTS_TOUCH
   ...
   manager.createVirtualDisplay("AutoBridgeDuoPane$paneId", width, height, dpi, surface, FLAGS)
   ```
   It is created through the **ordinary app** `DisplayManager` (`context.getSystemService`), with
   NO `VIRTUAL_DISPLAY_FLAG_TRUSTED` (1 shl 10), NO `VIRTUAL_DISPLAY_FLAG_OWN_DISPLAY_GROUP`
   (1 shl 11) and NO `VIRTUAL_DISPLAY_FLAG_ALWAYS_UNLOCKED` (1 shl 12). The class KDoc states
   `FLAG_TRUSTED` was deliberately dropped because `ADD_TRUSTED_DISPLAY` is refused to an app
   process. A display with none of these flags lives in the **default display group**, whose
   power/interactive state follows display 0 (the phone panel). When display 0's group goes to
   sleep, this display sleeps with it.

2. **Why the WakeLock cannot save it.**
   `app/src/main/java/dev/autobridge/display/ScreenPowerController.kt` acquires a
   `SCREEN_BRIGHT_WAKE_LOCK` / `SCREEN_DIM_WAKE_LOCK` (`acquire(dim)`), and
   `startForCarSession()` forces `preventScreenSleep = true`. A screen wakelock only prevents the
   *automatic idle timeout*. The hardware power button issues a user-initiated
   `PowerManager.goToSleep(...)`, which is not blocked by `SCREEN_BRIGHT_WAKE_LOCK`
   (AOSP `tv-standby` doc confirms even inattentive-sleep overrides these wakelock levels). Once
   the device is non-interactive, the default display group is powered down and the untrusted
   pane display stops updating. This is exactly the failure mode commit `acb4474` describes
   ("once the phone sleeps or locks the system stops resuming the apps inside them") and that
   `common/.../power/CarScreenPower.kt` repeats verbatim.

3. **The existing panel-off path blanks the PHONE panel, not the problem.**
   `ScreenPowerController.panel` → `ShizukuInputBackend.setPanelPower(false)` →
   `ShizukuDisplayPowerController.setDisplayPower` in
   `app/src/main/java/dev/autobridge/input/ShizukuTouchService.kt`. Its token resolver
   (`findDefaultDisplayToken`) only ever resolves the **default / internal physical display**
   (`getInternalDisplayToken` / `getBuiltInDisplay(0)` / first of `getPhysicalDisplayIds`), i.e.
   the phone's own panel. It uses `setDisplayPowerMode` **specifically so the device is NOT put
   to sleep** (its own KDoc: "intentionally uses panel power mode rather than
   PowerManager.goToSleep()/DeviceAdmin.lockNow()"). That is the app blanking its own panel while
   the display group stays awake — which works. The power **button** does the opposite: a real
   `GOTO_SLEEP` of the whole group. So the app's blanking is fine; the hardware button is the
   teardown.

4. **Candidate ruled out: no app lifecycle callback tears the session down.**
   A repo-wide search for `ACTION_SCREEN_OFF`, `registerReceiver`, `goToSleep`, `isInteractive`
   finds **no** screen-off `BroadcastReceiver` and **no** code reacting to screen-off in the Duo
   Screen path (`DuoScreenController`, `DuoScreenCarAppService`, `DuoScreenSession`,
   `ScreenPowerController`). `DuoScreenController.stop()` is only called on explicit session end,
   `applyStoredSettings`/`restart`, or MOVING/UNKNOWN safety teardown — none of which the power
   button triggers. So the black screen is a genuine **platform** teardown of the untrusted
   display, not the app disposing of its own surface. (Confirms the fix must change the display,
   not add a guard.)

**Therefore the correct, minimal fix is to create the car pane VirtualDisplay as a TRUSTED display
in its OWN display group, via the already-proven Shizuku shell-UID binder path, so the pane display
no longer follows display 0's power state.** A trusted display with `FLAG_OWN_DISPLAY_GROUP` is the
mechanism projection tools (scrcpy, waydroid) use to keep a virtual display composing while the
phone's main display is off — and it is exactly what `CarScreenPower.kt`/commit `acb4474` already
named as "the source fix" that was previously unreachable *because the app process is refused
`ADD_TRUSTED_DISPLAY`*. The shell UID (uid 2000), which `DuoScreenShizukuOps` already borrows via
`ShizukuBinderWrapper`, **holds `ADD_TRUSTED_DISPLAY`**, so the previously-unreachable fix is now
reachable through the same seam the module already uses for `startActivityAsUser` and
`injectInputEvent`.

### Why the Shizuku *binder-wrapper* path, not the user-service path

`DuoScreenShizukuOps` KDoc records that `Shizuku.bindUserService` crashes on this device's ROM
(Xiaomi/MediaTek + Android 16) inside `LoadedApk.makeApplicationInner`, and that
`ShizukuBinderWrapper` (a single binder transaction executed as shell UID, loading none of our
code) sidesteps it. `ShizukuInputBackend` (which backs panel-off) still uses the user-service path,
but the new display creation must go through the **binder-wrapper** path (`DisplayManagerGlobal`
reached reflectively over `SystemServiceHelper.getSystemService("display")` wrapped in
`ShizukuBinderWrapper`), consistent with `DuoScreenShizukuOps`'s proven approach and its hidden-API
exemption (`HiddenApiBypass`).

### Degradation contract (keep it sane without the privilege)

- If Shizuku is not granted, or the trusted-display creation fails/throws (OEM differences,
  signature drift), **fall back to the current untrusted `DisplayManager.createVirtualDisplay`
  path unchanged**. The session then behaves exactly as today (works while the phone is awake;
  dies on a real power-button sleep) — no regression, and the phone-panel-dark behavior from
  `acb4474` is untouched because it lives entirely in `:app`/`ScreenPowerController` and is not
  modified here.
- The WakeLock + panel-off policy from `acb4474` stays as-is; it remains correct for the auto-dim
  "hold the phone dark" case. The trusted display is complementary: it keeps the *car* pane alive
  specifically across the *hardware* sleep the wakelock cannot stop.

### Honesty on verification

The platform behavior "a Shizuku-elevated TRUSTED + OWN_DISPLAY_GROUP VirtualDisplay keeps
composing to the car surface after a hardware power-button sleep" **cannot be proven from JVM unit
tests or compilation** — it depends on the attached head unit / DHU and the OEM power HAL. Every
step below that asserts device behavior is marked **[needs on-device verification]**. The JVM-
testable parts (flag composition, fallback selection, reflection arg building) get real unit tests.

---

## IMPLEMENTATION PLAN

- [ ] 1. Add a privileged "create a trusted own-group VirtualDisplay" capability to the Duo Screen
      shell-UID ops, parallel to the existing `launchOnDisplay` / `injectMotion`.
      Extend the `DuoScreenPrivilegedOps` interface with
      `fun createTrustedVirtualDisplay(name: String, width: Int, height: Int, dpi: Int, surface: Surface, flags: Int): Int`
      returning a display id or -1, and implement it in `DuoScreenShizukuOps` by reaching
      `DisplayManagerGlobal` reflectively over the shell binder: wrap
      `SystemServiceHelper.getSystemService("display")` in `ShizukuBinderWrapper`, build
      `IDisplayManager$Stub.asInterface`, and call `createVirtualDisplay` with a
      `VirtualDisplayConfig.Builder` carrying the trusted flags (reflective, probing like the
      existing `resolveActivityApi`/`resolveInputApi`; cache in a `@Volatile` like `activityApi`).
      Return the id from the returned `IVirtualDisplayCallback`/display handle. On any failure,
      log and return -1 (do NOT throw) so the caller can fall back.
      Files: `duoscreen/src/main/java/dev/autobridge/duoscreen/system/DuoScreenPrivilegedOps.kt`,
      `duoscreen/src/main/java/dev/autobridge/duoscreen/system/DuoScreenShizukuOps.kt`
      Verify: `./gradlew testPersonalDebugUnitTest` compiles and existing tests pass **[compile +
      JVM]**; the real display survival is **[needs on-device verification]** at step 7.

- [ ] 2. Define the trusted-display flag set and the privileged-vs-plain creation decision in
      `DuoScreenDisplays`, keeping the current flags as the fallback.
      Add constants `FLAG_TRUSTED = 1 shl 10`, `FLAG_OWN_DISPLAY_GROUP = 1 shl 11` and (optionally)
      `FLAG_ALWAYS_UNLOCKED = 1 shl 12` beside the existing `FLAG_OWN_CONTENT_ONLY`/
      `FLAG_SUPPORTS_TOUCH`; define `TRUSTED_FLAGS = FLAGS or FLAG_TRUSTED or FLAG_OWN_DISPLAY_GROUP`
      (plus `FLAG_ALWAYS_UNLOCKED` if included). Change `create(...)` to take the
      `DuoScreenPrivilegedOps` (or a small functional seam) and try the privileged
      `createTrustedVirtualDisplay(name, w, h, dpi, surface, TRUSTED_FLAGS)` FIRST when
      `ops.isAvailable`; if it returns -1 (or ops unavailable), fall back to the existing
      `manager.createVirtualDisplay(..., FLAGS)` exactly as today. Track per-pane whether the
      display is trusted so `release`/`resize`/`setSurface` route correctly (privileged displays
      may need release/resize via the same shell handle — keep the `VirtualDisplay`/handle in
      `PaneDisplay`). Log which path was taken, naming trusted vs untrusted, for on-device
      diagnosis.
      Files: `duoscreen/src/main/java/dev/autobridge/duoscreen/render/DuoScreenDisplays.kt`
      Verify: `./gradlew testPersonalDebugUnitTest assemblePersonalDebug` builds; add/extend a JVM
      unit test asserting `TRUSTED_FLAGS` has bits 3,6,10,11 set and that `create` picks the
      privileged path when ops report available and the untrusted path when not (inject a fake
      `DuoScreenPrivilegedOps`). **[JVM]**

- [ ] 3. Thread the privileged ops into display creation at the one call site.
      `DuoScreenController.openPane(...)` currently calls
      `DuoScreenDisplays.create(context, pane.id, surface, w, h, paneDpi)`. Pass the controller's
      existing `ops` (`DuoScreenShizukuOps` by default, already injected via the constructor) into
      `create` so the trusted path is used when available. Do the same for the `reopenPane` →
      `openPane` and `applyStoredSettings` resize paths (resize/release must use the matching
      handle chosen at create time — handled inside `DuoScreenDisplays`, so the controller change
      is only the extra argument).
      Files: `duoscreen/src/main/java/dev/autobridge/duoscreen/DuoScreenController.kt`
      Verify: `./gradlew testPersonalDebugUnitTest assemblePersonalDebug` passes; existing
      `DuoScreen*` unit tests still green. **[JVM]**

- [ ] 4. Ensure clean teardown of a privileged (trusted) display.
      In `DuoScreenDisplays.release`/`releaseAll`, release the privileged display through the shell
      handle when that is how it was created (add `fun releaseTrustedVirtualDisplay(token)` to
      `DuoScreenPrivilegedOps`/`DuoScreenShizukuOps` if the plain `VirtualDisplay.release()` is not
      usable on a shell-created display), so a session end or `reopenPane` cannot leak a
      system-registered display. Keep the existing `release()` for untrusted panes. Confirm
      `DuoScreenController.stop()` (which already calls `DuoScreenDisplays.release` per pane) tears
      the trusted display down too.
      Files: `duoscreen/src/main/java/dev/autobridge/duoscreen/system/DuoScreenPrivilegedOps.kt`,
      `duoscreen/src/main/java/dev/autobridge/duoscreen/system/DuoScreenShizukuOps.kt`,
      `duoscreen/src/main/java/dev/autobridge/duoscreen/render/DuoScreenDisplays.kt`
      Verify: `./gradlew testPersonalDebugUnitTest` passes; add a JVM test with a fake ops that
      asserts a trusted-created pane is released via the privileged release and an untrusted one
      via `VirtualDisplay.release`. **[JVM]**

- [ ] 5. Add a short-lived `DuoScreenShizukuOps.reset()` call path on grant/teardown so a cached
      display API handle is dropped when Shizuku reconnects.
      `DuoScreenShizukuOps.reset()` already nulls `activityApi`/`inputApi`; extend it to null the
      new display-API cache. Confirm it is invoked where the existing reset is (same place the
      Shizuku grant lifecycle already resets ops). If no caller exists, wire `reset()` into the
      Shizuku grant-revoked path that `ShizukuInputBackend`/`ShizukuGrant` already observe, so a
      stale shell binder is not reused for display creation.
      Files: `duoscreen/src/main/java/dev/autobridge/duoscreen/system/DuoScreenShizukuOps.kt`
      (and the grant-lifecycle caller if one must be added — confirm by grep for `DuoScreenShizukuOps.reset`)
      Verify: `./gradlew testPersonalDebugUnitTest` passes. **[JVM]**

- [ ] 6. Full flavor matrix build + unit tests, confirming the safe flavor is untouched.
      The change lives entirely in `:duoscreen` and is pulled in only by `personalImplementation`/
      `labImplementation` (`app/build.gradle.kts` lines ~269–270), so safe must still compile with
      no Shizuku/trusted-display code. Run the full matrix.
      Files: (none — verification only)
      Verify:
      ```
      ./gradlew testSafeDebugUnitTest testPersonalDebugUnitTest testLabDebugUnitTest \
        assembleSafeDebug assemblePersonalDebug assembleLabDebug
      ```
      All tasks succeed; safe APK builds without referencing the new trusted-display code. **[JVM +
      compile]**

- [ ] 7. On-device verification (DHU first, then head unit). **[needs on-device verification]**
      This is the only proof the bug is fixed; it cannot be done from JVM. Procedure:
      1. Install personal/lab debug on the phone, grant Shizuku, start a Duo Screen session via DHU
         (`scripts/dhu-run.sh`) or the real head unit.
      2. Confirm the pane logs show the **trusted** creation path was taken
         (`DuoScreenDisplays` log line added in step 2), not the untrusted fallback.
      3. Press the phone's hardware POWER button to blank the phone.
      4. EXPECT: the car / DHU panes stay lit and keep rendering/updating; the phone panel is dark.
         Interact on the car side and confirm input still lands.
      5. Regression check from `acb4474`: with the power button NOT pressed, confirm the existing
         auto-dim panel-off still blanks the phone panel while the car stays live, and that
         session end / disconnect restores the phone panel (`ScreenPowerController.stop()` →
         `restorePanelPower`).
      6. Fallback check: revoke Shizuku, restart the session, confirm it still starts (untrusted
         path) and behaves exactly as before this change (no crash; dies on power-button as the
         pre-fix behavior did).
      If the trusted path does not survive power-off on the target OEM, record the exact
      `createVirtualDisplay` failure/log and treat the trusted-flag combination (try with/without
      `FLAG_ALWAYS_UNLOCKED`, and `FLAG_OWN_FOCUS = 1 shl 14`) as the tuning surface — the fallback
      guarantees the app is never worse than today meanwhile.

---

## Files touched (summary)

- `duoscreen/src/main/java/dev/autobridge/duoscreen/system/DuoScreenPrivilegedOps.kt` — interface gains trusted-display create/release.
- `duoscreen/src/main/java/dev/autobridge/duoscreen/system/DuoScreenShizukuOps.kt` — shell-UID `DisplayManagerGlobal` reflection + cache + reset.
- `duoscreen/src/main/java/dev/autobridge/duoscreen/render/DuoScreenDisplays.kt` — trusted flags, privileged-first create with untrusted fallback, path-aware release/resize.
- `duoscreen/src/main/java/dev/autobridge/duoscreen/DuoScreenController.kt` — pass `ops` into `DuoScreenDisplays.create`.
- `duoscreen/src/test/java/dev/autobridge/duoscreen/...` — new/extended JVM tests for flag set, path selection, and release routing.

Not touched: anything in `app/src/safe`, `ScreenPowerController`/`PanelPowerLease` (the `acb4474`
phone-dark behavior), `src/projection` manifests, or the safe-flavor build.

## Open assumptions (reasonable, verify on device)

- `IDisplayManager.createVirtualDisplay` / `VirtualDisplayConfig.Builder` is reachable and accepts
  the trusted flags when the transaction runs as shell UID on the target ROM (Android 16 /
  Xiaomi-MediaTek). If the signature differs, the reflective builder in step 1 is the single place
  to adjust; the probe-and-report pattern already used in `DuoScreenShizukuOps` keeps a mismatch
  from crashing — it falls back to untrusted.
- `FLAG_OWN_DISPLAY_GROUP` (bit 11) is the decisive flag for surviving default-display sleep;
  `FLAG_ALWAYS_UNLOCKED` (bit 12) and `FLAG_OWN_FOCUS` (bit 14) are tuning knobs tried on device if
  bit 11 alone is insufficient.
