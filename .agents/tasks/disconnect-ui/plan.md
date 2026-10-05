# Implementation Plan — Disconnect / Stop-mirroring affordance

Goal: add a "Disconnect" (stop mirroring) control to BOTH the live Android Auto mirror screen
(`MirrorCarScreen.kt`) and the mobile Control screen (`ControlScreen.kt`), reusing the existing stop
paths. No backend changes. New user-facing strings go in the default locale AND the Thai locale.

## Decisions (grounded in the codebase)

- New string key `car_mirror_disconnect` = "Disconnect" / Thai "หยุดมิเรอร์". Rationale: the task asks
  for a short (~1 word) action-strip title; the existing `car_settings_stop_mirroring` is "Stop
  mirroring" (two words, used as a list-row title). Thai reuses "หยุดมิเรอร์", the exact term already
  used for STOP_MIRROR in `CommandParser.kt` and for `car_settings_stop_mirroring` in values-th, so
  the terminology stays consistent.
- New string key `control_disconnect` = "Disconnect" / Thai "หยุดมิเรอร์", plus accessible-label key
  `control_disconnect_desc` = "Disconnect and stop mirroring" / Thai "ตัดการเชื่อมต่อและหยุดมิเรอร์".
  Rationale: the mobile button needs a label and a semantics contentDescription; keeping them as
  resources matches how every other user-facing string in `ControlScreen.kt` is sourced via
  `stringResource(...)`.
- Car Disconnect action is TITLED and lives in the TOP action strip (`topActions`), never the map
  strip — `ACTIONS_CONSTRAINTS_MAP` rejects titled actions. Order: speed (if present) → Controls →
  Disconnect. Max 2 pre-existing + 1 = 3 ≤ 4 top-strip limit.
- Car Disconnect reuses the exact stop pattern already in `MirrorControlScreen.kt`:
  `ProjectionService.stop(carContext)` then `carContext.finishCarApp()`. No icon (titled top-strip
  actions do not require one); do NOT invent a drawable.
- Mobile Disconnect sends `AutoBridgeCommand(type = CommandType.STOP_MIRROR, source = CommandSource.MOBILE)`
  through `AutoBridgeCommandBus.send(...)` — the confirmed existing stop path. Shown only when
  `state.mirrorStatus == MirrorStatus.ACTIVE` (hidden otherwise, which is clearest). Placed directly
  under `AndroidAutoStatusCard`, styled destructive with `ComposeTokens.Danger`.
- Only two locale folders exist (`res/values`, `res/values-th`); both must get all new keys. No
  other `values-xx` folders exist, so default + Thai is complete. (Verify during implementation.)
- Build flavors are `safe`, `personal`, `lab` (dimension `mode`); debug variant tasks are
  `compileSafeDebugKotlin` / `assembleSafeDebug`.

## Items

- [ ] 1. Add the new string resources to the DEFAULT locale.
      Add inside the "Car mirror surface ([MirrorCarScreen])" block, next to `car_mirror_controls`:
      `<string name="car_mirror_disconnect">Disconnect</string>`.
      Add near the control_* strings (same file; the mobile Control strings — search for
      `control_section_quick_actions` / `control_android_auto` to find the group):
      `<string name="control_disconnect">Disconnect</string>` and
      `<string name="control_disconnect_desc">Disconnect and stop mirroring</string>`.
      Files: app/src/main/res/values/strings.xml
      Verify: part of the compile/assemble gate in items 4–5; also confirm no duplicate-key warning.

- [ ] 2. Add the SAME three keys to the Thai locale with matching placement.
      `car_mirror_disconnect` = "หยุดมิเรอร์" (next to the values-th `car_mirror_controls`).
      `control_disconnect` = "หยุดมิเรอร์" and `control_disconnect_desc` = "ตัดการเชื่อมต่อและหยุดมิเรอร์"
      (near the values-th control_* group).
      Files: app/src/main/res/values-th/strings.xml
      Verify: covered by item 6 (`./gradlew lintSafeDebug`) — no MissingTranslation for the new keys.

