import SwiftUI

/// A section screen (TV or Radio): a horizontal category filter over a list of channels. Tapping a
/// row navigates to the player or the browser depending on the entry's playback kind.
struct CatalogView: View {
    let section: HomeSection

    @EnvironmentObject private var sourceStore: IptvSourceStore
    @EnvironmentObject private var historyStore: PlaybackHistoryStore
    @StateObject private var model = CatalogViewModel()

    private var accent: Color { AutoBridgeDesign.accent(for: section) }
    private var kind: IptvKind { section.iptvKind ?? .tv }

    var body: some View {
        content
            .background(AutoBridgeDesign.ink.ignoresSafeArea())
            .navigationTitle(section.title)
            .navigationBarTitleDisplayMode(.inline)
            .task(id: sourceStore.sources(kind: kind).map(\.id)) {
                model.load(sources: sourceStore.sources(kind: kind))
            }
            .onDisappear { model.cancel() }
    }

    @ViewBuilder
    private var content: some View {
        switch model.state {
        case .idle, .loading:
            ProgressView("Loading channels…")
                .tint(accent)
                .frame(maxWidth: .infinity, maxHeight: .infinity)
        case .failed(let message):
            VStack(spacing: 12) {
                Image(systemName: "exclamationmark.triangle")
                    .font(.largeTitle)
                    .foregroundStyle(accent)
                Text("Could not load this section")
                    .font(.headline)
                    .foregroundStyle(AutoBridgeDesign.primaryText)
                Text(message)
                    .font(.caption)
                    .multilineTextAlignment(.center)
                    .foregroundStyle(AutoBridgeDesign.secondaryText)
                Button("Retry") { model.load(sources: sourceStore.sources(kind: kind)) }
                    .tint(accent)
            }
            .padding(32)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        case .loaded:
            loadedList
        }
    }

    private var loadedList: some View {
        VStack(spacing: 0) {
            categoryFilter
            if model.visibleEntries.isEmpty {
                Spacer()
                Text("No channels here yet")
                    .foregroundStyle(AutoBridgeDesign.secondaryText)
                Spacer()
            } else {
                List(model.visibleEntries) { entry in
                    NavigationLink(value: entry) {
                        EntryRow(entry: entry, accent: accent, isFavorite: historyStore.isFavorite(entry))
                    }
                    .listRowBackground(AutoBridgeDesign.surface)
                    .swipeActions(edge: .leading) {
                        Button {
                            historyStore.toggleFavorite(entry)
                        } label: {
                            Label("Favorite", systemImage: "star")
                        }
                        .tint(accent)
                    }
                }
                .listStyle(.plain)
                .scrollContentBackground(.hidden)
            }
        }
    }

    private var categoryFilter: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                ForEach(model.categories) { category in
                    let selected = category.id == model.selectedCategoryId
                    Button {
                        model.selectedCategoryId = category.id
                    } label: {
                        Text(category.name)
                            .font(.subheadline)
                            .padding(.horizontal, 14)
                            .padding(.vertical, 8)
                            .background(selected ? accent : AutoBridgeDesign.surface)
                            .foregroundStyle(selected ? Color.black : AutoBridgeDesign.primaryText)
                            .clipShape(Capsule())
                    }
                    .buttonStyle(.plain)
                }
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 10)
        }
    }
}

/// One channel row: logo placeholder, title, optional subtitle hints, and a favorite marker.
struct EntryRow: View {
    let entry: IptvEntry
    let accent: Color
    let isFavorite: Bool

    var body: some View {
        HStack(spacing: 12) {
            RoundedRectangle(cornerRadius: 8, style: .continuous)
                .fill(AutoBridgeDesign.ink)
                .frame(width: 56, height: 40)
                .overlay {
                    Image(systemName: entry.isWebPage ? "globe" : "play.tv")
                        .foregroundStyle(accent)
                }
            VStack(alignment: .leading, spacing: 2) {
                Text(entry.title)
                    .foregroundStyle(AutoBridgeDesign.primaryText)
                    .lineLimit(1)
                if !entry.subtitle.isEmpty {
                    Text(entry.subtitle)
                        .font(.caption)
                        .foregroundStyle(AutoBridgeDesign.secondaryText)
                        .lineLimit(1)
                }
            }
            Spacer()
            if isFavorite {
                Image(systemName: "star.fill").foregroundStyle(accent).font(.caption)
            }
        }
    }
}
