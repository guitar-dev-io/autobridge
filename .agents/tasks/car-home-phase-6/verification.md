# Verification — Phase 6: Car Home dashboard look

Branch: `ui/phase-6`. Iteration: FIRST (no `review.json` present).

## Commands run (from repo root `/Users/anuwat.t/Documents/ChatGPT/AutoBridge`)

1. Baseline, before any edit:
   ```
   ./gradlew :app:testPersonalDebugUnitTest
   ```
   Result: **BUILD SUCCESSFUL**. Green starting point.

2. After the Phase 6 edits (the required phase build command):
   ```
   ./gradlew testPersonalDebugUnitTest assemblePersonalDebug
   ```
   Result: **BUILD SUCCESSFUL in 4s** (88 actionable tasks). The `personalDebug` APK assembled
   and all unit tests passed.

## Unit test detail (from `app/build/test-results/testPersonalDebugUnitTest/`)

- `dev.autobridge.car.HomeDashboardLayoutTest` — tests=19, failures=0, errors=0, skipped=0.
- `dev.autobridge.car.HomeDashboardClockTest` — tests=6, failures=0, errors=0, skipped=0.

`HomeDashboardLayoutTest` asserts `continueTitle` is non-null when a hero exists; the layout band
is left untouched (only the renderer stopped painting a title there), so that assertion still holds.

## What changed (look only)

- **Quick Access cards → horizontal (image 01).** `HomeMenuCard.draw` now draws the tinted icon
  tile at `bounds.left + CARD_PADDING`, vertically centred, with the label left-aligned to its
  right starting at `iconRight + CARD_ICON_LABEL_GAP`, baseline on the card centre line, ellipsized
  to the remaining card width. Label `Paint.Align` switched `CENTER → LEFT`. One label size for all
  six cards is still chosen by `HomeDashboardLayout` (unchanged). New token
  `HomeDashboardTheme.Dp.CARD_ICON_LABEL_GAP = 16f` for the icon→label gap. Card bounds, grid, tile
  tint (16% / 32% border), icon-size clamps and focus/pressed border logic are unchanged.
- **"Continue Watching" caption → inside the hero card (image 01).** The renderer no longer paints
  `layout.continueTitle` as a section title above the card. `drawHero` now stacks caption (secondary
  colour) → title → source line → progress line inside the card's right-hand text column. The hero
  inter-line gap shrinks to fit a short hero so the four lines never clip on a small head unit. The
  layout still reserves the `continueTitle` band (budget maths untouched); that band is simply left
  unpainted.
- **Wordmark / headers / count pill + chevron / Duo screen action strip:** confirmed already match
  the design; no change required. Duo strip (image 02) is preset glyph → Reload → Arrange/Done →
  Exit, four actions, EN + TH strings present.
- **Strings:** all `car_home_*` captions used already exist in EN (`values`) and TH (`values-th`);
  no new string keys were introduced, nothing renamed.

## Not verifiable in this environment

The DHU/visual section-by-section comparison against `01_Main.png` at the three profiles
(800×480, 1280×720, 1920×720) requires a running Desktop Head Unit / real head unit and was NOT
run here (headless build environment). The changes are drawing-only and the geometry/format unit
tests are green; a reviewer with a DHU should confirm the horizontal cards, the in-card caption,
and that nothing clips on the 800×480 profile.
