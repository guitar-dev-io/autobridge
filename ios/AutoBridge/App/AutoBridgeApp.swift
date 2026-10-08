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
    @StateObject private var vehicle = AutoBridgeStores.shared.vehicle
    @StateObject private var weather = AutoBridgeStores.shared.weather
    @Environment(\.scenePhase) private var scenePhase

    var body: some Scene {
        WindowGroup {
            HomeView()
                .environmentObject(sourceStore)
                .environmentObject(historyStore)
                .environmentObject(bookmarkStore)
                .environmentObject(youtube)
                .environmentObject(playback)
                .environmentObject(catalog)
                .environmentObject(vehicle)
                .environmentObject(weather)
                .preferredColorScheme(.dark)
        }
        // The moment the driver opens the app is the moment they are about to drive: say what
        // maintenance is due then, at most once a day, as the Android app does.
        .onChange(of: scenePhase) { phase in
            if phase == .active { VehicleReminders.checkMaintenance(vehicle) }
        }
    }
}
