import Foundation
import SwiftUI

/// Drives a single section's screen: loads every source of one `IptvKind`, merges their catalogs,
/// and exposes a category filter. Loading is async and cancellable; errors surface as a message
/// rather than an empty screen with no explanation.
@MainActor
final class CatalogViewModel: ObservableObject {
    enum State: Equatable {
        case idle
        case loading
        case loaded
        case failed(String)
    }

    @Published private(set) var state: State = .idle
    @Published private(set) var catalog: IptvCatalogData = .empty
    @Published var selectedCategoryId: String = IptvCatalogData.allCategoryId

    private let loader: IptvCatalogLoader
    private var loadTask: Task<Void, Never>?

    init(loader: IptvCatalogLoader = IptvCatalogLoader()) {
        self.loader = loader
    }

    var categories: [IptvCategory] {
        let all = IptvCategory(id: IptvCatalogData.allCategoryId, name: "All", count: catalog.entries.count)
        return [all] + catalog.categories
    }

    var visibleEntries: [IptvEntry] {
        catalog.entries(in: selectedCategoryId)
    }

    func load(sources: [IptvSource]) {
        loadTask?.cancel()
        guard !sources.isEmpty else {
            catalog = .empty
            state = .loaded
            return
        }
        state = .loading
        loadTask = Task { [loader] in
            var categories: [IptvCategory] = []
            var entries: [IptvEntry] = []
            var seenCategoryIds = Set<String>()
            var firstError: String?

            for source in sources {
                if Task.isCancelled { return }
                do {
                    let data = try await loader.load(source)
                    for category in data.categories where !seenCategoryIds.contains(category.id) {
                        seenCategoryIds.insert(category.id)
                        categories.append(category)
                    }
                    entries.append(contentsOf: data.entries)
                } catch {
                    if firstError == nil { firstError = error.localizedDescription }
                }
            }

            if Task.isCancelled { return }
            if entries.isEmpty, let message = firstError {
                self.state = .failed(message)
                return
            }
            self.catalog = IptvCatalogData(categories: categories, entries: entries)
            self.state = .loaded
        }
    }

    func cancel() {
        loadTask?.cancel()
    }
}
