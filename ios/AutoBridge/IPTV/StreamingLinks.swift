import Foundation

/// How the Streaming list is grouped, in display order.
/// Mirrors the Android `StreamingGroup` enum.
enum StreamingGroup: String, CaseIterable, Identifiable {
    case video
    case music
    case live
    case anime

    var id: String { rawValue }

    var title: String {
        switch self {
        case .video: return NSLocalizedString("Video", comment: "Streaming group")
        case .music: return NSLocalizedString("Music", comment: "Streaming group")
        case .live: return NSLocalizedString("Live / Gaming", comment: "Streaming group")
        case .anime: return NSLocalizedString("Anime", comment: "Streaming group")
        }
    }
}

/// One streaming website. It opens in the regular in-app browser, like any other page.
/// Mirrors the Android `StreamingLink` data class.
struct StreamingLink: Identifiable, Hashable {
    let title: String
    let group: StreamingGroup
    let url: URL

    var id: String { url.absoluteString }
}

/// The Streaming tile's catalog, ported from the Android `StreamingLinks`.
///
/// Every entry is a plain website loaded by the in-app `BrowserView` (`WKWebView`); there is no
/// per-site player. Only services that play without DRM are listed, because the web view has no
/// dependable Widevine/FairPlay path for the others (Netflix, Disney+, Prime Video, HBO Max, Viu,
/// iQIYI, WeTV, Youku, AIS PLAY, Spotify web, TrueVisions NOW).
enum StreamingLinks {
    static let all: [StreamingLink] = [
        StreamingLink(title: "YouTube", group: .video, url: URL(string: "https://m.youtube.com")!),
        StreamingLink(title: "TikTok", group: .video, url: URL(string: "https://www.tiktok.com")!),
        StreamingLink(title: "Facebook Watch", group: .video, url: URL(string: "https://m.facebook.com/watch")!),
        StreamingLink(title: "Dailymotion", group: .video, url: URL(string: "https://www.dailymotion.com")!),
        StreamingLink(title: "Vimeo", group: .video, url: URL(string: "https://vimeo.com")!),
        StreamingLink(title: "YouTube Music", group: .music, url: URL(string: "https://music.youtube.com")!),
        StreamingLink(title: "SoundCloud", group: .music, url: URL(string: "https://m.soundcloud.com")!),
        StreamingLink(title: "Mixcloud", group: .music, url: URL(string: "https://www.mixcloud.com")!),
        StreamingLink(title: "Radio Garden", group: .music, url: URL(string: "https://radio.garden")!),
        StreamingLink(title: "YouTube Live", group: .live, url: URL(string: "https://m.youtube.com/live")!),
        StreamingLink(title: "Twitch", group: .live, url: URL(string: "https://www.twitch.tv")!),
        StreamingLink(title: "Kick", group: .live, url: URL(string: "https://kick.com")!),
        // The international site; bilibili.com is the mainland-China front end.
        StreamingLink(title: "Bilibili", group: .anime, url: URL(string: "https://www.bilibili.tv")!),
        StreamingLink(title: "Muse Asia", group: .anime, url: URL(string: "https://m.youtube.com/@MuseAsia")!),
        StreamingLink(title: "Ani-One Asia", group: .anime, url: URL(string: "https://m.youtube.com/@AniOneAsia")!)
    ]

    /// [all] split by group, in `StreamingGroup` order, skipping empty groups.
    static func grouped() -> [(group: StreamingGroup, links: [StreamingLink])] {
        StreamingGroup.allCases.compactMap { group in
            let links = all.filter { $0.group == group }
            return links.isEmpty ? nil : (group, links)
        }
    }
}
