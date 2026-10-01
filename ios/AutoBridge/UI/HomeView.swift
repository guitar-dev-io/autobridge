import SwiftUI

/// The AutoBridge iOS home: a dashboard grid (TV, Radio, Web browser) plus a recently-played strip.
/// Mirrors the shape of the Android `HomeSection` dashboard, limited to the sections iOS supports.
struct HomeView: View {
    @EnvironmentObject private var sourceStore: IptvSourceStore
    @EnvironmentObject private var historyStore: PlaybackHistoryStore

    private let columns = [GridItem(.flexible(), spacing: 16), GridItem(.flexible(), spacing: 16)]

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 24) {
                    dashboard
                    if !historyStore.recentlyPlayed.isEmpty {
                        recentStrip
                    }
                    if !historyStore.favorites.isEmpty {
                        favoritesStrip
                    }
                }
                .padding(20)
            }
            .background(AutoBridgeDesign.ink.ignoresSafeArea())
            .navigationTitle("AutoBridge")
            .navigationDestination(for: HomeSection.self) { section in
                destination(for: section)
            }
            .navigationDestination(for: IptvEntry.self) { entry in
                PlayerOrBrowser(entry: entry)
            }
        }
        .tint(.white)
    }

    private var dashboard: some View {
        LazyVGrid(columns: columns, spacing: 16) {
            ForEach(HomeSection.allCases) { section in
                NavigationLink(value: section) {
                    sectionCard(section)
                }
                .buttonStyle(.plain)
            }
        }
    }

    private func sectionCard(_ section: HomeSection) -> some View {
        InkCard(accent: AutoBridgeDesign.accent(for: section)) {
            VStack(alignment: .leading, spacing: 12) {
                Image(systemName: section.systemImage)
                    .font(.system(size: 28, weight: .semibold))
                    .foregroundStyle(AutoBridgeDesign.accent(for: section))
                Text(section.title)
                    .font(.headline)
                    .foregroundStyle(AutoBridgeDesign.primaryText)
                if let kind = section.iptvKind {
                    Text("\(sourceStore.sources(kind: kind).count) source(s)")
                        .font(.caption)
                        .foregroundStyle(AutoBridgeDesign.secondaryText)
                } else {
                    Text(section.caption)
                        .font(.caption)
                        .foregroundStyle(AutoBridgeDesign.secondaryText)
                }
            }
            .frame(maxWidth: .infinity, minHeight: 120, alignment: .topLeading)
            .padding(16)
        }
    }

    private var recentStrip: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Recently played")
                .font(.headline)
                .foregroundStyle(AutoBridgeDesign.primaryText)
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 12) {
                    ForEach(historyStore.recentlyPlayed) { entry in
                        NavigationLink(value: entry) {
                            EntryChip(entry: entry)
                        }
                        .buttonStyle(.plain)
                    }
                }
            }
        }
    }

    private var favoritesStrip: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Favorites")
                .font(.headline)
                .foregroundStyle(AutoBridgeDesign.primaryText)
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 12) {
                    ForEach(historyStore.favorites) { entry in
                        NavigationLink(value: entry) {
                            EntryChip(entry: entry)
                        }
                        .buttonStyle(.plain)
                    }
                }
            }
        }
    }

    @ViewBuilder
    private func destination(for section: HomeSection) -> some View {
        switch section {
        case .tv:
            CatalogView(section: section)
        case .radio:
            CatalogView(section: section)
        case .streaming:
            StreamingView()
        case .browser:
            BrowserView(initialURL: URL(string: "https://www.youtube.com"))
                .navigationTitle("Web browser")
        }
    }
}

/// A compact entry tile used in the Home horizontal strips.
struct EntryChip: View {
    let entry: IptvEntry

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            RoundedRectangle(cornerRadius: 10, style: .continuous)
                .fill(AutoBridgeDesign.surface)
                .frame(width: 140, height: 80)
                .overlay {
                    Image(systemName: entry.isWebPage ? "globe" : "play.tv")
                        .foregroundStyle(AutoBridgeDesign.secondaryText)
                }
            Text(entry.title)
                .font(.caption)
                .lineLimit(1)
                .foregroundStyle(AutoBridgeDesign.primaryText)
        }
        .frame(width: 140, alignment: .leading)
    }
}
