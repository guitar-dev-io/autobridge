# Disconnect / stop-mirroring affordance on the car mirror and mobile Control screens

Adds a user-facing way to stop mirroring from two places: a titled "Disconnect" action on the live Android Auto mirror top strip, and a destructive "Disconnect" button on the mobile Control screen shown while mirroring is active. The car action reuses the existing `ProjectionService.stop(carContext)` + `carContext.finishCarApp()` stop path; the mobile button sends `AutoBridgeCommand(STOP_MIRROR, MOBILE)` over the existing command bus. Three new string keys land in both the default and Thai locales. No backend/stop logic was touched — the change is 42 insertions across two Kotlin files and two resource files.

Watch for: nothing blocking. The implementation matches the plan and the task spec; build, assemble, and lint evidence is recorded (confirmed). One minor UX note on the mobile button being `ACTIVE`-only (possible), which the plan explicitly sanctioned.

**Verdict**: APPROVED

## High-level view

The car side appends a third titled action to `topActions` after speed and Controls, staying within the four-action top-strip limit, and deliberately not touching the map strip (which rejects titled actions). The existing comment explaining the top-strip-vs-map-strip rationale is preserved, and the stop path reuses the already-imported `ProjectionService`. No drawable was invented — the titled action carries no icon, which is valid for the top strip.

The mobile side gates a full-width Danger-tinted `Surface` button on `state.mirrorStatus == MirrorStatus.ACTIVE`, placed directly under the Android Auto status card. It sends the stop command through `AutoBridgeCommandBus` with `CommandSource.MOBILE`, carries an accessible `contentDescription`, and reuses the file's established Surface + RoundedCornerShape + semantics pattern with `ComposeTokens.Danger`.

Strings are complete: `car_mirror_disconnect`, `control_disconnect`, and `control_disconnect_desc` are added to both `values/strings.xml` and `values-th/strings.xml` with placement matching the surrounding groups, and Thai reuses "หยุดมิเรอร์", the term already used elsewhere for STOP_MIRROR. Those are the only two locale folders, so coverage is complete, and lint reported no MissingTranslation.

<details>
<summary>Issues (1)</summary>

1. **Mobile button hidden in READY state** — the button only renders on `MirrorStatus.ACTIVE`, so a session that is READY but not yet ACTIVE offers no mobile disconnect. The plan explicitly permitted ACTIVE-only, so this is informational; broaden to `in setOf(ACTIVE, READY)` only if product wants it.

</details>

<details>
<summary>Details</summary>

### Car Disconnect in the top strip, not the map strip

The new action is appended as the third entry on `topActions`, after the optional speed readout and the Controls action, giving a worst case of three titled actions against the four-action top-strip ceiling. It is built with `setTitle(R.string.car_mirror_disconnect)` and no icon, and its click handler calls `ProjectionService.stop(carContext)` then `carContext.finishCarApp()` — the same ordering already used at line 52 and in the Controls stop row, so it rides the confirmed stop path rather than a new one (confirmed). `ProjectionService` was already imported, so no import churn.

The pre-existing comment explaining why titled actions live in the top strip — "map action strips reject titled actions (ActionsConstraints.ACTIONS_CONSTRAINTS_MAP allows 0 custom titles) ... the top strip, which allows up to four titled actions" — is left intact above the builder, so the rationale that justifies this placement still reads correctly after the addition (confirmed). `mapActions` was not touched.

### Mobile Disconnect button, gated and destructive

The button is a `Surface(onClick = ...)` rendered only inside `if (state.mirrorStatus == MirrorStatus.ACTIVE)`, placed immediately after `AndroidAutoStatusCard`. On click it calls `AutoBridgeCommandBus.send(AutoBridgeCommand(type = CommandType.STOP_MIRROR, source = CommandSource.MOBILE))` — the exact command, type, and source the spec asked for (confirmed). Styling uses `ComposeTokens.Danger` for a tinted fill (`alpha = 0.16f`), a Danger border (`alpha = 0.4f`), and Danger-colored centered text, matching the Surface + RoundedCornerShape(16.dp) idiom used elsewhere in the file. Accessibility is covered via `semantics { contentDescription = disconnectDesc }` sourced from `control_disconnect_desc` (confirmed). `MirrorStatus` is the one newly added import; every other symbol used was already present.

The button is hidden rather than disabled outside ACTIVE. In the READY state (session negotiated but not yet actively mirroring) there is no mobile disconnect affordance (possible UX gap). The plan explicitly allowed ACTIVE-only as the clearest option and flagged broadening to `{ACTIVE, READY}` as optional, so this is a product call, not a defect.

### Verification evidence

The build/lint evidence is recorded in commit `e8d5b4c` (`.agents/tasks/disconnect-ui/verification.md`) rather than requiring a re-run (confirmed). From a clean tree at `fea144f`: `./gradlew compileSafeDebugKotlin` → BUILD SUCCESSFUL, `./gradlew assembleSafeDebug` → BUILD SUCCESSFUL, `./gradlew lintSafeDebug` → BUILD SUCCESSFUL with no MissingTranslation for the three new keys in either locale. The evidence also notes that unrelated, uncommitted `browser-ui-polish` changes (a pre-existing `glyph` unresolved-reference failure in `CarWebRenderer.kt`) were stashed so the gates ran against the disconnect-ui change in isolation — those failures are outside this task's scope and not introduced by it. Spot-checks confirmed `MirrorStatus` is an enum with `ACTIVE`, `ComposeTokens.Danger` exists, and all Compose symbols used by the button are imported, which is consistent with the recorded clean compile.

</details>

<details>
<summary>File map</summary>

- `app/src/main/java/dev/autobridge/car/MirrorCarScreen.kt` — adds the titled Disconnect action to the top action strip; stop path and comment preserved.
- `app/src/main/java/dev/autobridge/ui/ControlScreen.kt` — adds the ACTIVE-gated Danger-styled Disconnect button under the status card; adds `MirrorStatus` import.
- `app/src/main/res/values/strings.xml` — adds `car_mirror_disconnect`, `control_disconnect`, `control_disconnect_desc` (English).
- `app/src/main/res/values-th/strings.xml` — adds the same three keys (Thai).

Full change: commit `fea144f` (code + strings) and `e8d5b4c` (verification evidence).

</details>
