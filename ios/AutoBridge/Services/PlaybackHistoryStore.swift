import Foundation

/// Favorites and a bounded recently-played list, persisted with `UserDefaults` + `Codable`.
///
/// Both lists store full `IptvEntry` values so the Home surface can show them without reloading a
/// catalog. Recently-played is most-recent-first and capped; favorites are an ordered set keyed by
/// entry id.
@MainActor
public final class PlaybackHistoryStore: ObservableObject {
    @Published public private(set) var favorites: [IptvEntry] = []
    @Published public private(set) var recentlyPlayed: [IptvEntry] = []

    private let defaults: UserDefaults
    private let favoritesKey = "iptv.favorites.v1"
    private let recentKey = "iptv.recent.v1"
    private let recentLimit = 30

    public init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        favorites = decode(favoritesKey)
        recentlyPlayed = decode(recentKey)
    }

    public func isFavorite(_ entry: IptvEntry) -> Bool {
        favorites.contains { $0.id == entry.id }
    }

    public func toggleFavorite(_ entry: IptvEntry) {
        if let index = favorites.firstIndex(where: { $0.id == entry.id }) {
            favorites.remove(at: index)
        } else {
            favorites.insert(entry, at: 0)
        }
        encode(favorites, favoritesKey)
    }

    public func recordPlayed(_ entry: IptvEntry) {
        recentlyPlayed.removeAll { $0.id == entry.id }
        recentlyPlayed.insert(entry, at: 0)
        if recentlyPlayed.count > recentLimit {
            recentlyPlayed = Array(recentlyPlayed.prefix(recentLimit))
        }
        encode(recentlyPlayed, recentKey)
    }

    public func clearRecent() {
        recentlyPlayed = []
        encode(recentlyPlayed, recentKey)
    }

    private func decode(_ key: String) -> [IptvEntry] {
        guard let data = defaults.data(forKey: key),
              let decoded = try? JSONDecoder().decode([IptvEntry].self, from: data) else {
            return []
        }
        return decoded
    }

    private func encode(_ entries: [IptvEntry], _ key: String) {
        guard let data = try? JSONEncoder().encode(entries) else { return }
        defaults.set(data, forKey: key)
    }
}
