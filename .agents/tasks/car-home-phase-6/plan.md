# Implementation Plan — Phase 6: Car Home dashboard look

Restyle the Android Auto car-surface Home dashboard to match `docs/design/01_Main.png`, changing
the **look only** — never the layout logic. Phases 1-5 are merged; this is the final phase on
branch `ui/phase-6`.

## Scope decision (why this is one plan, not a FEAT decomposition)

The work is a single cohesive visual restyle of one dashboard spread across three tightly-coupled
files that form one design system (`HomeDashboardTheme` tokens → `HomeDashboardLayout` geometry →
`HomeDashboardRenderer` / `HomeMenuCard` painting). The "look only, not layout logic" constraint
makes these edits interdependent rather than separable, so the existing implement-and-review loop
runs this plan directly. No workflow restructuring.

## What I found during exploration (so the implementer does not re-derive it)

Most of Phase 6 is **already implemented** in the current code — the hero card, quick-access grid,
Recently Sent / Queue blocks with count pill + chevron, progress bar + `elapsed / total`, tinted
icon tiles (16% / 32% via `ICON_TILE_TINT` / `ICON_TILE_BORDER_TINT`), the `Auto`+`Bridge` wordmark,
and all strings (EN + TH) exist. The Duo screen action strip already matches image 02 exactly
(preset glyph, Reload, Arrange/Done, Exit). So this plan is a **targeted set of look adjustments**
to close the gaps between the current rendering and image 01, plus verification — not a rewrite.

Two concrete visual gaps versus `01_Main.png`:

1. **Quick Access cards are stacked, the design is horizontal.** `HomeMenuCard.draw` centres the
   icon tile above a centred label as one vertical block. Image 01 shows the tinted icon tile on
   the **left**, with the title **left-aligned to its right**, vertically centred. This is the main
   look change.
2. **"Continue Watching" label placement.** The renderer draws `continueTitle` as a standalone
   section title *above* the hero card (`drawSectionTitle(layout.continueTitle, …)`). In image 01
   the "Continue Watching" caption sits **inside** the hero card, as a small secondary-colour line
   above the video title in the right-hand text column.

Everything else is a size/weight check against the design.

### Build & test commands (from `app/build.gradle.kts`, flavor dimension `mode` → safe/lab/personal)

- Unit tests (car dashboard geometry/format): `./gradlew :app:testPersonalDebugUnitTest`
- Full build the project uses for a phase: `./gradlew testPersonalDebugUnitTest assemblePersonalDebug`
- The pure-JVM tests that cover this work: `HomeDashboardLayoutTest`, `HomeDashboardClockTest`
  (both in `app/src/test/java/dev/autobridge/car/`).

### OFF-LIMITS (do not touch)

- `HomeDashboardLayout.kt` **logic**: budgets (`HomeDashboardTheme.Budget`), the `place()` passes,
  scrolling, side-by-side decision, hit-testing, and the GridTemplate fallback for Car API < 5 in
  `CarHomeDashboardScreen.gridTemplate()`. You MAY add/adjust **dp token values** in
  `HomeDashboardTheme.Dp` and read them from the layout, and you MAY add a new label-geometry field
  to the layout if the horizontal card needs it (see item 2) — but the budgeting/scroll/hit maths
  stays byte-for-byte equivalent in behaviour.
- All behaviour: playback, mirroring, bridge, IPTV parsing, safety-gate, navigation
  (`actionFor`, `handOverSurface`, `CarHomeNavigator`, resume-last-session).
- The host chrome: never draw/imitate the side rail, clock, or status bar. Keep the single `More`
  action strip (`CarIcons.APPS`) in `menuTemplate()`.
- Existing string resources: add new ones (EN + TH) if needed; never rename or remove.
- Do not invent ad-hoc colors — reuse / extend tokens in `HomeDashboardTheme`.

---

## Plan

- [ ] 1. Baseline: build and run the car dashboard unit tests to confirm a green starting point.
      Capture current `HomeDashboardLayoutTest` / `HomeDashboardClockTest` results so later diffs
      are attributable to this phase.
      Files: none (read-only baseline).
      Verify: `./gradlew :app:testPersonalDebugUnitTest` — passes before any edit.

