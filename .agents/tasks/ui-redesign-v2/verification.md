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

E3 phone attempt, 2026-10-25: `:app:assemblePersonalDebug` built (after initialising the whisper.cpp
submodule in this worktree from the main checkout) and installed on YXEMRCGYAI49S4SS (`Success`), but
the phone sat on its lock screen with a fingerprint prompt, so no screen was opened and no
screenshots were taken. Appearance was not changed; lock released. The device checks listed above
are all still open.

## Merged build: device pass (2026-10-25, real phone YXEMRCGYAI49S4SS + DHU 800x480)

All six pieces merged into `claude/ui-redesign-foundation` (only conflicts: this file). `:app:compilePersonalDebugKotlin` + `:app:compileSafeDebugKotlin`, `:common:testDebugUnitTest`, `:app:testPersonalDebugUnitTest` (all) pass; check-i18n OK. personalDebug installed with `install -r`.

Checked on the phone (screenshots/merged-*.png):
- Appearance page (A): live preview, 5 modes, accents, layout cards, rows, car toggle, reset dialog + reset back to defaults.
- Home (B): Classic grid, Dashboard, Compact; dark and light; 150% text (cards grow, status chip wraps).
- Light mode across: Radio/Library (E1), browser chrome (E2), My Car page, Maintenance (E3).
- Text sub-page: 6 steps, 150% note, Follow Android row.

Checked on the DHU (C):
- Car home follows the phone: Compact + light + 130% cap (scroll buttons), Dashboard + dark after a cycle.
- Live: accent Rose on the phone repaints the car (wordmark, glow, chip, focus ring) within seconds; Reset returns Sky and 100%.

