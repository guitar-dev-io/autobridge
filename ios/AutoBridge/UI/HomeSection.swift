import SwiftUI

/// The single home-grid definition, shared by the phone launcher and the CarPlay dashboard.
///
/// Keeping the order and wording in one place is what makes the two surfaces match, and keeping the
/// order the same as the Android `HomeSection` is what makes the two *apps* match. The sections
/// Android has that iOS cannot do are absent rather than stubbed: `MIRROR`, `APPS` and `REMOTE`
/// need full-device capture and input injection, which the App Sandbox forbids outright, and
/// `PLAYLISTS` would need the user's Apple Music library, whose tracks expose no playable asset URL
/// when they are DRM-protected. `WEATHER` is simply not ported yet.
///
/// `webUrl` is set for the sections that are simply a website; everything else is handled by the
/// screen for that section.
enum HomeSection: String, CaseIterable, Identifiable {
    case tv
    case radio
    case web
    case youtube
    case youtubeMusic
    case streaming
    case folders
    case favorites
    case gallery

    var id: String { rawValue }

    var title: LocalizedStringKey {
        switch self {
        case .tv: return "TV"
        case .radio: return "Radio"
        case .web: return "Web browser"
        case .youtube: return "YouTube"
        case .youtubeMusic: return "YouTube Music"
        case .streaming: return "Streaming"
        case .folders: return "Folders"
        case .favorites: return "Favorites"
        case .gallery: return "Gallery"
        }
    }

    /// The plain-text title, for the places that need a `String` rather than a localizable key.
    var plainTitle: String {
        switch self {
        case .tv: return NSLocalizedString("TV", comment: "Home section")
        case .radio: return NSLocalizedString("Radio", comment: "Home section")
        case .web: return NSLocalizedString("Web browser", comment: "Home section")
        case .youtube: return NSLocalizedString("YouTube", comment: "Home section")
        case .youtubeMusic: return NSLocalizedString("YouTube Music", comment: "Home section")
        case .streaming: return NSLocalizedString("Streaming", comment: "Home section")
        case .folders: return NSLocalizedString("Folders", comment: "Home section")
        case .favorites: return NSLocalizedString("Favorites", comment: "Home section")
        case .gallery: return NSLocalizedString("Gallery", comment: "Home section")
        }
    }

    /// Second line on the home card. The same wording as the Android captions.
    var caption: LocalizedStringKey {
        switch self {
        case .tv: return "Live channels & VOD"
        case .radio: return "Audio streams"
        case .web: return "Full page browsing"
        case .youtube: return "Video on the web"
        case .youtubeMusic: return "Streaming music"
        case .streaming: return "TikTok, Twitch & more"
        case .folders: return "On-device media"
        case .favorites: return "Saved channels & pages"
        case .gallery: return "Photos & clips"
        }
    }

    var accent: Color {
        switch self {
        case .tv: return AutoBridgeDesign.accentTV
        case .radio: return AutoBridgeDesign.accentRadio
        case .web: return AutoBridgeDesign.accentWeb
        case .youtube, .youtubeMusic, .streaming: return AutoBridgeDesign.accentVideo
        case .folders: return AutoBridgeDesign.accentFiles
        case .favorites: return AutoBridgeDesign.accentFavorite
        case .gallery: return AutoBridgeDesign.accentWeb
        }
    }

    var systemImage: String {
        switch self {
        case .tv: return "tv"
        case .radio: return "dot.radiowaves.left.and.right"
        case .web: return "globe"
        case .youtube: return "play.rectangle"
        case .youtubeMusic: return "music.note"
        case .streaming: return "play.rectangle.on.rectangle"
        case .folders: return "folder"
        case .favorites: return "star"
        case .gallery: return "photo.on.rectangle"
        }
    }

    /// Set for the sections that are simply a website.
    var webUrl: URL? {
        switch self {
        case .youtube: return URL(string: "https://m.youtube.com")
        case .youtubeMusic: return URL(string: "https://music.youtube.com")
        default: return nil
        }
    }

    var iptvKind: IptvKind? {
        switch self {
        case .tv: return .tv
        case .radio: return .radio
        default: return nil
        }
    }

    /// Sections the CarPlay dashboard offers. CarPlay is audio-only and template-based, so the
    /// browser-backed sections and the photo picker have nothing to show there.
    static let carSections: [HomeSection] = [.radio, .tv, .favorites]
}