- [ ] 2. Restyle Quick Access cards to the horizontal layout in image 01: tinted icon tile on the
      LEFT, title left-aligned to its right, both vertically centred in the card. Change painting
      only — the card bounds, grid, tile tint (16%) and border tint (32%) come from the existing
      `HomeDashboardLayout` / `HomeDashboardTheme` and must stay. In `HomeMenuCard.draw`, replace
      the centred icon-over-label block with: icon tile at `bounds.left + cardPadding`, vertically
      centred; label with `Paint.Align.LEFT`, baseline on the card's centre line, starting at
      `iconRight + gap`, ellipsized to the remaining width. Keep one consistent label size for all
      six cards (the design shows uniform sizing). If the label's available width now differs from
      the current `layout.labelMaxWidth` (which assumes a centred label spanning the card), add a
      token for the icon→label gap in `HomeDashboardTheme.Dp` (e.g. `CARD_ICON_LABEL_GAP`) and
      compute the label's max width in the renderer/card from `cardWidth - padding*2 - iconSize -
      gap` rather than changing `HomeDashboardLayout`'s budget maths. Do NOT alter card bounds,
      icon size clamps, or the focus/pressed border logic.
      Files: `app/src/main/java/dev/autobridge/car/HomeMenuCard.kt`,
      `app/src/main/java/dev/autobridge/car/HomeDashboardTheme.kt` (new gap token only, if needed).
      Verify: `./gradlew :app:testPersonalDebugUnitTest assemblePersonalDebug` — compiles and all
      existing tests still pass (the card painting is not asserted by the layout test, so no test
      should break; if `labelMaxWidth` usage moved, confirm `HomeDashboardLayoutTest` is untouched
      and green). Visual confirmation against `01_Main.png` is done in the review step on a DHU.

- [ ] 3. Move the "Continue Watching" caption inside the hero card. In `HomeDashboardRenderer.draw`,
      stop drawing `layout.continueTitle` as a standalone section title above the card. In
      `drawHero`, add a small secondary-colour caption line ("Continue Watching",
      `R.string.car_home_continue_watching`, `TEXT_SECONDARY`) at the top of the right-hand text
      column, above the title, matching image 01's stacking (caption → title → source line →
      progress line). Reuse the existing `meta`/`sectionTitle` paints and `heroMetaSize` token;
      do not add a new color. Because `HomeDashboardLayout` still reserves the `continueTitle` band,
      keep the band reservation untouched (layout logic is off-limits) — only the renderer stops
      painting a title there; if that leaves a visible gap above the card on a DHU, note it for the
      reviewer rather than changing the budget. (Preferred: keep `continueTitle` painted but confirm
      against the image whether the design truly has no outside caption; if the image clearly shows
      the caption only inside the card, make the renderer draw it inside and leave the outside band
      empty. State which choice you made and why in the commit message.)
      Files: `app/src/main/java/dev/autobridge/car/HomeDashboardRenderer.kt`.
      Verify: `./gradlew :app:testPersonalDebugUnitTest assemblePersonalDebug` — compiles, tests
      pass. `HomeDashboardLayoutTest` asserts `continueTitle` is non-null when a hero exists; that
      box must remain, so this change must not remove it from the layout.

- [ ] 4. Check wordmark, hero, and header sizes against image 01 and adjust **token values only**.
      The wordmark ("Auto" white + "Bridge" accent) is already drawn in
      `HomeDashboardRenderer.drawHeader` from `WORDMARK_RATIO` and the logo size. Compare against
      the design proportions (logo ≈ wordmark cap-height; "Bridge" in `ACCENT`); tune
      `WORDMARK_RATIO`, `Dp.LOGO_TO_TITLE`, and the hero title/meta ranges
      (`HERO_TITLE_MIN/MAX`, `HERO_META_MIN/HERO_META`) in `HomeDashboardTheme` if they read small
      or large versus the image. Change constants only; do not touch how the layout consumes them.
      Files: `app/src/main/java/dev/autobridge/car/HomeDashboardTheme.kt`.
      Verify: `./gradlew :app:testPersonalDebugUnitTest assemblePersonalDebug` — compiles, tests
      pass (these tokens are not range-asserted by the budget test).

- [ ] 5. Verify Recently Sent / Queue headers match image 01: section title + count pill (accent
      18% fill, accent text) + chevron, and the row content (thumbnail, title, meta line for
      Recently Sent, trailing time for Queue, play badge where the design shows one). This is
      already implemented in `drawBlock` / `drawRow`; confirm the count-pill and chevron sizing/
      spacing read like the design and adjust only `Dp.COUNT_PILL_PADDING`, `Dp.CHEVRON`, or the
      pill height multiplier if visibly off. Do not change which blocks appear or the row data.
      Files: `app/src/main/java/dev/autobridge/car/HomeDashboardRenderer.kt` (sizing tweaks only),
      `app/src/main/java/dev/autobridge/car/HomeDashboardTheme.kt` (token tweaks only).
      Verify: `./gradlew :app:testPersonalDebugUnitTest assemblePersonalDebug` — compiles, tests
      pass.

- [ ] 6. Confirm Car Duo screen (image 02) needs no change beyond the action strip, which already
      matches. Read `duoscreen/.../car/DuoScreenScreen.kt`: the strip is preset glyph →
      `duo_screen_reload` → `duo_screen_edit_layout`/`duo_screen_done` → `duo_screen_exit`, all four
      present with EN + TH strings. Make NO code change here unless a label is wrong versus image
      02; if it is, fix only the string value (EN + TH), never add a 5th action (strip caps at 4).
      Files: none expected (read-only confirmation).
      Verify: `./gradlew :duoscreen:testDebugUnitTest` if the module has JVM tests, otherwise
      `./gradlew assemblePersonalDebug` — compiles. Record "no change required" in the commit/notes.

- [ ] 7. Confirm strings: all `car_home_*` captions used by the dashboard
      (`car_home_continue_watching`, `car_home_quick_access`, `car_home_recently_sent`,
      `car_home_queue`, `car_home_sent_from_phone`, `car_home_more`) exist in both
      `app/src/main/res/values/strings.xml` and `values-th/strings.xml`. They do today. If any new
      caption was introduced in items 2-5, add it to BOTH locales. Never rename an existing key.
      Files: `app/src/main/res/values/strings.xml`, `app/src/main/res/values-th/strings.xml`
      (only if a new string was added).
      Verify: `./gradlew assemblePersonalDebug` — resource linking succeeds (a missing TH string
      for a referenced key fails the build / lint).

- [ ] 8. Full phase verification. Run the project's phase build command and confirm the whole app
      builds and the car dashboard unit tests pass. Then do the design comparison the working rules
      (`CLAUDE_md_snippet.md`) require: build the app, run it in the DHU at the three profiles the
      spec lists (800×480, 1280×720, wide 1920×720), screencap the Home dashboard, and compare it
      section-by-section with `docs/design/01_Main.png` — nothing clipped, scroll buttons only when
      needed, tap targets land on the right card after scrolling, text readable at arm's length.
      Record which DHU profiles were checked and any item that could not be verified without a real
      head unit.
      Files: none (verification only).
      Verify: `./gradlew testPersonalDebugUnitTest assemblePersonalDebug` — all green; DHU
      screencaps match `01_Main.png` section by section (hero caption inside card, horizontal
      quick-access cards, count-pill + chevron headers, progress readout).

## Notes / assumptions

- Items 3-6 are confirm-and-tune steps because the code already implements the structure; the real
  code change is item 2 (horizontal cards) and possibly item 3 (caption placement). Keep diffs
  minimal and token-driven so the "look only" constraint is provably honoured.
- The DHU/visual checks in item 8 and the design comparison may be impossible to fully confirm in a
  headless CI environment; if so, the implementer must say so explicitly (as prior phases did in
  their checklists) rather than claim visual parity.
