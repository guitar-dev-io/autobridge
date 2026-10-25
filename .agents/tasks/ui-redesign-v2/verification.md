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

## Piece E3: My Car tools, Mirror setup, Dual Display settings (branch claude/ui-redesign-tools-4614ba)

Common to every screen below (12 in `app/src/main`, 3 in `app/src/projection`):
- `attachBaseContext` now chains `AppearanceContext.wrap(AppLocale.rebase(newBase))`, so Text size applies.
- `onCreate` calls `AppearanceStore.refreshPalette(this)` then the new
  `settings/ScreenAppearance.applyWindow(this)`: window background and light/dark system-bar icons
  from the palette (below Android 15 the bar colours too).
- `private val accent = ...` became a getter, so it reads the palette after `refreshPalette`.
- Every `AlertDialog.Builder` takes `ScreenAppearance.dialogTheme()` (new
  `res/values/styles_tools_v2.xml`: `ToolDialogLight` / `ToolDialogDark`; the dialog accent is the
  default Sky, a style cannot follow the chosen accent).
- Fixed section accents used as text or icons go through `ScreenAppearance.readable` (darkened on a
  light page); status colours use the palette roles good / warn / danger.

Per screen:
- Maintenance: due tone (bar + "in N km" text) from `ScreenAppearance.tone`: overdue danger, soon warn, ok good (was danger / Radio amber / Files green).
- Fuel log, Trips, Backup, Weather: wiring + dialogs only (already on `AutoBridgeDesign` roles).
- Costs: fuel/other split bar and legend colours readable on light.
- Parking: "no position" line in warn; the parking icon chip readable on light.
- Emergency: public-line colours kept per service (no `DesignTokens` reads), darkened on light so the call buttons keep contrast; layout and 56 dp call buttons unchanged.
- Car profiles: "in use" in good.
- Voice settings / Voice test: "active / installed" in good; accent (System purple) readable on light; download progress already shows the loading state.
- Mirror setup: step badges done = good, optional = warn, waiting = secondary, blocking = accent.
- Projection setup: the main action is `ScreenAppearance.primaryButton` (accent + onAccent text, pressed ripple, spinner + "Working" while it runs; was 50% alpha INK-on-accent).
- Dual Display (`DuoScreenSettingsActivity`): new `onCreate`; backend, pane-count and content-size segments share one `segment()` (selected = accent fill with onAccent text and `isSelected`, others with a ripple, 44 dp min height; was white text and ~36 dp); the Grant button onAccent text and 44 dp; ready/missing banner good/warn; preview frame on `SURFACE_RAISED` (was the car's fixed near-black, unreadable in light mode); pane hint colours readable; preset chips get a pressed ripple and `isSelected`.
- Desktop browser settings: state colours good/warn, Web accent readable.

Checks run here:
- `:app:compilePersonalDebugKotlin` (includes `src/projection`; there is no separate projection variant) and `:app:compileSafeDebugKotlin`: see the commit message for the result.
- `:app:testPersonalDebugUnitTest --tests dev.autobridge.settings.ScreenAppearanceTest` (tone roles, dialog theme, dark page leaves accents alone).
- No new strings, so `scripts/check-i18n.sh` is unaffected (ran anyway).

Device pass must check (not run here: no emulator or phone for this piece):
- Each screen above in Dark, Light, AMOLED, one preset and one non-Sky accent: page, status bar icons (dark on Light), nav bar, dialogs (light dialogs on Light).
- Light mode: Maintenance soon/overdue colours, Costs bar, Emergency call buttons, Mirror setup badges are readable.
- Text size at the largest step: Dual Display segments still fit (pane count 2/3 next to its label), Projection setup button label.
- Projection setup: tap the main action, the spinner shows until the result toast.
- Dual Display: all three segment rows switch and say "selected" to TalkBack; End session and protected-app dialogs show both buttons.
- Theme changed while one of these screens is in the back stack: it takes the new look only when reopened (no live recreate).

Not done, needs another owner:
- Phone My Car page (`ui/CarConnectionScreen.kt` MyCar composables + `MainActivity.myCarState`): hero card, stat cards with bars (fuel, odometer, next service, battery) and the Trips / Fuel log / Maintenance / Parking / Expenses rows from the canvas; it should colour tones with `ScreenAppearance.tone` (or the same palette roles).
- Mirror settings page (`MainActivity.buildMirrorSettingsScreen`): the canvas's status card (phone → car, Start / Connecting / Stop, confirm dialog), quality segments and toggles. Connecting needs a re-render on `ProjectionService.SessionState.STARTING -> READY`, which nothing posts today.
- The canvas's Dual Display (front/rear cards, swap, rear app picker, audio route) describes a front/rear display model; `DuoScreenSettingsActivity` is the panes-on-one-car-screen feature, so only the styling was applied, no new features.
- ui/* items listed in the shared notes (`SettingsUi.segments` white text, `CarToolForm.chips` INK text, `CarToolViews.card` ignoring card style, dark-tuned `ACCENT_ONLINE`/`SIGNAL_*` consts).
- MainActivity could call `ScreenAppearance.applyWindow` for the foundation's "status bar stays dark in light mode" gap.
