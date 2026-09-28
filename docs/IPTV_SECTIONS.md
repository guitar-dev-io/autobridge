# Home sections and IPTV

Updated: 2026-09-28

The home grid on the phone and on Android Auto now follows the
[Fermata-Xtream](https://github.com/malebuffy/Fermata-Xtream) launcher layout: TV, Radio, Web
browser, Youtube, YouTube Music, YouTube Kids, Folders, Favorites, Playlists, Gallery, followed by
the AutoBridge-specific Mirror, Apps, Remote and Settings tiles.

This is an independent implementation. No Fermata or Fermata-Xtream source, asset or resource was
copied into this tree; the reference informed the section set, the ordering and the Xtream account
flow only. Upstream Fermata is GPL-3.0 and its terms remain independent of this repository.

## One definition, two surfaces

`dev.autobridge.library.HomeSection` is the single list of sections. The phone launcher
(`MainActivity.buildHomeScreen`) renders it as cards and the head unit
(`CarHomeDashboardScreen`) renders the same list as grid tiles. `HomeSection.carSections` drops
`REMOTE`, which exists to drive the car screen from the phone and would be circular on the car.

Head units cap grid items, so the car dashboard asks `ConstraintManager` for
`CONTENT_LIMIT_TYPE_GRID` and pages the sections, spending one slot per page on `More`. Long lists
(an Xtream account can return tens of thousands of entries) page the same way through
`CarListPaging` using `CONTENT_LIMIT_TYPE_LIST`.

## IPTV source layer

| Type | Fetched from | Notes |
|---|---|---|
| `XTREAM` | `player_api.php` | Live channels, movies, series; falls back to `get.php` m3u_plus when the API is missing. |
| `M3U` | Playlist URL | `#EXTM3U` / `#EXTINF` with `group-title`, `tvg-logo`, `tvg-name`, plus `#EXTGRP`. |

- `XtreamCredentials` accepts a bare portal, a full `player_api.php` link, or a `get.php` playlist
  link, and builds the live/VOD/series/catch-up URLs. It is pure string work and unit-tested.
- `M3uParser` is likewise pure and unit-tested, including the "comma inside a quoted attribute"
  case that otherwise truncates channel names.
- `IptvSourceStore` persists sources in app-private `SharedPreferences`, split by `TV`/`RADIO`.
- `XtreamClient` does the blocking HTTP and JSON work; `IptvCatalog` is the process-wide cache in
  front of it, with a 30-minute freshness window, request coalescing and main-thread callbacks.
  Both surfaces share that cache, so a portal loaded on the phone opens on the car with no second
  round trip.
- `IptvHistoryStore` keeps the recently-played list and the channel favourites, storing the direct
  stream URL so a replay never reloads the catalog.

Radio sources keep live entries only, and an M3U radio playlist is narrowed by group/name wording
or an audio file extension. That is a heuristic: a playlist the guess is too narrow for can simply
be added as a TV source as well.

### Cleartext

IPTV portals are overwhelmingly plain `http`, including the stream URLs themselves. The app
therefore ships `res/xml/network_security_config.xml` with `cleartextTrafficPermitted="true"`
rather than hiding the same effect behind `usesCleartextTraffic`. Certificate validation for https
hosts is untouched.

### Account entry

Car App templates cannot host a text field, so accounts are added and edited on the phone
(`LibraryActivity`). The car screens are read-only browsers over the same store and offer an
"Open on phone" action when a section has nothing to show yet.

## Local library

`LocalMediaRepository` backs Folders, Playlists and Gallery through MediaStore, read-only and
returning `content://` URIs so playback uses the same `MediaSourceResolver` path as remote media.

- Folders are MediaStore buckets, not a directory tree: scoped storage does not allow walking
  arbitrary directories.
- `MediaStore.Audio.Playlists` is deprecated from Android 11 and returns nothing on many devices,
  so Playlists always offers a synthetic "All music" entry and an empty playlist list is normal.
- Photos have no car playback path; the car says so instead of pushing an empty video surface.
- Permissions are requested from `LibraryActivity`. The car screens cannot request them, so they
  show a message that hands the user to the phone.

## Playback routing

| Content | Phone | Car |
|---|---|---|
| TV / video | `PlayerActivity` | `CarVideoScreen` on the car surface |
| Radio / audio | `PlayerActivity`, audio kind | Shared MediaSession + `CarNowPlayingScreen` |
| Photo | System image viewer | Not available; the car says so |

The phone's visual treatment for all of this is documented in [`PHONE_UI.md`](PHONE_UI.md).

Video and browser sections stay behind the existing `FeaturePolicy`/parked-state gate; nothing
here bypasses it. `EntertainmentActivity`'s new extras accept http, file and content URIs, which
the https-only `intent.data` path did not — required because portals and on-device media are
routinely not https.

## Not implemented

Catch-up is modelled (`IptvEntry.supportsCatchup`, `XtreamCredentials.catchupUrl`) and shown as a
row hint, but there is no timeshift picker yet. EPG, SponsorBlock, subtitle translation and the
YouTube quality selection listed by the reference mod are not implemented.

## Validation

- `testSafeDebugUnitTest` / `testPersonalDebugUnitTest` / `testLabDebugUnitTest`: 13 new
  `IptvParsingTest` cases pass. The 5 pre-existing `AppProfileRegressionTest`/`CoreRegressionTest`
  failures documented in [`IN_APP_MEDIA_PROTOTYPE.md`](IN_APP_MEDIA_PROTOTYPE.md) are unchanged.
- `assembleSafeDebug` / `assemblePersonalDebug` / `assembleLabDebug` all succeed.
- Not yet verified on hardware: a real Xtream portal round trip, large-catalog paging on a head
  unit, radio playback through the car MediaSession, and MediaStore behaviour across OEM devices.
