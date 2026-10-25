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

## Thai section labels (letterSpacing)

- `ScriptTracking` (:common) drops letter spacing for Thai, Lao, Khmer, Myanmar and Arabic text; `AutoBridgeDesign.sectionLabel`, `statusChip` and Compose `SectionLabel` use it. 4 JVM tests.
- Real phone (Redmi YXEMRCGYAI49S4SS, personalDebug), 2026-10-24: Appearance group labels "ธีม", "ตัวอักษร", "หน้าแรก" render without gaps (screenshots/thai-section-labels-phone.png).
- Still tracked in their own files (owned by other pieces): `library/PlayerActivity.kt` (E1), `browser/MoreActionsSheet.kt` (E2).

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
