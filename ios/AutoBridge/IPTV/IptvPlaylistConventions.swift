import Foundation

/// Conventions that public community playlists follow but the `#EXTM3U` format itself says nothing
/// about, kept apart from `M3UParser` so the parser stays a plain reader of the format.
///
/// 1. Not every line under an `#EXTINF` is a stream. Curated lists point a channel at a YouTube or
///    Twitch *page* when that is where the broadcaster publishes its live feed. Handing such a URL
///    to AVPlayer produces a failure the user cannot act on, so it is classified here and opened in
///    the browser instead.
/// 2. Free-TV encodes per-channel notes as circled letters glued onto the display name
///    (`Ⓢ` SD, `Ⓖ` geo-blocked, `Ⓨ` YouTube, `Ⓣ` Twitch, `Ⓓ` Dailymotion). Read out they are the
///    warnings worth showing as a subtitle.
///
/// Pure string work, so it is unit-testable without a network or a device.
public enum IptvPlaylistConventions {
    /// A display name split into the title to show and the notes its markers carried.
    public struct Label: Equatable {
        public let title: String
        public let hints: [String]

        public init(title: String, hints: [String]) {
            self.title = title
            self.hints = hints
        }
    }

    /// Free-TV's circled-letter markers, in the order they are worth reading back.
    private static let markers: [(Character, String)] = [
        ("Ⓨ", "YouTube"),
        ("Ⓣ", "Twitch"),
        ("Ⓓ", "Dailymotion"),
        ("Ⓖ", "Geo-blocked"),
        ("Ⓢ", "SD")
    ]

    /// Hosts that serve a watch *page*, never a stream a media player can open. Matched on the host
    /// only: a query string mentioning youtube.com must not turn a real stream into a web page.
    private static let pageHosts = [
        "youtube.com", "youtu.be", "twitch.tv", "dailymotion.com", "dai.ly", "facebook.com"
    ]

    private static let collapseWhitespace = try? NSRegularExpression(pattern: "\\s{2,}")

    /// Strips the markers out of `name` and returns them as readable hints.
    public static func label(_ name: String) -> Label {
        let found = markers.filter { name.contains($0.0) }
        if found.isEmpty {
            return Label(title: name.trimmingCharacters(in: .whitespaces), hints: [])
        }
        let markerSet = Set(markers.map { $0.0 })
        let stripped = String(name.filter { !markerSet.contains($0) })
            .trimmingCharacters(in: .whitespaces)
        let normalized = collapse(stripped)
        return Label(title: normalized, hints: found.map { $0.1 })
    }

    /// True when `url` addresses a page to browse rather than a stream to decode. Anything that is
    /// not recognisably a watch page stays a stream: provider endpoints hide behind `.php` and
    /// `.htm` URLs often enough that guessing from the extension would break working channels.
    public static func isWebPage(_ url: String) -> Bool {
        guard let host = host(of: url) else { return false }
        return pageHosts.contains { host == $0 || host.hasSuffix(".\($0)") }
    }

    private static func collapse(_ value: String) -> String {
        guard let regex = collapseWhitespace else { return value }
        let range = NSRange(value.startIndex..<value.endIndex, in: value)
        return regex.stringByReplacingMatches(in: value, range: range, withTemplate: " ")
    }

    private static func host(of url: String) -> String? {
        let lower = url.lowercased()
        let withoutScheme: String
        if lower.hasPrefix("http://") {
            withoutScheme = String(url.dropFirst(7))
        } else if lower.hasPrefix("https://") {
            withoutScheme = String(url.dropFirst(8))
        } else {
            return nil
        }
        let authority = withoutScheme.prefix { $0 != "/" && $0 != "?" && $0 != "#" }
        let afterUserInfo = authority.split(separator: "@").last.map(String.init) ?? String(authority)
        let hostOnly = afterUserInfo.split(separator: ":").first.map(String.init) ?? afterUserInfo
        let result = hostOnly.lowercased()
        return result.isEmpty ? nil : result
    }
}
