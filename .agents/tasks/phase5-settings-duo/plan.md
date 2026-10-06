# Implementation Plan — Phase 5 Settings (07) + Duo screen (08) UI fidelity

UI-ONLY. No change to playback, mirroring, bridge, IPTV parsing, safety-gate logic, preference
keys, Shizuku operations, or any functional behaviour. Every existing preference stays reachable.
New strings are ADDED (EN + TH); no existing string is renamed or removed.

Build/verify command for every step unless stated otherwise:
`./gradlew testPersonalDebugUnitTest assemblePersonalDebug`

## Context from exploration (what is already correct, so we do not redo it)

Phase 5 was already partly implemented. Confirmed by reading the code:

- `MainActivity.buildSettingsMenu()` (app/src/main/java/dev/autobridge/MainActivity.kt, ~line 2595)
  ALREADY has the four design groups and rows: **CAR & APPS** (Car & Connection, Duo screen via
  `duoScreenEntry()`, Apps & profiles, Display & Mirror, Input & Touch), **PLAYBACK & WEB**
  (Video & subtitles, YouTube, Browser → `openBrowserSettings()`), **SAFETY** (bypass row with an
  inline `Switch`), **GENERAL** (Agent & Commands, Language, Advanced, About). The confirm-on-enable
  dialog for Safety bypass is preserved. Strings `settings_group_car_apps/playback_web/safety/
  general`, `settings_duo_screen(_caption)`, `settings_browser(_caption)`,
  `settings_app_profiles_caption_full`, `settings_video_caption_full`, `settings_youtube_caption_full`
  all exist in BOTH values/strings.xml and values-th/strings.xml.
- Duo screen strings in duoscreen/src/main/res/values/strings.xml (+ values-th) already match the
  design copy exactly (Shizuku lines, "Number of panes", preset names, "Content size in a pane"
  + hint, "Reset arrangement" + hint, "End duo screen now" + hint). `DuoScreenPreset` has exactly
  the 5 presets in design order (EVEN_COLUMNS, WIDE_LEFT, WIDE_RIGHT, EVEN_ROWS=Stacked rows,
  PICTURE_IN_PICTURE); `DuoScreenStore.CONTENT_SCALES = [100,125,150,200]` matches the 4 chips.

So the remaining work is VISUAL FIDELITY, not structure. The gaps below are the differences
between the current `contentRow`/dialog rendering and the design mock-ups 07 and 08.

Assumptions recorded:
- "Match the reference" means visual layout/controls, not re-deciding IA. The IA already matches.
- Because the screens are drawn programmatically (no XML layouts), fidelity work is new builder
  helpers in `AutoBridgeDesign.kt` reused by both screens. This keeps one visual language and is
  the smallest change that fixes both screens.
- No device/adb screenshot comparison is possible in this environment; steps are verified by the
  gradle build + unit tests. Visual correctness against the PNGs must be confirmed on a device by
  the reviewer/human before "done" (per CLAUDE_md_snippet.md section 0.3). Every step that changes
  only drawing says so explicitly rather than claiming "already correct".

---

## Gap analysis

### Settings (07_PhoneSettings.png)
- G1. Design renders each section as ONE rounded card with hairline dividers between rows.
  Current `settingsListPage` renders every row as a SEPARATE rounded `contentRow` with 8dp gaps.
- G2. Design icon badges are per-row tinted colours (blue car, purple duo, purple apps-grid, blue
  display, blue touch, pink video, red YouTube, green globe, amber shield). Current `settingsEntry`
  hardcodes `ACCENT_SYSTEM` (purple) for every row.
- G3. GENERAL rows (Agent & Commands, Language) in the design have NO icon badge and show their
  value right-aligned (Agent → "Quick actions, history"; Language → "ไทย"). Current renders them
  as normal icon-badge rows with the value as a left subtitle.
- G4. Safety bypass uses a raw `android.widget.Switch` (platform default track/thumb colours).
  Design shows an accent-tinted toggle on a card inside the SAFETY group.

### Duo screen (08_PhoneDuo.png)
- D1. Shizuku row is a plain `contentRow` with a "›" chevron. Design is a card: amber warning
  badge + title + hint on the left, a filled accent "Grant" pill on the right (no chevron).
- D2. "Number of panes" is a `contentRow` that cycles on tap. Design is a row with a 2-segment
  toggle (2 | 3) on the right, selected segment filled accent.
- D3. "Layout preset" is a `contentRow` + list dialog. Design is a label above a horizontal row of
  5 mini-diagram tiles (each a small rect diagram + caption), the selected one accent-bordered.
