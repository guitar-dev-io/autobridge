import SwiftUI

/// App entry. Owns the shared stores and injects them into the SwiftUI environment so every screen
/// — and the CarPlay scene — reads the same sources, favorites, and history.
@main
struct AutoBridgeApp: App {
    @StateObject private var sourceStore = IptvSourceStore()
    @StateObject private var historyStore = PlaybackHistoryStore()

    var body: some Scene {
        WindowGroup {
            HomeView()
                .environmentObject(sourceStore)
                .environmentObject(historyStore)
                .preferredColorScheme(.dark)
        }
    }
}
