import Foundation

/// Xtream Codes account coordinates: a portal base URL plus username/password.
///
/// Providers hand these out in three shapes, so `parse` accepts all of them:
///  - a bare portal ("http://host:8080") with the credentials entered separately;
///  - a full `player_api.php?username=U&password=P` link;
///  - a full `get.php?username=U&password=P&type=m3u_plus` playlist link.
///
/// Everything here is pure string work so the URL shapes are testable without a device or a live
/// portal. Network access lives in the catalog loader.
public struct XtreamCredentials: Equatable {
    public let portal: String
    public let username: String
    public let password: String

    public init(portal: String, username: String, password: String) {
        self.portal = portal
        self.username = username
        self.password = password
    }

    /// `http://host:port/player_api.php?username=…&password=…&action=…`
    public func apiUrl(action: String? = nil, params: [String: String] = [:]) -> String {
        var query = "username=\(Self.encode(username))&password=\(Self.encode(password))"
        if let action = action, !action.isEmpty {
            query += "&action=\(Self.encode(action))"
        }
        for (key, value) in params {
            query += "&\(Self.encode(key))=\(Self.encode(value))"
        }
        return "\(portal)/player_api.php?\(query)"
    }

    /// The provider's full m3u_plus playlist, used as a fallback when `player_api.php` is absent.
    public func playlistUrl() -> String {
        "\(portal)/get.php?username=\(Self.encode(username))&password=\(Self.encode(password))&type=m3u_plus&output=ts"
    }

    /// Direct live stream URL. HLS (`m3u8`) is preferred; `ts` is the legacy fallback.
    public func liveUrl(streamId: String, extension ext: String = "m3u8") -> String {
        "\(portal)/live/\(Self.encode(username))/\(Self.encode(password))/\(streamId).\(ext)"
    }

    /// Direct VOD (movie) URL. `extension` comes from the portal's `container_extension`.
    public func vodUrl(streamId: String, extension ext: String) -> String {
        "\(portal)/movie/\(Self.encode(username))/\(Self.encode(password))/\(streamId).\(ext.isEmpty ? "mp4" : ext)"
    }

    /// Direct series-episode URL. `extension` comes from the episode's `container_extension`.
    public func seriesUrl(episodeId: String, extension ext: String) -> String {
        "\(portal)/series/\(Self.encode(username))/\(Self.encode(password))/\(episodeId).\(ext.isEmpty ? "mp4" : ext)"
    }

    /// Catch-up (timeshift) URL for a live channel, when the provider exposes archive data.
    /// `start` is `yyyy-MM-dd:HH-mm` in the portal's timezone and `durationMinutes` the length.
    public func catchupUrl(streamId: String, start: String, durationMinutes: Int) -> String {
        "\(portal)/streaming/timeshift.php?username=\(Self.encode(username))&password=\(Self.encode(password))"
            + "&stream=\(streamId)&start=\(Self.encode(start))&duration=\(durationMinutes)"
    }

