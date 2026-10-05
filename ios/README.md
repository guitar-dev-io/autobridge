# AutoBridge iOS

A native iOS companion app for AutoBridge, written in SwiftUI. It is a **separate project**
from the Android `:app` module and shares no build system with it.

## What this is (and is not)

iOS and Apple's platform rules block the Android app's core features, so this app does **not**
attempt them:

- No full-device screen mirroring (iOS only lets an app capture its own screen via ReplayKit).
- No touch injection into other apps (App Sandbox forbids it; there is no Shizuku/Accessibility equivalent).
- No arbitrary screen mirroring onto the car display. CarPlay is template-based and has no mirroring category.

What it **does** port — the entertainment surface, which is pure logic plus standard iOS media APIs:

- IPTV via **Xtream Codes** accounts and plain **M3U/M3U8** playlists (TV + Radio).
- Live/VOD playback with **AVPlayer** (HLS/DASH/progressive).
- Community-playlist conventions: YouTube/Twitch *watch pages* open in an in-app **WKWebView**
  instead of failing in the player; Free-TV `Ⓢ/Ⓖ/Ⓨ/Ⓣ/Ⓓ` name markers become subtitle hints.
- Built-in free public playlists (Free-TV, iptv-org, radio-browser), stored as addresses only and
  fetched live — nothing is bundled or redistributed.
- Favorites and a recently-played list.
- An AutoBridge-flavored Home: TV, Radio, Streaming, Web browser.
- A **Streaming** list of DRM-free web services (YouTube, , TikTok, YouTube Music,
  Twitch, Bilibili), grouped by category and opened in the in-app `WKWebView` — matching the
  Android Streaming tile.
- A **CarPlay audio** scene skeleton (`CPTemplateApplicationSceneDelegate`) for the Radio/audio
  surface. Requires the CarPlay audio entitlement from Apple to run on real hardware.

## Requirements

- Xcode 15 or newer, iOS 16+ deployment target.
- A paid Apple Developer account only if you want CarPlay on real hardware (requires the
  `com.apple.developer.carplay-audio` entitlement, granted by Apple).

## Project layout

```
ios/
  AutoBridge/
    App/            SwiftUI app entry, scene delegates
    IPTV/           Ported pure logic (M3U parser, Xtream, models, directory, conventions)
    Services/       Networking, catalog loader, source + favorites/history stores
    UI/             SwiftUI screens: Home, TV/Radio lists, player, browser
    CarPlay/        CarPlay audio scene skeleton
    Resources/      Info.plist, entitlements, assets
  AutoBridgeTests/  Unit tests for the ported logic (mirror the Android IPTV tests)
```

## Building

This repo ships the Swift sources. Generate an Xcode project with one of:

1. Open `ios/` in Xcode and create an app target pointing at `AutoBridge/`, **or**
2. Use the provided `project.yml` with [XcodeGen](https://github.com/yonaskolb/XcodeGen):

   ```bash
   brew install xcodegen
   cd ios && xcodegen generate && open AutoBridge.xcodeproj
   ```

The Swift sources have no third-party dependencies; everything uses the standard iOS SDK
(`Foundation`, `SwiftUI`, `AVFoundation`, `WebKit`, `CarPlay`).

## Mapping to the Android codebase

| Android (`dev.autobridge.iptv`) | iOS (`AutoBridge/IPTV`) |
|---|---|
| `IptvModels.kt`           | `IptvModels.swift`           |
| `M3uParser.kt`            | `M3UParser.swift`            |
| `XtreamCredentials.kt`    | `XtreamCredentials.swift`    |
| `IptvDirectory.kt`        | `IptvDirectory.swift`        |
| `IptvPlaylistConventions.kt` | `IptvPlaylistConventions.swift` |
| `library/StreamingLinks.kt` | `StreamingLinks.swift`       |

The ported logic keeps the same behavior so results stay consistent across platforms.
