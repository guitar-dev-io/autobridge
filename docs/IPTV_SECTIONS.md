# Home sections and IPTV

Updated: 2026-10-04

The home grid on the phone and on Android Auto now follows the
[Fermata-Xtream](https://github.com/malebuffy/Fermata-Xtream) launcher layout: TV, Radio, Web
browser, Youtube, YouTube Music, , Folders, Favorites, Playlists, Gallery, followed by
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
| `M3U` | Playlist URL | `#EXTM3U` / `#EXTINF` with `group-title`, a logo attribute, `tvg-name`, plus `#EXTGRP` and `#EXTIMG`. |

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
  stream URL so a replay never reloads the catalog, plus how that URL has to be opened.
- `IptvPlaylistConventions` reads the things community playlists do that the `#EXTM3U` format says
  nothing about; see [Public playlist conventions](#public-playlist-conventions).
- `IptvLogos` decides where a channel logo comes from and what makes its address usable; see
  [Channel logos](#channel-logos).
- `StreamPing` answers whether a channel address responds, and how fast; see
  [Checking a channel](#checking-a-channel).
- `IptvDirectory` is the built-in list of public playlist addresses offered in the picker.

Radio sources keep live entries only. An Xtream portal serves TV and radio from one playlist, so
that playlist is narrowed by group/name wording or an audio file extension. A plain M3U the user
filed under Radio is taken at its word and kept whole: run against a real station list, the
heuristic dropped "88 nice peak", "90.5 Delight" and "97qfm", which is most of it.

### Public lists

`IptvDirectory` is the built-in list of free, public playlists. The ones marked **default** are
created on first read, so TV and Radio open with channels to browse before anything is configured;
the rest are one tap away in the "Public lists" picker instead of a URL typed on a phone.

| Section | List | Default | Address |
|---|---|---|---|
| TV | Free-TV | yes | `https://raw.githubusercontent.com/Free-TV/IPTV/master/playlist.m3u8` |
| TV | Thai (dearbulut) | yes | `https://dearbulut.github.io/iptv/playlists/language/tha.m3u` |
| TV | iptv-org · All countries | no | `https://iptv-org.github.io/iptv/index.m3u` |
| Radio | radio-browser · Thailand | yes | `https://de1.api.radio-browser.info/m3u/stations/bycountry/thailand` |
| Radio | radio-browser · Top voted | no | `https://de1.api.radio-browser.info/m3u/stations/topvote/100` |

A seeded default is an ordinary source: it can be renamed, edited and removed like any other, and
removing it is permanent. `IptvSourceStore` records the default URLs it has already created rather
than a single "seeding has run" flag, so a deleted default is never recreated on the next read,
while a default introduced by a later version still arrives. The phone confirms the removal once
and says where to get the list back — the picker keeps offering it.

Seeding creates the source only; nothing is fetched until the user opens it, so a fresh install
still makes no network request of its own.

These are addresses only. AutoBridge does not host, bundle, mirror or redistribute any playlist or
stream: each one is fetched live from the project that publishes it, under that project's own terms,
exactly as if the address had been pasted into the M3U form. Picking one saves an ordinary `M3U`
source that is editable and removable like any other, and nothing is added without the user tapping
it. [Free-TV](https://github.com/Free-TV/IPTV) accepts only channels its own rules call free to air;
whether a given channel is licensed for a given viewer remains between that viewer and the
broadcaster.

The directory is deliberately short. Free-TV publishes ~90 per-country files as well, but the full
playlist already carries the country split as `group-title` groups, and enumerating file names that
upstream may rename would rot.

### Public playlist conventions

`IptvPlaylistConventions` handles two things the M3U format does not describe, and it is pure
string work, so both are unit-tested:

- **Not every line under an `#EXTINF` is a stream.** Curated lists point a channel at a YouTube,
  Twitch or Dailymotion *page* when that is where the broadcaster publishes its live feed. In the
  Free-TV playlist 140 of 2,080 entries are such pages. Handed to ExoPlayer they produce a parse
  error the user cannot act on, so they are marked `IptvPlayback.WEB_PAGE`, the row says "Opens in
  browser", and the tap goes to the browser surface — `EntertainmentActivity` on the phone,
  `CarBrowserScreen` on the head unit — which applies its own parked/browser policy gate.
  Classification is by host, never by extension: provider endpoints hide behind `.php` and `.htm`
  URLs often enough that guessing from the path would break working channels.
- **Free-TV encodes per-channel notes as circled letters glued onto the display name** — `Ⓢ`
  standard definition, `Ⓖ` geo-blocked, `Ⓨ` YouTube, `Ⓣ` Twitch, `Ⓓ` Dailymotion. Left in place
  they are noise in a title read at a glance on a head unit; they are stripped from the title and
  read back as subtitle hints instead (457 of the 2,080 entries carry at least one).

A long list is also honest about what it is showing: the phone caps a category at 300 rows, so when
there are more the subtitle says "showing first 300, search to narrow" rather than printing the full
count above a truncated list.

### Channel logos

A logo is the fastest thing to recognise on a head unit, so the one the playlist already ships is
what the rows show — on both surfaces, and with no image dependency added (`ImageLoader`, see
[`PHONE_UI.md`](PHONE_UI.md#artwork)).

`IptvLogos` is the small amount of knowledge that makes those addresses work. It is pure string
work and unit-tested (`IptvLogoTest`):

- **The attribute is spelled several ways.** `tvg-logo` is the convention, but panel generators
  emit `logo`, `url-logo`, `tvg-logo-small` and `tvg-icon`, and some lists put the address on its
  own `#EXTIMG:` line. The first one present wins, canonical name first.
- **The address comes in three shapes.** Absolute is used as-is; protocol-relative (`//cdn/x.png`)
  takes https; relative (`/logos/x.png`, `logos/x.png`) is resolved against the playlist or portal
  URL, which is the only thing that can resolve it. A `data:`, `file:` or `ftp:` logo is dropped
  rather than guessed at, because the loader behind the rows only speaks http(s), and a `null`,
  `n/a` or `-` value is a generator saying "no logo".
- Xtream's `stream_icon`/`cover` fields go through the same resolution against the portal URL.

`IptvHistoryStore` stores the logo with the entry, so a recently-played row and a favourite look
like the row they were played from. An item written by an older version has none and falls back to
its accent initial, as every row without a logo does.

On the head unit the constraint is different, and `CarChannelLogos` owns it. A list row accepts
only `TYPE_BITMAP`/`TYPE_RESOURCE` (`TYPE_URI` is allowed solely in the templates that document
it), so the host never fetches a logo itself: every pixel travels inside the template, and a
binder transaction is capped at about 1 MB. So a logo is scaled to 72px on its long edge
(`IMAGE_TYPE_SMALL` targets an 88 × 88 dp box and the host scales anyway), and at most 36 rows of
one template carry an image — which puts the logos on the top of a page, the rows a driver is
looking at. Logos that arrive from the network repaint the screen once per batch rather than once
each, because a host rejects templates pushed in a tight loop.

### Checking a channel

A public playlist is a list of addresses, not a list of working channels: some share of any list is
retired, geo-blocked, or behind a token that expired. Finding that out from a player that spins is
bad on a phone and worse while driving, so a channel can be checked first. `StreamPing` does it,
and the row then reads `142 ms`, `HTTP 403` or `No answer`.

It is deliberately not ICMP — an app cannot open a raw socket without root, and an echo reply from
a CDN edge says nothing about the stream behind it:

| Address | Probe |
|---|---|
| `http(s)` | One `GET` with `Range: bytes=0-1`; the status is read and the body never touched. This is the first thing the player would do, so a 403 on an expired token or a 404 on a retired channel is seen for what it is. |
| `rtmp`, `rtsp` | A TCP connect to the host and port, which is all a non-HTTP endpoint tells us cheaply. |
| `udp`, `rtp`, anything else | Reported as not checkable. Multicast answers no connection, and calling that "dead" would be a lie about a channel that may well play. |

**A channel list checks itself when it opens.** Asking the user to press a button to find out
whether a list works is asking them to do the app's job, and on a head unit it is asking a driver
to press a button. So the automatic pass runs on arrival and says nothing at all — no toast, no
spinner — and its entire output is the colour on the tiles and rows.

- The automatic pass stops at 24 addresses, about two screenfuls; an explicit check covers 60 on
  the phone and the whole visible page on the car. Probing a 2,000-channel list on the way past
  would be a port scan of a dozen CDNs for answers nobody asked for.
- The explicit action is "Check again" (a pill on the phone, the signal-bars header action on the
  car), and it *forgets* the remembered answers first. Inside the five-minute window it would
  otherwise hand back exactly what is already on screen and look like a button that does nothing.
- **A run always ends, and nothing gates the next one.** The first version held a process-wide
  "a run is in flight" flag and released it only when every probe had reported. That made one
  stalled socket fatal: the pool is four threads, a host that resolves slowly or accepts and then
  says nothing holds one for far longer than its timeouts suggest, and the run that never finished
  left the flag held, so every later check in the process was refused. On a device that showed as
  a page with no results whose "Check again" did nothing, until the app was restarted. Now a run
  closes on a 30-second deadline whatever its stragglers are doing, addresses that did not answer
  in time are recorded as "Timed out" rather than left blank, a straggler that lands later
  overwrites its own entry with the truth, and concurrent runs simply skip an address another run
  is already probing. Redirects are no longer followed either — a 3xx already counts as alive, and
  a chain of token hosts was the one way a single probe could outlast every timeout set here.
- Results are cached process-wide for 5 minutes, so re-rendering a page — or crossing from the
  phone to the head unit — re-probes nothing. Both surfaces read that cache per row.
- Four probes run at a time with 5-second timeouts, and progress is coalesced to one callback per
  800 ms: 90 channels would otherwise repaint 90 times, and a head unit rejects templates pushed
  that fast.
- The phone writes a landing result **into the tile that is already on screen** rather than
  re-rendering the page. Rebuilding it would scroll back to the top every time a batch arrives,
  which is an unusable page while a check runs. The tile's status line carries
  `R.id.autobridge_status_label` for exactly that.
- A channel's long-press menu on the phone still checks one address and explains the answer in a
  sentence rather than a row.

**Colour is the output.** `StreamPing.tone` maps a result onto three readings and each surface
supplies its own colours — `SIGNAL_GOOD` / `SIGNAL_SLOW` / `DANGER` on the phone,
`CarColor.GREEN` / `YELLOW` / `RED` through a `ForegroundCarColorSpan` on the car, which is the
only way an app colours anything inside a template it does not draw itself.

| Reading | When | Why not red |
|---|---|---|
| Good | answered in 1 s or less | — |
| Slow | answered, but took longer than a second | It works; it will just take its time starting. |
| Slow | the address cannot be probed at all (multicast) | Neither an answer nor a refusal, so calling it dead would be a lie. |
| Bad | refused (`HTTP 403`, `HTTP 404`) or silent | — |

- Pure parts (`endpoint`, `classify`, `describe`, `tone`) are unit-tested in `StreamPingTest`; the
  probe itself needs a server and belongs to the hardware pass.

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
| Channel that is a watch page | `EntertainmentActivity`, web kind | `CarBrowserScreen` |
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

- `testSafeDebugUnitTest` / `testPersonalDebugUnitTest` / `testLabDebugUnitTest`: 25
  `IptvParsingTest` cases pass, 10 of them over the public lists, the Free-TV conventions and the
  default-seeding rule, plus 13 `IptvLogoTest` and 16 `StreamPingTest` cases over the logo shapes,
  the ping classification and its three readings. The 5
  pre-existing `AppProfileRegressionTest`/`CoreRegressionTest` failures documented in
  [`IN_APP_MEDIA_PROTOTYPE.md`](IN_APP_MEDIA_PROTOTYPE.md) are unchanged.
- `assembleSafeDebug` / `assemblePersonalDebug` / `assembleLabDebug` all succeed.
- Verified on hardware (Xiaomi 13T Pro + DHU 2.0, see [`DHU_SCENARIOS.md`](DHU_SCENARIOS.md)): an
  M3U catalog of 78 channels / 13 groups and one of 134 radio stations, browsed and played on both
  the phone and the head unit, with favourites crossing between them. Public test playlists:
  `https://iptv-org.github.io/iptv/countries/th.m3u` and
  `https://de1.api.radio-browser.info/m3u/stations/bycountry/thailand`.
- The live Free-TV playlist was run through `M3uParser` + `IptvPlaylistConventions` on the JVM:
  2,080 channels, 97 groups, 140 watch pages routed to the browser, 457 rows given marker hints, no
  title emptied and no marker left behind. That is a parse-level check only.
- Verified on hardware (Xiaomi 13T Pro, 2026-10-04): the phone channel grid over the iptv-org
  Thailand list — logos drawn from the playlist, the automatic check running on arrival with no
  tap, `88 ms` in green and `HTTP 404` in red on the tiles of a list that is largely retired, in
  both portrait and landscape.
- Not yet verified on hardware: channel logos and the ping check on a head unit (the car row
  budget above is reasoned from the binder limit and the car-app constraints, not measured on a
  car), and a real Xtream portal round trip (needs a paid account), catch-up,
  MediaStore behaviour across OEM devices, and how many of Free-TV's streams actually play from
  Thailand — a `Ⓖ` channel is geo-blocked by the broadcaster, which no client-side change fixes.
