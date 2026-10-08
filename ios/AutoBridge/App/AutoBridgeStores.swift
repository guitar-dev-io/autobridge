import Foundation

/// The one set of stores the process holds.
///
/// CarPlay runs in its own `UIScene`, not inside the SwiftUI view tree, so it cannot read the
/// environment the app injects. Both surfaces therefore reach the stores through here, which is
/// what makes a source added on the phone appear in the car, a channel started in the car show up
/// in the phone's now-playing bar, and one `AVPlayer` serve both.
@MainActor
final class AutoBridgeStores {
    static let shared = AutoBridgeStores()

    let sources = IptvSourceStore()
    let history = IptvHistoryStore()
    let bookmarks = WebBookmarkStore()
    let youtube = YouTubeSettings()
    let playback = PlaybackController()
    let catalog = IptvCatalog.shared
    let vehicle = VehicleStore()
    let weather = WeatherStore()

    private init() {}
}
