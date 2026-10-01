import Foundation

/// Minimal `#EXTM3U` reader for IPTV playlists, including the `m3u_plus` attribute form Xtream
/// portals serve from `get.php`.
///
/// Only the attributes AutoBridge actually shows are read (`group-title`, `tvg-logo`, `tvg-name`);
/// anything else on the line is ignored rather than rejected, because provider playlists routinely
/// carry vendor-specific extras. Pure string work, so it is unit-testable without a network.
public enum M3UParser {
    /// One playlist line pair: its `#EXTINF` metadata plus the URL that follows it.
    public struct Channel: Equatable {
        public let name: String
        public let url: String
        public let group: String
        public let logo: String

        public init(name: String, url: String, group: String = "", logo: String = "") {
            self.name = name
            self.url = url
            self.group = group
            self.logo = logo
        }
    }

    public static func parse(_ text: String) -> [Channel] {
        var channels: [Channel] = []
        var pending: Channel?

        for rawLine in text.split(separator: "\n", omittingEmptySubsequences: false) {
            let line = rawLine.trimmingCharacters(in: .whitespacesAndNewlines)
            if line.isEmpty {
                continue
            }
            if line.lowercased().hasPrefix("#extinf") {
                pending = parseExtInf(line)
            } else if line.lowercased().hasPrefix("#extgrp:") {
                let group = String(line.drop(while: { $0 != ":" }).dropFirst())
                    .trimmingCharacters(in: .whitespaces)
                if let current = pending {
                    pending = Channel(name: current.name, url: current.url, group: group, logo: current.logo)
                }
            } else if line.hasPrefix("#") {
                continue
            } else {
                if let entry = pending, !entry.name.isEmpty {
                    channels.append(Channel(name: entry.name, url: line, group: entry.group, logo: entry.logo))
                }
                pending = nil
            }
        }
        return channels
    }

    /// `#EXTINF:-1 tvg-logo="…" group-title="…",Channel name`
    private static func parseExtInf(_ line: String) -> Channel {
        let payload = substringAfterFirst(line, separator: ":")
        // The display name is everything after the LAST comma that is not inside a quoted value.
        let nameStart = lastUnquotedComma(payload)
        let name: String
        let attributeSource: String
        if let nameStart = nameStart {
            let nameIndex = payload.index(after: nameStart)
            name = String(payload[nameIndex...]).trimmingCharacters(in: .whitespaces)
            attributeSource = String(payload[payload.startIndex..<nameStart])
        } else {
            name = ""
            attributeSource = payload
        }
        let attributes = parseAttributes(attributeSource)
        let resolvedName = name.isEmpty ? (attributes["tvg-name"] ?? "") : name
        return Channel(
            name: resolvedName,
            url: "",
            group: attributes["group-title"] ?? "",
            logo: attributes["tvg-logo"] ?? ""
        )
    }

    private static func substringAfterFirst(_ value: String, separator: Character) -> String {
        guard let index = value.firstIndex(of: separator) else { return "" }
        return String(value[value.index(after: index)...])
    }

    private static func lastUnquotedComma(_ value: String) -> String.Index? {
        var quoted = false
        var result: String.Index?
        var index = value.startIndex
        while index < value.endIndex {
            let character = value[index]
            if character == "\"" {
                quoted.toggle()
            } else if character == "," && !quoted {
                result = index
            }
            index = value.index(after: index)
        }
        return result
    }

    private static func parseAttributes(_ value: String) -> [String: String] {
        var attributes: [String: String] = [:]
        let pattern = "([A-Za-z0-9_-]+)=\"([^\"]*)\""
        guard let regex = try? NSRegularExpression(pattern: pattern) else { return attributes }
        let range = NSRange(value.startIndex..<value.endIndex, in: value)
        regex.enumerateMatches(in: value, range: range) { match, _, _ in
            guard let match = match,
                  let keyRange = Range(match.range(at: 1), in: value),
                  let valueRange = Range(match.range(at: 2), in: value) else { return }
            attributes[value[keyRange].lowercased()] = String(value[valueRange])
        }
        return attributes
    }
}
