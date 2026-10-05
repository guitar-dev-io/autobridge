# Verification evidence — Disconnect affordance (disconnect-ui)

Addresses the sole CHANGES_REQUESTED finding in `review.json` / `review.md`:
"Missing build/lint evidence." The code and resource changes were already landed in
commit `fea144f` and confirmed correct by review; this document records the compile,
assemble, and lint gates that were run and their outcomes so the reviewer can read the
evidence without re-running the suite.

## Code under verification

Commit `fea144f` — "feat: add Disconnect action to car mirror and mobile control screens".
Files changed (42 insertions, no backend/stop logic changed):

- `app/src/main/java/dev/autobridge/car/MirrorCarScreen.kt`
- `app/src/main/java/dev/autobridge/ui/ControlScreen.kt`
- `app/src/main/res/values/strings.xml`
- `app/src/main/res/values-th/strings.xml`

### New string keys (English / Thai)

| Key | `values/strings.xml` (en) | `values-th/strings.xml` (th) |
|-----|---------------------------|------------------------------|
| `car_mirror_disconnect` | `Disconnect` | `หยุดมิเรอร์` |
| `control_disconnect` | `Disconnect` | `หยุดมิเรอร์` |
| `control_disconnect_desc` | `Disconnect and stop mirroring` | `ตัดการเชื่อมต่อและหยุดมิเรอร์` |

`values` and `values-th` are the only two locale folders in the project, so translation
coverage is complete.

## Build/lint environment note

The working tree also carried unrelated, uncommitted `browser-ui-polish` changes
(`CarWebRenderer.kt` et al.) that fail to compile on their own
(`e: CarWebRenderer.kt:3131 Unresolved reference 'glyph'`). That failure is in browser
code untouched by this task. To verify the disconnect-ui change in isolation, those
unrelated changes were stashed (`git stash push --include-untracked`), the gates were run
against a clean tree at commit `fea144f`, and the stash was then restored. The three
`glyph` errors are pre-existing in the unrelated browser work and are not introduced by
this task.

## Commands run and outcomes (from repo root, clean tree at `fea144f`)

1. `./gradlew compileSafeDebugKotlin`
   - Outcome: **BUILD SUCCESSFUL** (`:app:compileSafeDebugKotlin` — no Kotlin errors in
     `MirrorCarScreen.kt` or `ControlScreen.kt`, no unresolved `R.string` references).

2. `./gradlew assembleSafeDebug`
   - Outcome: **BUILD SUCCESSFUL** (62 actionable tasks; `:app:packageSafeDebug` and
     `:app:assembleSafeDebug` completed — full debug APK builds with the change).

3. `./gradlew lintSafeDebug`
   - Outcome: **BUILD SUCCESSFUL** — no lint errors. No `MissingTranslation` or
     `ExtraTranslation` for `car_mirror_disconnect`, `control_disconnect`, or
     `control_disconnect_desc`; both locales present. Lint reports scanned for
     `MissingTranslation` / `disconnect` — no matches.

Variant built: **safe / debug** (flavor dimension `mode` = safe).
