import SwiftUI

/// One category's entries as a grid of tiles, with the channel check that runs by itself.
///
/// Mirrors the Android `LibraryActivity.showEntries` down to its limits: a page draws at most
/// `maxVisible` tiles, the search field appears only once a category is long enough to need one,
/// the automatic check on open covers the first two screenfuls, and the explicit recheck behind the
/// signal icon forgets the remembered answers first so it cannot look like a button that does
/// nothing.
struct EntriesView: View {
    let source: IptvSource
    let data: IptvCatalogData
    let categoryId: String
    let categoryName: String
    let accent: Color

    /// Cap on tiles drawn at once; above it the user searches instead of scrolling.
    private static let maxVisible = 300
    /// Cap on channels one explicit check probes. Probing a whole page would hammer a handful of
    /// providers for answers the user did not ask about.
    private static let maxPing = 60
    /// Cap on the automatic check a page runs when it opens — roughly the first two screenfuls.
    private static let autoPing = 24
    /// Below this a search field is clutter; above it, a long list is unusable without one.
    private static let searchThreshold = 12

    @EnvironmentObject private var historyStore: IptvHistoryStore

    @State private var query = ""
    @State private var pingRevision = 0
    @State private var autoChecked = false
    @State private var notice: String?
    @State private var noticeTask: Task<Void, Never>?
    @State private var inspecting: Inspection?

    private struct Inspection: Identifiable {
        let title: String
        let message: String
        var id: String { title + message }
    }

    private let columns = [GridItem(.flexible(), spacing: 12), GridItem(.flexible(), spacing: 12)]

    private var all: [IptvEntry] { data.entries(in: categoryId) }

    private var filtered: [IptvEntry] {
        guard !query.trimmingCharacters(in: .whitespaces).isEmpty else { return all }
        return all.filter { $0.title.localizedCaseInsensitiveContains(query) }
    }

    private var shown: [IptvEntry] { Array(filtered.prefix(Self.maxVisible)) }

    var body: some View {
        // Read so a landed check result re-renders the tiles, whose status comes out of
        // `StreamPing`'s cache rather than out of any state this view holds.
        let _ = pingRevision
        return PageShell(
            title: categoryName,
            subtitle: subtitle,
            accent: accent,
            headerActions: [
                // A signal icon in the corner instead of a labelled button: it reads this page's
                // worst check result at a glance, and still taps to recheck like a button would.
                HeaderAction(glyph: "📶", tint: signalTint) { recheck() }
            ],
            searchText: all.count >= Self.searchThreshold ? $query : nil,
            searchPrompt: NSLocalizedString("Search", comment: "Search field")
        ) {
            if shown.isEmpty {
                EmptyState(
                    title: NSLocalizedString("No matches", comment: "Empty state"),
                    message: NSLocalizedString(
                        "Nothing in this category matches that search.",
                        comment: "Empty state"
                    ),
                    accent: accent
                )
            } else {
                LazyVGrid(columns: columns, spacing: 12) {
                    ForEach(shown) { entry in
                        NavigationLink {
                            EntryDestination(source: source, entry: entry, siblings: shown)
                        } label: {
                            tile(entry)
                        }
                        .buttonStyle(.plain)
                        .contextMenu { menu(for: entry) }
                    }
                }
            }
        }
        .safeAreaInset(edge: .bottom) {
            VStack(spacing: 8) {
                if let notice {
                    Text(notice)
                        .font(.caption)
                        .foregroundStyle(AutoBridgeDesign.primaryText)
                        .padding(.horizontal, 14)
                        .padding(.vertical, 8)
                        .background(AutoBridgeDesign.surfaceRaised, in: Capsule())
                        .overlay(Capsule().stroke(AutoBridgeDesign.hairline, lineWidth: 1))
                }
                MiniPlayerBar()
            }
        }
        .alert(
            inspecting?.title ?? "",
            isPresented: Binding(
                get: { inspecting != nil },
                set: { if !$0 { inspecting = nil } }
            )
        ) {
            Button(NSLocalizedString("OK", comment: "Dialog"), role: .cancel) {}
        } message: {
            Text(inspecting?.message ?? "")
        }
        .onAppear {
            guard !autoChecked else { return }
            autoChecked = true
            // The automatic pass says nothing at all: it is background work the user did not ask
            // for, and its whole output is the colour on the tiles.
            ping(Array(shown.prefix(Self.autoPing)), quiet: true)
        }
    }

    private var subtitle: String {
        let counted = query.trimmingCharacters(in: .whitespaces).isEmpty
            ? plural(all.count, "entry", "entries")
            : String(
                format: NSLocalizedString("%1$d of %2$d match “%3$@”", comment: "Entries subtitle"),
                filtered.count, all.count, query
            )
        // A country-grouped public playlist puts thousands of channels in "All". Saying
        // "2080 entries" above 300 tiles is a miscount the user has no way to notice.
        guard shown.count < filtered.count else { return counted }
        return counted + " • " + String(
            format: NSLocalizedString("showing first %d, search to narrow", comment: "Entries subtitle"),
            shown.count
        )
    }

