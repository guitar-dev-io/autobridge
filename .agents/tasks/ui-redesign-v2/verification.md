# UI redesign v2: verification

## Step 0: foundation (branch claude/ui-redesign-foundation)

- `:common:testDebugUnitTest --tests dev.autobridge.design.*`: 18 tests pass (resolver, text-scale curve, codec).
- `:app:compilePersonalDebugKotlin` and `:app:testPersonalDebugUnitTest --tests dev.autobridge.ui.*`: pass.
- `scripts/check-i18n.sh`: OK, en and th have the same strings.
- Emulator (Pixel_8_API_35, personalDebug APK), 2026-10-24:
  - Settings > General > Appearance opens; Mode, Theme preset, Accent, Follow Android font size, Text size, Home layout, Use this look on Android Auto render (screenshots/appearance-dark.png).
  - Accent -> Rose and Mode -> Light apply at once; the activity recreates back on Appearance (screenshots/appearance-light-rose.png).
  - Text size slider to a larger step resizes Home text (screenshots/home-light-larger-text.png).

Known gaps, left to the pieces (not regressions in dark mode, which is the default):
- Light mode: Home's Android Auto card, the Send to car field and its send button keep hard-coded dark fills (piece B); other screens that read `DesignTokens.Phone` directly stay dark (piece E).
- Slider thumb is white, invisible on light surfaces (`SettingsUi.sliderRow`, piece A).
- "Android Auto" title wraps to two lines at large text (piece B).
- Status bar stays dark in light mode (piece A: set light system bars from the palette).
- Not tried on the real phone or on a head unit.

## Piece C: car home dashboard (branch claude/ui-redesign-car-home-8489fd)

What changed: the surface-drawn car home (`CarHomeDashboardScreen` -> `HomeDashboardRenderer`) now
paints from `HomeDashboardLook`, resolved from `ThemeHolder.spec` + `ThemeHolder.carPalette` and
Android's own font scale. The look covers the palette roles, home layout (Dashboard / Classic /
Compact), icon style, card style, corners, tile labels (Classic only), wallpaper when the target
is Car or Both (with its dim), and car text through `TextScaleCurve.carScale` (capped at 130%).
`renderMenu()` reads the look on every frame and lays out again when it changes; the screen also
collects `AppearanceStore.spec` so a change repaints at once. A three-line edit to
`CarHomeDashboardScreen.kt` (outside piece C's file list) was needed for that.

Tests run here (no device run, as the coordinating session asked):
- `./gradlew :app:compilePersonalDebugKotlin`: passes.
- `./gradlew :app:testPersonalDebugUnitTest --tests 'dev.autobridge.car.HomeDashboard*'`: 55 tests
  pass (Appearance 21, Layout 28, Clock 6), 2026-10-24. The new tests are in `HomeDashboardAppearanceTest`: layout selection, Classic 3x2 / 4x2,
  Compact 2 columns + strip hits, short-unit scrolling, no overlaps, the 130% text cap (including
  "follow Android" at 200%), carSameLook off = the full default look, wallpaper target, icon styles.

Not checked on a head unit. Known limits:
- Glass cards are translucent but not blurred (a surface Canvas has no backdrop blur).
- System/Auto theme modes are re-resolved only when `AppearanceStore.refreshPalette` runs (an
  activity resuming), not on a timer while only the car is on.
- Classic/Compact have no per-feature subtitle line (the prototype's "Coolwave 93 · Live"): the app
  has no real data for it, so the rows show the name only.
- Dashboard keeps the existing grid of all feature cards (3x2, or 4x2 with eight) beside Now Playing
  instead of the prototype's 2x2, so no feature becomes unreachable.

### DHU steps (one pass after merge)

Setup (see memory note "DHU needs a force-stop first"): `ANDROID_SERIAL=<phone>`, install
`personalDebug`, then `adb shell am force-stop com.google.android.projection.gearhead`, Android Auto
-> ⋮ -> Start head unit server, then start DHU at once. Redo the whole cycle after every install.
Use the 1280x720 profile first, then the 800x480 one.

1. Defaults (dark, Sky, Dashboard, Glow, elevated, soft): the home shows the AutoBridge header,
   the Now Playing card (or its dashed "Nothing playing yet") beside the feature cards, Recently Sent
   and the queue pill. A faint accent glow in the top-right corner, nothing else changed in layout.
2. Phone: Settings > Appearance > Accent -> Rose, with the car home showing. Without touching the
   car, the wordmark's "Bridge", the play button, the progress fill and the queue pill turn rose
   within a second.
3. Mode -> Light: background, cards and text switch to light; feature glyphs stay their hue
   (darker); YouTube's play mark stays visible.
4. Home layout -> Classic: features alone in two rows (3 across for six, 4 across for eight in the
   personal build); tap each tile, it opens its screen; Back returns with the opened tile drawn
   focused (accent ring).
5. Icons & cards -> Show labels off (Classic): tiles show the chip only, still tappable.
6. Home layout -> Compact: two columns of rows (chip, name, chevron) and a strip at the bottom.
   With a session: art, title, "YouTube · Now playing", play button; play toggles, tapping the
   strip opens the session. With none: the dashed strip saying nothing is playing.
7. Icon style Accent, then Mono: chips become accent with dark glyphs, then grey with white glyphs.
8. Card style Outlined, then Glass; Corners Sharp: card edges appear, radii shrink on cards, tiles,
   rows and chips; pressed cards still flash.
9. Wallpaper Lines / Night road / Solid / Your photo with target Car or Both: drawn behind the
   home with the dim; target Phone: the car shows the plain background.
10. Text size Huge (150%) and "Follow Android" with Android at max: car text grows to the 130%
    cap and no further; labels still fit; nothing overlaps; Dashboard grid stays where it was.
11. "Use this look on Android Auto" off: the car returns to the full default look (dark, Sky,
    Dashboard, 100% text) while the phone keeps its own.
12. 800x480 profile: Dashboard keeps its compact profile as before; Classic fits without
    scrolling; Compact fits with six features, and where it cannot (eight, or large text) it
    scrolls with the scroll buttons and the strip follows the last row.
