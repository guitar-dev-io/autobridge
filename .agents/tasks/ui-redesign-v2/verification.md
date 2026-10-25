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
## Thai section labels (letterSpacing)

- `ScriptTracking` (:common) drops letter spacing for Thai, Lao, Khmer, Myanmar and Arabic text; `AutoBridgeDesign.sectionLabel`, `statusChip` and Compose `SectionLabel` use it. 4 JVM tests.
- Real phone (Redmi YXEMRCGYAI49S4SS, personalDebug), 2026-10-24: Appearance group labels "ธีม", "ตัวอักษร", "หน้าแรก" render without gaps (screenshots/thai-section-labels-phone.png).
- Still tracked in their own files (owned by other pieces): `library/PlayerActivity.kt` (E1), `browser/MoreActionsSheet.kt` (E2).
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

### Piece C: real-phone attempt, 2026-10-25

- `:app:assemblePersonalDebug` builds (this worktree first needed the whisper.cpp submodule; it was
  cloned locally from the main checkout at the pinned v1.9.5 commit, nothing committed).
- Installed on YXEMRCGYAI49S4SS under the phone lock: `Success` (no MIUI gate this time).
- Blocked: the phone was on its secure lock screen (fingerprint/PIN, `deviceLocked=1`). Starting the
  head unit server needs Android Auto's settings open on an unlocked phone, so no DHU run and no
  screenshots. Appearance prefs reset to defaults, lock released.
- To finish: unlock the phone, then follow the DHU steps above. A helper that writes appearance prefs
  without the piece A UI: `adb shell run-as dev.autobridge` writing
  `shared_prefs/autobridge_appearance.xml` (keys from `AppearanceCodec`, e.g. `home_layout=CLASSIC`),
  then force-stop `dev.autobridge` and reopen AutoBridge on the DHU.

## Piece E1: phone media screens (branch claude/ui-redesign-media-72f07c)

- `:app:compilePersonalDebugKotlin`: pass. `:app:testPersonalDebugUnitTest --tests dev.autobridge.{library,entertainment,youtube}.*`: pass. No new strings, so check-i18n is not needed.
- 2026-10-25: built the personalDebug APK and installed it on the phone (YXEMRCGYAI49S4SS), install Success. The screen check was NOT done: the phone sat on its secure lock screen (PIN), which a session cannot unlock. Appearance settings were not touched. Still owed: Radio, the player and the library in Dark and Light.

What changed, per screen:
- All 9 activities (Library, Player, RadioPlayer, Subtitle/Video/VideoEnhancement/Storage settings, Entertainment, YouTube settings): `AppearanceContext.wrap(AppLocale.rebase(...))` in attachBaseContext, `AppearanceStore.refreshPalette(this)` at the start of onCreate.
- New `library/MediaTheme.kt`: chip/card selected state (accent outline + accentSoft + accent label, weight 600), accent circle, skeleton rows.
- Radio page (LibraryActivity): search field, list button, bottom tabs, genre chips (now 44dp), station cells and favourite star use palette roles + accent; on-air cell = accentSoft + accent ring. Loading = 5 skeleton rows instead of "Loading" text. Amber stays only on the no-logo station badge.
- Library chips row: new selected state, 44dp. Pills, empty-state buttons, ON CAR badge use the accent; rows/tiles keep the section hue on their icon chips only. Signal colours read palette good/warn.
- Radio player: play button, sleep minutes and favourite (selected state) take the accent; prev/next/round icons on `raised`, icons tinted with `text`; Live pill = amber section badge (warn text on amber tint), live dot = danger.
- EQ sheet (AudioEffectsSheet): surface sheet, accent switch/sliders/checkbox/values, new chip states, Reset 44dp.
- Player: scrubber, spinner and queue ripple take the accent; artwork glyph keeps the section hue; queue rows 44dp; video stage and fullscreen stay black; Thai-safe letter spacing (ScriptTracking).
- Settings activities (subtitle/video/enhancement/YouTube): control accent = user accent (was video red). Storage left on its system hue (icon chips only).
- Entertainment: background, text, buttons, checkbox from the palette.
- StreamingIcons: rim and star badge follow light/dark. HomeSections: unchanged (identity hues for icon chips, also read by the car).

The device pass must look at:
- Radio in Dark, Light, AMOLED and a non-blue accent: tabs, chips, on-air ring, the skeleton while a category loads.
- The Radio mini player still uses the warm amber style: `ui/MiniPlayer.kt` belongs to piece B.
- ic_radio_eq's knobs become solid when tinted (they had a dark fill); check they read.
- AlertDialogs, Switch and EditText underlines follow the app theme, not the palette.
- Entertainment buttons (custom background now) and the light-mode text.

## E2: phone browser (branch claude/ui-redesign-browser-654a50)

- 8618302 was committed uncompiled when the session hit its usage limit. Follow-up commit moves `NAV_HEIGHT_DP` / `NAV_MARGIN_DP` above `NAV_BAR_RESERVE_DP` (const initialised before declaration); nothing else changed.
- `:app:compilePersonalDebugKotlin`: pass. `:app:testPersonalDebugUnitTest --tests dev.autobridge.browser.*`: pass. `scripts/check-i18n.sh`: OK.
- Car browser path unchanged: car pages keep `BrowserAppearanceStore.wantsDark` / `apply` (system night mode); the phone uses the new `phonePalette` / `phoneWantsDark` / `applyPhone`.
- Device pass still owed on the phone: start page (search, speed dial, Continue reading, toolbar) in dark and light, loading bar, Light/Dark override in the browser's own Appearance setting, history delete/clear still works, Thai "More actions" labels without letter spacing.
