import Foundation

/// Loads a source into an `IptvCatalogData`.
///
/// - M3U sources: fetch the playlist text, run it through `M3UParser`, and turn each channel into
///   an `IptvEntry`. Groups become categories. Community conventions decide STREAM vs WEB_PAGE and
///   extract subtitle hints from Free-TV markers.
/// - Xtream sources: call `player_api.php` for live categories and streams, mapping each to a direct
///   `live/.../streamId.m3u8` URL. VOD/series are intentionally left for a later phase; the TV/Radio
///   home only needs live for now.
public struct IptvCatalogLoader {
    public enum LoadError: Error, LocalizedError {
        case invalidUrl(String)
        case emptyResponse
        case http(Int)
        case missingCredentials

        public var errorDescription: String? {
            switch self {
            case .invalidUrl(let url): return "Invalid URL: \(url)"
            case .emptyResponse: return "The source returned no data."
            case .http(let code): return "The source responded with HTTP \(code)."
            case .missingCredentials: return "This Xtream source has no usable credentials."
            }
        }
    }

    private let session: URLSession

    public init(session: URLSession = .shared) {
        self.session = session
    }

    public func load(_ source: IptvSource) async throws -> IptvCatalogData {
        switch source.type {
        case .m3u:
            return try await loadM3U(source)
        case .xtream:
            return try await loadXtream(source)
        }
    }

    // MARK: - M3U

    private func loadM3U(_ source: IptvSource) async throws -> IptvCatalogData {
        let text = try await fetchText(source.url)
        let channels = M3UParser.parse(text)
        return buildCatalog(from: channels, idPrefix: source.id)
    }

    /// Shared mapping from parsed `#EXTINF` channels to catalog entries, reused for Xtream's m3u
    /// fallback. Group titles become categories; blank groups fall into a single "General" bucket.
    func buildCatalog(from channels: [M3UParser.Channel], idPrefix: String) -> IptvCatalogData {
        var categoryOrder: [String] = []
        var categoryCounts: [String: Int] = [:]
        var entries: [IptvEntry] = []

        for (index, channel) in channels.enumerated() {
            let groupName = channel.group.isEmpty ? "General" : channel.group
            let categoryId = "grp:\(groupName)"
            if categoryCounts[categoryId] == nil {
                categoryOrder.append(categoryId)
            }
            categoryCounts[categoryId, default: 0] += 1

            let label = IptvPlaylistConventions.label(channel.name)
            let isWeb = IptvPlaylistConventions.isWebPage(channel.url)
            let subtitle = label.hints.joined(separator: " · ")

            entries.append(
                IptvEntry(
                    id: "\(idPrefix):\(index)",
                    title: label.title.isEmpty ? channel.name : label.title,
                    categoryId: categoryId,
                    type: .live,
                    url: channel.url,
                    logo: channel.logo,
                    subtitle: subtitle,
                    playback: isWeb ? .webPage : .stream
                )
            )
        }

        let categories = categoryOrder.map { id in
            IptvCategory(
                id: id,
                name: String(id.dropFirst("grp:".count)),
                count: categoryCounts[id] ?? 0
            )
        }
        return IptvCatalogData(categories: categories, entries: entries)
    }

    // MARK: - Xtream

    private func loadXtream(_ source: IptvSource) async throws -> IptvCatalogData {
        guard let credentials = source.credentials else { throw LoadError.missingCredentials }

        async let categoriesData = fetchData(credentials.apiUrl(action: "get_live_categories"))
        async let streamsData = fetchData(credentials.apiUrl(action: "get_live_streams"))

        let (categoriesRaw, streamsRaw) = try await (categoriesData, streamsData)

        let categoriesJson = (try? JSONSerialization.jsonObject(with: categoriesRaw)) as? [[String: Any]] ?? []
        let streamsJson = (try? JSONSerialization.jsonObject(with: streamsRaw)) as? [[String: Any]] ?? []

        var categoryNames: [String: String] = [:]
        for item in categoriesJson {
            let id = stringValue(item["category_id"])
            let name = stringValue(item["category_name"])
            if !id.isEmpty { categoryNames[id] = name }
        }

        var categoryCounts: [String: Int] = [:]
        var entries: [IptvEntry] = []
        for item in streamsJson {
            let streamId = stringValue(item["stream_id"])
            if streamId.isEmpty { continue }
            let categoryId = stringValue(item["category_id"])
            let name = stringValue(item["name"])
            let logo = stringValue(item["stream_icon"])
            let catchup = intValue(item["tv_archive_duration"])
            categoryCounts[categoryId, default: 0] += 1

            entries.append(
                IptvEntry(
                    id: "\(source.id):\(streamId)",
                    title: name,
                    categoryId: categoryId,
                    type: .live,
                    url: credentials.liveUrl(streamId: streamId),
                    logo: logo,
                    catchupDays: catchup,
                    playback: .stream
                )
            )
        }

        let categories = categoryNames
            .map { IptvCategory(id: $0.key, name: $0.value, count: categoryCounts[$0.key] ?? 0) }
            .sorted { $0.name.localizedCaseInsensitiveCompare($1.name) == .orderedAscending }

        if entries.isEmpty {
            // Portal exposes no player_api; fall back to the m3u_plus playlist.
            let text = try await fetchText(credentials.playlistUrl())
            return buildCatalog(from: M3UParser.parse(text), idPrefix: source.id)
        }
        return IptvCatalogData(categories: categories, entries: entries)
    }

    // MARK: - HTTP

    private func fetchText(_ urlString: String) async throws -> String {
        let data = try await fetchData(urlString)
        guard let text = String(data: data, encoding: .utf8) ?? String(data: data, encoding: .isoLatin1) else {
            throw LoadError.emptyResponse
        }
        return text
    }

    private func fetchData(_ urlString: String) async throws -> Data {
        guard let url = URL(string: urlString) else { throw LoadError.invalidUrl(urlString) }
        let (data, response) = try await session.data(from: url)
        if let http = response as? HTTPURLResponse, !(200...299).contains(http.statusCode) {
            throw LoadError.http(http.statusCode)
        }
        if data.isEmpty { throw LoadError.emptyResponse }
        return data
    }

    private func stringValue(_ value: Any?) -> String {
        if let string = value as? String { return string }
        if let number = value as? NSNumber { return number.stringValue }
        return ""
    }

    private func intValue(_ value: Any?) -> Int {
        if let number = value as? NSNumber { return number.intValue }
        if let string = value as? String { return Int(string) ?? 0 }
        return 0
    }
}
