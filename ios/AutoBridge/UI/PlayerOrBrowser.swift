import SwiftUI

/// Routes a tapped entry to the right surface: a stream goes to the AVPlayer screen, a watch page
/// (YouTube/Twitch/etc., per `IptvPlaylistConventions`) opens in the in-app browser. Either way the
/// entry is recorded as recently played.
struct PlayerOrBrowser: View {
    let entry: IptvEntry
    @EnvironmentObject private var historyStore: PlaybackHistoryStore

    var body: some View {
        Group {
            if entry.isWebPage, let url = URL(string: entry.url) {
                BrowserView(initialURL: url)
                    .navigationTitle(entry.title)
                    .navigationBarTitleDisplayMode(.inline)
            } else {
                PlayerView(entry: entry)
            }
        }
        .onAppear { historyStore.recordPlayed(entry) }
    }
}
