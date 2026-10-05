import Foundation

/// Recognising YouTube pages and pulling the video id out of them.
///
/// Pure string work with no network dependency, so every URL shape the app will meet — `m.youtube.com`
/// watch links, `youtu.be` shares, Shorts, embeds, and links that arrive with a playlist or a `t=`
/// offset attached — is covered by a unit test rather than by trying them by hand in a car.
/// Mirrors the Android `YouTubeUrls`.
public enum YouTubeUrls {
    /// Matched exactly, never by suffix. Only these front ends serve a watch page the add-ons can
    /// drive; hosts such as `v.youtube.com` sit under the same domain without being one, and a
    /// suffix match would arm SponsorBlock and the quality script on a page with no player.
    private static let hosts: Set<String> = [
        "youtube.com", "www.youtube.com", "m.youtube.com", "music.youtube.com",
        "youtube-nocookie.com", "www.youtube-nocookie.com", "youtu.be", "www.youtu.be"
    ]

    public static func isYouTube(_ url: String) -> Bool {
        guard let host = host(url) else { return false }
        return hosts.contains(host)
    }

    /// True for the music front end, which has its own player and no Shorts.
    public static func isYouTubeMusic(_ url: String) -> Bool {
        host(url) == "music.youtube.com"
    }

    /// The video id `url` plays, or nil when the page is not a single video (a channel, the home
    /// feed, search results).
    public static func videoId(_ url: String) -> String? {
        guard let host = host(url), hosts.contains(host) else { return nil }
        let path = self.path(url)

        if host.hasSuffix("youtu.be") {
            let first = path
                .trimmingCharacters(in: CharacterSet(charactersIn: "/"))
                .split(separator: "/", maxSplits: 1)
                .first
                .map(String.init)
            return validId(first)
        }

        let segments = path
            .trimmingCharacters(in: CharacterSet(charactersIn: "/"))
            .split(separator: "/")
            .map(String.init)
            .filter { !$0.isEmpty }
        // /shorts/<id>, /embed/<id>, /live/<id>, /v/<id>
        if let first = segments.first, ["shorts", "embed", "live", "v"].contains(first) {
            return validId(segments.count > 1 ? segments[1] : nil)
        }
        return validId(query(url)["v"])
    }

    /// An 11-character id; anything else is a channel, a search or a malformed link.
    private static func validId(_ value: String?) -> String? {
        guard let value, value.count == 11 else { return nil }
        let allowed = CharacterSet(charactersIn: "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789_-")
        return value.unicodeScalars.allSatisfy(allowed.contains) ? value : nil
    }

    private static func host(_ url: String) -> String? {
        guard let separator = url.range(of: "://") else { return nil }
        let rest = url[separator.upperBound...]
        let authority = rest.prefix { $0 != "/" && $0 != "?" && $0 != "#" }
        let afterUserInfo = authority.split(separator: "@", omittingEmptySubsequences: false)
            .last.map(String.init) ?? String(authority)
        let host = afterUserInfo.split(separator: ":", omittingEmptySubsequences: false)
            .first.map(String.init) ?? afterUserInfo
        let lowered = host.lowercased()
        return lowered.isEmpty ? nil : lowered
    }

    private static func path(_ url: String) -> String {
        guard let separator = url.range(of: "://") else { return "" }
        let rest = String(url[separator.upperBound...])
        guard let slash = rest.firstIndex(of: "/") else { return "" }
        let afterHost = String(rest[rest.index(after: slash)...])
        let withoutQuery = afterHost.split(separator: "?", maxSplits: 1).first.map(String.init) ?? afterHost
        return withoutQuery.split(separator: "#", maxSplits: 1).first.map(String.init) ?? withoutQuery
    }

    private static func query(_ url: String) -> [String: String] {
        guard let mark = url.firstIndex(of: "?") else { return [:] }
        let raw = String(url[url.index(after: mark)...])
            .split(separator: "#", maxSplits: 1).first.map(String.init) ?? ""
        if raw.isEmpty { return [:] }
        var result: [String: String] = [:]
        for pair in raw.split(separator: "&") {
            let parts = pair.split(separator: "=", maxSplits: 1, omittingEmptySubsequences: false)
            guard let key = parts.first.map(String.init), !key.isEmpty else { continue }
            result[key] = parts.count > 1 ? String(parts[1]) : ""
        }
        return result
    }
}
