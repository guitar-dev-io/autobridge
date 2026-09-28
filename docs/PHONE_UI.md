# Phone UI

Updated: 2026-09-28

AutoBridge's phone side has its own design language rather than copying a reference launcher. The
section set was informed by [Fermata-Xtream](https://github.com/malebuffy/Fermata-Xtream) (see
[`IPTV_SECTIONS.md`](IPTV_SECTIONS.md)); the visual treatment is AutoBridge's own.

## The design language

`dev.autobridge.ui.AutoBridgeDesign` holds the tokens and the component builders. Everything is
drawn programmatically, matching the rest of this codebase.

| Token | Value | Used for |
|---|---|---|
| `INK` | `#0B0E14` | Page background, status/navigation bar |
| `SURFACE` | `#141924` | Cards, rows, search field |
| `SURFACE_RAISED` | `#1C2331` | Bottom navigation, now-playing bar |
| `HAIRLINE` | `#26304A` | 1px borders instead of bright outlines |
| `TEXT` / `TEXT_MUTED` | `#EAF0FA` / `#8494B0` | Titles / captions |
| `ACCENT` / `ACCENT_SOFT` | `#4C7DF0` / `#33C9D6` | Primary actions, status pill |

What makes it recognisably ours rather than a generic dark theme:

- **Accent per section.** TV is blue, Radio amber, Folders/Playlists green, Favorites pink,
  Gallery teal, system sections violet. The colour starts on the home card's badge and carries into
  that section's header, rows, buttons and player, so the user can tell where they are without
  reading. `HomeSection` owns the mapping.
- **Tinted rounded-square badges.** A card's icon sits in a 44dp badge filled with its accent at
  16% over ink, not a bare glyph on black.
- **Left-aligned cards with a caption.** "TV / Live channels & VOD" rather than a centred label,
  which is what separates the grid from the reference launcher's symmetric tiles.
- **Hairlines, not outlines.** Surfaces are separated by a 1px `HAIRLINE` border, and everything
  tappable has a ripple in its own accent.

The legacy phone screens (Apps, Profiles, Devices, Developer, Control Center) were not rewritten,
but `MainActivity`'s colour constants now resolve to these tokens, so they share the same surface
stack and read as one app.

## Components

`AutoBridgeDesign` provides `header`, `statusChip`, `glyphButton`, `sectionLabel`, `sectionCard`,
`contentRow`, `pill`, `searchField`, `emptyState`, `page` and `body`.

`page()` owns the window insets. targetSdk 36 means edge-to-edge on Android 15+, so the page pads
itself for the system bars and folds in the IME inset; `applyInsets = false` is passed when the
hosting Activity already does it (`MainActivity`).

## Screens

| Screen | What it is |
|---|---|
| Home (`MainActivity` + `PhoneLauncherUi`) | The section grid, a status pill, an overflow menu, a voice-search button, and the now-playing bar. |
| Sections (`LibraryActivity`) | TV, Radio, Folders, Playlists, Gallery, Favorites. One Activity with a page stack; every page is header + optional search/action pills + rows + now-playing bar. |
| Player (`PlayerActivity`) | Video surface or artwork panel, scrubber with elapsed/remaining, ±10s, previous/next and play/pause. |

### Search

Entry lists with at least 12 items get a search field that filters as you type. At most 300 rows
are drawn at once: these pages are plain view stacks, not recycling lists.

### Artwork

`dev.autobridge.ui.ImageLoader` loads channel logos and cover art with no new dependency: an
`LruCache` in front of a bounded disk cache under `cacheDir`, a three-thread pool, two-pass
`inSampleSize` decoding to roughly 256px, and per-view tagging so a slow logo never lands on a
recycled row. A row shows an accent-tinted initial until a bitmap actually arrives, so a dead logo
URL is a letter rather than a hole. Failed URLs are remembered and not retried.

### Now playing

`MiniPlayer` reads the live MediaSession through `MediaPlaybackClient` rather than holding state,
so it shows whatever is playing — including audio the car started — and hides itself when the
session is empty. It polls on a one-second tick instead of threading a listener through every
screen.

### Playback

`PlayerActivity` drives the same shared MediaSession as the car, so pausing on the phone pauses on
the head unit. It enforces the same `FeaturePolicy` gate: video is parked-only, and when policy
turns video off mid-playback the surface is released and the screen says why. Live streams report
no duration, so the transport switches to a LIVE badge and disables seeking. Leaving the screen
pauses video but lets audio continue, matching the car.

