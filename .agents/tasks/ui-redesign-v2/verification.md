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

## Piece B: phone Home layouts (branch claude/ui-redesign-home-3f0fcc)

What changed: `ui/HomeDashboard.kt` picks Dashboard / Classic grid / Compact from `AppearanceStore`
(via `ThemeHolder.spec`); new `ui/home/*` draws each part from the palette (card style, corners,
icon style); `ui/PhoneHomeLayout.kt` gained the pure layout data (`Item`, cards, quick launch,
columns, chip colours); `ui/MiniPlayer.kt` follows the palette and hides on Home in Dashboard;
`res/values*/strings_home_v2.xml` (en + th). MainActivity.kt is untouched: My Car opens through
`onOpenConnection`, Displays through `onBridgeDuo`, Mirror through `onMirror`.

Checked here (no device, per the coordinating session):
- `:app:compilePersonalDebugKotlin`: see below.
- `:app:testPersonalDebugUnitTest --tests dev.autobridge.ui.*`: see below (`PhoneHomeLayoutTest` + new `HomeLayoutsTest`).
- `scripts/check-i18n.sh`: OK, en and th have the same strings.

Device pass still to do (after merge, on the user's phone). Switch via Settings > Appearance > Home layout.
For each of Dashboard, Classic grid, Compact, in dark and light mode, at 100% and 150% text:
1. Android Auto card: palette surface (no dark navy box in light mode), "Android Auto" on one line; at 150% the status pill sits under the status line.
2. Send to Car field and its ➤ button: light fill + accent button in light mode; sending works, Sent / Couldn't send banner shows, Retry works.
3. Recent: one horizontally scrolling row of pills; tapping one re-sends.
4. Dashboard: hero shows (a) the live or last radio station with play/pause, (b) a song from Music with progress, prev/play/next, tap opens the player, (c) "Nothing playing" when neither; the bottom mini player stays hidden in Dashboard. 2x2 cards Radio / TV / My Car / Displays open the right screens (Displays = Multi-app; on the safe build Mirror takes that card). Quick launch row: Browser, YouTube, Mirror, Music, More; Edit opens Apps favorites.
5. Classic grid: 3 columns at 100%, 2 columns and shorter tiles at 150%, labels ellipsize; Show tile labels off hides labels. Radio card above the grid when a station exists.
6. Compact: one card of rows with hairlines; mini player at the bottom while music plays (not for radio, which the radio card shows).
7. Icon style: Tinted (per-section hues), Accent (accent chips), Mono (raised chips). Card style Elevated / Outlined / Glass and Corners Soft / Standard / Sharp change every card and tile.
8. Wallpaper behind the whole Home page (header + mini player area too), not on other screens: None, Glow, Lines, Night road, Solid, Your photo (centre-cropped); Dim 0..70% darkens (whitens in light mode); target "Car only" shows no wallpaper on the phone; Your photo with the file deleted falls back to the plain background.
9. Update card, voice button, Settings, support link (sideload builds) still present.

Known gaps:
- Blur behind glass cards is not drawn (no backdrop blur); Glass is the translucent fill only.
- The page header (View, `AutoBridgeDesign.header`) keeps its existing look; the design's round header buttons on card fill are not applied (not a piece B file).
- `ControlScreen.kt` keeps its own top-level `private val` colours, read once per process, so Control can show stale colours after a theme change until the process restarts (piece E).
- Send to Car sits under the layout body (design order) instead of directly under the Android Auto card.
