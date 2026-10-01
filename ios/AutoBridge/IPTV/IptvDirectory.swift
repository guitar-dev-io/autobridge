import Foundation

/// A short list of public, free-to-air playlists the user can add with one tap instead of typing a
/// URL on a phone.
///
/// These are addresses only. AutoBridge does not host, mirror, bundle or redistribute any playlist
/// or stream; each entry is fetched live from the project that publishes it, under that project's
/// own terms. Nothing here is added automatically — adding a source stays an explicit action.
public enum IptvDirectory {
    /// One offer in the picker. `note` is what the row says about the list. `seeded` marks the ones
    /// a fresh install starts with, so TV and Radio have something to show before anything is set.
    public struct Entry: Equatable, Identifiable {
        public let name: String
        public let url: String
        public let kind: IptvKind
        public let note: String
        public let seeded: Bool

        public var id: String { url }

        public init(name: String, url: String, kind: IptvKind, note: String, seeded: Bool = false) {
            self.name = name
            self.url = url
            self.kind = kind
            self.note = note
            self.seeded = seeded
        }
    }

    private static let entries: [Entry] = [
        Entry(
            name: "Free-TV",
            url: "https://raw.githubusercontent.com/Free-TV/IPTV/master/playlist.m3u8",
            kind: .tv,
            note: "~2,000 free-to-air channels, grouped by country",
            seeded: true
        ),
        Entry(
            name: "iptv-org · Thailand",
            url: "https://iptv-org.github.io/iptv/countries/th.m3u",
            kind: .tv,
            note: "Thai channels only — a small list to test with",
            seeded: true
        ),
        Entry(
            name: "iptv-org · All countries",
            url: "https://iptv-org.github.io/iptv/index.m3u",
            kind: .tv,
            note: "Very large; the first load takes a while"
        ),
        Entry(
            name: "radio-browser · Thailand",
            url: "https://de1.api.radio-browser.info/m3u/stations/bycountry/thailand",
            kind: .radio,
            note: "Thai stations from the radio-browser community database",
            seeded: true
        ),
        Entry(
            name: "radio-browser · Top voted",
            url: "https://de1.api.radio-browser.info/m3u/stations/topvote/100",
            kind: .radio,
            note: "The 100 highest-voted stations worldwide"
        )
    ]

    public static func list(kind: IptvKind) -> [Entry] {
        entries.filter { $0.kind == kind }
    }

    /// The lists a fresh install starts with.
    public static func defaults() -> [Entry] {
        entries.filter { $0.seeded }
    }

    /// Which defaults still have to be created, given the URLs already seeded once and the URLs
    /// already configured. Seeding is remembered per URL so a deleted default is never recreated,
    /// while a default added in a later version still arrives.
    public static func pendingDefaults(seededUrls: Set<String>, existingUrls: Set<String>) -> [Entry] {
        defaults().filter { !seededUrls.contains($0.url) && !existingUrls.contains($0.url) }
    }

    /// The source to persist for `entry`; the caller still decides whether to save it.
    public static func toSource(_ entry: Entry, id: String) -> IptvSource {
        IptvSource(id: id, name: entry.name, kind: entry.kind, type: .m3u, url: entry.url)
    }
}
