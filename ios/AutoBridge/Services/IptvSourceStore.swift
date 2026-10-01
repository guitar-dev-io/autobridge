import Foundation

/// Persists the configured IPTV sources and seeds the first-run defaults exactly once per URL.
///
/// Seeding is remembered per URL (not behind a single "has run" flag) so a default the user deletes
/// is never recreated, while a default added in a later app version still arrives. This mirrors
/// `IptvDirectory.pendingDefaults`.
@MainActor
public final class IptvSourceStore: ObservableObject {
    @Published public private(set) var sources: [IptvSource] = []

    private let defaults: UserDefaults
    private let sourcesKey = "iptv.sources.v1"
    private let seededKey = "iptv.seededUrls.v1"

    public init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        load()
        seedDefaultsIfNeeded()
    }

    public func sources(kind: IptvKind) -> [IptvSource] {
        sources.filter { $0.kind == kind }
    }

    public func add(_ source: IptvSource) {
        if let index = sources.firstIndex(where: { $0.id == source.id }) {
            sources[index] = source
        } else {
            sources.append(source)
        }
        persist()
    }

    public func remove(id: String) {
        sources.removeAll { $0.id == id }
        persist()
    }

    public func contains(url: String) -> Bool {
        sources.contains { $0.url == url }
    }

    // MARK: - Seeding

    private func seedDefaultsIfNeeded() {
        var seeded = Set(defaults.stringArray(forKey: seededKey) ?? [])
        let existing = Set(sources.map { $0.url })
        let pending = IptvDirectory.pendingDefaults(seededUrls: seeded, existingUrls: existing)
        guard !pending.isEmpty else { return }

        for entry in pending {
            let source = IptvDirectory.toSource(entry, id: UUID().uuidString)
            sources.append(source)
            seeded.insert(entry.url)
        }
        defaults.set(Array(seeded), forKey: seededKey)
        persist()
    }

    // MARK: - Persistence

    private func load() {
        guard let data = defaults.data(forKey: sourcesKey),
              let decoded = try? JSONDecoder().decode([IptvSource].self, from: data) else {
            sources = []
            return
        }
        sources = decoded
    }

    private func persist() {
        guard let data = try? JSONEncoder().encode(sources) else { return }
        defaults.set(data, forKey: sourcesKey)
    }
}
