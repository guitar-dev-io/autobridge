import SwiftUI

/// One source's categories, loaded through the shared `IptvCatalog` cache.
///
/// The Android screen shows a progress dialog and then pushes a page; here the page *is* the
/// progress, which is the same sequence with one less thing on screen. Mirrors
/// `LibraryActivity.openSource` + `showCategories`.
struct CategoriesView: View {
    let source: IptvSource
    let accent: Color

    @EnvironmentObject private var catalog: IptvCatalog

    @State private var data: IptvCatalogData?
    @State private var failure: String?
    @State private var loading = false

    var body: some View {
        PageShell(
            title: source.name,
            subtitle: subtitle,
            accent: accent,
            actions: data == nil ? [] : [
                PageAction(title: NSLocalizedString("Refresh", comment: "Source menu")) {
                    Task { await load(forceRefresh: true) }
                }
            ]
        ) {
            if let data {
                LazyVStack(spacing: 10) {
                    ForEach(data.categories) { category in
                        NavigationLink {
                            EntriesView(
                                source: source,
                                data: data,
                                categoryId: category.id,
                                categoryName: category.name,
                                accent: accent
                            )
                        } label: {
                            ContentRow(
                                title: category.name,
                                subtitle: plural(category.count, "entry", "entries"),
                                accent: accent
                            )
                        }
                        .buttonStyle(.plain)
                    }
                    if data.categories.isEmpty {
                        EmptyState(
                            title: NSLocalizedString("Nothing here", comment: "Empty state"),
                            message: NSLocalizedString(
                                "This source returned no categories.",
                                comment: "Empty state"
                            ),
                            accent: accent
                        )
                    }
                }
            } else if let failure {
                EmptyState(
                    title: String(
                        format: NSLocalizedString("Could not load %@", comment: "Error"),
                        source.name
                    ),
                    message: failure,
                    accent: accent,
                    actionTitle: NSLocalizedString("Try again", comment: "Action"),
                    action: { Task { await load(forceRefresh: true) } }
                )
            } else {
                ProgressView()
                    .tint(accent)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 60)
            }
        }
        .safeAreaInset(edge: .bottom) { MiniPlayerBar() }
        .task { await load(forceRefresh: false) }
    }

    private var subtitle: String {
        guard let data else {
            return loading
                ? NSLocalizedString("Loading…", comment: "Loading")
                : hostOf(source.url)
        }
        return plural(data.entries.count, "entry", "entries")
            + " • " + plural(data.categories.count, "category", "categories")
    }

    private func load(forceRefresh: Bool) async {
        if data != nil && !forceRefresh { return }
        loading = true
        failure = nil
        if forceRefresh { data = nil }
        switch await catalog.load(source, forceRefresh: forceRefresh) {
        case .ready(let loaded):
            data = loaded
        case .failed(let message):
            failure = message
        }
        loading = false
    }
}
