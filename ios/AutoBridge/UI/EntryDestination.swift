import SwiftUI

/// Routes a tapped entry to the right surface, and records it as recently played on the way.
///
/// Mirrors the Android `LibraryActivity.openEntry`: a series folder opens its episode list, a watch
/// page (YouTube/Twitch/… per `IptvPlaylistConventions`) opens in the browser because a player can
/// do nothing with it, and anything else starts in the shared player with the list it came from as
/// its queue — without that queue a channel opened from a category would be the only thing the
/// player knows about.
struct EntryDestination: View {
    let source: IptvSource
    let entry: IptvEntry
    var siblings: [IptvEntry] = []

    @EnvironmentObject private var playback: PlaybackController
    @EnvironmentObject private var historyStore: IptvHistoryStore

    var body: some View {
        if entry.isSeriesFolder {
            EpisodesView(source: source, series: entry)
        } else if entry.isWebPage {
            BrowserView(initialURL: URL(string: entry.url), title: entry.title)
                .onAppear { historyStore.recordPlayback(source: source, entry: entry) }
        } else {
            PlayerView()
                .onAppear(perform: start)
        }
    }

    private func start() {
        guard playback.current?.url != entry.url else { return }
        historyStore.recordPlayback(source: source, entry: entry)
        let queue = siblings
            .filter { !$0.isSeriesFolder && !$0.isWebPage && !$0.url.isEmpty }
            .map { PlaybackController.Track(entry: $0, source: source) }
        let index = queue.firstIndex { $0.url == entry.url } ?? 0
        playback.play(
            PlaybackController.Track(entry: entry, source: source),
            queue: queue,
            index: index
        )
    }
}

/// A series folder's episodes, loaded on demand through the shared catalog cache.
struct EpisodesView: View {
    let source: IptvSource
    let series: IptvEntry

    @EnvironmentObject private var catalog: IptvCatalog

    @State private var episodes: [IptvEntry]?

    private var accent: Color {
        source.kind == .radio ? AutoBridgeDesign.accentRadio : AutoBridgeDesign.accentTV
    }

    var body: some View {
        PageShell(
            title: series.title,
            subtitle: episodes.map { plural($0.count, "episode") }
                ?? NSLocalizedString("Loading…", comment: "Loading"),
            accent: accent
        ) {
            if let episodes {
                LazyVStack(spacing: 10) {
                    ForEach(episodes) { episode in
                        NavigationLink {
                            EntryDestination(source: source, entry: episode, siblings: episodes)
                        } label: {
                            ContentRow(
                                title: episode.title,
                                subtitle: episode.subtitle,
                                accent: accent,
                                logo: episode.logo.isEmpty ? series.logo : episode.logo
                            )
                        }
                        .buttonStyle(.plain)
                    }
                    if episodes.isEmpty {
                        EmptyState(
                            title: NSLocalizedString("No episodes", comment: "Empty state"),
                            message: NSLocalizedString(
                                "The portal returned no episodes for this series.",
                                comment: "Empty state"
                            ),
                            accent: accent
                        )
                    }
                }
            } else {
                ProgressView()
                    .tint(accent)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 60)
            }
        }
        .safeAreaInset(edge: .bottom) { MiniPlayerBar() }
        .task {
            if episodes == nil {
                episodes = await catalog.episodes(for: source, entry: series)
            }
        }
    }
}
