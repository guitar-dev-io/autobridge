import SwiftUI

/// App entry. Injects the shared stores into the SwiftUI environment so every screen — and, through
/// `AutoBridgeStores`, the CarPlay scene — reads the same sources, favourites, history and player.
@main
struct AutoBridgeApp: App {
    @StateObject private var sourceStore = AutoBridgeStores.shared.sources
    @StateObject private var historyStore = AutoBridgeStores.shared.history
    @StateObject private var bookmarkStore = AutoBridgeStores.shared.bookmarks
    @StateObject private var youtube = AutoBridgeStores.shared.youtube
    @StateObject private var playback = AutoBridgeStores.shared.playback
    @StateObject private var catalog = AutoBridgeStores.shared.catalog

    var body: some Scene {
        WindowGroup {
            HomeView()
                .environmentObject(sourceStore)
                .environmentObject(historyStore)
                .environmentObject(bookmarkStore)
                .environmentObject(youtube)
                .environmentObject(playback)
                .environmentObject(catalog)
                .preferredColorScheme(.dark)
        }
    }
}
