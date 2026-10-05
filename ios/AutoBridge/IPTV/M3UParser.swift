import Foundation

/// Minimal `#EXTM3U` reader for IPTV playlists, including the `m3u_plus` attribute form Xtream
/// portals serve from `get.php`.
///
/// Only the attributes AutoBridge actually shows are read (`group-title`, the logo, `tvg-name`);
/// anything else on the line is ignored rather than rejected, because provider playlists routinely
/// carry vendor-specific extras. Which attribute carries the logo, and what makes its address
/// usable, is `IptvLogos`' business — the value arrives here however the provider wrote it.
/// Pure string work, so it is unit-testable without a network.
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

        func with(url: String? = nil, group: String? = nil, logo: String? = nil) -> Channel {
            Channel(
                name: name,
                url: url ?? self.url,
                group: group ?? self.group,
                logo: logo ?? self.logo
            )
        }
    }

    public static func parse(_ text: String) -> [Channel] {
        var channels: [Channel] = []
        var pending: Channel?

        for rawLine in text.split(whereSeparator: \.isNewline) {
            let line = rawLine.trimmingCharacters(in: .whitespacesAndNewlines)
            if line.isEmpty { continue }
            let lower = line.lowercased()
            if lower.hasPrefix("#extinf") {
                pending = parseExtInf(line)
            // Other directives (#EXTGRP, #EXTIMG, #EXTVLCOPT, #EXTM3U) refine or precede it.
            } else if lower.hasPrefix("#extgrp:") {
                pending = pending?.with(group: valueAfterColon(line))
            // Some generators put the logo on its own line instead of in an attribute; an
            // #EXTINF that carried one too keeps it, because the attribute is the convention.
            } else if lower.hasPrefix("#extimg:") {
                let logo = valueAfterColon(line)
                pending = pending.map { $0.logo.isEmpty ? $0.with(logo: logo) : $0 }
            } else if line.hasPrefix("#") {
                continue
            } else {
                if let entry = pending, !entry.name.isEmpty {
                    channels.append(entry.with(url: line))
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
        if let nameStart {
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
            logo: IptvLogos.pick(attributes)
        )
    }

    private static func valueAfterColon(_ line: String) -> String {
        substringAfterFirst(line, separator: ":").trimmingCharacters(in: .whitespaces)
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
            guard let match,
                  let keyRange = Range(match.range(at: 1), in: value),
                  let valueRange = Range(match.range(at: 2), in: value) else { return }
            attributes[value[keyRange].lowercased()] = String(value[valueRange])
        }
        return attributes
    }
}
