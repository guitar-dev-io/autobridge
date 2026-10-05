import Foundation

/// HTTP access to an Xtream Codes portal and to plain M3U playlists.
///
/// Responses are read defensively: portals differ in field naming and in whether `player_api.php`
/// exists at all, so a failed API call falls back to the `get.php` playlist rather than leaving the
/// user with an empty screen. `IptvCatalog` owns the caching in front of this.
/// Mirrors the Android `XtreamClient`.
public enum XtreamClient {
    public enum LoadError: Error, LocalizedError, Equatable {
        case invalidUrl(String)
        case emptyResponse
        case http(Int)
        case missingCredentials
        case tooLarge
        case unexpectedResponse

        public var errorDescription: String? {
            switch self {
            case .invalidUrl(let url): return "Invalid URL: \(url)"
            case .emptyResponse: return "The source returned no data."
            case .http(let code): return "The source responded with HTTP \(code)."
            case .missingCredentials: return "Invalid Xtream portal or credentials."
            case .tooLarge: return "Playlist too large."
            case .unexpectedResponse: return "Unexpected portal response."
            }
        }
    }

    private static let maxBodyBytes = 24 * 1024 * 1024
    private static let userAgent = "AutoBridge/1.0 (iOS)"

    private static let session: URLSession = {
        let configuration = URLSessionConfiguration.default
        configuration.timeoutIntervalForRequest = 15
        configuration.timeoutIntervalForResource = 60
        return URLSession(configuration: configuration)
    }()

    /// Loads a whole source into categories + playable entries. Throws on unrecoverable failure.
    public static func load(_ source: IptvSource) async throws -> IptvCatalogData {
        switch source.type {
        case .xtream:
            return try await loadXtream(source)
        // A playlist the user filed under Radio is taken at its word: no audio heuristic.
        case .m3u:
            return try await loadPlaylist(source.url, keepOnlyRadio: false)
        }
    }

    /// Episodes for one series folder, loaded on demand (`get_series_info`).
    public static func seriesEpisodes(_ source: IptvSource, seriesId: String) async -> [IptvEntry] {
        guard let credentials = source.credentials else { return [] }
        guard let body = try? await fetch(
            credentials.apiUrl(action: "get_series_info", params: ["series_id": seriesId])
        ) else { return [] }
        guard let data = body.data(using: .utf8),
              let root = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any],
              let seasons = root["episodes"] as? [String: Any] else { return [] }

