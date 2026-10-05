import SwiftUI

/// The Streaming section: the shared `StreamingLinks` catalog, grouped, each row opening the site in
/// the in-app browser. Mirrors the Android `LibraryActivity.showStreaming`.
struct StreamingView: View {
    private let accent = AutoBridgeDesign.accentVideo

    var body: some View {
        PageShell(
            title: NSLocalizedString("Streaming", comment: "Home section"),
            subtitle: plural(StreamingLinks.all.count, "site"),
            accent: accent
        ) {
            LazyVStack(spacing: 10) {
                ForEach(StreamingLinks.grouped(), id: \.group) { section in
                    SectionLabel(text: section.group.title)
                    ForEach(section.links) { link in
                        NavigationLink {
                            BrowserView(initialURL: link.url, title: link.title)
                        } label: {
                            ContentRow(
                                title: link.title,
                                subtitle: link.url.host ?? link.url.absoluteString,
                                accent: accent
                            )
                        }
                        .buttonStyle(.plain)
                    }
                }
            }
        }
        .safeAreaInset(edge: .bottom) { MiniPlayerBar() }
    }
}
