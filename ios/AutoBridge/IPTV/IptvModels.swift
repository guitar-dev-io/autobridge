import Foundation

/// What a source feeds: the TV grid (video) or the Radio grid (audio-only).
public enum IptvKind: String, Codable, Hashable, CaseIterable {
    case tv
    case radio
}

/// How a source is fetched: an Xtream Codes account, or a plain M3U playlist URL.
public enum IptvSourceType: String, Codable, Hashable {
    case xtream
    case m3u
}

/// A configured IPTV provider. `url` is the portal (Xtream) or the playlist address (M3U);
/// `username`/`password` are only meaningful for `.xtream`.
public struct IptvSource: Codable, Hashable, Identifiable {
    public let id: String
    public let name: String
    public let kind: IptvKind
    public let type: IptvSourceType
    public let url: String
    public let username: String
    public let password: String

    public init(
        id: String,
        name: String,
        kind: IptvKind,
        type: IptvSourceType,
        url: String,
        username: String = "",
        password: String = ""
    ) {
        self.id = id
        self.name = name
        self.kind = kind
        self.type = type
        self.url = url
        self.username = username
        self.password = password
    }

    public var credentials: XtreamCredentials? {
        guard type == .xtream else { return nil }
        return XtreamCredentials.parse(input: url, username: username, password: password)
    }
}

/// A provider category ("Sports", "News"). `id` is the Xtream category id, or a synthetic group id.
public struct IptvCategory: Codable, Hashable, Identifiable {
    public let id: String
    public let name: String
    public let count: Int

    public init(id: String, name: String, count: Int = 0) {
        self.id = id
        self.name = name
        self.count = count
    }
}

/// What the catalog can hand to a player: live channels, movies, and series episodes.
public enum IptvEntryType: String, Codable, Hashable {
    case live
    case movie
    case series
}

/// How an entry's address has to be opened. Curated playlists point some channels at a YouTube or
/// Twitch page rather than a stream, and a media player cannot do anything with those.
public enum IptvPlayback: String, Codable, Hashable {
    case stream
    case webPage
}

/// One playable catalog entry. `url` is already a direct stream address, so playback never needs
/// the portal again. `seriesId` is set for series folders whose episodes load on demand.
public struct IptvEntry: Codable, Hashable, Identifiable {
    public let id: String
    public let title: String
    public let categoryId: String
    public let type: IptvEntryType
    public let url: String
    public let logo: String
    public let subtitle: String
    public let seriesId: String
    public let catchupDays: Int
    public let playback: IptvPlayback

    public init(
        id: String,
        title: String,
        categoryId: String,
        type: IptvEntryType,
        url: String,
        logo: String = "",
        subtitle: String = "",
        seriesId: String = "",
        catchupDays: Int = 0,
        playback: IptvPlayback = .stream
    ) {
        self.id = id
        self.title = title
        self.categoryId = categoryId
        self.type = type
        self.url = url
        self.logo = logo
        self.subtitle = subtitle
        self.seriesId = seriesId
        self.catchupDays = catchupDays
        self.playback = playback
    }

    public var isSeriesFolder: Bool { type == .series && url.isEmpty }
    public var supportsCatchup: Bool { type == .live && catchupDays > 0 }
    public var isWebPage: Bool { playback == .webPage }
}

/// A loaded source: its categories and every entry, already resolved to playable URLs.
public struct IptvCatalogData: Codable, Hashable {
    public static let allCategoryId = "__all__"
    public static let empty = IptvCatalogData(categories: [], entries: [])

    public let categories: [IptvCategory]
    public let entries: [IptvEntry]

    public init(categories: [IptvCategory], entries: [IptvEntry]) {
        self.categories = categories
        self.entries = entries
    }

    public func entries(in categoryId: String) -> [IptvEntry] {
        categoryId == Self.allCategoryId
            ? entries
            : entries.filter { $0.categoryId == categoryId }
    }
}
