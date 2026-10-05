import SwiftUI

/// The TV or Radio root: favourites, recently played, and one row per configured source.
///
/// Mirrors the Android `LibraryActivity.showSources`. A starred channel is a deliberate choice, so
/// it leads; "Recently played" is just whatever happened to play last and sits under it.
struct SourcesView: View {
    let kind: IptvKind
    let accent: Color

    @EnvironmentObject private var sourceStore: IptvSourceStore
    @EnvironmentObject private var historyStore: IptvHistoryStore
    @EnvironmentObject private var catalog: IptvCatalog

    @State private var editing: SourceEditorView.Request?
    @State private var showingDirectory = false
    @State private var pendingDelete: IptvSource?

    private var sources: [IptvSource] { sourceStore.sources(kind: kind) }
    private var favorites: [IptvHistoryItem] { historyStore.favorites(kind: kind) }
    private var recent: [IptvHistoryItem] { historyStore.recent(kind: kind) }
    private var title: String {
        kind == .radio
            ? NSLocalizedString("Radio", comment: "Home section")
            : NSLocalizedString("TV", comment: "Home section")
    }

    var body: some View {
        PageShell(
            title: title,
            subtitle: sources.isEmpty
                ? NSLocalizedString("No source configured", comment: "Sources page")
                : plural(sources.count, "source"),
            accent: accent,
            actions: [
                PageAction(title: NSLocalizedString("Public lists", comment: "Action")) {
                    showingDirectory = true
                },
                PageAction(title: NSLocalizedString("+ Xtream", comment: "Action")) {
                    editing = .init(type: .xtream, existing: nil)
                },
                PageAction(title: NSLocalizedString("+ M3U", comment: "Action")) {
                    editing = .init(type: .m3u, existing: nil)
                }
            ]
        ) {
            LazyVStack(spacing: 10) {
                if !favorites.isEmpty {
                    NavigationLink {
                        FavoritesView(kind: kind)
                    } label: {
                        ContentRow(
                            title: NSLocalizedString("Favorites", comment: "Home section"),
                            subtitle: plural(favorites.count, "channel"),
                            accent: accent,
                            badge: "★"
                        )
                    }
                    .buttonStyle(.plain)
                }
                if !recent.isEmpty {
                    NavigationLink {
                        RecentView(kind: kind, accent: accent)
                    } label: {
                        ContentRow(
                            title: NSLocalizedString("Recently played", comment: "Row"),
                            subtitle: plural(recent.count, "item"),
                            accent: accent,
                            badge: "↺"
                        )
                    }
                    .buttonStyle(.plain)
                }
                ForEach(sources) { source in
                    sourceRow(source)
                }
                if sources.isEmpty {
                    EmptyState(
                        title: String(
                            format: NSLocalizedString("No %@ source yet", comment: "Empty state"),
                            title
                        ),
                        message: NSLocalizedString(
                            "Pick a free public list, or add your own Xtream account or M3U playlist.",
                            comment: "Empty state"
                        ),
                        accent: accent,
                        actionTitle: NSLocalizedString("Browse public lists", comment: "Action"),
                        action: { showingDirectory = true }
                    )
                }
            }
        }
        .sheet(item: $editing) { request in
            SourceEditorView(request: request, kind: kind)
                .environmentObject(sourceStore)
        }
        .sheet(isPresented: $showingDirectory) {
            DirectoryPickerView(kind: kind, accent: accent)
                .environmentObject(sourceStore)
        }
        .alert(
            NSLocalizedString("Remove this source?", comment: "Delete alert"),
            isPresented: Binding(
                get: { pendingDelete != nil },
                set: { if !$0 { pendingDelete = nil } }
            ),
            presenting: pendingDelete
        ) { source in
            Button(NSLocalizedString("Remove", comment: "Delete alert"), role: .destructive) {
                sourceStore.remove(id: source.id)
            }
            Button(NSLocalizedString("Cancel", comment: "Dialog"), role: .cancel) {}
        } message: { source in
            // Deleting a source is permanent — a built-in default that is removed stays removed —
            // so it is confirmed once. Nothing is lost for good: every public list is in the picker.
            Text(
                IptvDirectory.list(kind: kind).contains(where: { $0.url == source.url })
                    ? "This is one of the built-in public lists. It will not come back on its own, but you can add it again from \"Public lists\"."
                    : "The source is removed from this device. Channels you favourited stay."
            )
        }
    }

    private func sourceRow(_ source: IptvSource) -> some View {
        HStack(spacing: 8) {
            NavigationLink {
                CategoriesView(source: source, accent: accent)
            } label: {
                ContentRow(
                    title: source.name,
                    subtitle: subtitle(for: source),
                    accent: accent,
                    badge: source.type == .xtream ? "X" : "M",
                    trailing: ""
                )
            }
            .buttonStyle(.plain)
            Menu {
                Button(NSLocalizedString("Refresh", comment: "Source menu")) {
                    catalog.invalidate(source.id)
                }
                Button(NSLocalizedString("Edit", comment: "Source menu")) {
                    editing = .init(type: source.type, existing: source)
                }
                Button(NSLocalizedString("Delete", comment: "Source menu"), role: .destructive) {
                    pendingDelete = source
                }
            } label: {
                Image(systemName: "ellipsis")
                    .foregroundStyle(AutoBridgeDesign.secondaryText)
                    .frame(width: 34, height: 44)
                    .contentShape(Rectangle())
            }
        }
    }

    private func subtitle(for source: IptvSource) -> String {
        let type = source.type == .xtream
            ? NSLocalizedString("Xtream", comment: "Source type")
            : NSLocalizedString("M3U playlist", comment: "Source type")
        if catalog.isLoading(source.id) {
            return type + " • " + NSLocalizedString("loading…", comment: "Source subtitle")
        }
        if let cached = catalog.cached(source.id) {
            return type + " • " + plural(cached.entries.count, "entry", "entries")
        }
        return type + " • " + hostOf(source.url)
    }
}
