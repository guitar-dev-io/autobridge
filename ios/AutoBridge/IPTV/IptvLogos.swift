import Foundation

/// Where a channel logo comes from, and what makes one usable.
///
/// `tvg-logo` is the attribute the format is known for, but playlists in the wild spell it several
/// ways and write the address in three shapes: absolute, protocol-relative (`//cdn/x.png`), and
/// relative to the playlist itself (`/logos/x.png`, `logos/x.png`). The loader behind the rows only
/// speaks http(s), so an address is either turned into that here or dropped — one left in the entry
/// costs a dead fetch on every surface that draws the row, and the row shows nothing more for it
/// than the accent initial it would have shown anyway.
///
/// Kept apart from `M3UParser` for the same reason as `IptvPlaylistConventions`: the parser reads
/// the format, this knows what providers do with it. Pure string work, so every shape is
/// unit-tested without a network. Mirrors the Android `IptvLogos`.
public enum IptvLogos {
    /// The attribute spellings seen in real playlists, in the order they are trusted.
    ///
    /// `tvg-logo` is the convention; `logo` and `url-logo` come out of panel generators, and
    /// `tvg-logo-small` is sometimes the only one a list carries. Taking the first non-blank rather
    /// than only the canonical name is what makes a logo appear on lists that use the others.
    private static let attributeNames = ["tvg-logo", "logo", "tvg-logo-small", "url-logo", "tvg-icon"]

    /// Values a generator writes when it means "no logo"; they are addresses to nothing.
    private static let placeholders: Set<String> = ["null", "none", "nil", "n/a", "-", "0"]

    /// The first logo attribute present in `attributes`, trimmed; "" when the entry carries none.
    public static func pick(_ attributes: [String: String]) -> String {
        for name in attributeNames {
            let value = attributes[name]?.trimmingCharacters(in: .whitespaces) ?? ""
            if !value.isEmpty { return value }
        }
        return ""
    }

    /// `raw` as an address the image loader can fetch, or "" when there is nothing usable.
    ///
    /// `base` is the playlist or portal URL the entry came from, which is the only thing that can
    /// resolve a relative logo path. A scheme that is neither http nor https (`data:`, `file:`,
    /// `ftp:`) is dropped rather than guessed at.
    public static func resolve(base: String, raw: String) -> String {
        let value = raw
            .trimmingCharacters(in: .whitespaces)
            .trimmingCharacters(in: CharacterSet(charactersIn: "\""))
            .trimmingCharacters(in: .whitespaces)
        if value.isEmpty || placeholders.contains(value.lowercased()) { return "" }
        // `//cdn/logo.png` inherits the page's scheme; https is the safe half of that choice.
        if value.hasPrefix("//") { return "https:\(value)" }
        let lower = value.lowercased()
        if lower.hasPrefix("http://") || lower.hasPrefix("https://") { return value }
        if hasScheme(value) { return "" }
        guard let origin = origin(of: base) else { return "" }
        if value.hasPrefix("/") { return origin + value }
        return "\(directory(of: base) ?? origin)/\(value)"
    }

    /// `scheme://host[:port]` of `url`, or nil when `url` is not an http(s) address.
    private static func origin(of url: String) -> String? {
        guard let separator = url.range(of: "://"), separator.lowerBound != url.startIndex else {
            return nil
        }
        let scheme = String(url[url.startIndex..<separator.lowerBound]).lowercased()
        if scheme != "http" && scheme != "https" { return nil }
        let authority = url[separator.upperBound...].prefix { $0 != "/" && $0 != "?" && $0 != "#" }
        if authority.trimmingCharacters(in: .whitespaces).isEmpty { return nil }
        return "\(scheme)://\(authority)"
    }

    /// The directory `url` sits in, so `logos/x.png` next to `playlist.m3u` resolves.
    private static func directory(of url: String) -> String? {
        let withoutQuery = url.split(separator: "?", maxSplits: 1).first.map(String.init) ?? url
        let withoutFragment = withoutQuery.split(separator: "#", maxSplits: 1).first.map(String.init) ?? withoutQuery
        guard let origin = origin(of: withoutFragment) else { return nil }
        let path = String(withoutFragment.dropFirst(origin.count))
        guard let lastSlash = path.lastIndex(of: "/") else { return origin }
        var result = origin + String(path[path.startIndex..<lastSlash])
        while result.hasSuffix("/") { result.removeLast() }
        return result
    }

    /// A leading `scheme:` — matched to reject what cannot be fetched, not to parse it.
    private static func hasScheme(_ value: String) -> Bool {
        guard let first = value.first, first.isLetter else { return false }
        for character in value.dropFirst() {
            if character == ":" { return true }
            let allowed = character.isLetter || character.isNumber || character == "+"
                || character == "." || character == "-"
            if !allowed { return false }
        }
        return false
    }
}