`EntertainmentActivity` keeps its existing role (browser/YouTube entry and the developer-facing
address field) and gained `EXTRA_SOURCE_URL`/`_KIND`/`_TITLE` for direct playback requests.

## Remote and browser

These two screens predate the design language and were the worst offenders, so both were brought
onto it.

### Remote

`MobileRemoteScreen` is the Compose slice. It drew **its own bottom navigation**, which stacked
directly on top of `MainActivity`'s global bottom navigation: two bars, both starting with
"Home / Remote", meaning different things, eating about 130px between them and clipping the last
card. Its four entries are sub-tabs of the Remote feature, not app destinations, so they are now a
segmented control under the header and the global bar is the only bottom bar.

Also fixed there: the private teal-navy palette now resolves to `ComposeTokens` (which mirrors
`AutoBridgeDesign`, so `AutoBridgePhoneTheme` and the view-based screens agree); the quick-command
icons were half colour emoji and half flat glyphs — colour emoji ignore the accent tint, so the set
is now monochrome and tinted, with stored legacy emoji normalised on load; and the screen mixed
Thai and English labels, which is now English throughout like the rest of the app.

### Browser

`BrowserActivity` applied the status-bar inset to its toolbar but left the page's top inset at 0,
so a site's own sticky header rendered into the status bar — the clock sat on top of the page
title. The page now starts below both the status bar and the toolbar that overlays it, and
`setFullscreen` re-requests insets because the toolbar's presence changes that offset.

The address pill showed the raw URL. Five toolbar buttons leave it about a third of the width, so a
search result read as `https://www.goo` — truncated at the one part of the address that identifies
nothing. Unfocused it now shows the host (`google.com`); focusing restores the real URL to edit.

The floating `☰` was visible exactly when the toolbar (which already has a `☰`) was showing, and
hidden in fullscreen — the one state with no other way to reach the menu. That is inverted.

### Apps and Settings

`screenHeader` — shared by Applications, Profiles, Devices, Debug and Mirror settings — drew a
small-caps eyebrow above the title next to a bare `←`. It now delegates to
`AutoBridgeDesign.header`, so all five legacy screens get the same circular back button, title and
caption as the rest of the app.

On Applications, the Quick Apps / All apps chips differed only by a slightly lighter surface, which
is not a legible selected state; the selected chip is now a filled accent pill, matching the
Remote's segmented control.

On Settings, every row rendered its first letter in the badge — "M / T / A / C / D" reads as
placeholder text, not iconography — so `contentRow` gained an optional `badgeIcon` and the entries
pass real icons.

Header actions also became explicit about primacy. They used to render the *last* action filled,
so a screen whose only action was the overflow menu showed it as a loud accent disc. An action now
opts into `filled`, and the overflow menu never does.

## Permissions

Rendering never requests a permission. `requestPermissions` can deliver its result synchronously —
for a permission the user has permanently denied, or under an OEM policy — and a render-time
request re-enters the same page until the stack overflows. That was observed on a Xiaomi 13T Pro
(Android 16) as a `StackOverflowError` through
`onRequestPermissionsResult -> showFolders -> requestMediaPermission`. Pages now check, and render
a "needs media access" state whose button does the asking; a second attempt sends the user to the
app's settings page.

## Device validation, 2026-09-28

On a Xiaomi 13T Pro (`23078PND5G`, Android 16, MIUI):

- Home grid, section headers, accent badges, status pill and captions render as designed at
  1220x2712; the app draws at about 52 fps while scrolling.
- The TV section's empty state and its "+ Xtream"/"+ M3U" pills render and are tappable.
- The Folders permission state renders with the Folders accent and does not request on render;
  the crash buffer is clean after the fix.
- Remote: one bottom bar, segmented sub-tabs, tinted monochrome quick-command icons.
- Browser: status bar clear of page content, address pill reading `google.com`, no duplicate menu.
- Applications: design header, filled selected chip. Settings: real row icons, quiet overflow menu.
- Not verified: a live Xtream portal, playback in `PlayerActivity`, artwork loading against real
  logo URLs, the now-playing bar with an active session, the Gallery/Playlists lists with media
  permission granted, and the browser's fullscreen state (toolbar hidden, floating menu shown).
