# Task report — Phase 5 Settings (07) + Duo screen (08) UI fidelity

UI-only. No change to playback, mirroring, bridge, IPTV parsing, safety-gate logic, preference
keys, Shizuku operations, or any functional behaviour. Every pre-existing preference stays
reachable. No string was renamed or removed; no new string was needed (all copy already existed in
EN + TH).

## Starting state

Phase 5 was partly committed already (groups + Duo copy in place), but the Settings screen still
rendered each row as a separate floating card with purple-only badges — it did **not** match
design 07. A finished-but-unwired helper `app/src/main/java/dev/autobridge/ui/SettingsUi.kt`
(grouped-card rows, value rows, tinted switch row, segment/danger helpers) was sitting untracked
in the tree. This task wired that helper into Settings and tightened the Duo screen grouping to
match 08.

## Screen 1 — Settings (`MainActivity.kt`)

Reworked `settingsListPage`, `settingsEntry`, `SettingsGroup`, and `buildSettingsMenu` to use
`SettingsUi`:

- **Grouped cards (G1):** each section (CAR & APPS / PLAYBACK & WEB / SAFETY / GENERAL) is now one
  rounded card with hairline dividers between rows via `SettingsUi.group`, instead of separate
  floating `contentRow`s. Advanced and About (which share `settingsListPage`) gain the same
  grouped-card look and still list every row they listed before.
- **Per-row accent badges (G2):** `settingsEntry` takes an `accent` param. Car & Connection =
  ACCENT (blue), Duo screen = ACCENT_SYSTEM (purple), Apps & profiles = ACCENT_SYSTEM, Display &
  Mirror = ACCENT, Input & Touch = ACCENT, Video & subtitles = ACCENT_VIDEO (pink), YouTube =
  ACCENT_FAVORITE (red/pink), Browser = ACCENT_WEB (green).
- **GENERAL value rows (G3):** new `settingsValueEntry` → `SettingsUi.valueRow` draws Agent &
  Commands ("Quick actions, history") and Language ("ไทย") with no icon badge and the value
  right-aligned before the chevron. Advanced and About below keep normal icon-badge rows.
- **Safety toggle (G4):** the bypass row is now `SettingsUi.switchRow` with an accent-tinted
  (`ACCENT_RADIO` amber) toggle inside the SAFETY card. Turning ON still shows the exact same
  confirmation dialog (then `toggleBypass()`); turning OFF still calls `toggleBypass()`
  immediately. The persistent notification and `BypassPolicyStore` behaviour are unchanged.

Every pre-existing Settings destination is still reachable: Car & Connection, Duo screen,
Apps & profiles, Display & Mirror, Input & Touch, Video & subtitles, YouTube, Browser, Safety
bypass, Agent & Commands, Language, Advanced, About — each row calls the same
`showPhoneScreen(...)`/`startActivity(...)`/handler as before.

## Screen 2 — Duo screen (`DuoScreenSettingsActivity.kt`)

The screen already carried the Shizuku banner + Grant pill, pane-count 2|3 segment, 5 layout
preset mini-diagrams, 4 content-size chips, and the danger "End duo screen now" card. Tightened
the grouping and pane rows to match 08:

- **PANES as one card (D5):** the "Number of panes" segment row and the pane rows now live in a
  single `SettingsUi.group` card with hairline dividers, instead of loose stacked cards.
- **Pane rows:** each pane row shows a numbered square badge (green for pane 1, red for pane 2,
  blue for pane 3 — matching 08), the quiet "Pane N" label over the bold chosen-app name, and a
  chevron. Still opens the same `pickAppFor(index)` picker and writes via `DuoScreenStore`.
- **Reset arrangement:** now a standalone rounded card with no icon badge (title over hint +
  chevron), matching 08. Same `resetLayout` + `DuoScreenHost.onLayoutReset()` + toast.
- LAYOUT card, content-size chips, Shizuku banner/Grant, and the SESSION danger card are
  unchanged in behaviour; the "End duo screen now" confirm dialog and toasts are exactly as
  before.

No Duo preference, `DuoScreenHost` apply path, Shizuku permission call, app picker, confirm-end
dialog, or `applied()` toast logic was changed — only how the controls look.

## Verification

See `verification.md`. `./gradlew testPersonalDebugUnitTest assemblePersonalDebug` → BUILD
SUCCESSFUL, unit tests pass. Device-level section-by-section comparison against 07/08 remains for
the reviewer/human per CLAUDE_md_snippet.md 0.3.