    /// Builds credentials from free-form user input.
    ///
    /// `input` may be a portal URL or a full `player_api.php`/`get.php` link. Credentials found in
    /// the link win over `username`/`password` only when those are blank, so a user who pastes a
    /// link and also types a username still gets what they typed. Returns nil when no usable portal
    /// or credential pair can be formed.
    public static func parse(input: String?, username: String? = nil, password: String? = nil) -> XtreamCredentials? {
        let raw = (input ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        if raw.isEmpty { return nil }

        let withScheme = raw.contains("://") ? raw : "http://\(raw)"
        guard let schemeRange = withScheme.range(of: "://") else { return nil }
        let scheme = String(withScheme[withScheme.startIndex..<schemeRange.upperBound]).lowercased()
        if scheme != "http://" && scheme != "https://" { return nil }

        let rest = String(withScheme[schemeRange.upperBound...])
        let authorityAndPath = substringBefore(substringBefore(rest, "?"), "#")
        let authority = substringBefore(authorityAndPath, "/")
        if authority.isEmpty { return nil }

        let query = substringBefore(substringAfter(rest, "?"), "#")
        let parameters = parseQuery(query)

        // Keep any directory prefix ("/iptv/") but drop the endpoint file itself.
        var path = authorityAndPath
        if path.hasPrefix(authority) {
            path = String(path.dropFirst(authority.count))
        }
        var directory = substringBeforeLast(path, "/")
        directory = trimTrailing(directory, "/")
        if directory.isEmpty || directory.hasSuffix(".php") {
            directory = ""
        }

        let portal = trimTrailing(scheme + authority + directory, "/")

        let user = nonEmptyTrimmed(username) ?? parameters["username"].flatMap { $0.isEmpty ? nil : $0 }
        guard let resolvedUser = user else { return nil }

        let secret = nonEmptyTrimmed(password) ?? parameters["password"].flatMap { $0.isEmpty ? nil : $0 }
        guard let resolvedSecret = secret else { return nil }

        return XtreamCredentials(portal: portal, username: resolvedUser, password: resolvedSecret)
    }

    // MARK: - String helpers (mirror the Kotlin substring* semantics)

    private static func nonEmptyTrimmed(_ value: String?) -> String? {
        guard let trimmed = value?.trimmingCharacters(in: .whitespaces), !trimmed.isEmpty else { return nil }
        return trimmed
    }

    private static func substringBefore(_ value: String, _ delimiter: Character) -> String {
        guard let index = value.firstIndex(of: delimiter) else { return value }
        return String(value[value.startIndex..<index])
    }

    private static func substringAfter(_ value: String, _ delimiter: Character) -> String {
        guard let index = value.firstIndex(of: delimiter) else { return "" }
        return String(value[value.index(after: index)...])
    }

    private static func substringBeforeLast(_ value: String, _ delimiter: Character) -> String {
        guard let index = value.lastIndex(of: delimiter) else { return "" }
        return String(value[value.startIndex..<index])
    }

    private static func trimTrailing(_ value: String, _ character: Character) -> String {
        var result = value
        while result.last == character {
            result.removeLast()
        }
        return result
    }

    private static func parseQuery(_ query: String) -> [String: String] {
        var result: [String: String] = [:]
        for pair in query.split(separator: "&", omittingEmptySubsequences: false) {
            let pairString = String(pair)
            if pairString.isEmpty { continue }
            guard let separator = pairString.firstIndex(of: "="),
                  separator != pairString.startIndex else { continue }
            let key = decode(String(pairString[pairString.startIndex..<separator]))
            let value = decode(String(pairString[pairString.index(after: separator)...]))
            result[key] = value
        }
        return result
    }

    private static func encode(_ value: String) -> String {
        var result = ""
        for character in value {
            if character.isLetter || character.isNumber || "-._~".contains(character) {
                result.append(character)
            } else {
                for byte in String(character).utf8 {
                    result += String(format: "%%%02X", byte)
                }
            }
        }
        return result
    }

    private static func decode(_ value: String) -> String {
        if !value.contains("%") && !value.contains("+") { return value }
        var bytes: [UInt8] = []
        let characters = Array(value)
        var index = 0
        while index < characters.count {
            let character = characters[index]
            if character == "+" {
                bytes.append(UInt8(ascii: " "))
                index += 1
            } else if character == "%" && index + 2 < characters.count {
                let hex = String(characters[(index + 1)...(index + 2)])
                if let parsed = UInt8(hex, radix: 16) {
                    bytes.append(parsed)
                    index += 3
                } else {
                    bytes.append(contentsOf: Array(String(character).utf8))
                    index += 1
                }
            } else {
                bytes.append(contentsOf: Array(String(character).utf8))
                index += 1
            }
        }
        return String(decoding: bytes, as: UTF8.self)
    }
}