        var episodes: [IptvEntry] = []
        for season in seasons.keys.sorted() {
            guard let list = seasons[season] as? [[String: Any]] else { continue }
            for episode in list {
                let id = string(episode["id"])
                if id.isEmpty { continue }
                let container = string(episode["container_extension"])
                let fallbackTitle = "S\(season) E\(string(episode["episode_num"]))"
                let title = string(episode["title"]).isEmpty ? fallbackTitle : string(episode["title"])
                episodes.append(
                    IptvEntry(
                        id: id,
                        title: title,
                        categoryId: seriesId,
                        type: .series,
                        url: credentials.seriesUrl(episodeId: id, extension: container),
                        subtitle: "Season \(season)",
                        seriesId: seriesId
                    )
                )
            }
        }
        return episodes.sorted { $0.subtitle < $1.subtitle }
    }

    // MARK: - Xtream

    private static func loadXtream(_ source: IptvSource) async throws -> IptvCatalogData {
        guard let credentials = source.credentials else { throw LoadError.missingCredentials }

        // A failing player_api.php is common on resold portals; the m3u_plus playlist still works.
        let fromApi = try? await loadXtreamApi(source, credentials: credentials)
        if let fromApi, !fromApi.entries.isEmpty { return fromApi }
        // One portal playlist carries live TV, radio, movies and series together, so the Radio
        // grid does need the audio heuristic here.
        return try await loadPlaylist(
            credentials.playlistUrl(),
            keepOnlyRadio: source.kind == .radio
        )
    }

    private static func loadXtreamApi(
        _ source: IptvSource,
        credentials: XtreamCredentials
    ) async throws -> IptvCatalogData {
        var categories: [IptvCategory] = []
        var entries: [IptvEntry] = []

        // Live channels feed both the TV and the Radio grid; Radio keeps only audio-ish groups.
        let liveCategories = await categoryList(credentials, action: "get_live_categories")
        let live = try await jsonArray(credentials.apiUrl(action: "get_live_streams"))
        for stream in live {
            let id = string(stream["stream_id"])
            if id.isEmpty { continue }
            let archived = int(stream["tv_archive"]) == 1
            entries.append(
                IptvEntry(
                    id: "live:\(id)",
                    title: string(stream["name"], fallback: "Channel \(id)"),
                    categoryId: string(stream["category_id"], fallback: IptvCatalogData.allCategoryId),
                    type: .live,
                    url: credentials.liveUrl(streamId: id),
                    logo: IptvLogos.resolve(base: credentials.portal, raw: string(stream["stream_icon"])),
                    catchupDays: archived ? int(stream["tv_archive_duration"]) : 0
                )
            )
        }
        categories += liveCategories

        // Radio sources stop at live audio streams; movies and series are TV-only content.
        if source.kind == .tv {
            let vodCategories = await categoryList(credentials, action: "get_vod_categories")
            let vod = (try? await jsonArray(credentials.apiUrl(action: "get_vod_streams"))) ?? []
            for movie in vod {
                let id = string(movie["stream_id"])
                if id.isEmpty { continue }
                entries.append(
                    IptvEntry(
                        id: "vod:\(id)",
                        title: string(movie["name"], fallback: "Movie \(id)"),
                        categoryId: string(movie["category_id"], fallback: IptvCatalogData.allCategoryId),
                        type: .movie,
                        url: credentials.vodUrl(
                            streamId: id,
                            extension: string(movie["container_extension"])
                        ),
                        logo: IptvLogos.resolve(base: credentials.portal, raw: string(movie["stream_icon"])),
                        subtitle: "Movie"
                    )
                )
            }
            categories += vodCategories.map {
                IptvCategory(id: $0.id, name: "Movies · \($0.name)", count: $0.count)
            }

            let seriesCategories = await categoryList(credentials, action: "get_series_categories")
            let series = (try? await jsonArray(credentials.apiUrl(action: "get_series"))) ?? []
            for show in series {
                let id = string(show["series_id"])
                if id.isEmpty { continue }
                entries.append(
                    IptvEntry(
                        // Series carry no direct URL: episodes load on demand from get_series_info.
                        id: "series:\(id)",
                        title: string(show["name"], fallback: "Series \(id)"),
                        categoryId: string(show["category_id"], fallback: IptvCatalogData.allCategoryId),
                        type: .series,
                        url: "",
                        logo: IptvLogos.resolve(base: credentials.portal, raw: string(show["cover"])),
                        subtitle: "Series",
                        seriesId: id
                    )
                )
            }
            categories += seriesCategories.map {
                IptvCategory(id: $0.id, name: "Series · \($0.name)", count: $0.count)
            }
        }

        return finish(categories: categories, entries: entries)
    }

    private static func categoryList(
        _ credentials: XtreamCredentials,
        action: String
    ) async -> [IptvCategory] {
        guard let array = try? await jsonArray(credentials.apiUrl(action: action)) else { return [] }
        return array.compactMap { item in
            let id = string(item["category_id"])
            if id.isEmpty { return nil }
            return IptvCategory(id: id, name: string(item["category_name"], fallback: "Category \(id)"))
        }
    }

    // MARK: - M3U

    static func loadPlaylist(_ url: String, keepOnlyRadio: Bool) async throws -> IptvCatalogData {
        let channels = M3UParser.parse(try await fetch(url))
        return catalog(from: channels, base: url, keepOnlyRadio: keepOnlyRadio)
    }

    /// Shared mapping from parsed `#EXTINF` channels to catalog entries. Split out from the fetch so
    /// the mapping is unit-testable without a network.
    static func catalog(
        from channels: [M3UParser.Channel],
        base: String,
        keepOnlyRadio: Bool
    ) -> IptvCatalogData {
        var entries: [IptvEntry] = []
        for (index, channel) in channels.enumerated() {
            // Curated lists carry their notes in the display name and their warnings in the URL;
            // both are read here so the row can say "YouTube · Geo-blocked" instead of failing
            // inside the player with a parse error.
            let label = IptvPlaylistConventions.label(channel.name)
            let webPage = IptvPlaylistConventions.isWebPage(channel.url)
            let subtitle = ([channel.group] + label.hints)
                .filter { !$0.isEmpty }
                // Same separator the rows themselves use, so a row never mixes two.
                .joined(separator: " • ")
            entries.append(
                IptvEntry(
                    id: "m3u:\(index)",
                    title: label.title.isEmpty ? channel.name : label.title,
                    categoryId: channel.group.isEmpty ? IptvCatalogData.allCategoryId : channel.group,
                    type: .live,
                    url: channel.url,
                    // A logo is routinely written relative to the list it came from, so the
                    // playlist address is what resolves it; see `IptvLogos`.
                    logo: IptvLogos.resolve(base: base, raw: channel.logo),
                    subtitle: subtitle,
                    playback: webPage ? .webPage : .stream
                )
            )
        }
        let kept = keepOnlyRadio ? entries.filter(looksLikeRadio) : entries
        var seen = Set<String>()
        let categories = kept
            .map(\.categoryId)
            .filter { $0 != IptvCatalogData.allCategoryId && seen.insert($0).inserted }
            .map { IptvCategory(id: $0, name: $0) }
        return finish(categories: categories, entries: kept)
    }

    /// Separates audio from video inside a mixed portal playlist, by group/name wording or an audio
    /// file extension.
    ///
    /// The guess is far too narrow to run over a playlist the user chose themselves: a real station
    /// list from radio-browser contains "88 nice peak", "90.5 Delight" and "97qfm", none of which
    /// match, so filtering a user-added Radio playlist emptied most of it. It is therefore applied
    /// only where one URL genuinely mixes both kinds — an Xtream portal's m3u_plus playlist.
    private static func looksLikeRadio(_ entry: IptvEntry) -> Bool {
        let haystack = (entry.title + " " + entry.subtitle).lowercased()
        if radioWords.contains(where: { haystack.contains($0) }) { return true }
        let path = (entry.url.split(separator: "?", maxSplits: 1).first.map(String.init) ?? entry.url)
            .lowercased()
        return audioExtensions.contains { path.hasSuffix($0) }
    }

    /// Drops empty categories, sorts by name, and prepends the "All" bucket.
    private static func finish(categories: [IptvCategory], entries: [IptvEntry]) -> IptvCatalogData {
        var counts: [String: Int] = [:]
        for entry in entries { counts[entry.categoryId, default: 0] += 1 }
        var seen = Set<String>()
        let populated = categories
            .filter { seen.insert($0.id).inserted }
            .map { IptvCategory(id: $0.id, name: $0.name, count: counts[$0.id] ?? 0) }
            .filter { $0.count > 0 }
            .sorted { $0.name.localizedCaseInsensitiveCompare($1.name) == .orderedAscending }
        let all = IptvCategory(id: IptvCatalogData.allCategoryId, name: "All", count: entries.count)
        return IptvCatalogData(categories: [all] + populated, entries: entries)
    }

    // MARK: - HTTP

    private static func jsonArray(_ urlString: String) async throws -> [[String: Any]] {
        let body = try await fetch(urlString).trimmingCharacters(in: .whitespacesAndNewlines)
        if body.hasPrefix("[") {
            guard let data = body.data(using: .utf8),
                  let array = (try? JSONSerialization.jsonObject(with: data)) as? [[String: Any]] else {
                return []
            }
            return array
        }
        // Some portals answer an error object instead of a list; treat that as "no content".
        if body.hasPrefix("{") { return [] }
        throw LoadError.unexpectedResponse
    }

    private static func fetch(_ urlString: String) async throws -> String {
        guard let url = URL(string: urlString) else { throw LoadError.invalidUrl(urlString) }
        var request = URLRequest(url: url)
        // Several portals reject the default agent outright.
        request.setValue(userAgent, forHTTPHeaderField: "User-Agent")
        request.setValue("*/*", forHTTPHeaderField: "Accept")
        let (data, response) = try await session.data(for: request)
        if let http = response as? HTTPURLResponse, !(200...299).contains(http.statusCode) {
            throw LoadError.http(http.statusCode)
        }
        // Bounded after the fact rather than mid-stream: `URLSession.AsyncBytes` only yields one
        // byte at a time, which costs more on a 10 MB playlist than holding it does.
        if data.count > maxBodyBytes { throw LoadError.tooLarge }
        if data.isEmpty { throw LoadError.emptyResponse }
        guard let text = String(data: data, encoding: .utf8)
            ?? String(data: data, encoding: .isoLatin1) else {
            throw LoadError.emptyResponse
        }
        return text
    }

    // MARK: - JSON helpers

    private static func string(_ value: Any?, fallback: String = "") -> String {
        if let text = value as? String, !text.isEmpty { return text }
        if let number = value as? NSNumber { return number.stringValue }
        return fallback
    }

    private static func int(_ value: Any?) -> Int {
        if let number = value as? NSNumber { return number.intValue }
        if let text = value as? String { return Int(text) ?? 0 }
        return 0
    }

    // Matched against the names a provider gives its streams, so the words stay bilingual in code
    // rather than moving to a strings file: a Thai playlist has Thai channel names whatever
    // language the UI happens to be in.
    private static let radioWords = ["radio", "fm ", " fm", "am ", "music", "audio", "วิทยุ"]
    private static let audioExtensions = [".mp3", ".aac", ".m4a", ".ogg", ".opus", ".flac", ".wav"]
}
