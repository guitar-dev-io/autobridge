# AutoBridge iOS

A native iOS companion app for AutoBridge, written in SwiftUI. It is a **separate project** from the
Android `:app` module and shares no build system with it; what it shares is behaviour — every piece
of logic here is a deliberate port of its Android counterpart, so the two apps read the same
playlists the same way and show the same answers.

## What this is (and is not)

iOS and Apple's platform rules block the Android app's core features, so this app does **not**
attempt them, and does not hide the gap either — Settings says so in the app:

- No full-device screen mirroring (ReplayKit only captures the app's own screen).
- No touch injection into other apps (the App Sandbox has no Shizuku or accessibility-input
  equivalent).
- No arbitrary video surface on the car display. CarPlay is template-based and has no mirroring
  category.
- No Smart Mode, per-app profiles, LAB mode or parked gate: all of those exist on Android only
  because mirroring does. There is no `CAR_SPEED` signal to gate on here.

What it **does** carry is the entertainment surface, which is pure logic plus standard iOS media
APIs — and it now carries essentially all of it.

## Feature parity with the Android app

| Android | iOS | Notes |
|---|---|---|
| Home dashboard from one shared `HomeSection` list | ✅ | Same order, same captions, same accent colours |
| TV / Radio sections, Xtream + M3U sources | ✅ | |
| Add / edit / delete sources, public-list picker | ✅ | Same validation and the same "a deleted default stays deleted" rule |
| Xtream live + **VOD + series**, episodes on demand, catch-up flag | ✅ | |
| Shared catalog cache with a freshness window and in-flight de-duplication | ✅ | `IptvCatalog` |
| Channel logos, including the attribute spellings and relative paths real playlists use | ✅ | `IptvLogos` + a dependency-free cached image loader |
| Automatic channel check (`88 ms` / `HTTP 404` / `No answer`) with tones and an explicit recheck | ✅ | `StreamPing`, same limits: 24 on open, 60 per explicit sweep |
| Two-column entry grid, 300-tile cap, search above 12 entries | ✅ | |
| Favourites, recently played, saved browser pages | ✅ | Keyed by URL, like the Android store |
| Credentials in encrypted storage, excluded from transfer to another device | ✅ | Keychain, `ThisDeviceOnly` — the iOS half of `SecretPrefs` |
| Now-playing bar, lock-screen controls, background audio | ✅ | One shared `PlaybackController` + `MPNowPlayingInfoCenter` |
| Player with a scrubber and a LIVE state | ✅ | Video uses `VideoPlayer` (PiP + AirPlay for free) |
| In-app browser with address bar, bookmarks, desktop-site switch | ✅ | |
| YouTube add-ons: SponsorBlock (hash-prefix lookup, per-category), auto-highest-quality, ad-skip | ✅ | Same JavaScript, same opt-in-only policy |
| Streaming sites list | ✅ | Same catalog, DRM-free sites only |
| Folders / Gallery (on-device media) | ⚠️ | Through the system pickers — iOS has no `MediaStore` to walk |
| Thai + English UI | ✅ | |
| Car surface | ⚠️ | CarPlay **audio**: sections, sources, categories, paged channel lists, logos, check results, now-playing |
| Playlists (music library) | ❌ | A DRM-protected Apple Music track exposes no playable asset URL |
| Weather section | ✅ | Open-Meteo, one place picked by name; the temperature shows on the home card |
| Utilities: fuel/charging log, maintenance, car costs, emergency card, parking spot, backup | ✅ | Settings → Utilities. Same JSON as Android, so a backup file moves between the two apps |
| Maintenance reminder, break reminder | ✅ | Local notifications: maintenance on opening the app (once a day), break every N hours while CarPlay is connected |
| Odometer from the car, voice fill-up (Agent), trips | ❌ | CarPlay gives an audio app no vehicle data; no voice agent on iOS yet. Trips in a backup are kept untouched |
| Mirror / Apps / Remote, Smart Mode, LAB, parked gate | ❌ | Platform boundary, see above |

## Requirements

- Xcode 15 or newer, iOS 16+ deployment target.
- A paid Apple Developer account only if you want CarPlay on real hardware (requires the
  `com.apple.developer.carplay-audio` entitlement, granted by Apple on request).

## Project layout

```
ios/
  AutoBridge/
    App/            SwiftUI entry point and the shared store container
    IPTV/           Ported pure logic (M3U parser, Xtream, models, directory, conventions,
                    logos, channel check)
    Services/       Keychain store, source/history stores, catalog cache, image loader, player
    YouTube/        SponsorBlock, ad-skip, URL parsing, settings, the per-web-view enhancer
    UI/             SwiftUI screens and the AutoBridge design language
    CarPlay/        CarPlay audio scene
    Resources/      Info.plist, entitlements, en/th strings
  AutoBridgeTests/  Unit tests for the ported logic (mirror the Android IPTV/YouTube tests)
```

## Building

The repo ships the Swift sources; the Xcode project is generated.

```bash
brew install xcodegen
cd ios && xcodegen generate && open AutoBridge.xcodeproj
```

From the command line:

```bash
cd ios && xcodegen generate
xcodebuild -project AutoBridge.xcodeproj -scheme AutoBridge \
  -sdk iphonesimulator -destination 'platform=iOS Simulator,name=iPhone 17' test
```

There are no third-party dependencies; everything uses the standard iOS SDK (`Foundation`,
`SwiftUI`, `AVFoundation`, `AVKit`, `WebKit`, `CarPlay`, `MediaPlayer`, `PhotosUI`, `Network`,
`CryptoKit`, `Security`).

`project.yml` deliberately has **no `info:` block** for the app target: XcodeGen *generates* a plist
at `info.path` and would overwrite the hand-written `Resources/Info.plist`, which carries the
CarPlay scene manifest, the audio background mode and the ATS exception. `INFOPLIST_FILE` is what
points at it.

### Tests

The suite is hermetic and runs anywhere. One test — `PublicPlaylistLiveTests` — reaches the real
public playlists and is skipped unless `AUTOBRIDGE_NETWORK_TESTS=1` is set; the scheme declares that
variable, unticked, so it is one checkbox away in Xcode's Test action.

## Things worth knowing before shipping it

- **App Transport Security is opened up.** A large share of every public playlist, and most Xtream
  portals, serve `http://` stream and logo addresses; with ATS at its default those requests fail
  before they are made. `NSAllowsArbitraryLoads` is therefore set, which is a real reduction in
  transport security and is commented as such in `Info.plist`.
- **CarPlay needs Apple's grant.** `Resources/AutoBridge.entitlements` intentionally ships *without*
  `com.apple.developer.carplay-audio` so the app signs and installs as a normal iPhone app. The
  scene manifest is already in `Info.plist`; add the entitlement once Apple approves it at
  <https://developer.apple.com/contact/carplay/> and the car surface activates.
- **Credentials.** Sources and playback history go through the keychain with
  `kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly`: out of iCloud Keychain, out of a backup
  restored onto another handset, gone when the app is deleted, and still readable by the CarPlay
  scene while the phone is locked in a pocket.
- **The YouTube add-ons are off by default**, for the reasons the Android app gives: they change
  what someone asked to watch, SponsorBlock's lookups go to a third party, and YouTube detects ad
  skipping and may answer with an interstitial of its own. Nothing here tries to defeat that check.

## Mapping to the Android codebase

| Android (`dev.autobridge.*`) | iOS |
|---|---|
| `iptv/IptvModels.kt` | `IPTV/IptvModels.swift` |
| `iptv/M3uParser.kt` | `IPTV/M3UParser.swift` |
| `iptv/IptvLogos.kt` | `IPTV/IptvLogos.swift` |
| `iptv/XtreamCredentials.kt` | `IPTV/XtreamCredentials.swift` |
| `iptv/XtreamClient.kt` | `IPTV/XtreamClient.swift` |
| `iptv/IptvDirectory.kt` | `IPTV/IptvDirectory.swift` |
| `iptv/IptvPlaylistConventions.kt` | `IPTV/IptvPlaylistConventions.swift` |
| `iptv/StreamPing.kt` | `IPTV/StreamPing.swift` |
| `iptv/IptvCatalog.kt` | `Services/IptvCatalog.swift` |
| `iptv/IptvSourceStore.kt` | `Services/IptvSourceStore.swift` |
| `iptv/IptvHistoryStore.kt` | `Services/IptvHistoryStore.swift` |
| `core/crypto/SecretPrefs.kt` | `Services/SecretStore.swift` (Keychain) |
| `library/StreamingLinks.kt` | `IPTV/StreamingLinks.swift` |
| `library/HomeSections.kt` | `UI/HomeSection.swift` |
| `library/LibraryActivity.kt` | `UI/SourcesView`, `CategoriesView`, `EntriesView`, `HistoryViews`, … |
| `ui/AutoBridgeDesign.kt` | `UI/AutoBridgeDesign.swift` |
| `ui/ImageLoader.kt` | `Services/ImageLoader.swift` |
| `ui/MiniPlayer.kt` | `UI/MiniPlayerBar.swift` |
| `youtube/SponsorBlock.kt`, `SponsorBlockClient.kt`, `YouTubeAdSkip.kt`, `YouTubeUrls.kt`, `YouTubeSettings.kt`, `YouTubeEnhancer.kt` | `YouTube/*.swift` (same names) |
| `car/` Car App templates | `CarPlay/CarPlaySceneDelegate.swift` |

The ported logic keeps the same behaviour so results stay consistent across platforms; where the
Android test suite pins a rule down, the iOS suite pins down the same rule.
