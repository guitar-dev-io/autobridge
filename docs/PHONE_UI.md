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
`contentRow`, `contentTile`, `grid`, `pill`, `searchField`, `emptyState`, `page` and `body`.

`contentRow` and `contentTile` are the same content at two densities. A tile is for things that
carry artwork worth seeing — channels, stations, films — because the logo is what a user recognises
before they have read anything; a row is for everything whose identity is its text. `grid` lays
tiles out two to a line as ordinary stacked views, so a grid page needs no new container and keeps
the one scroll view; a short last row holds its cell width instead of stretching across the page.
A tile's logo is fitted, never cropped: a channel wordmark cut in half reads as a broken image.

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

Channel logos come from the playlist itself, including the lists that spell the attribute
differently or write the address relative to themselves; recently-played rows and favourites keep
the logo they were played with. See
[`IPTV_SECTIONS.md`](IPTV_SECTIONS.md#channel-logos).

### Checking a channel

A public playlist is a list of addresses, not of working channels, so a category page checks itself
when it opens — silently, with no button pressed — and each tile shows what it got: `88 ms` in
green, a slow answer in amber, `HTTP 404` or `No answer` in red. "Check again" forgets the
remembered answers and sweeps a wider set; a channel's long-press menu checks one address and
explains it in a sentence instead.

Results are written into the tiles that are already on screen rather than re-rendering the page:
these pages rebuild from scratch, so a re-render while a check runs would scroll the user back to
the top every time a batch of answers landed. See
[`IPTV_SECTIONS.md`](IPTV_SECTIONS.md#checking-a-channel).

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

### The browser menu is one sheet on both surfaces

The menu was a grid of undifferentiated tiles — an `AlertDialog` on the phone, a Canvas grid on the
car. Nine common actions and nine rare ones were drawn identically, the page's own address was
nowhere on it, and the desktop-site setting was stated as a label (`Desktop: เปิด`) the user had to
read and decode rather than a switch whose position they could see.

Both surfaces now draw the same sheet, top to bottom: a header naming the app and the page with a
`✕ Close` beside it, the address row, a card of six page actions (Back / Reload / Forward,
Bookmarks / External / Settings), a card of three place actions, the desktop-site switch, and a
footer carrying the version and the two routes off the sheet — `More` and `Exit`.

`BrowserDrawerModel` owns the *content*: the same item lists build both, so an entry added for one
surface exists on the other and the two cannot drift into being different menus. It does not own
the phone's *geometry* — `BrowserMenuSheet` builds Views and lets the layout engine measure them,
while the model's pixel boxes stay the car's, where nothing measures anything. Where the surfaces
genuinely differ they say so: `MenuSurface.PHONE` has no tabs, so its second card holds History and
Downloads, and its secondary list carries the send-to-car / get-from-car pair instead of the car's
media and agent screens. A tile is never drawn over an action its surface cannot perform.

Each band of the sheet declares a preferred and a minimum height and they shrink together, so the
whole layout fits without scrolling on every head unit down to a 480px-tall panel. The 800x320
stable area a DHU session reports is the one exception: there is no arrangement of a header, an
address row, two cards, a switch and a footer that leaves a touchable tile in 260px, so that panel
scrolls — with the header and its close button pinned above the scroll, so the sheet is never
opened into a state it cannot be closed from.

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

## The system Back button

The screens here extend the framework `Activity` and used to handle Back by overriding
`onBackPressed()`. That override is dead on this app's own target: predictive back is enabled by
default for apps targeting API 35+, and on such a device **a back gesture or button never calls
it** — the system finishes the Activity instead. The symptom was identical everywhere and easy to
read as something else: on an Android 16 phone one Back jumped out of the library's page stack from
three levels deep, the browser stopped walking its own history, and leaving fullscreen closed the
player.

`dev.autobridge.ui.SystemBack` registers one `OnBackInvokedCallback` with the platform dispatcher
(androidx's `OnBackPressedDispatcher` needs `ComponentActivity`, which these are not), and each
screen keeps its deprecated override for devices older than API 33. Exactly one of the two routes
runs on any device — the object documents which, and why both stay. A registered callback replaces
the default entirely, so each handler finishes its own Activity through `SystemBack.finishFromBack`
once it has nothing left of its own to do.

| Screen | What its Back does first |
|---|---|
| `LibraryActivity` | Pops one page off the in-memory page stack |
| `BrowserActivity` | Leaves a fullscreen video, then browser fullscreen, then goes back in web history |
| `PlayerActivity` | Leaves fullscreen |
| `EntertainmentActivity` | Leaves a fullscreen video |

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
