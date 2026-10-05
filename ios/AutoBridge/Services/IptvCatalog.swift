import Foundation

/// Process-wide cache in front of `XtreamClient`, shared by the phone screens and the CarPlay scene
/// so a portal is fetched once and both surfaces show the same catalog.
///
/// Large Xtream accounts return tens of thousands of entries, so the result is kept in memory with a
/// freshness window and reused until the user asks for a refresh. A load already in flight adopts
/// new callers instead of firing a second request. Mirrors the Android `IptvCatalog`.
@MainActor
public final class IptvCatalog: ObservableObject {
    public static let shared = IptvCatalog()

    /// What a caller sees once a source is resolved.
    public enum Outcome {
        case ready(IptvCatalogData)
        case failed(String)
    }

    private static let freshness: TimeInterval = 30 * 60

    private struct Stamped {
        let data: IptvCatalogData
        let at: Date
    }

    /// Bumped whenever the cache changes, so a view that reads `cached`/`isLoading` re-renders.
    @Published public private(set) var revision = 0

    private var cache: [String: Stamped] = [:]
    private var inFlight: [String: Task<Outcome, Never>] = [:]
    private var episodeCache: [String: [IptvEntry]] = [:]

    private init() {}

    /// Cached catalog for `sourceId`, or nil when nothing has been loaded yet.
    public func cached(_ sourceId: String) -> IptvCatalogData? {
        cache[sourceId]?.data
    }

    public func isLoading(_ sourceId: String) -> Bool {
        inFlight[sourceId] != nil
    }

    /// Resolves `source`, serving the cache while it is still fresh. `forceRefresh` bypasses the
    /// cache for the user's explicit "Refresh" action.
    public func load(_ source: IptvSource, forceRefresh: Bool = false) async -> Outcome {
        if !forceRefresh, let stamped = cache[source.id],
           Date().timeIntervalSince(stamped.at) < Self.freshness {
            return .ready(stamped.data)
        }
        if forceRefresh { cache.removeValue(forKey: source.id) }
        if let running = inFlight[source.id] { return await running.value }

        let task = Task<Outcome, Never> {
            do {
                let data = try await XtreamClient.load(source)
                return .ready(data)
            } catch {
                return .failed(message(for: error))
            }
        }
        inFlight[source.id] = task
        revision += 1
        let outcome = await task.value
        inFlight.removeValue(forKey: source.id)
        if case .ready(let data) = outcome {
            cache[source.id] = Stamped(data: data, at: Date())
        }
        revision += 1
        return outcome
    }

    /// Series episodes for `entry`, cached per series so reopening a show is instant.
    public func episodes(for source: IptvSource, entry: IptvEntry) async -> [IptvEntry] {
        let key = "\(source.id):\(entry.seriesId)"
        if let cached = episodeCache[key] { return cached }
        let episodes = await XtreamClient.seriesEpisodes(source, seriesId: entry.seriesId)
        if !episodes.isEmpty { episodeCache[key] = episodes }
        return episodes
    }

    /// Drops cached data for a source after it is edited or deleted.
    public func invalidate(_ sourceId: String) {
        cache.removeValue(forKey: sourceId)
        for key in episodeCache.keys where key.hasPrefix("\(sourceId):") {
            episodeCache.removeValue(forKey: key)
        }
        revision += 1
    }

    /// Drops every in-memory catalog and episode list for a "clear cache" action. A load already in
    /// flight is left alone: it repopulates the cache when it finishes, which is the same as any
    /// fresh load after clearing.
    public func clearAll() {
        cache.removeAll()
        episodeCache.removeAll()
        revision += 1
    }

    /// The number of sources currently held, for the Settings row that offers to clear them.
    public var cachedSourceCount: Int { cache.count }

    private func message(for error: Error) -> String {
        if let localized = (error as? LocalizedError)?.errorDescription { return localized }
        return (error as NSError).localizedDescription
    }
}