- D4. "Content size in a pane" is a `contentRow` + list dialog. Design is a label + helper line +
  a 4-chip segmented control (100 / 125 / 150 / 200%), selected chip filled accent.
- D5. Panes section, Layout section, Reset row grouping: design groups PANES (count + pane rows)
  in one card and LAYOUT (preset + content size) in one card; current is loose stacked rows.
- D6. The current screen also has a "Connection" section with an Android Auto status row that is
  NOT in the design mock (08 shows only the Shizuku card, PANES, LAYOUT, SESSION). See step 11 —
  do not silently delete; it carries live AA status. Flagged for a decision, default = keep but
  restyle minimally, since removing it hides connection status with no MOVE destination.

---

## Shared helpers first (both screens depend on these)

- [ ] 1. Add a grouped-card container helper to AutoBridgeDesign.
      Add `fun cardGroup(context, rows: List<View>): View` that stacks the given row views in a
      single `SURFACE`/radius-20 rounded container (`AutoBridgeDesign.surface`) with a 1px
      `HAIRLINE` divider `View` between consecutive rows and the standard inner padding. Rows passed
      in must render with a TRANSPARENT background (no own card) so only the group has the card.
      Add an optional `rowBackground`-less variant of `contentRow` by giving `contentRow` a new
      `grouped: Boolean = false` param: when true, it uses a transparent/ripple-only background
      instead of `tappable(...SURFACE...)`, keeps the ripple for tap feedback, and drops its own
      corner radius. Do not change existing call sites (default false preserves current look).
      Files: app/src/main/java/dev/autobridge/ui/AutoBridgeDesign.kt
      Verify: `./gradlew testPersonalDebugUnitTest assemblePersonalDebug` compiles and passes.

- [ ] 2. Add a segmented-toggle helper to AutoBridgeDesign.
      Add `fun segmentedToggle(context, options: List<String>, selectedIndex: Int, accent: Int =
      ACCENT, onSelect: (Int) -> Unit): View` — a pill-shaped `SURFACE_RAISED` track holding one
      button per option; the selected one gets a filled `accent` rounded background with `INK` text,
      others `TEXT_MUTED`. Used by Duo's panes 2|3 and the content-size chips.
      Files: app/src/main/java/dev/autobridge/ui/AutoBridgeDesign.kt
      Verify: build + unit tests pass.

- [ ] 3. Add a mini layout-diagram tile helper to AutoBridgeDesign.
      Add `fun layoutPresetTile(context, label: String, selected: Boolean, draw: (FrameLayout) ->
      Unit, onClick: () -> Unit): View` — a small `SURFACE`/radius-14 tile containing a fixed-size
      diagram area the caller fills, with the caption under it; selected tiles get a 2px `ACCENT`
      stroke (design "Wide left" state). Also add a tiny `presetDiagram(context, preset:
      DuoScreenPreset): View` that draws filled accent/muted rectangles approximating each preset's
      split (even columns, wide-left, wide-right, stacked rows, pip) — pure drawing, no store reads.
      Because `DuoScreenPreset` lives in the duoscreen module (not visible to AutoBridgeDesign in
      the base module), put `presetDiagram` in the Duo activity instead and keep only the generic
      `layoutPresetTile` in AutoBridgeDesign. Decision: generic tile in AutoBridgeDesign, preset-
      specific diagram drawing in DuoScreenSettingsActivity (module boundary forces this).
      Files: app/src/main/java/dev/autobridge/ui/AutoBridgeDesign.kt
      Verify: build + unit tests pass.

---

## Settings screen (07)

- [ ] 4. Give each settings row its design accent colour (fixes G2).
      Change `settingsEntry(...)` to take an `accent: Int = AutoBridgeDesign.ACCENT_SYSTEM` param,
      and pass per-row accents from `buildSettingsMenu`: Car & Connection `ACCENT` (blue), Duo
      screen `ACCENT_SYSTEM` (purple), Apps & profiles `ACCENT_SYSTEM`, Display & Mirror `ACCENT`,
      Input & Touch `ACCENT`, Video & subtitles `ACCENT_VIDEO` (pink), YouTube `ACCENT_VIDEO`/red,
      Browser `ACCENT_WEB` (green), Safety bypass `ACCENT_RADIO`/amber. `duoScreenEntry()` also
      passes `ACCENT_SYSTEM`. No preference or navigation target changes.
      Files: app/src/main/java/dev/autobridge/MainActivity.kt
      Verify: build + unit tests pass; `PhoneNavTest` still green (navigation untouched).