- [ ] 3. Add the "Disconnect" action to the TOP action strip in MirrorCarScreen.
      In `onGetTemplate()`, append a third `.addAction(...)` to the `topActions` builder AFTER the
      existing "Controls" action. Build it as:
      `Action.Builder().setTitle(carContext.getString(R.string.car_mirror_disconnect))`
      `.setOnClickListener { ProjectionService.stop(carContext); carContext.finishCarApp() }.build()`.
      (`ProjectionService` is already imported.) Do NOT touch `mapActions`. Preserve the existing
      comment explaining why titled actions live in the top strip vs the map strip, and leave the
      speed/Controls ordering intact so the final order is speed? → Controls → Disconnect.
      Files: app/src/main/java/dev/autobridge/car/MirrorCarScreen.kt
      Verify: `./gradlew compileSafeDebugKotlin` compiles clean (see item 4).

- [ ] 4. Add the destructive "Disconnect" button to the mobile Control screen.
      In `ControlScreen(...)`, immediately after the `AndroidAutoStatusCard(...)` line, insert a
      conditional block: `if (state.mirrorStatus == MirrorStatus.ACTIVE) { ... }` rendering a
      full-width danger button. Use a `Surface(onClick = { AutoBridgeCommandBus.send(`
      `AutoBridgeCommand(type = CommandType.STOP_MIRROR, source = CommandSource.MOBILE)) }, ...)`
      styled with `ComposeTokens.Danger` (e.g. Danger-tinted background/border and Danger text),
      `shape = RoundedCornerShape(16.dp)`, `modifier = Modifier.fillMaxWidth().semantics {`
      `contentDescription = <control_disconnect_desc> }`, containing a centered
      `Text(stringResource(R.string.control_disconnect), ...)`. Reuse existing patterns from the
      file (Surface + RoundedCornerShape + semantics as in `CommandField`/`QuickCard`). Add the
      import `import dev.autobridge.remote.MirrorStatus` (the other symbols — `AutoBridgeCommand`,
      `AutoBridgeCommandBus`, `CommandType`, `CommandSource`, `ComposeTokens`, `stringResource`,
      `semantics`, `contentDescription`, `RoundedCornerShape`, `fillMaxWidth` — are already imported).
      Files: app/src/main/java/dev/autobridge/ui/ControlScreen.kt
      Verify: `./gradlew compileSafeDebugKotlin` compiles clean (see item 5).

- [ ] 5. Compile both Kotlin changes together.
      Files: (none — build only)
      Verify: from repo root run `./gradlew compileSafeDebugKotlin` — BUILD SUCCESSFUL, no Kotlin
      errors in MirrorCarScreen.kt or ControlScreen.kt, no unresolved R.string references.

- [ ] 6. Build a debug variant and run lint for translation completeness.
      Files: (none — build only)
      Verify: from repo root run `./gradlew assembleSafeDebug` — BUILD SUCCESSFUL. Then run
      `./gradlew lintSafeDebug` and confirm no MissingTranslation error for `car_mirror_disconnect`,
      `control_disconnect`, or `control_disconnect_desc` (both locales present). If lint flags an
      extra `values-xx` folder that actually exists, add the three keys there too and re-run.

## Notes / assumptions

- READY state: task says ACTIVE is the key case and may optionally also show for READY. This plan
  shows the button only for ACTIVE (clearest); the implementer may broaden to
  `in setOf(MirrorStatus.ACTIVE, MirrorStatus.READY)` if it reads better, but ACTIVE alone satisfies
  the requirement.
- Do NOT re-dismiss any part as already done: the car "Stop mirroring" ROWS exist in
  MirrorControlScreen/CarSettingsScreen, but the LIVE mirror top-strip Disconnect action and the
  mobile button do NOT exist yet and must be added and verified by compiling.
- No tests are added; the build/lint gate is the verification per the task constraints.
