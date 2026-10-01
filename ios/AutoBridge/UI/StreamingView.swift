import SwiftUI

/// The Streaming section: a grouped list of streaming websites (Video, Music, Live/Gaming, Anime).
/// Each row opens the plain in-app `BrowserView`, mirroring the Android Streaming tile — there is
/// no per-site player, every entry is just a web page.
struct StreamingView: View {
    private let accent = AutoBridgeDesign.accent(for: .streaming)

    var body: some View {
        List {
            ForEach(StreamingLinks.grouped(), id: \.group) { section in
                Section {
                    ForEach(section.links) { link in
                        NavigationLink(value: link) {
                            StreamingRow(link: link, accent: accent)
                        }
                        .listRowBackground(AutoBridgeDesign.surface)
                    }
                } header: {
                    Text(section.group.title)
                        .foregroundStyle(AutoBridgeDesign.secondaryText)
                }
            }
        }
        .listStyle(.insetGrouped)
        .scrollContentBackground(.hidden)
        .background(AutoBridgeDesign.ink.ignoresSafeArea())
        .navigationTitle("Streaming")
        .navigationBarTitleDisplayMode(.inline)
        .navigationDestination(for: StreamingLink.self) { link in
            BrowserView(initialURL: link.url)
                .navigationTitle(link.title)
                .navigationBarTitleDisplayMode(.inline)
        }
    }
}

/// One streaming-site row: a globe-ish icon, the site name, and its host.
struct StreamingRow: View {
    let link: StreamingLink
    let accent: Color

    var body: some View {
        HStack(spacing: 12) {
            RoundedRectangle(cornerRadius: 8, style: .continuous)
                .fill(AutoBridgeDesign.ink)
                .frame(width: 56, height: 40)
                .overlay {
                    Image(systemName: "play.rectangle")
                        .foregroundStyle(accent)
                }
            VStack(alignment: .leading, spacing: 2) {
                Text(link.title)
                    .foregroundStyle(AutoBridgeDesign.primaryText)
                    .lineLimit(1)
                Text(link.url.host ?? link.url.absoluteString)
                    .font(.caption)
                    .foregroundStyle(AutoBridgeDesign.secondaryText)
                    .lineLimit(1)
            }
            Spacer()
        }
    }
}
