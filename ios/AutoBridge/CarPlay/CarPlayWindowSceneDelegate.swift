import CarPlay
import UIKit

/// The drawn CarPlay screen for a car that hosts AutoBridge as a video app.
///
/// With `com.apple.developer.carplay-video` (iOS 26.4 and later) CarPlay no longer opens the app as
/// a template scene: DashBoard hosts it as a drawn app and asks for a plain `UIWindowScene` in the
/// `UIWindowSceneSessionRoleCarPlay` session role, the same role Apple's own drawn CarPlay apps
/// (CarPlay Settings, CarPlay Wallpaper) declare in their Info.plist. Without a configuration for
/// that role UIKit still creates the scene, but with no delegate and no window, so the car showed
/// an empty grey screen and `CarPlaySceneDelegate` was never called. Info.plist now names this
/// class for the role; it puts `CarScreenViewController` in the scene's window.
///
/// There is no `CPInterfaceController` here, so no map buttons or CarPlay keyboard: the screen is
/// driven by touch and, through the focus engine, by the car's knob and buttons.
@MainActor
@objc(CarPlayWindowSceneDelegate)
final class CarPlayWindowSceneDelegate: UIResponder, UIWindowSceneDelegate {
    var window: UIWindow?
    private var session: CPSessionConfiguration?

    func scene(_ scene: UIScene, willConnectTo session: UISceneSession, options connectionOptions: UIScene.ConnectionOptions) {
        CarPlaySceneLog.note("CarPlayWindowSceneDelegate willConnectTo: \(CarPlaySceneLog.describe(scene))")
        guard let windowScene = scene as? UIWindowScene else { return }
        let screen = CarScreenViewController()
        let window = UIWindow(windowScene: windowScene)
        window.rootViewController = screen
        window.isHidden = false
        self.window = window

        self.session = CPSessionConfiguration(delegate: self)
        var video = false
        if #available(iOS 26.4, *) { video = self.session?.supportsVideoPlayback ?? false }
        CarPlaySceneLog.note("drawn scene window \(NSCoder.string(for: windowScene.coordinateSpace.bounds.size)) supportsVideoPlayback=\(video ? 1 : 0)")
        AutoBridgeStores.shared.playback.carPlayVideoAllowed = video
        VehicleReminders.carConnected()
        CarConnectionState.shared.connected = true
    }

    func sceneDidDisconnect(_ scene: UIScene) {
        CarPlaySceneLog.note("CarPlayWindowSceneDelegate sceneDidDisconnect")
        CarWebScreen.shared.detach(.carPlay)
        window = nil
        session = nil
        AutoBridgeStores.shared.playback.carPlayVideoAllowed = false
        VehicleReminders.carDisconnected()
        CarConnectionState.shared.connected = false
    }
}

extension CarPlayWindowSceneDelegate: CPSessionConfigurationDelegate {}