Found, not fixed yet:
1. `LibraryActivity` (Radio) in light mode: status bar icons stay white on the light background (A's system-bar fix covers MainActivity only).
2. Appearance live preview at 150%: the mini phone sits lower than the mini car.
3. DHU 800x480, Dashboard: the Now Playing card fills the height and the feature cards start below the fold (scroll), at 100% and at the 130% cap.
4. Font rows (Inter, Manrope, Plex, Noto, Roboto) all draw in the same face: real fonts are piece D.
- Environment, not a bug: force-stopping the app while the DHU runs freezes the car surface on its last frame; a full force-stop + head unit server + DHU cycle recovers it (memory: dhu-needs-force-stop-first).

## Bug fixes + remaining pages (2026-10-25, phone, Light + Sky)

Fixed from the "Found, not fixed yet" list above:
1. Library, radio and the library/YouTube settings pages call `ScreenAppearance.applyWindow` (7 activities): dark status/navigation bar icons on light pages.
2. Appearance live preview row is top-aligned: the mini phone no longer drops below the car at large text.
3. Car Dashboard, compact profile, nothing playing, card stacked above the grid (eight features on 800x480, which cannot sit beside a four-column grid, since 2ff4ada): the empty "Nothing playing yet" card gives way, so every feature card is on screen. Test: `HomeDashboardLayoutTest` "a fresh install on the DHU small profile with eight features...". Not a redesign regression, but found in this pass.

Pages that did not follow the theme, now do (checked on the phone in Light):
- `MainActivity.COLOR_*` were compile-time constants: Display & mirror, Touch & safety, Debug, Developer/Diagnostics pages were dark cards on a light page. Now getters on the palette; primary action text uses `onAccent`.
- Control screen: selected segment and queue badge (dark navy under dark text), Home card: now accent-soft from the palette.
- Radio-style mini player: warm amber card and amber play button now palette (accent) like the rest of the radio design; amber stays on the "on air" bars and artwork chip.
Checked light: Settings, Control, Control history, Apps, Profile, Car connection, Agent & commands, Mirror, Input & touch, Advanced, Debug, Developer, About, More, Streaming, Favorites.
Not changed on purpose: brand logos (StreamingIcons), QR code, the section hue chips, video player surfaces.

## Pieces D and F: fonts and Thai letterforms (2026-10-25, phone + DHU 800x480)

What it is: `AppFonts` builds the app's typeface from `AppearanceSpec.font` (Inter, Manrope, IBM Plex Sans, Noto Sans: variable fonts in `res/font`, one file each, the `wght` axis cut into 400/500/600/700; Roboto/System = the phone's own) and `AppearanceSpec.thai` (System, Looped = Noto Sans Thai Looped, Loopless = Noto Sans Thai), Latin first, Thai second, system fallback last. 3.9 MB of fonts (debug APK 96 -> 100 MB); SIL OFL texts in `assets/licenses`, credit in About (en + th).
- About 90 `Typeface.create("sans-serif...")` / `DEFAULT_BOLD` calls in 33 files now go through `AppFonts` (regular/medium/bold/weight). Left alone: `Typeface.MONOSPACE`, and `Typeface.create(otherTypeface, BOLD)` derivations.
- Compose: `AutoBridgePhoneTheme` Typography uses one Compose `AndroidFont` per weight, each wrapping the typeface for that weight. A bare `FontFamily(Typeface)` made Compose ignore every `fontWeight` (found on the phone: card titles lost their bold) and a per-weight font also carries the chosen Thai.
- Car home: `HomeDashboardRenderer.syncFonts` re-fits the paints when the car's font changes (car follows the phone's font unless "use this look on Android Auto" is off); `HomeDashboardLook` carries `font`/`thai`, so a change lays out again (text widths differ).
- Text page: each family row draws "Aa" in its own face; new "Thai letterforms" group (phone's, with loops, loopless) with a sample in each.
- `ThaiStyle` in `:common` + codec + tests (unknown value falls back to System).

Checked on the phone: Text page rows in their own faces; Manrope + Looped, Manrope + Loopless, Roboto + Loopless on Home (Latin and Thai both change; semibold titles on Home cards and Control restored); no crash in logcat. DHU: car home follows the phone's Thai style live (Loopless then Looped) without a restart. Defaults restored afterwards (Inter, System Thai, Dark, Sky).
Tests: `:common`, `:app:testPersonalDebugUnitTest` pass; safe flavor compiles; check-i18n OK.

Not done / known:
- Thai line height is not overridden: Noto Sans Thai's own metrics are used (no clipping seen on Home at 150%); the 1.6 leading in the Text page sample stays as A set it. A per-script line-height rule was not added.
- Typefaces cached at construction by the car radio screens (`CarRadioNowPlayingScreen`, `CarRadioGridRenderer`, `CarSurfaceKeyboard`) take a new font the next time those screens open, not live.
- Car template screens (ListTemplate etc.) are drawn by the host and keep its font.
- `AlertDialog` titles and buttons use the dialog theme's font, not `AppFonts`.
- Sarabun/Prompt and other Thai families were not added; Looped/Loopless are the two Noto Thai variants.
- Not checked: About page credits on screen; IBM Plex Sans and Noto Sans Latin rows were built but only Manrope and System were picked in the pass.

## Menus, dialogs and pickers follow the theme (2026-10-25, phone)

Why: pages drew from the palette, but menus, popups, pickers, switches and `AlertDialog`s take their look from the activity's Android theme, which XML fixes as dark. A light page opened dark dialogs, and the accent never reached them.
- `AppTheming` (an `ActivityLifecycleCallbacks.onActivityPreCreated`, registered in `AutoBridgeApplication`): an activity on `AppTheme` gets `AppThemeLight` (a Material Light twin) when the palette is light, and `AccentOverlay.<accent>.<Dark|Light>` (the user's accent as `colorAccent`) on either. Activities with a theme of their own (browser, translucent share target, licences, car-facing surfaces) are skipped.
- `AppTheme` / `AppThemeLight` set `colorBackgroundFloating` (menus, popups) and `alertDialogTheme`.
- `ScreenAppearance.dialogTheme()` now returns a style per accent and brightness (12, `ToolDialog{Dark,Light}.<Accent>`); 31 more `AlertDialog.Builder` calls in 10 files use it (browser dialogs keep the browser's own theme).
- Test: `ScreenAppearanceTest` covers brightness and accent.

Checked on the phone: time picker (Auto schedule) in Light + Rose: light dialog, rose header and buttons; the My Car break-reminder sheet in Light + Rose; Appearance in Dark + Rose unchanged. Android's per-app language screen is the system's, not ours.
Not checked: View-based `PopupMenu`s and spinners on a device (they take the activity theme, so they should follow it); `AlertDialog`s in the library and browser pages; the safe flavor on a device.

## System status on the car (CPU / RAM / temperature), 2026-10-25

- `display/SystemStats` reads what an app may: **RAM** is the system's used share (`ActivityManager.MemoryInfo`); **CPU** is this app's own load (CPU time over wall time, share of all cores), because `/proc/stat` and the load average are blocked for apps on this phone (Android 16, tested with run-as; thermal zones are blocked too); **temperature** is the battery's, with the system thermal status deciding warm/hot (battery 40/45 °C without it). `/proc/meminfo` and `cpufreq/scaling_cur_freq` are readable but not used.
- Car home header: right-aligned "CPU 0% · RAM 66% · 36°C" (`HomeDashboardRenderer.drawStatus`); CPU then RAM are dropped when the line would meet the wordmark; the temperature turns warm/hot colour. Redraws every 2 s while the home is on screen; readings are cached 1.5 s. Toggle: Settings > Appearance > "System status on Android Auto" (`AppearanceSpec.carSystemStatus`, default on; the car keeps its default when "use this look on Android Auto" is off).
- Car diagnostics screen: CPU, Memory (with MB) and Temperature (battery + a word for the heat) rows.
- Tests: `SystemStatsTest` (CPU share, RAM share, heat thresholds), codec round trip with the new field. All unit tests and compile pass.
- Checked on the phone + DHU 800x480: the line shows in the header; the 36 °C matches `dumpsys battery` (361 tenths).
- Not verified: that the figures visibly change between redraws (they stayed the same across screenshots taken minutes apart, which an idle app and a steady battery would also give); the diagnostics rows on the DHU; the line on the 1280x720 profile and at large text; the CPU figure under real load (mirroring).