    private func tile(_ entry: IptvEntry) -> some View {
        let favorite = !entry.url.isEmpty && historyStore.isFavorite(url: entry.url)
        return ContentTile(
            title: entry.title,
            subtitle: [
                entry.subtitle.isEmpty ? nil : entry.subtitle,
                entry.isWebPage ? NSLocalizedString("Opens in browser", comment: "Entry note") : nil,
                entry.supportsCatchup ? NSLocalizedString("Catch-up", comment: "Entry note") : nil
            ].compactMap { $0 }.joined(separator: " • "),
            accent: accent,
            logo: entry.logo,
            status: StreamPing.cached(entry.url).map(AutoBridgeDesign.status(for:)),
            corner: entry.isSeriesFolder ? "" : (favorite ? "★" : "☆"),
            onCorner: entry.isSeriesFolder ? nil : {
                historyStore.toggleFavorite(source: source, entry: entry)
            }
        )
    }

    @ViewBuilder
    private func menu(for entry: IptvEntry) -> some View {
        if !entry.url.isEmpty && !entry.isSeriesFolder && !entry.isWebPage {
            Button(NSLocalizedString("Check this channel", comment: "Entry menu")) {
                inspect(entry)
            }
        }
        if !entry.isSeriesFolder {
            Button(
                historyStore.isFavorite(url: entry.url)
                    ? NSLocalizedString("Remove from favorites", comment: "Entry menu")
                    : NSLocalizedString("Add to favorites", comment: "Entry menu")
            ) {
                historyStore.toggleFavorite(source: source, entry: entry)
            }
        }
    }

    // MARK: - Checking

    /// The worst reading on this page, which is what the header's signal icon shows. Nil when
    /// nothing has been checked yet, so the icon stays neutral rather than claiming a result.
    private var signalTint: Color? {
        let tones = shown.compactMap { StreamPing.cached($0.url).map(StreamPing.tone) }
        if tones.isEmpty { return nil }
        if tones.contains(.bad) { return AutoBridgeDesign.danger }
        if tones.contains(.slow) { return AutoBridgeDesign.signalSlow }
        return AutoBridgeDesign.signalGood
    }

    /// The addresses on a page worth probing: a folder and a watch page are not streams.
    private func checkable(_ entries: [IptvEntry]) -> [String] {
        var seen = Set<String>()
        return entries
            .filter { !$0.isSeriesFolder && !$0.isWebPage && !$0.url.isEmpty }
            .map(\.url)
            .filter { seen.insert($0).inserted }
            .prefix(Self.maxPing)
            .map { $0 }
    }

    private func ping(_ entries: [IptvEntry], quiet: Bool) {
        let urls = checkable(entries)
        if urls.isEmpty {
            if !quiet {
                show(NSLocalizedString("Nothing here can be checked.", comment: "Check notice"))
            }
            return
        }
        if !quiet {
            show(
                String(
                    format: NSLocalizedString("Checking %d channels…", comment: "Check notice"),
                    urls.count
                ),
                sticky: true
            )
        }
        StreamPing.checkAll(urls) { progress in
            // The tiles re-read `StreamPing`'s cache as results land, which is why this bumps a
            // revision rather than tracking anything itself.
            pingRevision += 1
            if progress.done && !quiet {
                show(
                    String(
                        format: NSLocalizedString(
                            "%1$d of %2$d channels answered.",
                            comment: "Check notice"
                        ),
                        progress.alive, progress.total
                    )
                )
            }
        }
    }

    /// The explicit recheck behind the signal icon: the same sweep, but the remembered answers are
    /// dropped first. Without that, a tap inside the freshness window would hand back exactly what
    /// is already on screen.
    private func recheck() {
        let entries = Array(shown.prefix(Self.maxPing))
        StreamPing.forget(checkable(entries))
        pingRevision += 1
        ping(entries, quiet: false)
    }

    /// One channel, checked from its menu and reported in full rather than in a tile.
    private func inspect(_ entry: IptvEntry) {
        StreamPing.forget([entry.url])
        show(NSLocalizedString("Checking…", comment: "Check notice"), sticky: true)
        StreamPing.check(entry.url) { result in
            pingRevision += 1
            notice = nil
            noticeTask?.cancel()
            inspecting = Inspection(title: entry.title, message: StreamPing.sentence(result))
        }
    }

    private func show(_ message: String, sticky: Bool = false) {
        noticeTask?.cancel()
        notice = message
        guard !sticky else { return }
        noticeTask = Task {
            try? await Task.sleep(for: .seconds(3))
            guard !Task.isCancelled else { return }
            notice = nil
        }
    }
}
