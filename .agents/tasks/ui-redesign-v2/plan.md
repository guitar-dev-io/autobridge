# UI redesign v2: implementation plan

Request (Thai): implement the approved redesign from the "AutoBridge UI Redesign" design canvas
(https://claude.ai/artifact/4UMy88Fun3n2qMdHpKVygb), split into pieces.

Design reference: the canvas's interactive prototype (phone + Android Auto) and its design-system
board (color roles, accents, type scale, font scale table, component states).

## Why a foundation first

Every colour the phone draws is a compile-time `const val` (`DesignTokens` -> `AutoBridgeDesign`,
35 files), text sizes are hard-coded `textSize = Nf`, and no appearance setting exists. Nothing in
the redesign can change at runtime until there is:

1. a model of the user's choices (`AppearanceSpec`) and a pure resolver from it to a palette,
2. a store that persists it,
3. the phone tokens reading the resolved palette instead of constants,
4. the font scale applied to every activity context,
5. a Settings > Appearance route the pieces can grow.

## Step 0: foundation (this session, branch `claude/ui-redesign-foundation`)

- `common/.../design/Appearance.kt`: enums (mode, preset, accent, font, text step, home layout,
  wallpaper, icon style, card style, corners) and `AppearanceSpec` with defaults.
- `common/.../design/ThemeResolver.kt`: `resolve(spec, systemDark, minuteOfDay) -> ThemePalette`,
  `radii`, `TextScaleCurve` (non-linear, Android 14 shape) and the car cap (130%).
- `common/.../design/AppearanceCodec.kt`: spec <-> string map (prefs), unknown values fall back.
- `common/.../design/ThemeHolder.kt`: the process-wide current palette (default = AutoBridge dark).
- `app/.../settings/AppearanceStore.kt`: SharedPreferences `autobridge_appearance`, `StateFlow`.
- `app/.../ui/AppearanceContext.kt`: applies the manual text step to a context's fontScale.
- `AutoBridgeDesign` / `ComposeTokens` / `AutoBridgePhoneTheme`: tokens become getters on
  `ThemeHolder.palette`; light mode gets a light Material scheme.
- `PhoneNav.Route.APPEARANCE` (parent SETTINGS), a Settings > General row, and a first
  `ui/AppearanceScreen.kt` (mode, accent, text size) so the whole chain runs end to end.
- JVM tests for resolver, codec, curve, nav.

## Step 1: parallel pieces (each its own session and branch, from the foundation tip)

| Piece | Owns (files) | Done when |
|---|---|---|
| A. Appearance settings UI | `ui/AppearanceScreen.kt` (+ new `ui/appearance/*`), `res/values*/strings_appearance.xml` | All prototype sub-pages: Theme & colors (presets, 5 modes, auto schedule, accents), Text (fonts list, 6 steps, follow Android, resulting sizes), Wallpaper (6 options incl. custom photo picker, dim, blur, target), Icons & cards (icon style, card style, corners, labels), live preview, reset dialog. Writes only through `AppearanceStore`. |
| B. Phone Home layouts | `ui/HomeDashboard.kt`, `ui/PhoneHomeLayout.kt`, `ui/PhoneLauncherUi.kt`, `ui/MiniPlayer.kt`, new `ui/home/*`, `res/values*/strings_home_v2.xml` | Home renders Classic grid / Dashboard / Compact from `AppearanceStore`, wallpaper + dim behind it, icon style + card style + corners, 2-column grid at >=130% text. |
| C. Car home dashboard | `car/HomeDashboard*.kt`, `car/DashboardArtwork.kt`, `car/HomeMenuCard.kt` | Car home reads the palette when "same look on Android Auto" is on (default theme when off), three layouts, card/icon style, car text capped at 130%. DHU check listed in verification.md. |

Rules for every piece: do not touch `MainActivity.kt`, `PhoneNav.kt`, `common/.../design/*`,
`settings/AppearanceStore.kt` (ask the foundation owner instead), and never the safety/bypass
files. New strings go in the piece's own strings file, en + th.

## Step 2: later (after A-C merge)

- D. Font families: bundle or downloadable Inter, Manrope, IBM Plex Sans (+Thai), Noto Sans
  (+Thai); one `AutoBridgeDesign.font(weight)` and replace `Typeface.create` call sites.
- E. Screen restyles: Radio, Browser, Music, TV, Mirror, My Car, Dual Display, to the component
  states on the design-system board.
- F. Thai fonts (looped/loopless) and Thai line heights.