- [ ] 5. Render each settings group as one card with dividers (fixes G1).
      In `settingsListPage`, build each group's standard `rows` as `grouped = true` `contentRow`s
      and wrap them (plus any `customRows`) in `AutoBridgeDesign.cardGroup(...)` from step 1, with
      the section label above and the standard 16dp side gutter / inter-group spacing. Keep the
      existing per-group `label` handling. This must not drop any row — assert visually that all
      rows in all five groups still appear and still invoke their original `open`/handler.
      RISK: this touches the shared `settingsListPage`, also used by Advanced and About screens —
      those should keep working and simply gain the grouped-card look (acceptable, same design
      language). Verify Advanced/About still show every row.
      Files: app/src/main/java/dev/autobridge/MainActivity.kt
      Verify: build + unit tests pass. Manually (device) confirm Settings, Advanced, About each
      list every row they listed before, each still opening the same destination.

- [ ] 6. GENERAL rows: no icon badge, value trailing (fixes G3).
      Add a `settingsValueRow(title, value, accent, onClick)` path (either a `settingsEntry`
      overload with `icon = 0` + a trailing value, or a dedicated builder) that renders no badge
      and shows `value` right-aligned before the chevron, matching the Agent & Commands ("Quick
      actions, history") and Language ("ไทย") rows in 07. Use it for the Agent & Commands and
      Language rows in GENERAL only; Advanced and About rows below keep normal styling as the design
      does not show them. `contentRow` already supports `badgeIcon = 0` (falls back to initial) — if
      an icon-less variant needs the badge area fully gone, extend `contentRow` with a
      `showBadge: Boolean = true` param rather than relying on the initial letter.
      Files: app/src/main/java/dev/autobridge/ui/AutoBridgeDesign.kt,
      app/src/main/java/dev/autobridge/MainActivity.kt
      Verify: build + unit tests pass. Device: Language row still opens the language picker and
      still shows the active language; Agent & Commands still opens the agent screen.

- [ ] 7. Style the Safety bypass toggle to the design (fixes G4).
      Tint the existing `Switch` in `buildSettingsMenu`'s `bypassRow`: set `thumbTintList`/
      `trackTintList` so the ON state uses `AutoBridgeDesign.ACCENT`/`ACCENT_RADIO` and OFF uses a
      muted track, matching 07. Do NOT change the confirm-on-enable dialog, `toggleBypass()`, the
      persistent notification, or `BypassPolicyStore`. Keep the row-tap-toggles-switch behaviour.
      Files: app/src/main/java/dev/autobridge/MainActivity.kt
      Verify: build + unit tests pass. Device: toggling ON still shows the confirmation dialog and
      only enables on confirm; toggling OFF disables immediately; notification behaviour unchanged.

---

## Duo screen (08) — app/src/projection/java/dev/autobridge/projection/DuoScreenSettingsActivity.kt

All edits are inside `render()` and its helpers. Preference writes (`DuoScreenStore.*`), the live
push (`DuoScreenHost.*`), Shizuku calls (`ShizukuInputBackend.*`), the app picker, confirm-end
dialog, and `applied()` toasts MUST remain exactly as they are — only how the controls look changes.

- [ ] 8. Shizuku status card with a Grant pill (fixes D1).
      Replace the Shizuku `contentRow` (currently `trailing = "›"`) with a horizontal card: amber
      (`AutoBridgeDesign.DANGER` when missing / `ACCENT_ONLINE` when ready) badge + title
      (`duo_screen_shizuku_missing`/`_ready`) + hint (`duo_screen_shizuku_hint`) on the left, and on
      the right a filled `AutoBridgeDesign.pill(..., primary = true)` labelled Grant that calls
      `ShizukuInputBackend.requestPermission()` then `render()` — only shown when `!shizukuReady`.
      ADD strings: `duo_screen_grant` = "Grant" / TH "อนุญาต" in duoscreen values + values-th.
      Keep the existing permission call and re-render; do not change `ShizukuInputBackend`.
      Files: app/src/projection/java/dev/autobridge/projection/DuoScreenSettingsActivity.kt,
      duoscreen/src/main/res/values/strings.xml, duoscreen/src/main/res/values-th/strings.xml
      Verify: `./gradlew testPersonalDebugUnitTest assemblePersonalDebug`. Device: with Shizuku not
      granted, Grant appears and launches the Shizuku permission prompt; granted state shows the
      ready card with no Grant button.

- [ ] 9. "Number of panes" 2|3 segmented toggle (fixes D2).
      Replace the pane-count `contentRow` with a row: title `duo_screen_pane_count` on the left,
      `AutoBridgeDesign.segmentedToggle(["2","3"], selectedIndex = paneCount-2) { idx -> ... }` on
      the right. On select, call the SAME path `cyclePaneCount` uses: `DuoScreenStore.setPaneCount(
      this, idx + 2)` then `applied()` then `render()`. Keep `cyclePaneCount` or inline its body;
      do not change `DuoScreenStore.setPaneCount` (it still drops the arrangement as before).
      Files: app/src/projection/java/dev/autobridge/projection/DuoScreenSettingsActivity.kt
      Verify: build + tests pass. Device: tapping 3 then 2 changes pane rows and shows the same
      "Changed on the car display"/"applies on next connection" toast as before.

- [ ] 10. Layout preset mini-diagram tiles (fixes D3).
      Replace the preset `contentRow` + `pickPreset()` dialog with: a `duo_screen_preset` label,
      then a horizontal (scrollable) row of 5 `AutoBridgeDesign.layoutPresetTile(...)` — one per
      `DuoScreenPreset.entries` — each labelled `preset.label(this)`, filled by `presetDiagram`
      (step 3), `selected = (preset == current)`, onClick = the SAME body as the old dialog item:
      `DuoScreenStore.setPreset(this, preset); applied(); render()`. Keep `pickPreset()` only if
      still referenced; otherwise remove it. `DuoScreenHost`/apply path unchanged.
      Files: app/src/projection/java/dev/autobridge/projection/DuoScreenSettingsActivity.kt
      Verify: build + tests pass. Device: all 5 presets selectable, selected one is accent-bordered,
      each still writes the preset and shows the apply toast.

- [ ] 11. Content-size 4-chip control, grouped PANES/LAYOUT cards, keep Reset + End (fixes D4, D5).
      Replace the content-scale `contentRow` + `pickContentScale()` with a `duo_screen_content_scale`
      label, the `duo_screen_content_scale_hint` helper line, and
      `AutoBridgeDesign.segmentedToggle(CONTENT_SCALES.map { "$it%" }, selectedIndex =
      CONTENT_SCALES.indexOf(scale)) { idx -> DuoScreenStore.setContentScale(this, CONTENT_SCALES[idx]);
      applied(); render() }`. Wrap the PANES section (count toggle + pane rows) in one
      `cardGroup`, and the LAYOUT section (preset tiles + content size) in one `cardGroup`, matching
      08. Keep the Reset arrangement row (now outside the LAYOUT card as a standalone row like the
      design) and the SESSION "End duo screen now" danger row EXACTLY as today (same handlers,
      confirm dialog, toasts). Keep the pane picker rows (`pickAppFor`) unchanged inside PANES.
      Decision on the extra "Connection" section (D6): the design 08 does not show an Android Auto
      status row, but it is the only place Duo surfaces live AA status and has no MOVE destination,
      so KEEP it but move it visually to sit quietly above PANES (or inside the Shizuku card area)
      rather than delete it — deleting would hide a status with nowhere else to see it. If the
      reviewer insists it must go to match 08 exactly, that is a visible behaviour change: raise it
      via send_message (warning) before removing, per the loop's change-control rule.
      Files: app/src/projection/java/dev/autobridge/projection/DuoScreenSettingsActivity.kt
      Verify: build + tests pass. Device: 4 size chips selectable with the same apply toast; PANES
      and LAYOUT read as two cards; Reset arrangement and End duo screen now behave exactly as
      before (reset toast; end shows confirm then ends/none toast).

---

## Final verification

- [ ] 12. Full build + test gate and preference-safety audit.
      Run `./gradlew testPersonalDebugUnitTest assemblePersonalDebug` and confirm it passes.
      Confirm no preference KEY changed (grep the diff for `KEY_`, `DuoScreenStore`, `BypassPolicy`
      — there should be no key edits), no string was renamed/removed (only additions:
      `duo_screen_grant` EN+TH), and every row/handler that existed before still exists and points
      at the same destination/store call. On a device, screenshot Settings and Duo screen and
      compare section-by-section against docs/design/07_PhoneSettings.png and 08_PhoneDuo.png per
      CLAUDE_md_snippet.md 0.3 before marking done.
      Files: (verification only)
      Verify: build + unit tests pass; device screenshot comparison matches 07 and 08.
