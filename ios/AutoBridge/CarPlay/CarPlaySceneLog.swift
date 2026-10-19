import CarPlay
import Foundation
import os
import UIKit

/// Public syslog lines for every scene CarPlay (or anything else) asks the app for, so which role,
/// scene class and delegate a car session ends up with can be read from the device log
/// (`log stream --predicate 'subsystem == "dev.autobridge"'`, or Console.app) without Xcode.
enum CarPlaySceneLog {
    static let logger = Logger(subsystem: "dev.autobridge", category: "CarPlayScene")

    static func note(_ message: String) {
        logger.notice("AutoBridge CarPlay: \(message, privacy: .public)")
    }

    static func describe(_ scene: UIScene) -> String {
        let session = scene.session
        let delegate = scene.delegate.map { String(describing: type(of: $0)) } ?? "nil"
        return "role=\(session.role.rawValue) scene=\(type(of: scene)) "
            + "config=\(session.configuration.name ?? "nil") delegate=\(delegate)"
    }

    private static var observers: [NSObjectProtocol] = []

    /// Logs each scene as it connects, activates and disconnects, whatever its role.
    @MainActor
    static func startWatching() {
        guard observers.isEmpty else { return }
        let center = NotificationCenter.default
        let events: [(Notification.Name, String)] = [
            (UIScene.willConnectNotification, "scene will connect"),
            (UIScene.didActivateNotification, "scene did activate"),
            (UIScene.didDisconnectNotification, "scene did disconnect"),
        ]
        for (name, label) in events {
            observers.append(center.addObserver(forName: name, object: nil, queue: .main) { note in
                guard let scene = note.object as? UIScene else { return }
                CarPlaySceneLog.note("\(label): \(describe(scene))")
            })
        }
    }
}
