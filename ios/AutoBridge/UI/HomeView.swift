import SwiftUI

/// The AutoBridge iOS home: the shared `HomeSection` dashboard as a two-column grid, with the
/// now-playing bar pinned under it and Settings in the navigation bar.
///
/// Settings is not a tile for the same reason the Android phone grid leaves it out: it is reachable
/// from every screen already, and listing it twice pushes the actual content further down.
struct HomeView: View {
    @EnvironmentObject private var sourceStore: IptvSourceStore
    @EnvironmentObject private var historyStore: IptvHistoryStore
    @EnvironmentObject private var weather: WeatherStore

    private let columns = [GridItem(.flexible(), spacing: 14), GridItem(.flexible(), spacing: 14)]

    var body: some View {
        NavigationStack {
            PageShell(title: "AutoBridge", subtitle: subtitle) {
                LazyVGrid(columns: columns, spacing: 14) {
                    ForEach(HomeSection.allCases) { section in
                        NavigationLink {
                            SectionDestination(section: section)
                        } label: {
                            card(section)
                        }
                        .buttonStyle(.plain)
                    }
                }
                .padding(.top, 2)
            }
            .safeAreaInset(edge: .bottom) { MiniPlayerBar() }
            .task { await weather.refresh() }
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    NavigationLink {
                        SettingsView()
                    } label: {
                        Image(systemName: "gearshape")
                    }
                }
            }
        }
        .tint(AutoBridgeDesign.accent)
    }

    private var subtitle: String {
        let sources = plural(sourceStore.sources.count, "source")
        let favorites = plural(historyStore.favorites.count, "favorite")
        return "\(sources) • \(favorites)"
    }

    private func card(_ section: HomeSection) -> some View {
        InkCard(accent: section.accent) {
            VStack(alignment: .leading, spacing: 10) {
                Image(systemName: section.systemImage)
                    .font(.system(size: 24, weight: .semibold))
                    .foregroundStyle(section.accent)
                Spacer(minLength: 0)
                Text(section.title)
                    .font(.headline)
                    .foregroundStyle(AutoBridgeDesign.primaryText)
                Text(detail(section))
                    .font(.caption)
                    .foregroundStyle(AutoBridgeDesign.secondaryText)
                    .lineLimit(2, reservesSpace: true)
            }
            .frame(maxWidth: .infinity, minHeight: 124, alignment: .topLeading)
            .padding(14)
        }
    }

    /// The card's second line: a live count where there is one to show, the section caption
    /// otherwise. Mirrors the Android dashboard, which counts sources on TV and Radio.
    private func detail(_ section: HomeSection) -> LocalizedStringKey {
        if let kind = section.iptvKind {
            let count = sourceStore.sources(kind: kind).count
            return count == 0 ? "No source yet" : "\(count) source(s)"
        }
        if section == .favorites {
            let count = historyStore.favorites.count
            return count == 0 ? section.caption : "\(count) saved"
        }
        if section == .weather, let snapshot = weather.snapshot {
            return "\(Int(snapshot.temperatureC.rounded()))°C · \(snapshot.condition)"
        }
        return section.caption
    }
}

/// Routes a home tile to its screen. Kept out of `HomeView` so the CarPlay scene and the Settings
/// screen can reach the same destinations.
struct SectionDestination: View {
    let section: HomeSection

    var body: some View {
        switch section {
        case .tv, .radio:
            SourcesView(kind: section.iptvKind ?? .tv, accent: section.accent)
        case .web:
            BrowserView(initialURL: nil, title: section.plainTitle)
        case .youtube, .youtubeMusic:
            BrowserView(initialURL: section.webUrl, title: section.plainTitle)
        case .streaming:
            StreamingView()
        case .folders:
            LocalMediaView(mode: .files)
        case .gallery:
            LocalMediaView(mode: .gallery)
        case .favorites:
            FavoritesView()
        case .weather:
            WeatherView()
        }
    }
}
