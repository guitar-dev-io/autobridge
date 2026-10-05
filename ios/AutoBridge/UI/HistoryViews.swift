import SwiftUI

/// A remembered channel, opened the way it was opened the first time.
///
/// Replaying has to reach the same destination as the first play — a YouTube page handed to the
/// player would only fail there — which is why `IptvHistoryItem` carries its playback kind.
struct HistoryDestination: View {
    let item: IptvHistoryItem
    var peers: [IptvHistoryItem] = []

    @EnvironmentObject private var playback: PlaybackController
    @EnvironmentObject private var historyStore: IptvHistoryStore

    var body: some View {
        if item.playback == .webPage {
            BrowserView(initialURL: URL(string: item.url), title: item.title)
                .onAppear { historyStore.recordPlayback(item) }
        } else {
            PlayerView()
                .onAppear(perform: start)
        }
    }

    private func start() {
        guard playback.current?.url != item.url else { return }
        historyStore.recordPlayback(item)
        // Items of the same kind are a channel list in their own right, so the player gets them as
        // its queue.
        let queue = peers
            .filter { $0.kind == item.kind && !$0.url.isEmpty && $0.playback == .stream }
            .map(PlaybackController.Track.init(item:))
        let index = queue.firstIndex { $0.url == item.url } ?? 0
        playback.play(PlaybackController.Track(item: item), queue: queue, index: index)
    }
}

/// Recently played, for one section. Mirrors the Android `LibraryActivity.showRecent`.
struct RecentView: View {
    let kind: IptvKind
    let accent: Color

    @EnvironmentObject private var historyStore: IptvHistoryStore

    private var items: [IptvHistoryItem] { historyStore.recent(kind: kind) }

    var body: some View {
        PageShell(
            title: NSLocalizedString("Recently played", comment: "Row"),
            subtitle: plural(items.count, "item"),
            accent: accent,
            actions: items.isEmpty ? [] : [
                PageAction(title: NSLocalizedString("Clear history", comment: "Action")) {
                    historyStore.clearRecent()
                }
            ]
        ) {
            LazyVStack(spacing: 10) {
                ForEach(items) { item in
                    NavigationLink {
                        HistoryDestination(item: item, peers: items)
                    } label: {
                        ContentRow(
                            title: item.title,
                            subtitle: typeLabel(item),
                            accent: accent,
                            logo: item.logo
                        )
                    }
                    .buttonStyle(.plain)
                }
                if items.isEmpty {
                    EmptyState(
                        title: NSLocalizedString("Nothing yet", comment: "Empty state"),
                        message: NSLocalizedString(
                            "Channels you play show up here.",
                            comment: "Empty state"
                        ),
                        accent: accent
                    )
                }
            }
        }
        .safeAreaInset(edge: .bottom) { MiniPlayerBar() }
    }

    private func typeLabel(_ item: IptvHistoryItem) -> String {
        switch item.type {
        case .live: return NSLocalizedString("Live", comment: "Entry type")
        case .movie: return NSLocalizedString("Movie", comment: "Entry type")
        case .series: return NSLocalizedString("Series", comment: "Entry type")
        }
    }
}

/// Starred channels and saved pages.
///
/// `kind` narrows it to one section, which is how it is reached from TV and Radio; the home tile
/// passes nil and gets everything, plus the browser's bookmarks, like the Android page.
struct FavoritesView: View {
    var kind: IptvKind?

    @EnvironmentObject private var historyStore: IptvHistoryStore
    @EnvironmentObject private var bookmarkStore: WebBookmarkStore

    private var channels: [IptvHistoryItem] {
        guard let kind else { return historyStore.favorites }
        return historyStore.favorites(kind: kind)
    }

    private var bookmarks: [WebBookmark] {
        kind == nil ? bookmarkStore.bookmarks : []
    }

    private var accent: Color {
        guard let kind else { return AutoBridgeDesign.accentFavorite }
        return kind == .radio ? AutoBridgeDesign.accentRadio : AutoBridgeDesign.accentTV
    }

    var body: some View {
        PageShell(
            title: NSLocalizedString("Favorites", comment: "Home section"),
            subtitle: subtitle,
            accent: accent
        ) {
            LazyVStack(spacing: 10) {
                ForEach(channels) { item in
                    HStack(spacing: 8) {
                        NavigationLink {
                            HistoryDestination(item: item, peers: channels)
                        } label: {
                            ContentRow(
                                title: item.title,
                                subtitle: item.kind == .radio
                                    ? NSLocalizedString("Radio", comment: "Home section")
                                    : NSLocalizedString("TV", comment: "Home section"),
                                accent: item.kind == .radio
                                    ? AutoBridgeDesign.accentRadio
                                    : AutoBridgeDesign.accentTV,
                                logo: item.logo,
                                trailing: ""
                            )
                        }
                        .buttonStyle(.plain)
                        Button {
                            historyStore.removeFavorite(url: item.url)
                        } label: {
                            Image(systemName: "xmark")
                                .foregroundStyle(AutoBridgeDesign.secondaryText)
                                .frame(width: 34, height: 44)
                                .contentShape(Rectangle())
                        }
                        .buttonStyle(.plain)
                    }
                }
                ForEach(bookmarks) { bookmark in
                    HStack(spacing: 8) {
                        NavigationLink {
                            BrowserView(initialURL: URL(string: bookmark.url), title: bookmark.title)
                        } label: {
                            ContentRow(
                                title: bookmark.title,
                                subtitle: hostOf(bookmark.url),
                                accent: AutoBridgeDesign.accentWeb,
                                badge: "@",
                                trailing: ""
                            )
                        }
                        .buttonStyle(.plain)
                        Button {
                            bookmarkStore.remove(url: bookmark.url)
                        } label: {
                            Image(systemName: "xmark")
                                .foregroundStyle(AutoBridgeDesign.secondaryText)
                                .frame(width: 34, height: 44)
                                .contentShape(Rectangle())
                        }
                        .buttonStyle(.plain)
                    }
                }
                if channels.isEmpty && bookmarks.isEmpty {
                    EmptyState(
                        title: NSLocalizedString("Nothing saved yet", comment: "Empty state"),
                        message: NSLocalizedString(
                            "Star a channel in TV or Radio, or save a page in the browser, and it appears here.",
                            comment: "Empty state"
                        ),
                        accent: accent
                    )
                }
            }
        }
        .safeAreaInset(edge: .bottom) { MiniPlayerBar() }
    }

    private var subtitle: String {
        if kind == nil {
            return plural(channels.count, "channel") + " • " + plural(bookmarks.count, "page")
        }
        return plural(channels.count, "channel")
    }
}
