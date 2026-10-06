# Verification — Phase 5 Settings (07) + Duo screen (08) UI fidelity

Iteration: FIRST (no `review.json` present at start).

## Command run

From the workspace root `/Users/anuwat.t/Documents/ChatGPT/AutoBridge`:

```
./gradlew testPersonalDebugUnitTest assemblePersonalDebug
```

## Result

**BUILD SUCCESSFUL** — unit tests passed, `assemblePersonalDebug` produced the APK.

- `:app:testPersonalDebugUnitTest` — passed (no failures).
- `:app:compilePersonalDebugKotlin` — compiled. Only warnings emitted are pre-existing
  `startActivityForResult` / `onActivityResult` deprecation warnings in `MainActivity.kt`
  (lines 2419, 3956, 3977, 3978) that are unrelated to this change and were present before.
- `:app:assemblePersonalDebug` — produced `app/build/outputs/apk/personal/debug/`.

No failing tests. No test references the settings menu structure (`settingsEntry`,
`SettingsGroup`, `settingsListPage`, `buildSettingsMenu`, `SettingsUi`), so the refactor did not
require test updates.

### First attempt (fixed)

The first compile failed with two type-mismatch errors because `settingsEntry` now returns the
new private `MainActivity.SettingsRow` type while `duoScreenEntry()` still declared a
`List<PhoneLauncherUi.Entry>` return type. Fixed by changing `duoScreenEntry()`'s return type to
`List<SettingsRow>`. The re-run built and tested green (above).

## Safety / preference audit

`git diff` was grepped for `KEY_`, `DuoScreenStore.`, `BypassPolicyStore`,
`ShizukuInputBackend.`, and `R.string.` edits:

- No preference KEY was added, renamed, or removed.
- No `DuoScreenStore` / `BypassPolicyStore` / `ShizukuInputBackend` method call changed — the
  same store read/write and Shizuku/safety calls are invoked from the restyled controls.
- No string resource was renamed or removed; **no new strings were added** (all copy reuses
  existing resources already present in both EN and TH).
- Safety bypass still routes "turn ON" through the same confirmation dialog
  (`bypass_confirm_title` / `bypass_confirm_message`, positive button → `toggleBypass()`), and
  "turn OFF" still calls `toggleBypass()` immediately. The persistent-notification behaviour lives
  in `toggleBypass()` / `BypassPolicyStore`, which are untouched.

## Device check (not performed here)

Per `CLAUDE_md_snippet.md` 0.3, final section-by-section visual comparison of the running
Settings and Duo screens against `docs/design/07_PhoneSettings.png` and `08_PhoneDuo.png` must be
done on a device by the reviewer/human. No device/adb screenshot comparison was possible in this
environment.
