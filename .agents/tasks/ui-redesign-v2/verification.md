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

## E2: phone browser (branch claude/ui-redesign-browser-654a50)

- 8618302 was committed uncompiled when the session hit its usage limit. Follow-up commit moves `NAV_HEIGHT_DP` / `NAV_MARGIN_DP` above `NAV_BAR_RESERVE_DP` (const initialised before declaration); nothing else changed.
- `:app:compilePersonalDebugKotlin`: pass. `:app:testPersonalDebugUnitTest --tests dev.autobridge.browser.*`: pass. `scripts/check-i18n.sh`: OK.
- Car browser path unchanged: car pages keep `BrowserAppearanceStore.wantsDark` / `apply` (system night mode); the phone uses the new `phonePalette` / `phoneWantsDark` / `applyPhone`.
- Device pass still owed on the phone: start page (search, speed dial, Continue reading, toolbar) in dark and light, loading bar, Light/Dark override in the browser's own Appearance setting, history delete/clear still works, Thai "More actions" labels without letter spacing.
