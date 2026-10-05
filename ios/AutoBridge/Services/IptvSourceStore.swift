import Foundation

/// Persisted IPTV sources, split by `IptvKind` so the TV and Radio grids stay independent.
///
/// Credentials never touch the container in the clear: the whole list is written through
/// `SecretStore`, so the username, the password and the portal URL that embeds both live in the
/// keychain rather than in `UserDefaults`. A list written by an earlier build in plaintext is
/// migrated on the first read and the plaintext copy is removed.
///
/// The seeding marker is the only value here written in the clear, and it holds URLs of public
/// playlists only. Seeding is remembered per URL (not behind a single "has run" flag) so a default
/// the user deletes is never recreated, while a default added in a later app version still arrives.
/// Mirrors the Android `IptvSourceStore`.
@MainActor
public final class IptvSourceStore: ObservableObject {
    @Published public private(set) var sources: [IptvSource] = []

    private let defaults: UserDefaults
    private let service: String
    private let itemsAccount = "sources"
    private let seededKey = "iptv.seededUrls.v1"
    private let legacySourcesKey = "iptv.sources.v1"

    public init(defaults: UserDefaults = .standard, service: String = "dev.autobridge.iptv.sources") {
        self.defaults = defaults
        self.service = service
        migrateLegacyPlaintext()
        load()
        seedDefaults()
    }

    public func sources(kind: IptvKind) -> [IptvSource] {
        sources.filter { $0.kind == kind }
    }

    public func find(id: String) -> IptvSource? {
        sources.first { $0.id == id }
    }

    public func contains(url: String) -> Bool {
        sources.contains { $0.url == url }
    }

    /// Adds a new source, or replaces the existing one with the same id.
    public func save(_ source: IptvSource) {
        if let index = sources.firstIndex(where: { $0.id == source.id }) {
            sources[index] = source
        } else {
            sources.append(source)
        }
        persist()
        IptvCatalog.shared.invalidate(source.id)
    }

    public func remove(id: String) {
        sources.removeAll { $0.id == id }
        persist()
        IptvCatalog.shared.invalidate(id)
    }

    /// Stable id for a freshly entered source; keeps saved entries addressable across restarts.
    public static func newId() -> String {
        "src-" + UUID().uuidString.replacingOccurrences(of: "-", with: "").prefix(8)
    }

    // MARK: - Seeding

    /// Creates the default public lists that have never been created before.
    ///
    /// The URLs seeded so far are remembered, not just the fact that seeding ran. That is what makes
    /// "delete" mean delete: a removed default is gone from the source list but still recorded as
    /// seeded, so no later read brings it back — while a default introduced by a future version,
    /// recorded nowhere yet, still arrives.
    private func seedDefaults() {
        var seeded = Set(defaults.stringArray(forKey: seededKey) ?? [])
        let pending = IptvDirectory.pendingDefaults(
            seededUrls: seeded,
            existingUrls: Set(sources.map(\.url))
        )
        guard !pending.isEmpty else { return }
        for entry in pending {
            sources.append(IptvDirectory.toSource(entry, id: Self.newId()))
            seeded.insert(entry.url)
        }
        persist()
        defaults.set(Array(seeded), forKey: seededKey)
    }

    // MARK: - Persistence

    private func load() {
        let raw = SecretStore.read(service: service, account: itemsAccount)
        guard !raw.isEmpty, let data = raw.data(using: .utf8),
              let decoded = try? JSONDecoder().decode([IptvSource].self, from: data) else {
            sources = []
            return
        }
        sources = decoded
    }

    private func persist() {
        guard let data = try? JSONEncoder().encode(sources),
              let text = String(data: data, encoding: .utf8) else { return }
        SecretStore.write(service: service, account: itemsAccount, value: text)
    }

    /// Moves a list written by an earlier build into the keychain and clears the plaintext copy.
    private func migrateLegacyPlaintext() {
        guard let data = defaults.data(forKey: legacySourcesKey) else { return }
        defaults.removeObject(forKey: legacySourcesKey)
        guard SecretStore.read(service: service, account: itemsAccount).isEmpty,
              let text = String(data: data, encoding: .utf8) else { return }
        SecretStore.write(service: service, account: itemsAccount, value: text)
    }
}
